package com.omniface.ai.tier2

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.RegistrationQualityEvaluator
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.recognition.FaceMatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 2: Boundary & Corner Cases - Feature 2: Zero-Stub Vector Space Boundaries
 */
class Tier2ZeroStubBoundaryTest {

    private lateinit var matcher: FaceMatcher

    @Before
    fun setUp() {
        matcher = FaceMatcher()
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 1e-7f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    @Test
    fun testAntiParallelVectorsSimilarity() {
        val v = l2Normalize(FloatArray(512) { 1.0f })
        val opposite = FloatArray(512) { -v[it] }

        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", opposite.joinToString(","), false)
        matcher.preloadTemplates(listOf(template))

        val result = matcher.match(v, mapOf("R001" to "Alice"), SecurityTier.STANDARD)
        assertTrue("Anti-parallel vectors must have similarity <= 0.0", result.similarity <= 0.0f)
        assertFalse("Anti-parallel vector must not match", result.isMatch)
    }

    @Test
    fun testOrthogonalVectorsSimilarity() {
        // Construct 2 orthogonal vectors
        val v1 = FloatArray(512) { if (it < 256) 1.0f else 0.0f }
        val v2 = FloatArray(512) { if (it >= 256) 1.0f else 0.0f }
        l2Normalize(v1)
        l2Normalize(v2)

        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", v1.joinToString(","), false)
        matcher.preloadTemplates(listOf(template))

        val result = matcher.match(v2, mapOf("R001" to "Alice"), SecurityTier.STANDARD)
        assertEquals("Orthogonal vectors must have cosine similarity ~ 0.0", 0.0f, result.similarity, 1e-4f)
        assertFalse(result.isMatch)
    }

    @Test
    fun testSingleSampleCentroidNormalization() {
        val single = l2Normalize(FloatArray(512) { 3.14f })
        val (centroid, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = listOf(single),
            qualityScores = listOf(95.0f)
        )

        var sumSq = 0f
        for (x in centroid) sumSq += x * x
        assertEquals(1.0f, sqrt(sumSq), 1e-4f)
        assertEquals(1, matrix.sampleCount)
        assertTrue(matrix.isConsistent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun testCentroidComputationWithEmptyInput() {
        RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = emptyList(),
            qualityScores = emptyList()
        )
    }

    @Test
    fun testInconsistentProfileOutlierRejection() {
        val base = l2Normalize(FloatArray(512) { 0.5f })
        val outlier = l2Normalize(FloatArray(512) { if (it % 2 == 0) 1.0f else -1.0f })

        // 4 good samples + 1 completely different person / extreme outlier
        val samples = listOf(base.clone(), base.clone(), base.clone(), base.clone(), outlier)
        val quality = listOf(95f, 95f, 95f, 95f, 90f)

        val (_, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(samples, quality)
        assertFalse("Inconsistent registration matrix must be flagged as not consistent", matrix.isConsistent)
        assertTrue("Minimum similarity should drop below threshold 0.78", matrix.minimumSimilarity < 0.78f)
    }
}
