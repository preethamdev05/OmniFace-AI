package com.omniface.ai.tier2

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.recognition.FaceMatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 2: Boundary & Corner Cases - Feature 1: MobileFaceNet Inference & Normalization
 */
class Tier2MobileFaceNetBoundaryTest {

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
    fun testZeroVectorInputHandling() {
        val zeroVec = FloatArray(512) { 0.0f }
        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", zeroVec.joinToString(","), false)
        matcher.preloadTemplates(listOf(template))

        val query = FloatArray(512) { 0.0f }
        val result = matcher.match(query, mapOf("R001" to "Alice"), SecurityTier.HIGH)

        assertNotNull(result)
        assertFalse("Zero vector must not cause false match", result.isMatch)
        assertEquals("Cosine similarity of zero vectors should be 0.0", 0.0f, result.similarity, 1e-4f)
        assertFalse("Similarity should not be NaN", result.similarity.isNaN())
    }

    @Test
    fun testNegativeVectorValues() {
        val allNegative = FloatArray(512) { -1.0f }
        val normalized = l2Normalize(allNegative)
        var sumSq = 0f
        for (v in normalized) sumSq += v * v
        assertEquals(1.0f, sqrt(sumSq), 1e-4f)
    }

    @Test
    fun testDimensionMismatchHandling() {
        // Query of 256 dimensions vs 512 dimensions template
        val queryShort = FloatArray(256) { 1.0f }
        val tpl512 = l2Normalize(FloatArray(512) { 1.0f })

        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", tpl512.joinToString(","), false)
        matcher.preloadTemplates(listOf(template))

        val result = matcher.match(queryShort, mapOf("R001" to "Alice"), SecurityTier.HIGH)
        assertNotNull(result)
        assertFalse("Dimension mismatch should be handled safely", result.similarity.isNaN())
    }

    @Test
    fun testExtremeFloatRangeBounds() {
        val hugeVec = FloatArray(512) { 1e6f }
        val normalized = l2Normalize(hugeVec)
        for (v in normalized) {
            assertFalse("Normalized elements must not be Infinite", v.isInfinite())
            assertFalse("Normalized elements must not be NaN", v.isNaN())
        }
    }

    @Test
    fun testEmptyEmbeddingCsvHandling() {
        val templateCorrupt = FaceTemplateEntity("t_corrupt", "R001", "FRONTAL", "", false)
        val templateNonNumeric = FaceTemplateEntity("t_invalid", "R002", "FRONTAL", "abc,def,ghi", false)

        matcher.preloadTemplates(listOf(templateCorrupt, templateNonNumeric))
        assertEquals("Corrupt/empty CSV templates should be skipped safely", 0, matcher.enrolledTemplateCount)
    }
}
