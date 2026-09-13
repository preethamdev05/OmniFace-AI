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
        val REVIEW = REVIEW_AMBIGUOUS_MATCH
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
        multiStageLiveness: MultiStageLivenessResult? = null,
        faceMap3DMM: com.omniface.ai.ml.FaceMap3DMMResult? = null
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

        // 3D Physical Topography from FaceMap 3DMM
        val has3DDepth = faceMap3DMM?.let { it.depthVariance > 0.0015f } ?: temporalLiveness.stable3DDepth
        val isPlanarSurface = faceMap3DMM?.let { it.depthVariance <= 0.0015f } ?: (!temporalLiveness.stable3DDepth)
        val depthScore = when {
            faceMap3DMM != null -> if (faceMap3DMM.depthVariance > 0.0015f) 1.0f else (faceMap3DMM.depthVariance / 0.0015f).coerceIn(0f, 1f)
            temporalLiveness.stable3DDepth -> 1.0f
            else -> 0.2f
        }

        // Multi-modal consensus fusion score:
        // When faceMap3DMM is present, combine 2D texture (40%), 3D geometry (30%), and temporal dynamics (30%)
        val livenessScore = if (faceMap3DMM != null) {
            (multiStageScore * 0.40f + depthScore * 0.30f + temporalLiveness.temporalConfidence * 0.30f).coerceIn(0f, 1f)
        } else {
            multiStageScore * 0.6f + temporalLiveness.temporalConfidence * 0.4f
        }

        // Multi-Modal Anti-Spoofing Consensus & Threat Classification:
        // 1. High-confidence passive PAD attack (spoof >= 70% AND failed liveness)
        val isHighConfidencePadSpoof = (!isPassiveLive && passivePad.spoofProbability >= 0.70f)

        // 2. Planar attack consensus (2D photo / screen replay):
        // Physical 3D surface is planar (depthVariance <= 0.0015) AND (passive PAD failed OR temporal static OR multi-stage flagged)
        val isPlanarAttack = isPlanarSurface && (
            !isPassiveLive ||
            !isMultiStageLive ||
            !isTemporalLive ||
            (multiStageLiveness?.stageBreakdown?.hasUnnaturalPaperFlatness == true) ||
            (multiStageLiveness?.stageBreakdown?.hasSpecularScreenHotspots == true) ||
            (multiStageLiveness?.stageBreakdown?.hasPeriodicDisplayGrid == true)
        )

        // 3. Screen Replay Display Attack
        val isScreenReplay = (multiStageLiveness?.stageBreakdown?.hasSpecularScreenHotspots == true || multiStageLiveness?.stageBreakdown?.hasPeriodicDisplayGrid == true) && (!isPassiveLive || isPlanarSurface)

        // 4. 2D Printed Paper Photo Attack
        val isPaperPrintAttack = (multiStageLiveness?.stageBreakdown?.hasUnnaturalPaperFlatness == true) && (!isPassiveLive || isPlanarSurface)

        // 5. 3D Mask / Prosthetic Attack: Artificial material lacking physiological blood pulse
        val is3DMaskAttack = has3DDepth &&
            (multiStageLiveness?.stageBreakdown?.chromaticScore ?: 1.0f) < 0.30f &&
            (temporalLiveness.rppgVitality != null && !temporalLiveness.rppgVitality.isLive)

        // 6. Explicit static attack with zero micro-motion
        val isStaticReplay = (!isTemporalLive && temporalLiveness.explanation.contains("Static", ignoreCase = true))

        // 7. Multi-modal consensus failure
        val isConsensusFailure = (!isMultiStageLive && !isTemporalLive && livenessScore < 0.45f) ||
                                 (multiStageLiveness?.primaryAttackVector != null && livenessScore < 0.35f)

        // Genuine 3D Face Protection (High-ISO / sensor noise immunity):
        // If genuine 3D depth (depthVariance > 0.0015) AND temporal micro-motion detected AND no hard attack,
        // prevent false rejections when passive 2D PAD is noisy in dim lighting
        val isGenuine3DProtected = has3DDepth && temporalLiveness.microMotionDetected && !isHighConfidencePadSpoof && !isPlanarSurface && !is3DMaskAttack && !isScreenReplay && !isPaperPrintAttack && multiStageScore >= 0.35f

        val isConfirmedSpoof = (isHighConfidencePadSpoof || isPlanarAttack || isScreenReplay || isPaperPrintAttack || is3DMaskAttack || isStaticReplay || isConsensusFailure) && !isGenuine3DProtected

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
            val attackDesc = when {
                isScreenReplay -> multiStageLiveness.primaryAttackVector ?: "Electronic Screen Replay Detected"
                isPaperPrintAttack -> multiStageLiveness.primaryAttackVector ?: "2D Printed Photo Spoof Attack"
                isPlanarAttack && faceMap3DMM != null && faceMap3DMM.depthVariance <= 0.0015f -> "Planar 2D Surface Detected (Depth Variance <= 0.0015)"
                is3DMaskAttack -> "Artificial 3D Mask / Prosthetic Attack (No Cardiovascular Pulse)"
                multiStageLiveness?.primaryAttackVector != null -> multiStageLiveness.primaryAttackVector
                passivePad?.attackTypeDescription?.isNotBlank() == true -> passivePad.attackTypeDescription
                else -> temporalLiveness.explanation
            }
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
