package com.omniface.ai.attendance

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.face.FaceEngine
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.QualcommFaceIntelligenceEngine
import com.omniface.ai.ml.SecurityTier

data class PipelineExecutionResult(
    val matchResult: MatchResult,
    val qualityResult: QualityGateResult,
    val isLive: Boolean,
    val decision: AttendanceDecision,
    val executionMs: Float
)

class RecognitionPipeline(val context: Context) {

    val faceEngine = FaceEngine(context)
    val qualityGate = QualityGate()
    val livenessGate = LivenessGate()
    val decisionEngine = AttendanceDecisionEngine(context)

    suspend fun processFace(
        face: Face,
        faceCrop: Bitmap,
        knownTemplates: List<FaceTemplateEntity>,
        studentMap: Map<String, String>,
        securityTier: SecurityTier,
        qualcommEngine: QualcommFaceIntelligenceEngine? = null
    ): PipelineExecutionResult {
        val t0 = System.nanoTime()

        // 1. Quality Gate
        val quality = qualityGate.evaluateFaceQuality(faceCrop, face.headEulerAngleZ)

        // 2. Liveness Gate
        val livenessState = livenessGate.verifyLiveness(face, faceCrop, qualcommEngine)
        val isLive = livenessGate.isLivenessPassed(livenessState)
        val livenessScore = if (isLive) 1.0f else 0.0f

        // 3. Embedding Extraction & Matching
        val embedding = faceEngine.extractEmbedding(faceCrop)
        val match = faceEngine.matchFace(embedding, knownTemplates, studentMap, securityTier)

        // 4. Decision Fusion & Attendance Logging
        val decision = decisionEngine.evaluateAndRecordAttendance(match, quality, isLive, livenessScore)

        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0f

        return PipelineExecutionResult(
            matchResult = match,
            qualityResult = quality,
            isLive = isLive,
            decision = decision,
            executionMs = elapsedMs
        )
    }
}
