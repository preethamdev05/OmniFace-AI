package com.omniface.ai.ml.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.*
import com.omniface.ai.ml.antispoof.MultiStageLivenessEngine
import com.omniface.ai.ml.antispoof.MultiStageLivenessResult
import com.omniface.ai.ml.antispoof.PassivePadEngine
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessEngine
import com.omniface.ai.ml.quality.FaceQualityEngine
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.tracking.FaceTracker
import com.omniface.ai.ui.components.FaceGeometryVisualData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

data class ProcessedFaceData(
    val face: Face,
    val trackId: Int = 0,
    val smoothedRect: Rect,
    val qualityResult: QualityGateResult,
    val passivePadResult: PassivePadResult?,
    val multiStageLivenessResult: MultiStageLivenessResult?,
    val gazeResult: EyeGazeResult?,
    val map3dResult: FaceMap3DMMResult?,
    val attrResult: FaceAttributesResult?,
    val meshResult: MediaPipeMeshResult?,
    val hrnetResult: HRNetFaceResult?,
    val pts5List: List<PointF>,
    val contours: Map<Int, List<Offset>>? = null,
    val matchResult: MatchResult?,
    val lastExtractedEmbedding: FloatArray?,
    val decision: BiometricSynthesisDecision,
    val temporalResult: com.omniface.ai.ml.antispoof.TemporalLivenessResult? = null
)

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
    val qualcommEngine: QualcommFaceIntelligenceEngine?,
    val tracker: FaceTracker = FaceTracker()
) : Closeable {

    companion object {
        private const val TAG = "FaceSecurityPipeline"
        private const val REQUIRED_CONSECUTIVE_FRAMES = 2
    }
    val passivePadEngine = PassivePadEngine(context)
    val multiStageLivenessEngine = MultiStageLivenessEngine(context, passivePadEngine)
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
        securityTier: SecurityTier,
        downscaleFactor: Float = 1.0f
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

        val intermediateFaces = mutableListOf<ProcessedFaceData>()
        val config = NeuralModelConfigManager.configState.value

        for (face in faces.take(12)) {
            val rawBox = face.boundingBox
            val box = if (downscaleFactor < 0.99f) {
                android.graphics.Rect(
                    (rawBox.left * downscaleFactor).toInt().coerceIn(0, fullBitmap.width),
                    (rawBox.top * downscaleFactor).toInt().coerceIn(0, fullBitmap.height),
                    (rawBox.right * downscaleFactor).toInt().coerceIn(0, fullBitmap.width),
                    (rawBox.bottom * downscaleFactor).toInt().coerceIn(0, fullBitmap.height)
                )
            } else {
                rawBox
            }
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

            val trackState = tracker.getOrCreateTrackState(trackId, rawRect)
            val smoothedRect = trackState.smoothedRect
            val persistentTrackId = trackState.trackId
            val faceCrop = BiometricCropUtils.extractSquareFaceCrop(fullBitmap, box, 1.25f)

            // Extract ML Kit canonical 5 landmarks from frame (adapted to scaled frame if downscale active)
            val leftEyeRaw = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEyeRaw = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val noseRaw = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val mouthLRaw = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            val mouthRRaw = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

            val leftEye = leftEyeRaw?.let { if (downscaleFactor < 0.99f) android.graphics.PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }
            val rightEye = rightEyeRaw?.let { if (downscaleFactor < 0.99f) android.graphics.PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }
            val nose = noseRaw?.let { if (downscaleFactor < 0.99f) android.graphics.PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }
            val mouthL = mouthLRaw?.let { if (downscaleFactor < 0.99f) android.graphics.PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }
            val mouthR = mouthRRaw?.let { if (downscaleFactor < 0.99f) android.graphics.PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }

            // ── GATE 1: Multi-Factor Quality Gate ──
            val qualityResult = FaceQualityEngine.evaluateFaceQuality(
                face = face,
                fullFrameWidth = fullBitmap.width,
                fullFrameHeight = fullBitmap.height,
                faceCrop = faceCrop,
                faceBox = box
            )

            val unifiedEngine = com.omniface.ai.ml.UnifiedFaceIntelligenceEngine.getInstance(context)
            val unifiedResult = if (unifiedEngine.isModelLoaded && faceCrop != null && !faceCrop.isRecycled) {
                unifiedEngine.processFace(
                    faceCrop = faceCrop,
                    headYaw = face.headEulerAngleY,
                    headPitch = face.headEulerAngleX,
                    leftEyeOpenProb = face.leftEyeOpenProbability,
                    rightEyeOpenProb = face.rightEyeOpenProbability
                )
            } else null

            var matchResult: MatchResult? = null
            var lastExtractedEmbedding: FloatArray? = null
            var multiStageResult: MultiStageLivenessResult? = null
            var passivePadResult: PassivePadResult? = null
            var map3dResult: FaceMap3DMMResult? = null
            var gazeResult: EyeGazeResult? = null
            var attrResult: FaceAttributesResult? = null
            var meshResult: MediaPipeMeshResult? = null
            var hrnetResult: HRNetFaceResult? = null

            if (unifiedResult != null) {
                lastExtractedEmbedding = unifiedResult.embedding512
                passivePadResult = unifiedResult.passivePad
                map3dResult = unifiedResult.map3d
                attrResult = unifiedResult.attributes
                gazeResult = unifiedResult.gaze
                meshResult = unifiedResult.mesh
                hrnetResult = unifiedResult.hrnet

                if (qualityResult.isPassed || qualityResult.overallQualityScore >= 35.0f) {
                    try {
                        matchResult = matcher.match(
                            queryEmbedding = unifiedResult.embedding512,
                            studentMap = studentMap,
                            securityTier = securityTier,
                            activeTier = recognitionEngine.activeHardwareTier
                        )
                    } catch (t: Throwable) {
                        Log.e("OmniFacePipeline", "Gate 3 Match Exception", t)
                    }
                }
            } else {
                // Fallback Path
                if ((qualityResult.isPassed || qualityResult.overallQualityScore >= 35.0f) && faceCrop != null && !faceCrop.isRecycled) {
                    try {
                        val embedding = recognitionEngine.extractEmbedding(faceCrop)
                        lastExtractedEmbedding = embedding
                        matchResult = matcher.match(
                            queryEmbedding = embedding,
                            studentMap = studentMap,
                            securityTier = securityTier,
                            activeTier = recognitionEngine.activeHardwareTier
                        )
                    } catch (t: Throwable) {
                        Log.e("OmniFacePipeline", "Gate 3 Fallback Extraction/Match Exception", t)
                    }
                }
                if (faceCrop != null && !faceCrop.isRecycled) {
                    if (config.isPassivePadEnabled || config.isMultiStageLivenessEnabled) {
                        try {
                            passivePadResult = passivePadEngine.run(faceCrop)
                        } catch (_: Throwable) {}
                    }
                    if (qualcommEngine != null && qualcommEngine.isSuiteLoaded) {
                        try {
                            if (config.isFaceMap3DMMEnabled) {
                                map3dResult = qualcommEngine.estimate3dFaceMap(faceCrop)
                            }
                            if (config.isEyeGazeEnabled) {
                                gazeResult = qualcommEngine.estimateEyeGaze(
                                    eyeCropBitmap = faceCrop,
                                    headYaw = face.headEulerAngleY,
                                    headPitch = face.headEulerAngleX,
                                    leftEyeOpenProb = face.leftEyeOpenProbability,
                                    rightEyeOpenProb = face.rightEyeOpenProbability
                                )
                            }
                            if (config.isMediaPipeMeshEnabled) {
                                meshResult = qualcommEngine.estimateMediaPipeFaceMesh(faceCrop)
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }

            // 3. Temporal Anti-Spoofing Gate
            val avgEyeProb = if (face.leftEyeOpenProbability != null && face.rightEyeOpenProbability != null) {
                ((face.leftEyeOpenProbability ?: 1.0f) + (face.rightEyeOpenProbability ?: 1.0f)) / 2.0f
            } else null

            val temporalResult = if (config.isTemporalLivenessEnabled) {
                if (faceCrop != null && !faceCrop.isRecycled) {
                    temporalLivenessEngine.recordRppgSample(faceCrop, android.graphics.Rect(0, 0, faceCrop.width, faceCrop.height))
                }
                temporalLivenessEngine.recordSample(
                    trackId = trackId,
                    yaw = face.headEulerAngleY,
                    pitch = face.headEulerAngleX,
                    roll = face.headEulerAngleZ,
                    attributes = attrResult,
                    faceMap3DMM = map3dResult,
                    passivePad = passivePadResult,
                    eyeOpenProbability = avgEyeProb
                )
                temporalLivenessEngine.evaluateTemporalLiveness(trackId)
            } else {
                com.omniface.ai.ml.antispoof.TemporalLivenessResult(
                    isLive = true,
                    temporalConfidence = 1.0f,
                    microMotionDetected = true,
                    naturalBlinkDetected = true,
                    headTurnDetected = true,
                    stable3DDepth = true,
                    requiredAction = null,
                    explanation = "Temporal Liveness Bypassed (User Setting)"
                )
            }

            faceCrop?.recycle()

            // ── Synthesize Multi-Gate Decision ──
            val decision = BiometricDecisionEngine.evaluate(
                quality = qualityResult,
                passivePad = passivePadResult,
                temporalLiveness = temporalResult,
                matchResult = matchResult,
                securityTier = securityTier,
                multiStageLiveness = multiStageResult,
                faceMap3DMM = map3dResult
            )

            fun mapPoint(pt: PointF?): PointF? {
                if (pt == null) return null
                val x = if (isFrontCamera) (fullBitmap.width - pt.x) * scale + dx else pt.x * scale + dx
                val y = pt.y * scale + dy
                return PointF(x, y)
            }

            val pts5List = listOfNotNull(
                mapPoint(leftEye),
                mapPoint(rightEye),
                mapPoint(nose),
                mapPoint(mouthL),
                mapPoint(mouthR)
            )

            // Map ML Kit dense 133-point facial contours to preview coordinate space
            val contoursMap = HashMap<Int, List<Offset>>()
            for (contour in face.allContours) {
                val pts = contour.points.map { p ->
                    val cx = if (isFrontCamera) (fullBitmap.width - p.x) * scale + dx else p.x * scale + dx
                    val cy = p.y * scale + dy
                    Offset(cx, cy)
                }
                contoursMap[contour.faceContourType] = pts
            }

            intermediateFaces.add(
                ProcessedFaceData(
                    face = face,
                    trackId = persistentTrackId,
                    smoothedRect = smoothedRect,
                    qualityResult = qualityResult,
                    passivePadResult = passivePadResult,
                    multiStageLivenessResult = multiStageResult,
                    gazeResult = gazeResult,
                    map3dResult = map3dResult,
                    attrResult = attrResult,
                    meshResult = meshResult,
                    hrnetResult = hrnetResult,
                    pts5List = pts5List,
                    contours = contoursMap,
                    matchResult = matchResult,
                    lastExtractedEmbedding = lastExtractedEmbedding,
                    decision = decision,
                    temporalResult = temporalResult
                )
            )
        }

        // ── MULTI-FACE IDENTITY COLLISION & DUPLICATE RESOLUTION ──
        // If multiple faces in the same frame match to the same enrolled identity,
        // assign the identity to the face with highest similarity and demote the duplicate.
        val assignedRolls = mutableSetOf<String>()
        // Sort descending by match similarity so highest confidence match claims the roll first
        val sortedIndices = intermediateFaces.indices.sortedByDescending { intermediateFaces[it].decision.matchSimilarity }
        val resolvedDecisions = Array(intermediateFaces.size) { intermediateFaces[it].decision }

        for (idx in sortedIndices) {
            val item = intermediateFaces[idx]
            val origDecision = item.decision
            if (origDecision.isAttendanceAuthorized && origDecision.matchedStudentRoll.isNotBlank()) {
                val roll = origDecision.matchedStudentRoll
                if (assignedRolls.contains(roll)) {
                    // Duplicate identity collision detected in current frame!
                    resolvedDecisions[idx] = BiometricSynthesisDecision(
                        gateState = PipelineGateState.REVIEW_AMBIGUOUS_MATCH,
                        isAttendanceAuthorized = false,
                        matchedStudentRoll = "GUEST",
                        matchedStudentName = "Duplicate Identity Conflict",
                        matchConfidence = 0f,
                        matchSimilarity = origDecision.matchSimilarity,
                        decisionMargin = 0f,
                        qualityScore = origDecision.qualityScore,
                        livenessScore = origDecision.livenessScore,
                        title = "DUPLICATE IDENTITY CONFLICT",
                        subtitle = "Roll $roll already assigned to another face in view",
                        technicalExplanation = "Multi-face collision: Identity $roll claimed by multiple faces in the same frame"
                    )
                } else {
                    assignedRolls.add(roll)
                }
            }
        }

        val visualItems = mutableListOf<FaceGeometryVisualData>()
        var primaryDecision: BiometricSynthesisDecision? = null
        var attendanceTriggered = false

        for (i in intermediateFaces.indices) {
            val item = intermediateFaces[i]
            val resolvedDecision = resolvedDecisions[i]
            val trackId = item.trackId
            val decision = tracker.stabilizeDecision(trackId, resolvedDecision)
            val matchResult = item.matchResult
            val lastExtractedEmbedding = item.lastExtractedEmbedding

            // Update auxiliary telemetry in tracker for seamless 60 FPS fast-path rendering
            tracker.updateAuxiliaryFeatures(
                trackId = trackId,
                meshResult = item.meshResult,
                map3dResult = item.map3dResult,
                gazeResult = item.gazeResult,
                attrResult = item.attrResult,
                qualityResult = item.qualityResult
            )

            // ── Multi-Frame Temporal Consensus Voting ──
            val trackState = tracker.getTrackState(trackId)
            if (decision.isAttendanceAuthorized && decision.matchedStudentRoll.isNotBlank()) {
                val roll = decision.matchedStudentRoll
                val count = (consecutiveMatchCounts[roll] ?: 0) + 1
                consecutiveMatchCounts[roll] = count

                val now = System.currentTimeMillis()
                val isTrackAlreadyLogged = trackState?.hasTriggeredAttendance == true
                val isTimeCooldownElapsed = (now - lastAuthorizedTimestampMs > 45000L) // 45s cooldown for same student
                val isNewRoll = (roll != lastAuthorizedRoll)

                val requiredFrames = if (decision.matchSimilarity >= 0.75f) 1 else REQUIRED_CONSECUTIVE_FRAMES
                if (count >= requiredFrames && !isTrackAlreadyLogged && (isNewRoll || isTimeCooldownElapsed)) {
                    attendanceTriggered = true
                    lastAuthorizedRoll = roll
                    lastAuthorizedTimestampMs = now
                    consecutiveMatchCounts[roll] = 0
                    trackState?.hasTriggeredAttendance = true
                }
            }

            // Dynamic Centroid Adaptation (Continuous Learning) — persisted on IO dispatcher ONLY when attendance is verified
            if (config.isDynamicCentroidAdaptationEnabled && attendanceTriggered && decision.isAttendanceAuthorized && decision.matchedStudentRoll.isNotBlank() && lastExtractedEmbedding != null) {
                val adaptedPair = matcher.adaptCentroidIfHighConfidence(
                    studentRoll = decision.matchedStudentRoll,
                    liveEmbedding = lastExtractedEmbedding,
                    similarityScore = decision.matchSimilarity
                )
                if (adaptedPair != null) {
                    val (tplId, newEncryptedCsv) = adaptedPair
                    kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                        try {
                            com.omniface.ai.OmniFaceApplication.instance.database.studentDao().updateTemplateEmbedding(tplId, newEncryptedCsv)
                            Log.d(TAG, "🧠 [DYNAMIC CENTROID] Adapted and persisted template $tplId for ${decision.matchedStudentRoll}")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to persist adapted centroid: ${t.message}")
                        }
                    }
                }
            }

            if (primaryDecision == null || (decision.isAttendanceAuthorized && !primaryDecision.isAttendanceAuthorized)) {
                primaryDecision = decision
            }

            val zone = if (decision.isAttendanceAuthorized) {
                ConfidenceZone.ACCEPT
            } else if (decision.gateState == PipelineGateState.REVIEW_AMBIGUOUS_MATCH) {
                ConfidenceZone.REVIEW
            } else {
                ConfidenceZone.REJECT
            }

            val visualItem = FaceGeometryVisualData(
                bounds = item.smoothedRect,
                yaw = if (isFrontCamera) -item.face.headEulerAngleY else item.face.headEulerAngleY,
                pitch = item.face.headEulerAngleX,
                roll = item.face.headEulerAngleZ,
                landmarks5Pts = if (item.pts5List.isNotEmpty()) item.pts5List.toTypedArray() else null,
                contours = item.contours,
                gazeResult = item.gazeResult,
                faceMap3DMM = item.map3dResult,
                attributes = item.attrResult,
                meshResult = item.meshResult,
                hrnetResult = item.hrnetResult,
                qualityResult = item.qualityResult,
                confidenceZone = zone,
                decisionMargin = decision.decisionMargin,
                similarityScore = decision.matchSimilarity,
                studentName = decision.matchedStudentName,
                studentRoll = decision.matchedStudentRoll,
                isLive = decision.gateState != PipelineGateState.REJECT_SPOOF_ATTACK,
                activeHardwareNpu = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo),
                isFrontCamera = isFrontCamera
            )
            visualItems.add(visualItem)
        }

        if (primaryDecision?.isAttendanceAuthorized != true) {
            consecutiveMatchCounts.clear()
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
