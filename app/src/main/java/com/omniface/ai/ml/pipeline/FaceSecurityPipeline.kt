package com.omniface.ai.ml.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.face.Face
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.*
import com.omniface.ai.ml.antispoof.PassivePadEngine
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessEngine
import com.omniface.ai.ml.quality.FaceQualityEngine
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.tracking.FaceTracker
import com.omniface.ai.ui.components.FaceGeometryVisualData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

data class PipelineFrameOutput(
    val visualGeometries: List<FaceGeometryVisualData>,
    val topDecision: BiometricSynthesisDecision,
    val isAttendanceTriggered: Boolean,
    val executionLatencyMs: Long,
    val activeHardwareTier: String
)

/**
 * Sovereign Master Multi-Stage Biometric Security Pipeline.
 *
 * Implements the 3-Gate Biometric Verification Architecture:
 * 1. Tracking: Smooths bounding box jitter via Kalman / EMA.
 * 2. Gate 1 (Quality): Evaluates blur, exposure, head pose, and face scale.
 * 3. Gate 2 (Anti-Spoof): Executes PassivePadEngine (MiniFASNetV2) + FaceMap 3DMM + Temporal Liveness.
 * 4. Gate 3 (Identity): Extracts 512-D L2 embedding + Best-Angle FAISS-equivalent matching.
 * 5. Temporal Consensus: Requires 3 consecutive frame agreements before authorized attendance.
 */
class FaceSecurityPipeline(
    private val context: Context,
    val recognitionEngine: FaceRecognitionEngine,
    val qualcommEngine: QualcommFaceIntelligenceEngine?
) : Closeable {

    companion object {
        private const val TAG = "FaceSecurityPipeline"
        private const val REQUIRED_CONSECUTIVE_FRAMES = 3
    }

    private val tracker = FaceTracker()
    val passivePadEngine = PassivePadEngine(context)
    val temporalLivenessEngine = TemporalLivenessEngine()
    val matcher = FaceMatcher()

    // Sliding agreement counter: studentRoll -> consecutiveCount
    private val consecutiveMatchCounts = ConcurrentHashMap<String, Int>()
    private var lastAuthorizedRoll = ""
    private var lastAuthorizedTimestampMs = 0L

    fun preloadTemplates(templates: List<FaceTemplateEntity>) {
        matcher.preloadTemplates(templates)
        recognitionEngine.preloadTemplates(templates)
    }

    suspend fun processFrame(
        faces: List<Face>,
        fullBitmap: Bitmap,
        previewWidth: Float,
        previewHeight: Float,
        isFrontCamera: Boolean,
        studentMap: Map<String, String>,
        securityTier: SecurityTier
    ): PipelineFrameOutput = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtimeNanos()

        if (faces.isEmpty() || fullBitmap.isRecycled) {
            tracker.purgeOldTracks()
            return@withContext PipelineFrameOutput(
                visualGeometries = emptyList(),
                topDecision = BiometricSynthesisDecision(
                    gateState = PipelineGateState.REJECT_QUALITY,
                    isAttendanceAuthorized = false,
                    matchedStudentRoll = "",
                    matchedStudentName = "",
                    matchConfidence = 0f,
                    matchSimilarity = 0f,
                    decisionMargin = 0f,
                    qualityScore = 0f,
                    livenessScore = 0f,
                    title = "READY TO SCAN",
                    subtitle = "Align face in camera frame",
                    technicalExplanation = "No faces detected in current frame"
                ),
                isAttendanceTriggered = false,
                executionLatencyMs = 0L,
                activeHardwareTier = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)
            )
        }

        val scale = maxOf(previewWidth / fullBitmap.width, previewHeight / fullBitmap.height)
        val dx = (previewWidth - fullBitmap.width * scale) / 2f
        val dy = (previewHeight - fullBitmap.height * scale) / 2f

        val visualItems = mutableListOf<FaceGeometryVisualData>()
        var primaryDecision: BiometricSynthesisDecision? = null
        var attendanceTriggered = false

        for (face in faces.take(2)) {
            val box = face.boundingBox
            val trackId = face.trackingId ?: 0

            // 1. Map coordinates to preview coordinates
            val rawRect = if (isFrontCamera) {
                Rect(
                    left = (fullBitmap.width - box.right) * scale + dx,
                    top = box.top * scale + dy,
                    right = (fullBitmap.width - box.left) * scale + dx,
                    bottom = box.bottom * scale + dy
                )
            } else {
                Rect(
                    left = box.left * scale + dx,
                    top = box.top * scale + dy,
                    right = box.right * scale + dx,
                    bottom = box.bottom * scale + dy
                )
            }

            val smoothedRect = tracker.updateTrack(trackId, rawRect)
            val faceCrop = BiometricCropUtils.extractSquareFaceCrop(fullBitmap, box, 1.25f)

            // ── GATE 1: Multi-Factor Quality Gate ──
            val qualityResult = FaceQualityEngine.evaluateFaceQuality(
                face = face,
                fullFrameWidth = fullBitmap.width,
                fullFrameHeight = fullBitmap.height,
                faceCrop = faceCrop
            )

            // ── Neural Feature Extraction (Qualcomm Suite & Passive PAD) ──
            var map3dResult: FaceMap3DMMResult? = null
            var gazeResult: EyeGazeResult? = null
            var attrResult: FaceAttributesResult? = null
            var meshResult: MediaPipeMeshResult? = null
            var passivePadResult: PassivePadResult? = null

            if (faceCrop != null && !faceCrop.isRecycled) {
                // 1. Passive RGB PAD
                if (passivePadEngine.isReady) {
                    try {
                        passivePadResult = passivePadEngine.run(faceCrop)
                    } catch (_: Throwable) {}
                }

                // 2. Qualcomm AI Hub Suite
                if (qualcommEngine != null && qualcommEngine.isSuiteLoaded) {
                    try {
                        map3dResult = qualcommEngine.estimate3dFaceMap(faceCrop)
                        gazeResult = qualcommEngine.estimateEyeGaze(faceCrop)
                        attrResult = qualcommEngine.detectFaceAttributes(faceCrop)
                        meshResult = qualcommEngine.estimateMediaPipeFaceMesh(faceCrop)
                    } catch (_: Throwable) {}
                }
            }

            // ── GATE 2: Temporal Anti-Spoofing Gate ──
            temporalLivenessEngine.recordSample(
                trackId = trackId,
                yaw = face.headEulerAngleY,
                pitch = face.headEulerAngleX,
                roll = face.headEulerAngleZ,
                attributes = attrResult,
                faceMap3DMM = map3dResult,
                passivePad = passivePadResult
            )
            val temporalResult = temporalLivenessEngine.evaluateTemporalLiveness(trackId)

            // ── GATE 3: 512-D Identity Embedding Extraction & Matching ──
            var matchResult: MatchResult? = null
            if (qualityResult.isPassed && temporalResult.isLive && faceCrop != null && !faceCrop.isRecycled) {
                try {
                    val embedding = recognitionEngine.extractEmbedding(faceCrop)
                    matchResult = matcher.match(
                        queryEmbedding = embedding,
                        studentMap = studentMap,
                        securityTier = securityTier,
                        activeTier = recognitionEngine.activeHardwareTier
                    )
                } catch (_: Throwable) {}
            }

            faceCrop?.recycle()

            // ── Synthesize 3-Gate Decision ──
            val decision = BiometricDecisionEngine.evaluate(
                quality = qualityResult,
                passivePad = passivePadResult,
                temporalLiveness = temporalResult,
                matchResult = matchResult,
                securityTier = securityTier
            )

            if (primaryDecision == null) {
                primaryDecision = decision
            }

            // ── Multi-Frame Temporal Consensus Voting ──
            if (decision.isAttendanceAuthorized && decision.matchedStudentRoll.isNotBlank()) {
                val roll = decision.matchedStudentRoll
                val count = (consecutiveMatchCounts[roll] ?: 0) + 1
                consecutiveMatchCounts[roll] = count

                val now = System.currentTimeMillis()
                if (count >= REQUIRED_CONSECUTIVE_FRAMES && (roll != lastAuthorizedRoll || now - lastAuthorizedTimestampMs > 5000L)) {
                    attendanceTriggered = true
                    lastAuthorizedRoll = roll
                    lastAuthorizedTimestampMs = now
                    consecutiveMatchCounts[roll] = 0
                }
            } else {
                consecutiveMatchCounts.clear()
            }

            // ── Build Visual Geometry for Viewfinder HUD ──
            val visualItem = FaceGeometryVisualData(
                bounds = smoothedRect,
                yaw = face.headEulerAngleY,
                pitch = face.headEulerAngleX,
                roll = face.headEulerAngleZ,
                gazeResult = gazeResult,
                faceMap3DMM = map3dResult,
                attributes = attrResult,
                meshResult = meshResult,
                confidenceZone = matchResult?.confidenceZone ?: ConfidenceZone.REJECT,
                decisionMargin = decision.decisionMargin,
                similarityScore = decision.matchSimilarity,
                studentName = decision.matchedStudentName,
                isLive = decision.gateState != PipelineGateState.REJECT_SPOOF_ATTACK,
                activeHardwareNpu = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)
            )
            visualItems.add(visualItem)
        }

        val elapsedMs = ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L).coerceAtLeast(1L)

        return@withContext PipelineFrameOutput(
            visualGeometries = visualItems,
            topDecision = primaryDecision ?: BiometricSynthesisDecision(
                gateState = PipelineGateState.REJECT_QUALITY,
                isAttendanceAuthorized = false,
                matchedStudentRoll = "",
                matchedStudentName = "",
                matchConfidence = 0f,
                matchSimilarity = 0f,
                decisionMargin = 0f,
                qualityScore = 0f,
                livenessScore = 0f,
                title = "ANALYZING...",
                subtitle = "Align face in frame",
                technicalExplanation = "Processing multi-stage biometric gates"
            ),
            isAttendanceTriggered = attendanceTriggered,
            executionLatencyMs = elapsedMs,
            activeHardwareTier = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)
        )
    }

    override fun close() {
        passivePadEngine.close()
        matcher.clear()
        temporalLivenessEngine.clearAll()
        tracker.clear()
    }
}
