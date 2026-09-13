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
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.ui.components.FaceGeometryVisualData
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.ml.verification.domain.IdentityStore
import com.omniface.ai.ml.verification.domain.IdentityTemplate
import com.omniface.ai.ml.verification.domain.BitmapBiometricFrame
import com.omniface.ai.ml.verification.engine.BiometricVerificationEngineImpl
import kotlinx.coroutines.flow.emptyFlow
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
    val activeHardwareTier: String,
    val allDecisions: List<BiometricSynthesisDecision> = emptyList(),
    val triggeredDecisions: List<BiometricSynthesisDecision> = emptyList()
)

/**
 * Sovereign Master Multi-Stage Biometric Security Pipeline Presentation Adapter.
 *
 * Wraps the authoritative [BiometricVerificationEngineImpl] and provides screen-space
 * visual coordinate mapping and viewport projection for CameraX Compose surfaces.
 * Eliminates duplicate neural inference graphs, delegates, and tracker instances.
 */
class FaceSecurityPipeline(
    private val context: Context,
    val engine: BiometricVerificationEngineImpl
) : Closeable {

    constructor(
        context: Context,
        recognitionEngine: FaceRecognitionEngine,
        omniFaceEngine: OmniFaceIntelligenceEngine?,
        tracker: FaceTracker = FaceTracker()
    ) : this(
        context,
        try {
            (OmniFaceApplication.instance.verificationEngine as? BiometricVerificationEngineImpl)
                ?: BiometricVerificationEngineImpl(
                    context = context.applicationContext,
                    identityStore = OmniFaceApplication.instance.identityStore,
                    recognitionEngine = recognitionEngine,
                    omniFaceEngine = omniFaceEngine,
                    tracker = tracker
                )
        } catch (_: Throwable) {
            BiometricVerificationEngineImpl(
                context = context.applicationContext,
                identityStore = EmptyIdentityStore(),
                recognitionEngine = recognitionEngine,
                omniFaceEngine = omniFaceEngine,
                tracker = tracker
            )
        }
    )

    val recognitionEngine: FaceRecognitionEngine get() = engine.recognitionEngine
    val omniFaceEngine: OmniFaceIntelligenceEngine? get() = engine.omniFaceEngine
    val qualcommEngine: OmniFaceIntelligenceEngine? get() = engine.omniFaceEngine
    val tracker: FaceTracker get() = engine.tracker
    val passivePadEngine: PassivePadEngine get() = engine.passivePadEngine
    val multiStageLivenessEngine: MultiStageLivenessEngine get() = engine.multiStageLivenessEngine
    val temporalLivenessEngine: TemporalLivenessEngine get() = engine.temporalLivenessEngine
    val matcher: FaceMatcher get() = engine.matcher

    companion object {
        private const val TAG = "FaceSecurityPipeline"

        @Volatile
        private var INSTANCE: FaceSecurityPipeline? = null

        fun getInstance(context: Context): FaceSecurityPipeline =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: run {
                    val appContext = context.applicationContext
                    val app = appContext as? OmniFaceApplication
                    val verificationEngine = try {
                        if (app != null) {
                            app.verificationEngine as BiometricVerificationEngineImpl
                        } else {
                            val recEngine = FaceRecognitionEngine.getInstance(appContext)
                            val intelEngine = try {
                                OmniFaceIntelligenceEngine.getInstance(appContext)
                            } catch (_: Throwable) {
                                null
                            }
                            BiometricVerificationEngineImpl(
                                context = appContext,
                                identityStore = EmptyIdentityStore(),
                                recognitionEngine = recEngine,
                                omniFaceEngine = intelEngine
                            )
                        }
                    } catch (_: Throwable) {
                        val recEngine = FaceRecognitionEngine.getInstance(appContext)
                        val intelEngine = try {
                            OmniFaceIntelligenceEngine.getInstance(appContext)
                        } catch (_: Throwable) {
                            null
                        }
                        BiometricVerificationEngineImpl(
                            context = appContext,
                            identityStore = EmptyIdentityStore(),
                            recognitionEngine = recEngine,
                            omniFaceEngine = intelEngine
                        )
                    }
                    FaceSecurityPipeline(appContext, verificationEngine).also { INSTANCE = it }
                }
            }
    }

    private class EmptyIdentityStore : IdentityStore {
        override fun observeTemplates() = emptyFlow<List<IdentityTemplate>>()
        override suspend fun getTemplateCount(): Int = 0
    }

    fun preloadTemplates(templates: List<FaceTemplateEntity>) {
        engine.preloadTemplates(templates)
    }

    fun preloadCachedBiometrics(cachedList: List<CachedBiometric>) {
        engine.preloadCachedBiometrics(cachedList)
    }

    /**
     * Presentation adapter delegates face feature extraction and inference directly to
     * [BiometricVerificationEngineImpl], which executes `unifiedEngine.processScannerFace`
     * under bounded concurrency control without dynamic tensor resizing.
     */
    fun processScannerFace(
        faceCrop: Bitmap,
        headYaw: Float,
        headPitch: Float,
        leftEyeOpenProb: Float?,
        rightEyeOpenProb: Float?,
        alignedFace: Bitmap?
    ) = engine.unifiedEngine.processScannerFace(
        faceCrop = faceCrop,
        headYaw = headYaw,
        headPitch = headPitch,
        leftEyeOpenProb = leftEyeOpenProb,
        rightEyeOpenProb = rightEyeOpenProb,
        alignedFace = alignedFace
    )

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
        if (faces.isEmpty() || fullBitmap.isRecycled) {
            engine.tracker.purgeOldTracks()
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

        val frame = BitmapBiometricFrame(
            bitmap = fullBitmap,
            rotationDegrees = 0,
            isFrontFacing = isFrontCamera,
            detectedFaces = faces
        )

        val batch = engine.evaluateFrame(
            frame = frame,
            studentMap = studentMap,
            securityTier = securityTier,
            downscaleFactor = downscaleFactor
        )

        val scale = maxOf(
            previewWidth / fullBitmap.width.coerceAtLeast(1).toFloat(),
            previewHeight / fullBitmap.height.coerceAtLeast(1).toFloat()
        )
        val dx = (previewWidth - fullBitmap.width * scale) / 2f
        val dy = (previewHeight - fullBitmap.height * scale) / 2f

        fun mapPoint(pt: PointF?): PointF? {
            if (pt == null) return null
            val x = if (isFrontCamera) (fullBitmap.width - pt.x) * scale + dx else pt.x * scale + dx
            val y = pt.y * scale + dy
            return PointF(x, y)
        }

        val visualItems = mutableListOf<FaceGeometryVisualData>()
        for (candidate in batch.candidateEvaluations) {
            val face = candidate.face
            val pts5List = listOfNotNull(
                mapPoint(candidate.leftEye),
                mapPoint(candidate.rightEye),
                mapPoint(candidate.nose),
                mapPoint(candidate.mouthL),
                mapPoint(candidate.mouthR)
            )

            val contoursMap = HashMap<Int, List<Offset>>()
            for (contour in face.allContours) {
                val pts = contour.points.map { p ->
                    val cx = if (isFrontCamera) (fullBitmap.width - p.x) * scale + dx else p.x * scale + dx
                    val cy = p.y * scale + dy
                    Offset(cx, cy)
                }
                contoursMap[contour.faceContourType] = pts
            }

            val projectedRect = if (isFrontCamera) {
                Rect(
                    left = (fullBitmap.width - candidate.smoothedRect.right) * scale + dx,
                    top = candidate.smoothedRect.top * scale + dy,
                    right = (fullBitmap.width - candidate.smoothedRect.left) * scale + dx,
                    bottom = candidate.smoothedRect.bottom * scale + dy
                )
            } else {
                Rect(
                    left = candidate.smoothedRect.left * scale + dx,
                    top = candidate.smoothedRect.top * scale + dy,
                    right = candidate.smoothedRect.right * scale + dx,
                    bottom = candidate.smoothedRect.bottom * scale + dy
                )
            }

            val decision = candidate.synthesis
            val zone = if (decision.isAttendanceAuthorized) {
                ConfidenceZone.ACCEPT
            } else if (decision.gateState == PipelineGateState.REVIEW_AMBIGUOUS_MATCH) {
                ConfidenceZone.REVIEW
            } else {
                ConfidenceZone.REJECT
            }

            val visualItem = FaceGeometryVisualData(
                bounds = projectedRect,
                yaw = if (isFrontCamera) -face.headEulerAngleY else face.headEulerAngleY,
                pitch = face.headEulerAngleX,
                roll = face.headEulerAngleZ,
                landmarks5Pts = if (pts5List.isNotEmpty()) pts5List.toTypedArray() else null,
                contours = contoursMap,
                gazeResult = candidate.gazeResult,
                faceMap3DMM = candidate.map3dResult,
                attributes = candidate.attrResult,
                meshResult = candidate.meshResult,
                hrnetResult = candidate.hrnetResult,
                qualityResult = candidate.qualityResult,
                confidenceZone = zone,
                decisionMargin = decision.decisionMargin,
                similarityScore = decision.matchSimilarity,
                studentName = decision.matchedStudentName,
                studentRoll = decision.matchedStudentRoll,
                isLive = decision.gateState != PipelineGateState.REJECT_SPOOF_ATTACK,
                activeHardwareNpu = batch.activeHardwareTier,
                isFrontCamera = isFrontCamera
            )
            visualItems.add(visualItem)
        }

        PipelineFrameOutput(
            visualGeometries = visualItems,
            topDecision = batch.topDecision,
            isAttendanceTriggered = batch.isAttendanceTriggered,
            executionLatencyMs = batch.executionLatencyMs,
            activeHardwareTier = batch.activeHardwareTier,
            allDecisions = batch.allDecisions,
            triggeredDecisions = batch.triggeredDecisions
        )
    }

    override fun close() {
        engine.close()
        synchronized(FaceSecurityPipeline::class.java) {
            if (INSTANCE === this) {
                INSTANCE = null
            }
        }
    }
}

