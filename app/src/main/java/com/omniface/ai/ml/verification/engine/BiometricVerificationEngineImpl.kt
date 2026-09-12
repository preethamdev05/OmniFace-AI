package com.omniface.ai.ml.verification.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.ml.*
import com.omniface.ai.ml.antispoof.MultiStageLivenessEngine
import com.omniface.ai.ml.antispoof.MultiStageLivenessResult
import com.omniface.ai.ml.antispoof.PassivePadEngine
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessEngine
import com.omniface.ai.ml.pipeline.BiometricDecisionEngine
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.FaceQualityEngine
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.tracking.FaceTracker
import com.omniface.ai.ml.verification.domain.*
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Sovereign Master Biometric Verification Engine Implementation.
 *
 * Implements the 3-Gate Biometric Verification Architecture:
 * 1. Tracking: Smooths bounding box jitter via Kalman / EMA.
 * 2. Gate 1 (Quality): Evaluates blur, exposure, head pose, and face scale.
 * 3. Gate 2 (Anti-Spoof): Executes PassivePadEngine (MiniFASNetV2) + FaceMap 3DMM + Temporal Liveness.
 * 4. Gate 3 (Identity): Extracts 512-D L2 embedding + Best-Angle cosine matching against volatile in-memory templates.
 * 5. Temporal Consensus: Multi-frame agreement voting before authorized verification.
 *
 * Exposes a pure domain seam with zero persistence leakage.
 */
class BiometricVerificationEngineImpl(
    private val context: Context,
    private val identityStore: IdentityStore,
    val recognitionEngine: FaceRecognitionEngine,
    val omniFaceEngine: OmniFaceIntelligenceEngine?,
    val tracker: FaceTracker = FaceTracker(),
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) : BiometricVerificationEngine {

    companion object {
        private const val TAG = "BiometricEngine"
        private const val REQUIRED_CONSECUTIVE_FRAMES = 2
        private const val COOLDOWN_PERIOD_MS = 45000L // 45s cooldown per identity

        fun create(
            context: Context,
            identityStore: IdentityStore
        ): BiometricVerificationEngineImpl {
            val appContext = context.applicationContext
            val recEngine = FaceRecognitionEngine.getInstance(appContext)
            val intelEngine = try {
                OmniFaceIntelligenceEngine.getInstance(appContext)
            } catch (_: Throwable) {
                null
            }
            return BiometricVerificationEngineImpl(
                context = appContext,
                identityStore = identityStore,
                recognitionEngine = recEngine,
                omniFaceEngine = intelEngine
            )
        }
    }

    val passivePadEngine = PassivePadEngine(context)
    val multiStageLivenessEngine = MultiStageLivenessEngine(context, passivePadEngine)
    val temporalLivenessEngine = TemporalLivenessEngine()
    val matcher = FaceMatcher()
    val unifiedEngine: UnifiedFaceIntelligenceEngine get() = UnifiedFaceIntelligenceEngine.getInstance(context)

    // Volatile in-memory cache of domain identity templates
    @Volatile
    private var enrolledTemplates: List<IdentityTemplate> = emptyList()
    private val identityMap = ConcurrentHashMap<String, IdentityTemplate>()

    // Sliding agreement counters and cooldown tracking
    private val consecutiveMatchCounts = ConcurrentHashMap<String, Int>()
    private val lastVerifiedTimestamps = ConcurrentHashMap<String, Long>()
    @Volatile
    private var lastAuthorizedRoll = ""
    @Volatile
    private var lastAuthorizedTimestampMs = 0L

    private val _telemetry = MutableStateFlow(
        HardwareTelemetry(
            backend = when (recognitionEngine.activeHardwareTier) {
                HardwareTier.NPU_NNAPI, HardwareTier.NPU_DELEGATE -> HardwareBackend.NPU_NNAPI
                HardwareTier.GPU_DELEGATE -> HardwareBackend.GPU_DELEGATE
                HardwareTier.CPU_XNNPACK -> HardwareBackend.CPU_XNNPACK
            },
            resolvedBackendLabel = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo),
            thermalState = ThermalGovernor.thermalState.value,
            deviceTemperature = ThermalGovernor.currentTemperature.value,
            latencyMs = 4L,
            activeModelName = "OmniFace Deep AI Engine",
            isReady = true
        )
    )
    override val telemetry: StateFlow<HardwareTelemetry> = _telemetry.asStateFlow()

    private val _transientEvents = MutableSharedFlow<BiometricTransientEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val transientEvents: SharedFlow<BiometricTransientEvent> = _transientEvents.asSharedFlow()

    private var templateObservationJob: Job? = null
    private var thermalObservationJob: Job? = null

    init {
        // 1. Reactive observation of IdentityStore
        templateObservationJob = coroutineScope.launch {
            identityStore.observeTemplates().collect { templates ->
                updateVolatileTemplates(templates)
            }
        }

        // 2. Telemetry synchronization with ThermalGovernor
        thermalObservationJob = coroutineScope.launch {
            combine(
                ThermalGovernor.thermalState,
                ThermalGovernor.currentTemperature
            ) { thermal, temp ->
                Pair(thermal, temp)
            }.collect { (thermal, temp) ->
                _telemetry.update {
                    it.copy(
                        thermalState = thermal,
                        deviceTemperature = temp
                    )
                }
            }
        }
    }

    private fun updateVolatileTemplates(templates: List<IdentityTemplate>) {
        enrolledTemplates = templates
        identityMap.clear()
        for (tpl in templates) {
            identityMap[tpl.identityId] = tpl
        }

        val cachedList = templates.map { tpl ->
            CachedBiometric(
                templateId = tpl.identityId,
                studentRoll = tpl.identityId,
                angleType = "FRONT",
                embedding = tpl.embedding
            )
        }
        matcher.preloadCachedBiometrics(cachedList)
        recognitionEngine.preloadCachedBiometrics(cachedList)

        val tierLabel = if (unifiedEngine.isModelLoaded) unifiedEngine.activeBackend
        else recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)

        _telemetry.update {
            it.copy(
                resolvedBackendLabel = tierLabel,
                isReady = unifiedEngine.isModelLoaded || recognitionEngine.isEngineReady
            )
        }
        Log.i(TAG, "BiometricVerificationEngine reloaded ${templates.size} volatile identity templates")
    }

    override suspend fun verifyFrame(
        frame: BiometricFrame,
        securityTier: SecurityTier
    ): VerificationDecision = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val bitmap = frame.toBitmap()

        if (bitmap == null || bitmap.isRecycled) {
            tracker.purgeOldTracks()
            return@withContext VerificationDecision.Idle
        }

        val faces = frame.detectedFaces
        if (faces.isEmpty()) {
            tracker.purgeOldTracks()
            return@withContext VerificationDecision.Idle
        }

        val downscaleFactor = if (ThermalGovernor.isAutoScalingEnabled.value) {
            ThermalGovernor.thermalState.value.downscaleFactor
        } else 1.0f

        val claimedTrackIds = mutableSetOf<Int>()
        val intermediateDecisions = mutableListOf<CandidateFaceEvaluation>()

        val studentMap = identityMap.mapValues { it.value.displayName }
        val config = NeuralModelConfigManager.configState.value

        for (face in faces.take(6)) {
            val rawBox = face.boundingBox
            val box = if (downscaleFactor < 0.99f) {
                android.graphics.Rect(
                    (rawBox.left * downscaleFactor).toInt().coerceIn(0, bitmap.width),
                    (rawBox.top * downscaleFactor).toInt().coerceIn(0, bitmap.height),
                    (rawBox.right * downscaleFactor).toInt().coerceIn(0, bitmap.width),
                    (rawBox.bottom * downscaleFactor).toInt().coerceIn(0, bitmap.height)
                )
            } else {
                rawBox
            }
            val trackId = face.trackingId ?: 0

            val rawRect = androidx.compose.ui.geometry.Rect(
                left = box.left.toFloat(),
                top = box.top.toFloat(),
                right = box.right.toFloat(),
                bottom = box.bottom.toFloat()
            )
            val trackState = tracker.getOrCreateTrackState(trackId, rawRect, claimedTrackIds)
            val smoothedRect = trackState.smoothedRect

            val domainGeometry = DomainFaceGeometry(
                left = smoothedRect.left,
                top = smoothedRect.top,
                right = smoothedRect.right,
                bottom = smoothedRect.bottom,
                rollAngle = face.headEulerAngleZ,
                pitchAngle = face.headEulerAngleX,
                yawAngle = if (frame.isFrontFacing) -face.headEulerAngleY else face.headEulerAngleY
            )

            val faceCrop = BiometricCropUtils.extractDirectFaceCrop(bitmap, box, targetSize = 192, marginMultiplier = 1.25f)
                ?: BiometricCropUtils.extractSquareFaceCrop(bitmap, box, 1.25f)

            // ML Kit canonical 5 fiducials
            val leftEyeRaw = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEyeRaw = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val noseRaw = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val mouthLRaw = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            val mouthRRaw = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

            val leftEye = leftEyeRaw?.let { if (downscaleFactor < 0.99f) PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }
            val rightEye = rightEyeRaw?.let { if (downscaleFactor < 0.99f) PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }
            val nose = noseRaw?.let { if (downscaleFactor < 0.99f) PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }
            val mouthL = mouthLRaw?.let { if (downscaleFactor < 0.99f) PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }
            val mouthR = mouthRRaw?.let { if (downscaleFactor < 0.99f) PointF(it.x * downscaleFactor, it.y * downscaleFactor) else it }

            // ── GATE 1: Multi-Factor Quality Gate ──
            val qualityResult = FaceQualityEngine.evaluateFaceQuality(
                face = face,
                fullFrameWidth = bitmap.width,
                fullFrameHeight = bitmap.height,
                faceCrop = faceCrop,
                faceBox = box
            )

            // Umeyama 5-Point Alignment
            val raw5Pts = if (leftEye != null && rightEye != null && nose != null && mouthL != null && mouthR != null) {
                arrayOf(rightEye, leftEye, nose, mouthR, mouthL)
            } else null

            var alignedFaceBitmap: Bitmap? = null
            var isTemporaryAligned = false
            if (raw5Pts != null) {
                val alignmentResult = UmeyamaSimilarityTransform.alignFace5Points(bitmap, raw5Pts, 112, 112)
                if (alignmentResult != null && alignmentResult.alignmentError < 18.0f) {
                    alignedFaceBitmap = alignmentResult.alignedBitmap
                    isTemporaryAligned = true
                }
            }

            val unifiedResult = if (unifiedEngine.isModelLoaded && faceCrop != null && !faceCrop.isRecycled) {
                unifiedEngine.processScannerFace(
                    faceCrop = faceCrop,
                    headYaw = face.headEulerAngleY,
                    headPitch = face.headEulerAngleX,
                    leftEyeOpenProb = face.leftEyeOpenProbability,
                    rightEyeOpenProb = face.rightEyeOpenProbability,
                    alignedFace = alignedFaceBitmap
                )
            } else null

            if (isTemporaryAligned && alignedFaceBitmap != null && alignedFaceBitmap != faceCrop && !alignedFaceBitmap.isRecycled) {
                alignedFaceBitmap.recycle()
            }

            var matchResult: MatchResult? = null
            var lastExtractedEmbedding: FloatArray? = null
            var passivePadResult: PassivePadResult? = null
            var map3dResult: FaceMap3DMMResult? = null
            var attrResult: FaceAttributesResult? = null
            var gazeResult: EyeGazeResult? = null
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
                        Log.e(TAG, "Gate 3 Match Exception", t)
                    }
                }
            } else {
                if (faceCrop != null && !faceCrop.isRecycled) {
                    val fallbackEmbedding = try {
                        val alignedOrCrop = alignedFaceBitmap ?: faceCrop
                        recognitionEngine.extractEmbedding(alignedOrCrop)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Fallback embedding error: ${t.message}")
                        FloatArray(0)
                    }
                    if (fallbackEmbedding.isNotEmpty()) {
                        lastExtractedEmbedding = fallbackEmbedding
                        if (qualityResult.isPassed || qualityResult.overallQualityScore >= 35.0f) {
                            try {
                                matchResult = matcher.match(
                                    queryEmbedding = fallbackEmbedding,
                                    studentMap = studentMap,
                                    securityTier = securityTier,
                                    activeTier = recognitionEngine.activeHardwareTier
                                )
                            } catch (t: Throwable) {
                                Log.e(TAG, "Gate 3 Match Exception (fallback)", t)
                            }
                        }
                    }
                    try {
                        passivePadResult = passivePadEngine.run(faceCrop)
                    } catch (_: Throwable) {}
                }
            }

            // ── GATE 2: Temporal Anti-Spoofing Gate ──
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
                    explanation = "Temporal Liveness Bypassed"
                )
            }

            faceCrop?.recycle()

            // ── Synthesize Multi-Gate Decision ──
            val synthesis = BiometricDecisionEngine.evaluate(
                quality = qualityResult,
                passivePad = passivePadResult,
                temporalLiveness = temporalResult,
                matchResult = matchResult,
                securityTier = securityTier,
                multiStageLiveness = null,
                faceMap3DMM = map3dResult
            )

            intermediateDecisions.add(
                CandidateFaceEvaluation(
                    face = face,
                    trackId = trackState.trackId,
                    geometry = domainGeometry,
                    qualityResult = qualityResult,
                    passivePadResult = passivePadResult,
                    temporalResult = temporalResult,
                    map3dResult = map3dResult,
                    gazeResult = gazeResult,
                    attrResult = attrResult,
                    meshResult = meshResult,
                    hrnetResult = hrnetResult,
                    matchResult = matchResult,
                    lastExtractedEmbedding = lastExtractedEmbedding,
                    synthesis = synthesis
                )
            )
        }

        // Multi-Face Identity Collision Resolution
        val assignedRolls = mutableSetOf<String>()
        val sortedIndices = intermediateDecisions.indices.sortedByDescending { intermediateDecisions[it].synthesis.matchSimilarity }
        val resolvedDecisions = Array(intermediateDecisions.size) { intermediateDecisions[it].synthesis }

        for (idx in sortedIndices) {
            val item = intermediateDecisions[idx]
            val origDecision = item.synthesis
            if (origDecision.isAttendanceAuthorized && origDecision.matchedStudentRoll.isNotBlank()) {
                val roll = origDecision.matchedStudentRoll
                if (assignedRolls.contains(roll)) {
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
                        subtitle = "Roll $roll claimed by multiple faces in view",
                        technicalExplanation = "Multi-face collision: Identity $roll claimed by multiple faces"
                    )
                } else {
                    assignedRolls.add(roll)
                }
            }
        }

        var primaryDomainDecision: VerificationDecision? = null

        for (i in intermediateDecisions.indices) {
            val item = intermediateDecisions[i]
            val resolved = resolvedDecisions[i]
            val trackId = item.trackId
            val stabilized = tracker.stabilizeDecision(trackId, resolved)
            val trackState = tracker.getTrackState(trackId)

            // Update auxiliary telemetry in tracker
            tracker.updateAuxiliaryFeatures(
                trackId = trackId,
                meshResult = item.meshResult,
                map3dResult = item.map3dResult,
                gazeResult = item.gazeResult,
                attrResult = item.attrResult,
                qualityResult = item.qualityResult
            )

            val faceDecision: VerificationDecision = when (stabilized.gateState) {
                PipelineGateState.PASS -> {
                    val roll = stabilized.matchedStudentRoll
                    val identity = identityMap[roll]
                    val displayName = identity?.displayName ?: stabilized.matchedStudentName
                    val role = identity?.role ?: "STUDENT"
                    val confidence = stabilized.matchConfidence / 100f
                    val liveness = stabilized.livenessScore / 100f

                    val count = (consecutiveMatchCounts[roll] ?: 0) + 1
                    consecutiveMatchCounts[roll] = count

                    val now = System.currentTimeMillis()
                    val isTrackAlreadyLogged = trackState?.hasTriggeredAttendance == true
                    val lastTime = lastVerifiedTimestamps[roll] ?: 0L
                    val isCooldownElapsed = (now - lastTime > COOLDOWN_PERIOD_MS)
                    val isNewRoll = (roll != lastAuthorizedRoll)

                    val requiredFrames = if (stabilized.matchSimilarity >= 0.72f) 1 else REQUIRED_CONSECUTIVE_FRAMES

                    if (count >= requiredFrames && !isTrackAlreadyLogged && (isNewRoll || isCooldownElapsed)) {
                        lastAuthorizedRoll = roll
                        lastAuthorizedTimestampMs = now
                        lastVerifiedTimestamps[roll] = now
                        consecutiveMatchCounts[roll] = 0
                        trackState?.hasTriggeredAttendance = true

                        val prevHash = AndroidSecurityUtils.AEGIS_GENESIS_HASH
                        val leafHash = AndroidSecurityUtils.computeAegisBlockHash(
                            previousHash = prevHash,
                            studentRoll = roll,
                            timestamp = now,
                            confidencePct = stabilized.matchConfidence
                        )

                        _transientEvents.emit(
                            BiometricTransientEvent.AttendanceConfirmed(
                                identityId = roll,
                                displayName = displayName,
                                role = role,
                                confidence = confidence,
                                leafHash = leafHash,
                                timestamp = now
                            )
                        )

                        VerificationDecision.Verified(
                            identityId = roll,
                            displayName = displayName,
                            role = role,
                            confidence = confidence,
                            liveness = liveness,
                            leafHash = leafHash,
                            geometry = item.geometry
                        )
                    } else if (isTrackAlreadyLogged || !isCooldownElapsed) {
                        val remainingCooldown = (COOLDOWN_PERIOD_MS - (now - lastTime)).coerceAtLeast(0L)
                        _transientEvents.emit(
                            BiometricTransientEvent.DuplicateDetected(
                                identityId = roll,
                                displayName = displayName,
                                role = role
                            )
                        )
                        VerificationDecision.Duplicate(
                            identityId = roll,
                            displayName = displayName,
                            role = role,
                            remainingCooldownMs = remainingCooldown,
                            geometry = item.geometry
                        )
                    } else {
                        // Temporal consensus accumulating
                        VerificationDecision.Verified(
                            identityId = roll,
                            displayName = displayName,
                            role = role,
                            confidence = confidence,
                            liveness = liveness,
                            leafHash = "",
                            geometry = item.geometry
                        )
                    }
                }
                PipelineGateState.REJECT_SPOOF_ATTACK -> {
                    val reason = if (item.map3dResult != null && item.map3dResult.depthVariance < 0.0010f) {
                        SpoofReason.DEPTH_VARIANCE_FLAT
                    } else if (item.temporalResult != null && !item.temporalResult.isLive) {
                        SpoofReason.TEMPORAL_JITTER
                    } else {
                        SpoofReason.TEXTURE_ANOMALY
                    }
                    val spoofScore = (100f - stabilized.livenessScore) / 100f
                    _transientEvents.emit(
                        BiometricTransientEvent.SpoofAttemptBlocked(
                            reason = reason,
                            confidence = spoofScore
                        )
                    )
                    VerificationDecision.SpoofRejected(
                        reason = reason,
                        confidence = spoofScore,
                        geometry = item.geometry
                    )
                }
                PipelineGateState.REJECT_QUALITY -> {
                    val qReason = item.qualityResult.rejectionReason
                    val reason = when {
                        qReason.contains("blurry", ignoreCase = true) || item.qualityResult.sharpnessScore < 32f -> QualityReason.BLURRY
                        qReason.contains("lighting", ignoreCase = true) -> {
                            if (item.qualityResult.meanLuminance > 195f) QualityReason.OVEREXPOSED else QualityReason.UNDEREXPOSED
                        }
                        qReason.contains("level", ignoreCase = true) || item.qualityResult.poseScore < 40f -> {
                            if (abs(item.face.headEulerAngleX) > 20f) QualityReason.POSE_PITCH_EXCEEDED else QualityReason.POSE_YAW_EXCEEDED
                        }
                        else -> QualityReason.FACE_TOO_SMALL
                    }
                    VerificationDecision.PoorQuality(
                        reason = reason,
                        geometry = item.geometry
                    )
                }
                PipelineGateState.REJECT_UNKNOWN_IDENTITY,
                PipelineGateState.REVIEW_AMBIGUOUS_MATCH -> {
                    VerificationDecision.Unknown(
                        confidence = stabilized.matchConfidence / 100f,
                        geometry = item.geometry
                    )
                }
            }

            if (primaryDomainDecision == null || (faceDecision is VerificationDecision.Verified && primaryDomainDecision !is VerificationDecision.Verified)) {
                primaryDomainDecision = faceDecision
            }
        }

        val elapsedMs = ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L).coerceAtLeast(1L)
        _telemetry.update { it.copy(latencyMs = elapsedMs) }

        primaryDomainDecision ?: VerificationDecision.Idle
    }

    override fun close() {
        templateObservationJob?.cancel()
        thermalObservationJob?.cancel()
        coroutineScope.cancel()
        passivePadEngine.close()
        matcher.clear()
        temporalLivenessEngine.clearAll()
        tracker.clear()
        Log.i(TAG, "BiometricVerificationEngine closed successfully")
    }

    private data class CandidateFaceEvaluation(
        val face: Face,
        val trackId: Int,
        val geometry: DomainFaceGeometry,
        val qualityResult: QualityGateResult,
        val passivePadResult: PassivePadResult?,
        val temporalResult: com.omniface.ai.ml.antispoof.TemporalLivenessResult?,
        val map3dResult: FaceMap3DMMResult?,
        val gazeResult: EyeGazeResult?,
        val attrResult: FaceAttributesResult?,
        val meshResult: MediaPipeMeshResult?,
        val hrnetResult: HRNetFaceResult?,
        val matchResult: MatchResult?,
        val lastExtractedEmbedding: FloatArray?,
        val synthesis: BiometricSynthesisDecision
    )
}
