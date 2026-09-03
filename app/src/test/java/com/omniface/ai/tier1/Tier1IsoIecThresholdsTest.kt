package com.omniface.ai.tier1

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.recognition.FaceMatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 1: Feature 3 - Calibrated ISO/IEC Decision Thresholds (STANDARD, HIGH, STRICT)
 */
class Tier1IsoIecThresholdsTest {

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

    private fun makeEmbedding(seed: Float): FloatArray {
        return l2Normalize(FloatArray(512) { i -> seed + i * 0.001f })
    }

    private fun toCsv(v: FloatArray): String =
        v.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }

    @Test
    fun testStandardTierThresholdAndMargin() {
        val tier = SecurityTier.STANDARD
        assertEquals("STANDARD tier threshold must be calibrated to 0.650", 0.650f, tier.threshold, 1e-4f)
        assertEquals("STANDARD tier marginThreshold must be 0.040", 0.040f, tier.marginThreshold, 1e-4f)
        assertTrue("STANDARD tier label should indicate Standard", tier.displayName.contains("Standard") || tier.name == "STANDARD")
    }

    @Test
    fun testHighTierThresholdAndMargin() {
        val tier = SecurityTier.HIGH
        assertEquals("HIGH tier (ISO/IEC baseline) threshold must be 0.720", 0.720f, tier.threshold, 1e-4f)
        assertEquals("HIGH tier marginThreshold must be 0.045", 0.045f, tier.marginThreshold, 1e-4f)
    }

    @Test
    fun testStrictTierThresholdAndMargin() {
        val tier = SecurityTier.STRICT
        assertEquals("STRICT tier threshold must be 0.800", 0.800f, tier.threshold, 1e-4f)
        assertEquals("STRICT tier marginThreshold must be 0.050", 0.050f, tier.marginThreshold, 1e-4f)
    }

    @Test
    fun testDecisionMarginRejectsAmbiguousMatch() {
        // Person 1 and Person 2 have very close embeddings to query
        val query = makeEmbedding(1.0f)
        val person1 = makeEmbedding(1.01f) // very close
        val person2 = makeEmbedding(1.015f) // also very close

        val templates = listOf(
            FaceTemplateEntity("t1", "R001", "FRONTAL", toCsv(person1), false),
            FaceTemplateEntity("t2", "R002", "FRONTAL", toCsv(person2), false)
        )
        matcher.preloadTemplates(templates)

        val result = matcher.match(query, mapOf("R001" to "Alice", "R002" to "Bob"), SecurityTier.STRICT)

        // If the margin between top 1 and top 2 is narrower than marginThreshold (0.040 for STRICT), must go to REVIEW
        if (result.decisionMargin < SecurityTier.STRICT.marginThreshold) {
            assertEquals("Narrow decision margin must place result in REVIEW zone", ConfidenceZone.REVIEW, result.confidenceZone)
            assertFalse("Ambiguous match with narrow margin must not auto-accept", result.isMatch)
        }
    }

    @Test
    fun testDecisionMarginAcceptsDecisiveMatch() {
        val query = FloatArray(512) { 0f }.also { it[0] = 1f }
        val target = FloatArray(512) { 0f }.also { it[0] = 1f } // Exact match (sim = 1.0)
        val distant = FloatArray(512) { 0f }.also { it[5] = 1f } // Orthogonal (sim = 0.0)

        val templates = listOf(
            FaceTemplateEntity("t1", "R001", "FRONTAL", toCsv(target), false),
            FaceTemplateEntity("t2", "R002", "FRONTAL", toCsv(distant), false)
        )
        matcher.preloadTemplates(templates)

        val result = matcher.match(query, mapOf("R001" to "Alice", "R002" to "Distant Person"), SecurityTier.HIGH)

        assertTrue("High margin top match must be accepted", result.isMatch)
        assertEquals(ConfidenceZone.ACCEPT, result.confidenceZone)
        assertEquals("R001", result.studentRoll)
        assertTrue("Decision margin must exceed threshold", result.decisionMargin >= SecurityTier.HIGH.marginThreshold)
    }
}
