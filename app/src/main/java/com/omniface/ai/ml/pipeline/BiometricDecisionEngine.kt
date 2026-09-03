package com.omniface.ai.ml.pipeline

import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.antispoof.MultiStageLivenessResult
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.quality.QualityGateResult

enum class PipelineGateState {
    PASS,
    REJECT_QUALITY,
    REJECT_SPOOF_ATTACK,
    REJECT_UNKNOWN_IDENTITY,
    REVIEW_AMBIGUOUS_MATCH;

    companion object {
        val VERIFIED = PASS
        val SPOOF_DETECTED = REJECT_SPOOF_ATTACK
        val QUALITY_REJECTED = REJECT_QUALITY
    }
}

data class BiometricSynthesisDecision(
    val gateState: PipelineGateState,
    val isAttendanceAuthorized: Boolean,
    val matchedStudentRoll: String,
    val matchedStudentName: String,
    val matchConfidence: Float,
    val matchSimilarity: Float,
    val decisionMargin: Float,
    val qualityScore: Float,
    val livenessScore: Float,
    val title: String,
    val subtitle: String,
    val technicalExplanation: String
)

/**
 * Three-Gate Biometric Decision Engine.
 *
 * Enforces the strict rule:
 * Highest Similarity != Automatically Accepted.
 *
 * GATE 1 (Quality) -> GATE 2 (Anti-Spoof PAD) -> GATE 3 (Identity Match)
 * A strong identity score CANNOT override a failed liveness or quality gate!
 */
object BiometricDecisionEngine {

    fun evaluatePipeline(
        quality: QualityGateResult,
        passivePad: PassivePadResult?,
        temporalLiveness: TemporalLivenessResult,
        matchResult: MatchResult?,
        securityTier: SecurityTier = SecurityTier.HIGH
    ): PipelineGateState {
        return evaluate(quality, passivePad, temporalLiveness, matchResult, securityTier).gateState
    }

    fun evaluate(
        quality: QualityGateResult,
        passivePad: PassivePadResult?,
        temporalLiveness: TemporalLivenessResult,
        matchResult: MatchResult?,
        securityTier: SecurityTier,
        multiStageLiveness: MultiStageLivenessResult? = null
    ): BiometricSynthesisDecision {

        // ── GATE 1: QUALITY EVALUATION ──
        if (!quality.isPassed) {
            return BiometricSynthesisDecision(
                gateState = PipelineGateState.REJECT_QUALITY,
                isAttendanceAuthorized = false,
                matchedStudentRoll = "",
                matchedStudentName = "",
                matchConfidence = 0f,
                matchSimilarity = 0f,
                decisionMargin = 0f,
                qualityScore = quality.overallQualityScore,
                livenessScore = 0f,
                title = "MOVE CLOSER & LEVEL FACE",
                subtitle = quality.rejectionReason,
                technicalExplanation = "Gate 1 (Quality) Failed: ${quality.rejectionReason} (Score: ${quality.overallQualityScore.toInt()}/100)"
            )
        }

        // ── GATE 2: ANTI-SPOOFING / PAD EVALUATION ──
        val isMultiStageLive = multiStageLiveness?.isLive ?: true
        val isPassiveLive = passivePad?.isLive ?: true
        val isTemporalLive = temporalLiveness.isLive
        val multiStageScore = multiStageLiveness?.overallLivenessScore ?: (passivePad?.livenessScore ?: 0.90f)
        val livenessScore = multiStageScore * 0.6f + temporalLiveness.temporalConfidence * 0.4f

        // Confirmed spoof rejection: passive PAD failure OR multi-stage + temporal consensus failure OR primary attack vector
        val isConfirmedSpoof = (!isPassiveLive && passivePad.spoofProbability >= 0.70f) ||
                               (!isMultiStageLive && !isTemporalLive && livenessScore < 0.45f) ||
                               (multiStageLiveness?.primaryAttackVector != null && livenessScore < 0.35f) ||
                               (!isTemporalLive && temporalLiveness.explanation.contains("Static"))

        if (isConfirmedSpoof) {
            val titleText = when (temporalLiveness.requiredAction) {
                com.omniface.ai.ml.antispoof.LivenessChallengeType.BLINK -> "PLEASE BLINK YOUR EYES"
                com.omniface.ai.ml.antispoof.LivenessChallengeType.TURN_LEFT,
                com.omniface.ai.ml.antispoof.LivenessChallengeType.TURN_RIGHT,
                com.omniface.ai.ml.antispoof.LivenessChallengeType.TILT_UP,
                com.omniface.ai.ml.antispoof.LivenessChallengeType.TILT_DOWN -> "TURN HEAD SLIGHTLY"
                com.omniface.ai.ml.antispoof.LivenessChallengeType.SMILE -> "PLEASE SMILE"
                null -> if (multiStageLiveness?.primaryAttackVector != null) "SPOOF ATTACK DETECTED" else "LIVENESS CHECK FAILED"
            }
            val attackDesc = multiStageLiveness?.primaryAttackVector
                ?: passivePad?.attackTypeDescription
                ?: temporalLiveness.explanation
            return BiometricSynthesisDecision(
                gateState = PipelineGateState.REJECT_SPOOF_ATTACK,
                isAttendanceAuthorized = false,
                matchedStudentRoll = "",
                matchedStudentName = "",
                matchConfidence = 0f,
                matchSimilarity = 0f,
                decisionMargin = 0f,
                qualityScore = quality.overallQualityScore,
                livenessScore = livenessScore,
                title = titleText,
                subtitle = attackDesc,
                technicalExplanation = "Gate 2 (PAD) Failed: $attackDesc (Live Score: ${"%.2f".format(livenessScore)})"
            )
        }

        // ── GATE 3: IDENTITY MATCH EVALUATION ──
        if (matchResult == null || !matchResult.isMatch) {
            val sim = matchResult?.similarity ?: 0.0f
            val isReview = matchResult?.confidenceZone == ConfidenceZone.REVIEW

            return if (isReview) {
                BiometricSynthesisDecision(
                    gateState = PipelineGateState.REVIEW_AMBIGUOUS_MATCH,
                    isAttendanceAuthorized = false,
                    matchedStudentRoll = matchResult.studentRoll,
                    matchedStudentName = matchResult.studentName,
                    matchConfidence = matchResult.confidence,
                    matchSimilarity = sim,
                    decisionMargin = matchResult.decisionMargin,
                    qualityScore = quality.overallQualityScore,
                    livenessScore = livenessScore,
                    title = "REVIEW REQUIRED",
                    subtitle = matchResult.explanation,
                    technicalExplanation = "Gate 3 (Identity) Borderline: ${matchResult.explanation}"
                )
            } else {
                BiometricSynthesisDecision(
                    gateState = PipelineGateState.REJECT_UNKNOWN_IDENTITY,
                    isAttendanceAuthorized = false,
                    matchedStudentRoll = "GUEST",
                    matchedStudentName = "Unknown Visitor",
                    matchConfidence = 0f,
                    matchSimilarity = sim,
                    decisionMargin = 0f,
                    qualityScore = quality.overallQualityScore,
                    livenessScore = livenessScore,
                    title = "UNKNOWN IDENTITY",
                    subtitle = "Match: ${(sim * 100).toInt()}% (Requires ≥${(securityTier.threshold * 100).toInt()}%)",
                    technicalExplanation = "Gate 3 (Identity) Failed: Cosine sim ${"%.3f".format(sim)} < threshold ${"%.3f".format(securityTier.threshold)}"
                )
            }
        }

        // ── ALL THREE GATES PASSED ──
        return BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = matchResult.studentRoll,
            matchedStudentName = matchResult.studentName,
            matchConfidence = matchResult.confidence,
            matchSimilarity = matchResult.similarity,
            decisionMargin = matchResult.decisionMargin,
            qualityScore = quality.overallQualityScore,
            livenessScore = livenessScore,
            title = "✓ VERIFIED: ${matchResult.studentName.uppercase()}",
            subtitle = "${"%.1f".format(matchResult.confidence)}% Match • Live 3D Face Verified",
            technicalExplanation = "Gate 1: PASS, Gate 2: PASS, Gate 3: PASS (${matchResult.explanation})"
        )
    }
}
