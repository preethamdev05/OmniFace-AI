package com.omniface.ai.ml.verification.policy

import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult

/**
 * Autonomous Attendance Confirmation Action.
 */
enum class AutomationAction {
    /** High-confidence, live, unambiguous identity - attend immediately without user intervention. */
    AUTO_CONFIRM,

    /** Borderline score, ambiguous nearest neighbor, or awaiting temporal frame consensus. */
    OBSERVE_MORE_FRAMES,

    /** Quality rejection, spoof attack, or unrecognized identity - do not attend. */
    REJECT_OR_WAIT,

    /** Student already successfully verified within cooldown period. */
    COOLDOWN,

    /** Uncertain/ambiguous match, borderline margin, or supervisor inspection required. */
    NEEDS_REVIEW
}

/**
 * Detailed telemetry and decision produced by [AutomationPolicy].
 */
data class AutomationDecision(
    val action: AutomationAction,
    val studentRoll: String,
    val studentName: String,
    val confidencePct: Float,
    val livenessScore: Float,
    val matchSimilarity: Float,
    val decisionMargin: Float,
    val requiredFrames: Int,
    val currentFrames: Int,
    val reason: String
)

/**
 * Sovereign Automation Policy for OmniFace AI (Stage 14 & 15).
 *
 * Implements the core product north-star:
 * 1. Automatic hands-free confirmation:
 *    PERSON ENTERS -> TRACK -> QUALITY GATE -> MULTI-FRAME EVIDENCE ->
 *    VERIFICATION -> AUTOMATION POLICY -> AUTO-CONFIRM -> ATOMIC ATTENDANCE.
 *
 * 2. Adaptive Quality Policy (Stage 15):
 *    Never compromises security for poor cameras. Instead, adapts temporal consensus:
 *    - EXCELLENT (Quality >= 70 & Sim >= 0.72): 1-2 frames (fast confirmation).
 *    - GOOD (Quality >= 60): 2 frames standard consensus.
 *    - MODERATE (Quality 40..59): 3 frames required.
 *    - MARGINAL (Quality 30..39): 4 frames required.
 *    - REJECT (Quality < 30): Reject / wait for clearer capture.
 *
 * 3. Conservative on Uncertainty:
 *    Requires decision margin >= 0.04 over nearest imposter to prevent false identity claims.
 */
object AutomationPolicy {
    const val COOLDOWN_PERIOD_MS = 60_000L
    const val MIN_DECISION_MARGIN = 0.04f

    /**
     * Adaptively calculates required consecutive consensus frames based on
     * empirical image quality and similarity confidence.
     */
    fun calculateRequiredFrames(qualityScore: Float, similarity: Float): Int {
        return when {
            similarity >= 0.72f && qualityScore >= 70.0f -> 1
            qualityScore >= 60.0f -> 2
            qualityScore >= 40.0f -> 3
            else -> 4
        }
    }

    /**
     * Evaluates whether a synthesized biometric decision satisfies the strict
     * requirements for hands-free automatic attendance confirmation.
     */
    fun evaluate(
        synthesis: BiometricSynthesisDecision,
        quality: QualityGateResult?,
        consecutiveMatchCount: Int,
        lastVerifiedTimestampMs: Long,
        hasTrackAlreadyTriggered: Boolean,
        currentTimeMs: Long = System.currentTimeMillis()
    ): AutomationDecision {
        // Gate 0: Poor Quality Gate - Poor quality never forces recognition
        val qScore = quality?.overallQualityScore ?: synthesis.qualityScore
        if ((quality != null && !quality.isPassed) || qScore < 30.0f) {
            return AutomationDecision(
                action = AutomationAction.REJECT_OR_WAIT,
                studentRoll = synthesis.matchedStudentRoll,
                studentName = synthesis.matchedStudentName,
                confidencePct = synthesis.matchConfidence,
                livenessScore = synthesis.livenessScore,
                matchSimilarity = synthesis.matchSimilarity,
                decisionMargin = synthesis.decisionMargin,
                requiredFrames = 4,
                currentFrames = consecutiveMatchCount,
                reason = "Image quality rejected (score: %.1f < 30.0): %s".format(
                    qScore, quality?.rejectionReason?.ifBlank { "Low quality capture" } ?: "Low quality capture"
                )
            )
        }

        // Gate 1: Biometric Gate State
        if (synthesis.gateState == PipelineGateState.REVIEW_AMBIGUOUS_MATCH) {
            return AutomationDecision(
                action = AutomationAction.NEEDS_REVIEW,
                studentRoll = synthesis.matchedStudentRoll,
                studentName = synthesis.matchedStudentName,
                confidencePct = synthesis.matchConfidence,
                livenessScore = synthesis.livenessScore,
                matchSimilarity = synthesis.matchSimilarity,
                decisionMargin = synthesis.decisionMargin,
                requiredFrames = 2,
                currentFrames = consecutiveMatchCount,
                reason = "Biometric review required: ${synthesis.technicalExplanation}"
            )
        }

        if (synthesis.gateState != PipelineGateState.PASS || synthesis.matchedStudentRoll.isBlank()) {
            return AutomationDecision(
                action = AutomationAction.REJECT_OR_WAIT,
                studentRoll = synthesis.matchedStudentRoll,
                studentName = synthesis.matchedStudentName,
                confidencePct = synthesis.matchConfidence,
                livenessScore = synthesis.livenessScore,
                matchSimilarity = synthesis.matchSimilarity,
                decisionMargin = synthesis.decisionMargin,
                requiredFrames = 2,
                currentFrames = consecutiveMatchCount,
                reason = "Biometric gate ${synthesis.gateState.name}: ${synthesis.technicalExplanation}"
            )
        }

        val roll = synthesis.matchedStudentRoll
        val isCooldownElapsed = (currentTimeMs - lastVerifiedTimestampMs > COOLDOWN_PERIOD_MS)

        // Gate 3: Track and Identity Cooldown Enforcement
        if (hasTrackAlreadyTriggered || !isCooldownElapsed) {
            val remainingSec = ((COOLDOWN_PERIOD_MS - (currentTimeMs - lastVerifiedTimestampMs)) / 1000L).coerceAtLeast(0)
            return AutomationDecision(
                action = AutomationAction.COOLDOWN,
                studentRoll = roll,
                studentName = synthesis.matchedStudentName,
                confidencePct = synthesis.matchConfidence,
                livenessScore = synthesis.livenessScore,
                matchSimilarity = synthesis.matchSimilarity,
                decisionMargin = synthesis.decisionMargin,
                requiredFrames = 1,
                currentFrames = consecutiveMatchCount,
                reason = "Identity $roll in cooldown ($remainingSec s remaining)"
            )
        }

        // Gate 4: Decision Margin Verification (Prevent Ambiguous Imposter Matches)
        if (synthesis.decisionMargin < MIN_DECISION_MARGIN && synthesis.decisionMargin > 0f) {
            if (consecutiveMatchCount >= 4) {
                return AutomationDecision(
                    action = AutomationAction.NEEDS_REVIEW,
                    studentRoll = roll,
                    studentName = synthesis.matchedStudentName,
                    confidencePct = synthesis.matchConfidence,
                    livenessScore = synthesis.livenessScore,
                    matchSimilarity = synthesis.matchSimilarity,
                    decisionMargin = synthesis.decisionMargin,
                    requiredFrames = 4,
                    currentFrames = consecutiveMatchCount,
                    reason = "Ambiguous identity match: decision margin (%.3f) below threshold (%.2f) across %d frames".format(
                        synthesis.decisionMargin, MIN_DECISION_MARGIN, consecutiveMatchCount
                    )
                )
            }
            return AutomationDecision(
                action = AutomationAction.OBSERVE_MORE_FRAMES,
                studentRoll = roll,
                studentName = synthesis.matchedStudentName,
                confidencePct = synthesis.matchConfidence,
                livenessScore = synthesis.livenessScore,
                matchSimilarity = synthesis.matchSimilarity,
                decisionMargin = synthesis.decisionMargin,
                requiredFrames = 3,
                currentFrames = consecutiveMatchCount,
                reason = "Decision margin (%.3f) below threshold (%.2f) - observing more frames".format(
                    synthesis.decisionMargin, MIN_DECISION_MARGIN
                )
            )
        }

        // Gate 5: Quality-Adaptive Temporal Consensus
        val requiredFrames = calculateRequiredFrames(qScore, synthesis.matchSimilarity)

        return if (consecutiveMatchCount >= requiredFrames) {
            AutomationDecision(
                action = AutomationAction.AUTO_CONFIRM,
                studentRoll = roll,
                studentName = synthesis.matchedStudentName,
                confidencePct = synthesis.matchConfidence,
                livenessScore = synthesis.livenessScore,
                matchSimilarity = synthesis.matchSimilarity,
                decisionMargin = synthesis.decisionMargin,
                requiredFrames = requiredFrames,
                currentFrames = consecutiveMatchCount,
                reason = "Auto-confirmed: similarity %.2f, quality %.1f, consensus %d/%d frames".format(
                    synthesis.matchSimilarity, qScore, consecutiveMatchCount, requiredFrames
                )
            )
        } else {
            AutomationDecision(
                action = AutomationAction.OBSERVE_MORE_FRAMES,
                studentRoll = roll,
                studentName = synthesis.matchedStudentName,
                confidencePct = synthesis.matchConfidence,
                livenessScore = synthesis.livenessScore,
                matchSimilarity = synthesis.matchSimilarity,
                decisionMargin = synthesis.decisionMargin,
                requiredFrames = requiredFrames,
                currentFrames = consecutiveMatchCount,
                reason = "Accumulating temporal consensus (%d/%d frames, quality %.1f)".format(
                    consecutiveMatchCount, requiredFrames, qScore
                )
            )
        }
    }
}
