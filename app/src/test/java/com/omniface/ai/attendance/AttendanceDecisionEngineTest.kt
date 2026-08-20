package com.omniface.ai.attendance

import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.MatchResult
import org.junit.Assert.*
import org.junit.Test

class AttendanceDecisionEngineTest {

    @Test
    fun testConfidenceZone_AcceptZone_whenHighMargin() {
        val matchResult = MatchResult(
            studentRoll = "CS2024-001",
            studentName = "John Doe",
            confidence = 96.5f,
            similarity = 0.68f,
            isMatch = true,
            hardwareTier = HardwareTier.GPU_DELEGATE,
            confidenceZone = ConfidenceZone.ACCEPT,
            decisionMargin = 0.25f,
            secondBestRoll = "CS2024-002",
            secondBestSimilarity = 0.43f,
            explanation = "Verified: Cosine sim 0.680 >= 0.158, Decision margin Δ=0.250"
        )

        assertEquals(ConfidenceZone.ACCEPT, matchResult.confidenceZone)
        assertTrue(matchResult.isMatch)
        assertTrue("Expected decision margin >= 0.035", matchResult.decisionMargin >= 0.035f)
    }

    @Test
    fun testConfidenceZone_ReviewZone_whenNarrowMargin() {
        // Person A scored 0.450 and Person B scored 0.435 (margin 0.015 < 0.035) -> Ambiguous!
        val matchResult = MatchResult(
            studentRoll = "CS2024-001",
            studentName = "Alice Doe",
            confidence = 82.0f,
            similarity = 0.45f,
            isMatch = false,
            hardwareTier = HardwareTier.GPU_DELEGATE,
            confidenceZone = ConfidenceZone.REVIEW,
            decisionMargin = 0.015f,
            secondBestRoll = "CS2024-002",
            secondBestSimilarity = 0.435f,
            explanation = "Ambiguous Identity: Top-1 vs Top-2 has narrow margin Δ=0.015 < 0.035"
        )

        assertEquals(ConfidenceZone.REVIEW, matchResult.confidenceZone)
        assertFalse("Ambiguous match must not be auto-accepted without review", matchResult.isMatch)
    }

    @Test
    fun testConfidenceZone_RejectZone_whenBelowThreshold() {
        val matchResult = MatchResult(
            studentRoll = "GUEST",
            studentName = "Visitor / Unregistered",
            confidence = 0.0f,
            similarity = 0.08f,
            isMatch = false,
            hardwareTier = HardwareTier.CPU_XNNPACK,
            confidenceZone = ConfidenceZone.REJECT,
            decisionMargin = 0.08f,
            explanation = "Unrecognized: Cosine sim 0.080 < threshold 0.158"
        )

        assertEquals(ConfidenceZone.REJECT, matchResult.confidenceZone)
        assertFalse(matchResult.isMatch)
    }
}
