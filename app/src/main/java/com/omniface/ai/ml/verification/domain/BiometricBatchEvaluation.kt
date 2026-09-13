package com.omniface.ai.ml.verification.domain

import android.graphics.PointF
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.face.Face
import com.omniface.ai.ml.*
import com.omniface.ai.ml.antispoof.MultiStageLivenessResult
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.quality.QualityGateResult

/**
 * 🎯 Evaluated Candidate Face Domain Data
 *
 * Represents the multi-gate biometric evaluation of an individual face track,
 * retaining unprojected canonical coordinates and multi-gate decisions.
 */
data class CandidateFaceEvaluation(
    val face: Face,
    val trackId: Int,
    val smoothedRect: Rect,
    val domainGeometry: DomainFaceGeometry,
    val qualityResult: QualityGateResult,
    val passivePadResult: PassivePadResult?,
    val multiStageLivenessResult: MultiStageLivenessResult?,
    val temporalResult: TemporalLivenessResult?,
    val map3dResult: FaceMap3DMMResult?,
    val gazeResult: EyeGazeResult?,
    val attrResult: FaceAttributesResult?,
    val meshResult: MediaPipeMeshResult?,
    val hrnetResult: HRNetFaceResult?,
    val matchResult: MatchResult?,
    val lastExtractedEmbedding: FloatArray?,
    val synthesis: BiometricSynthesisDecision,
    val leftEye: PointF?,
    val rightEye: PointF?,
    val nose: PointF?,
    val mouthL: PointF?,
    val mouthR: PointF?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CandidateFaceEvaluation
        return trackId == other.trackId && synthesis == other.synthesis
    }

    override fun hashCode(): Int {
        var result = trackId
        result = 31 * result + synthesis.hashCode()
        return result
    }
}

/**
 * 📦 Biometric Batch Evaluation Result
 *
 * Comprehensive multi-subject evaluation output emitted by the authoritative
 * [BiometricVerificationEngine], supporting group workloads up to 100 faces
 * without collapsing to a single top decision.
 */
data class BiometricBatchEvaluation(
    val topDecision: BiometricSynthesisDecision,
    val allDecisions: List<BiometricSynthesisDecision>,
    val triggeredDecisions: List<BiometricSynthesisDecision>,
    val isAttendanceTriggered: Boolean,
    val executionLatencyMs: Long,
    val activeHardwareTier: String,
    val candidateEvaluations: List<CandidateFaceEvaluation> = emptyList()
)
