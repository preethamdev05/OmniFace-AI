package com.omniface.ai.ml

import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.verification.policy.AutomationAction
import com.omniface.ai.ml.verification.policy.AutomationPolicy
import org.junit.Assert.*
import org.junit.Test

/**
 * 🧪 Unit Test Suite for [AutomationPolicy] (Stage 14 & 15).
 *
 * Verifies:
 * 1. Immediate auto-confirm on high-quality captures with decisive similarity & margin.
 * 2. Adaptive frame requirements based on capture quality (faster on clean frames).
 * 3. Decision margin gating (prevents ambiguous identity assignments).
 * 4. Cooldown window enforcement & duplicate prevention.
 * 5. Rejection handling when quality or liveness fails.
 */
class AutomationPolicyTest {

    private fun createPassSynthesis(
        similarity: Float = 0.75f,
        margin: Float = 0.12f,
        qualityScore: Float = 85.0f,
        livenessScore: Float = 95.0f
    ): BiometricSynthesisDecision {
        return BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "ROLL_001",
            matchedStudentName = "Rahul Sharma",
            matchConfidence = similarity * 100f,
            matchSimilarity = similarity,
            decisionMargin = margin,
            qualityScore = qualityScore,
            livenessScore = livenessScore,
            title = "VERIFIED",
            subtitle = "Rahul Sharma",
            technicalExplanation = "All gates passed"
        )
    }

    private fun createQualityResult(score: Float = 85.0f): QualityGateResult {
        return QualityGateResult(
            isPassed = score >= 50.0f,
            overallQualityScore = score,
            sharpnessScore = score,
            exposureScore = score,
            poseScore = score,
            sizeScore = score,
            rejectionReason = if (score < 50.0f) "Blurry" else ""
        )
    }

    @Test
    fun testAutoConfirm_highQualityHighConfidence() {
        val synthesis = createPassSynthesis(similarity = 0.78f, margin = 0.15f)
        val quality = createQualityResult(score = 80.0f)

        // 1 frame on high similarity and quality >= 70
        val decision = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 1,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )

        assertEquals(AutomationAction.AUTO_CONFIRM, decision.action)
        assertEquals("ROLL_001", decision.studentRoll)
        assertEquals(1, decision.requiredFrames)
    }

    @Test
    fun testObserveMoreFrames_whenAccumulatingFrames() {
        val synthesis = createPassSynthesis(similarity = 0.65f, margin = 0.08f, qualityScore = 55.0f)
        val quality = createQualityResult(score = 55.0f)

        // Quality 55 requires 3 frames; 1 frame provided -> observe more
        val decision1 = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 1,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )
        assertEquals(AutomationAction.OBSERVE_MORE_FRAMES, decision1.action)
        assertEquals(3, decision1.requiredFrames)

        // 3 frames provided -> auto-confirm!
        val decision3 = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 3,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )
        assertEquals(AutomationAction.AUTO_CONFIRM, decision3.action)
    }

    @Test
    fun testObserveMoreFrames_whenDecisionMarginBelowThreshold() {
        // High similarity but ambiguous margin (0.02 < 0.04)
        val synthesis = createPassSynthesis(similarity = 0.80f, margin = 0.02f)
        val quality = createQualityResult(score = 85.0f)

        val decision = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 2,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )

        assertEquals(AutomationAction.OBSERVE_MORE_FRAMES, decision.action)
        assertTrue(decision.reason.contains("margin"))
    }

    @Test
    fun testCooldown_whenWithinCooldownWindow() {
        val synthesis = createPassSynthesis()
        val quality = createQualityResult()
        val now = System.currentTimeMillis()

        // Verified 10 seconds ago (cooldown is 60s)
        val decision = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 5,
            lastVerifiedTimestampMs = now - 10_000L,
            hasTrackAlreadyTriggered = false,
            currentTimeMs = now
        )

        assertEquals(AutomationAction.COOLDOWN, decision.action)
    }

    @Test
    fun testCooldown_whenTrackAlreadyTriggered() {
        val synthesis = createPassSynthesis()
        val quality = createQualityResult()
        val now = System.currentTimeMillis()

        // Even if cooldown timestamp is old, track already triggered
        val decision = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 5,
            lastVerifiedTimestampMs = now - 100_000L,
            hasTrackAlreadyTriggered = true,
            currentTimeMs = now
        )

        assertEquals(AutomationAction.COOLDOWN, decision.action)
    }

    @Test
    fun testRejectOrWait_whenGateNotPass() {
        val synthesis = BiometricSynthesisDecision(
            gateState = PipelineGateState.REJECT_SPOOF_ATTACK,
            isAttendanceAuthorized = false,
            matchedStudentRoll = "",
            matchedStudentName = "",
            matchConfidence = 0f,
            matchSimilarity = 0f,
            decisionMargin = 0f,
            qualityScore = 80f,
            livenessScore = 10f,
            title = "SPOOF ALERT",
            subtitle = "Screen attack detected",
            technicalExplanation = "MiniFASNet PAD triggered"
        )

        val decision = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = null,
            consecutiveMatchCount = 3,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )

        assertEquals(AutomationAction.REJECT_OR_WAIT, decision.action)
    }

    @Test
    fun testAdaptiveFrames_scalesWithQuality() {
        // High quality (>= 70) and similarity >= 0.72 -> 1 frame
        assertEquals(1, AutomationPolicy.calculateRequiredFrames(qualityScore = 75f, similarity = 0.75f))

        // High quality (>= 60) -> 2 frames
        assertEquals(2, AutomationPolicy.calculateRequiredFrames(qualityScore = 65f, similarity = 0.60f))

        // Moderate quality (40..59) -> 3 frames
        assertEquals(3, AutomationPolicy.calculateRequiredFrames(qualityScore = 45f, similarity = 0.60f))

        // Marginal quality (< 40) -> 4 frames
        assertEquals(4, AutomationPolicy.calculateRequiredFrames(qualityScore = 32f, similarity = 0.60f))
    }

    @Test
    fun testRejectOrWait_whenQualityBelowThreshold() {
        val synthesis = createPassSynthesis(qualityScore = 25.0f)
        val quality = createQualityResult(score = 25.0f)

        val decision = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 3,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )

        assertEquals(AutomationAction.REJECT_OR_WAIT, decision.action)
        assertTrue(decision.reason.contains("30.0"))
    }

    @Test
    fun testNeedsReview_whenBiometricGateReview() {
        val synthesis = BiometricSynthesisDecision(
            gateState = PipelineGateState.REVIEW_AMBIGUOUS_MATCH,
            isAttendanceAuthorized = false,
            matchedStudentRoll = "ROLL_002",
            matchedStudentName = "Review Candidate",
            matchConfidence = 65f,
            matchSimilarity = 0.65f,
            decisionMargin = 0.03f,
            qualityScore = 75f,
            livenessScore = 80f,
            title = "MANUAL REVIEW",
            subtitle = "Supervisor inspection required",
            technicalExplanation = "Ambiguous identity match in boundary region"
        )
        val quality = createQualityResult(score = 75.0f)

        val decision = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 2,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )

        assertEquals(AutomationAction.NEEDS_REVIEW, decision.action)
        assertTrue(decision.reason.contains("Biometric review required"))
    }

    @Test
    fun testNeedsReview_whenAmbiguousMarginPersistsAcrossFrames() {
        val synthesis = createPassSynthesis(similarity = 0.72f, margin = 0.02f)
        val quality = createQualityResult(score = 70.0f)

        // Frames 1-3 yield OBSERVE_MORE_FRAMES
        val decisionEarly = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 2,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )
        assertEquals(AutomationAction.OBSERVE_MORE_FRAMES, decisionEarly.action)

        // Persistent ambiguity on frame 4+ yields NEEDS_REVIEW
        val decisionPersist = AutomationPolicy.evaluate(
            synthesis = synthesis,
            quality = quality,
            consecutiveMatchCount = 4,
            lastVerifiedTimestampMs = 0L,
            hasTrackAlreadyTriggered = false
        )
        assertEquals(AutomationAction.NEEDS_REVIEW, decisionPersist.action)
        assertTrue(decisionPersist.reason.contains("Ambiguous identity match"))
    }
}
