package com.omniface.ai.ml.pipeline

import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.quality.QualityGateResult

enum class PipelineGateState {
    PASS,
    REJECT_QUALITY,
    REJECT_SPOOF_ATTACK,
    REJECT_UNKNOWN_IDENTITY,
    REVIEW_AMBIGUOUS_MATCH
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

    fun evaluate(
        quality: QualityGateResult,
        passivePad: PassivePadResult?,
        temporalLiveness: TemporalLivenessResult,
        matchResult: MatchResult?,
        securityTier: SecurityTier
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
        val isPassiveLive = passivePad?.isLive ?: true
        val isTemporalLive = temporalLiveness.isLive
        val livenessScore = (passivePad?.livenessScore ?: 0.90f) * 0.6f + temporalLiveness.temporalConfidence * 0.4f

        if (!isPassiveLive || !isTemporalLive) {
            val attackDesc = passivePad?.attackTypeDescription ?: temporalLiveness.explanation
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
                title = "SPOOF ATTACK DETECTED",
                subtitle = attackDesc,
                technicalExplanation = "Gate 2 (PAD) Failed: $attackDesc (Live Score: ${"%.2f".format(livenessScore)})"
            )
        }

        // ── GATE 3: IDENTITY MATCH EVALUATION ──
        if (matchResult == null || !matchResult.isMatch) {
            val sim = matchResult?.similarity ?: 0.0f
            val isReview = matchResult?.confidenceZone == ConfidenceZone.REVIEW

            return if (isReview && matchResult != null) {
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
                    subtitle = "Score: ${"%.3f".format(sim)} < ${"%.3f".format(securityTier.threshold)}",
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
            subtitle = "${matchResult.confidence.toInt()}% Match • Live 3D Face Verified",
            technicalExplanation = "Gate 1: PASS, Gate 2: PASS, Gate 3: PASS (${matchResult.explanation})"
        )
    }
}
