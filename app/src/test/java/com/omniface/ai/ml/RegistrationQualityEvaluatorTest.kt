package com.omniface.ai.ml

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class RegistrationQualityEvaluatorTest {

    @Test
    fun testQualityWeightedCentroid_singleVector_isNormalized() {
        val emb = FloatArray(512) { 1.0f }
        var sumSq = 0.0f
        for (v in emb) sumSq += v * v
        val norm = sqrt(sumSq)
        for (i in emb.indices) emb[i] /= norm

        val (centroid, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = listOf(emb),
            qualityScores = listOf(100f)
        )

        assertEquals(1, matrix.sampleCount)
        assertEquals(1.0f, matrix.averageSimilarity, 1e-4f)
        assertTrue(matrix.isConsistent)

        // Verify centroid L2 norm is ~1.0
        var cNormSq = 0f
        for (v in centroid) cNormSq += v * v
        assertEquals(1.0f, sqrt(cNormSq), 1e-4f)
    }

    @Test
    fun testQualityWeightedCentroid_consistentMultiAngle_passesGate() {
        // Construct 5 consistent angle vectors with high intra-identity similarity (> 0.85)
        val base = FloatArray(512) { 0.5f }
        var baseNormSq = 0f
        for (v in base) baseNormSq += v * v
        val baseNorm = sqrt(baseNormSq)
        for (i in base.indices) base[i] /= baseNorm

        val samples = mutableListOf<FloatArray>()
        for (k in 0 until 5) {
            val s = base.clone()
            // Add subtle variation (e.g. angle shift)
            s[k] += 0.02f
            var sNormSq = 0f
            for (v in s) sNormSq += v * v
            val sNorm = sqrt(sNormSq)
            for (i in s.indices) s[i] /= sNorm
            samples.add(s)
        }

        val qualityScores = listOf(98f, 95f, 96f, 92f, 94f)
        val (centroid, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = samples,
            qualityScores = qualityScores
        )

        assertEquals(5, matrix.sampleCount)
        assertTrue("Expected average similarity > 0.90", matrix.averageSimilarity > 0.90f)
        assertTrue("Expected minimum similarity >= 0.78", matrix.minimumSimilarity >= 0.78f)
        assertTrue(matrix.isConsistent)
    }

    @Test
    fun testQualityWeightedCentroid_inconsistentOutlier_detected() {
        // Base identity
        val base = FloatArray(512) { 0.5f }
        var baseNormSq = 0f
        for (v in base) baseNormSq += v * v
        for (i in base.indices) base[i] /= sqrt(baseNormSq)

        // Orthogonal/outlier vector (different person/corrupt angle)
        val outlier = FloatArray(512) { i -> if (i % 2 == 0) 1.0f else -1.0f }
        var outNormSq = 0f
        for (v in outlier) outNormSq += v * v
        for (i in outlier.indices) outlier[i] /= sqrt(outNormSq)

        val samples = listOf(base.clone(), base.clone(), base.clone(), base.clone(), outlier)
        val qualityScores = listOf(95f, 95f, 95f, 95f, 90f)

        val (_, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = samples,
            qualityScores = qualityScores
        )

        assertFalse("Expected inconsistent registration to be flagged", matrix.isConsistent)
        assertTrue("Expected minimum similarity to drop below threshold", matrix.minimumSimilarity < 0.78f)
    }
}
