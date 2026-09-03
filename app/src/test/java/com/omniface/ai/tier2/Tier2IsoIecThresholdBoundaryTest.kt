package com.omniface.ai.tier2

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.recognition.FaceMatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 3: Threshold Epsilon Boundaries
 */
class Tier2IsoIecThresholdBoundaryTest {

    private lateinit var matcher: FaceMatcher

    @Before
    fun setUp() {
        matcher = FaceMatcher()
    }

    private fun makeVectorWithSim(targetSim: Float): Pair<FloatArray, FloatArray> {
        // Construct 2 512-D vectors with cosine similarity close to targetSim
        val v1 = FloatArray(512) { 0f }.also { it[0] = 1f }
        val v2 = FloatArray(512) { 0f }.also {
            it[0] = targetSim
            it[1] = kotlin.math.sqrt((1f - targetSim * targetSim).coerceAtLeast(0f))
        }
        return v1 to v2
    }

    @Test
    fun testExactThresholdBoundaryScore() {
        val (query, templateVec) = makeVectorWithSim(0.720f)
        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", templateVec.joinToString(","), false)
        matcher.preloadTemplates(listOf(template))

        val result = matcher.match(query, mapOf("R001" to "Alice"), SecurityTier.HIGH)
        assertNotNull(result)
        assertEquals(0.720f, result.similarity, 1e-3f)
    }

    @Test
    fun testScoreEpsilonBelowThreshold() {
        val (query, templateVec) = makeVectorWithSim(0.710f) // Just below 0.720
        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", templateVec.joinToString(","), false)
        matcher.preloadTemplates(listOf(template))

        val result = matcher.match(query, mapOf("R001" to "Alice"), SecurityTier.HIGH)
        assertFalse("Score below threshold must not match", result.isMatch)
        assertNotEquals(ConfidenceZone.ACCEPT, result.confidenceZone)
    }

    @Test
    fun testScoreEpsilonAboveThreshold() {
        val (query, templateVec) = makeVectorWithSim(0.730f) // Just above 0.720
        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", templateVec.joinToString(","), false)
        matcher.preloadTemplates(listOf(template))

        val result = matcher.match(query, mapOf("R001" to "Alice"), SecurityTier.HIGH)
        assertTrue("Score above threshold with sole candidate must match", result.isMatch)
        assertEquals(ConfidenceZone.ACCEPT, result.confidenceZone)
    }

    @Test
    fun testDecisionMarginBoundary() {
        // Top 1 similarity 0.85, Top 2 similarity 0.80 -> Margin = 0.050 (>= 0.045 for HIGH)
        val (_, vTop1) = makeVectorWithSim(0.850f)
        val (_, vTop2) = makeVectorWithSim(0.800f)
        val query = FloatArray(512) { 0f }.also { it[0] = 1f }

        val templates = listOf(
            FaceTemplateEntity("t1", "R001", "FRONTAL", vTop1.joinToString(","), false),
            FaceTemplateEntity("t2", "R002", "FRONTAL", vTop2.joinToString(","), false)
        )
        matcher.preloadTemplates(templates)

        val result = matcher.match(query, mapOf("R001" to "Alice", "R002" to "Bob"), SecurityTier.HIGH)
        assertTrue("Decision margin >= 0.045 should be accepted", result.isMatch)
    }

    @Test
    fun testNegativeSimilarityScoreBoundary() {
        val v1 = FloatArray(512) { 0f }.also { it[0] = 1f }
        val v2 = FloatArray(512) { 0f }.also { it[0] = -1f }

        val template = FaceTemplateEntity("t1", "R001", "FRONTAL", v2.joinToString(","), false)
        matcher.preloadTemplates(listOf(template))

        val result = matcher.match(v1, mapOf("R001" to "Alice"), SecurityTier.HIGH)
        assertFalse(result.isMatch)
        assertEquals(ConfidenceZone.REJECT, result.confidenceZone)
        assertEquals(0.0f, result.confidence, 1e-4f)
    }
}
