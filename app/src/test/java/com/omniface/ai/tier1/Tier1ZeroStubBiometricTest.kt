package com.omniface.ai.tier1

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.RegistrationQualityEvaluator
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.recognition.FaceMatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 1: Feature 2 - Zero-Stub Biometric Verification & Genuine Metric Paths
 */
class Tier1ZeroStubBiometricTest {

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

    private fun makeEmbedding(seed: Float, dim: Int = 512): FloatArray {
        return l2Normalize(FloatArray(dim) { i -> seed + i * 0.002f })
    }

    private fun toCsv(v: FloatArray): String =
        v.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }

    @Test
    fun testZeroStubNoRandomVectorsInProduction() {
        // Repeated matching with identical inputs must produce identical mathematical results
        val embA = makeEmbedding(1.5f)
        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", toCsv(embA), false)
        matcher.preloadTemplates(listOf(template))

        val result1 = matcher.match(embA, mapOf("R001" to "Bob"), SecurityTier.HIGH)
        val result2 = matcher.match(embA, mapOf("R001" to "Bob"), SecurityTier.HIGH)

        assertEquals("Biometric matching must be 100% deterministic (zero random jitter)", result1.similarity, result2.similarity, 1e-6f)
        assertEquals("Confidence must match exactly across repeated passes", result1.confidence, result2.confidence, 1e-6f)
    }

    @Test
    fun testCentroidComputationMathematicalMean() {
        val v1 = makeEmbedding(1.0f)
        val v2 = makeEmbedding(1.0f) // Identical
        val (centroid, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = listOf(v1, v2),
            qualityScores = listOf(90f, 90f)
        )

        assertEquals("Centroid of identical vectors must preserve sample count", 2, matrix.sampleCount)
        assertEquals("Average similarity between identical vectors is 1.0", 1.0f, matrix.averageSimilarity, 1e-4f)
        assertTrue("Centroid quality gate must pass", matrix.isConsistent)
    }

    @Test
    fun testQualityWeightsInfluenceCentroid() {
        val base = makeEmbedding(1.0f)
        val alt = makeEmbedding(2.0f)

        // Heavily weight the base vector
        val (centroidHighBase, _) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = listOf(base, alt),
            qualityScores = listOf(100f, 10f)
        )

        // Heavily weight the alt vector
        val (centroidHighAlt, _) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = listOf(base, alt),
            qualityScores = listOf(10f, 100f)
        )

        // Dot product of centroidHighBase with base should be higher than centroidHighAlt with base
        var dotBase1 = 0f
        var dotBase2 = 0f
        for (i in 0 until 512) {
            dotBase1 += centroidHighBase[i] * base[i]
            dotBase2 += centroidHighAlt[i] * base[i]
        }
        assertTrue("Centroid with higher base weight must be closer to base", dotBase1 > dotBase2)
    }

    @Test
    fun testDynamicCentroidAdaptationFormula() {
        val currentCentroid = makeEmbedding(1.0f)
        val liveEmbedding = makeEmbedding(2.0f)
        val template = FaceTemplateEntity("t_master", "R001", "MASTER_CENTROID", toCsv(currentCentroid), false)
        matcher.preloadTemplates(listOf(template))

        // High similarity >= 0.72 triggers EMA adaptation
        val adaptedPair = matcher.adaptCentroidIfHighConfidence("R001", liveEmbedding, 0.85f)
        assertNotNull("Adaptation should succeed for high confidence", adaptedPair)
        assertEquals("t_master", adaptedPair!!.first)

        // Verify adapted vector is 512 dimensions.
        // adaptCentroidIfHighConfidence returns an encrypted base64 CSV when AndroidSecurityUtils.encrypt
        // succeeds (even on host JVM via software AES fallback). Decrypt before parsing floats.
        val rawAdaptedCsv = run {
            val raw = adaptedPair.second
            // If it's a comma-separated float string directly, use it; otherwise try to decrypt.
            if (raw.contains(",") && raw.split(",").firstOrNull()?.trim()?.toFloatOrNull() != null) {
                raw
            } else {
                com.omniface.ai.security.AndroidSecurityUtils.decrypt(raw)
            }
        }
        assertTrue("Adapted CSV must be non-empty after decrypt", rawAdaptedCsv.isNotBlank())
        val adaptedVec = rawAdaptedCsv.split(",").map { it.trim().toFloat() }.toFloatArray()
        assertEquals(512, adaptedVec.size)
    }

    @Test
    fun testCompositeScoreWeighting() {
        val frontal = makeEmbedding(1.0f)
        val centroid = makeEmbedding(1.2f)

        val templates = listOf(
            FaceTemplateEntity("t1", "R001", "FRONTAL", toCsv(frontal), false),
            FaceTemplateEntity("t2", "R001", "MASTER_CENTROID", toCsv(centroid), false)
        )
        matcher.preloadTemplates(templates)

        val query = makeEmbedding(1.05f)
        val result = matcher.match(query, mapOf("R001" to "Charlie"), SecurityTier.STANDARD)

        assertNotNull(result)
        assertTrue("Composite similarity must be bounded in [0.0, 1.0]", result.similarity in 0f..1f)
    }
}
