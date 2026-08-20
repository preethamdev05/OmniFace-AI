package com.omniface.ai.ml

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import com.omniface.ai.data.local.entity.FaceTemplateEntity

data class FaceAnalysisResult(
    val faceId: Int,
    val boundingBox: Rect,
    val detectionConfidence: Float,
    val qualityScore: RegistrationQualityScore,
    val faceMap3DMM: FaceMap3DMMResult?,
    val eyeGaze: EyeGazeResult?,
    val attributes: FaceAttributesResult?,
    val headEulerYaw: Float,
    val headEulerPitch: Float,
    val headEulerRoll: Float,
    val embedding: FloatArray?,
    val matchResult: MatchResult?,
    val top1Margin: Float, // Top1 similarity - Top2 similarity
    val executionLatencyMs: Float
)

class FaceAnalysisFusionEngine(
    private val recognitionEngine: FaceRecognitionEngine,
    private val qualcommEngine: QualcommFaceIntelligenceEngine?
) {

    /**
     * Executes adaptive multi-model fusion analysis on a single detected face frame.
     */
    suspend fun analyzeFace(
        face: Face,
        allFaces: List<Face>,
        fullBitmap: Bitmap,
        knownTemplates: List<FaceTemplateEntity> = emptyList(),
        studentMap: Map<String, String> = emptyMap(),
        securityTier: SecurityTier = SecurityTier.HIGH,
        enableQualcommSuite: Boolean = true
    ): FaceAnalysisResult {
        val t0 = System.nanoTime()
        val box = face.boundingBox

        val faceCrop = BiometricCropUtils.extractSquareFaceCrop(fullBitmap, box, 1.30f)

        // 1. Qualcomm AI Hub Suite (FaceMap 3DMM, EyeGaze, FaceAttribNet)
        var faceMapResult: FaceMap3DMMResult? = null
        var eyeGazeResult: EyeGazeResult? = null
        var attribResult: FaceAttributesResult? = null

        if (enableQualcommSuite && qualcommEngine != null && faceCrop != null && !faceCrop.isRecycled) {
            try {
                faceMapResult = qualcommEngine.estimate3dFaceMap(faceCrop)
                attribResult = qualcommEngine.detectFaceAttributes(faceCrop)
                eyeGazeResult = qualcommEngine.estimateEyeGaze(faceCrop)
            } catch (_: Throwable) {}
        }

        // 2. Multi-factor Registration Quality Gate
        val quality = RegistrationQualityEvaluator.evaluateFrame(
            face = face,
            allDetectedFaces = allFaces,
            frameWidth = fullBitmap.width,
            frameHeight = fullBitmap.height,
            faceCrop = faceCrop,
            targetYaw = 0f,
            targetPitch = 0f,
            qualcommAttributes = attribResult,
            qualcommGaze = eyeGazeResult
        )

        // 3. Extract 512-D Embedding Vector
        var embedding: FloatArray? = null
        var matchResult: MatchResult? = null

        if (faceCrop != null && !faceCrop.isRecycled) {
            try {
                embedding = recognitionEngine.extractEmbedding(faceCrop)
                // Compute match against enrolled database
                matchResult = recognitionEngine.matchFace(
                    queryEmbedding = embedding,
                    knownTemplates = knownTemplates,
                    studentMap = studentMap,
                    securityTier = securityTier
                )
            } catch (_: Throwable) {}
        }

        val top1Margin = matchResult?.decisionMargin ?: 0.0f

        val latencyMs = (System.nanoTime() - t0) / 1_000_000f

        faceCrop?.recycle()

        return FaceAnalysisResult(
            faceId = face.trackingId ?: 0,
            boundingBox = box,
            detectionConfidence = (face.leftEyeOpenProbability ?: 0.95f) * 100f,
            qualityScore = quality,
            faceMap3DMM = faceMapResult,
            eyeGaze = eyeGazeResult,
            attributes = attribResult,
            headEulerYaw = face.headEulerAngleY,
            headEulerPitch = face.headEulerAngleX,
            headEulerRoll = face.headEulerAngleZ,
            embedding = embedding,
            matchResult = matchResult,
            top1Margin = top1Margin,
            executionLatencyMs = latencyMs
        )
    }
}
