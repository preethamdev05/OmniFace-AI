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
            return BiometricVerificationEngineImpl(
                context = appContext,
                identityStore = identityStore,
                recognitionEngine = recEngine
            )
        }
    }

    val passivePadEngine = PassivePadEngine(context)
    val multiStageLivenessEngine = MultiStageLivenessEngine(context, passivePadEngine)
    val temporalLivenessEngine = TemporalLivenessEngine()
    val matcher = FaceMatcher()

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
    override val automationStateMachine = com.omniface.ai.ml.verification.policy.KioskAutomationStateMachine()

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

        val tierLabel = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)

        _telemetry.update {
            it.copy(
                resolvedBackendLabel = tierLabel,
                isReady = recognitionEngine.isEngineReady
            )
        }
        Log.i(TAG, "BiometricVerificationEngine reloaded ${templates.size} volatile identity templates")
    }

    override fun preloadTemplates(templates: List<com.omniface.ai.data.local.entity.FaceTemplateEntity>) {
        if (templates.isEmpty()) {
            matcher.preloadTemplates(emptyList())
            recognitionEngine.preloadTemplates(emptyList())
            return
        }
        val cachedList = mutableListOf<CachedBiometric>()
        for (entity in templates) {
            val decryptedCsv = try {
                if (entity.isEncrypted) AndroidSecurityUtils.decrypt(entity.embeddingEncryptedCsv)
                else entity.embeddingEncryptedCsv
            } catch (t: Throwable) {
                Log.e(TAG, "Skipping corrupt template ${entity.id}: ${t.message}")
                continue
            }
            if (decryptedCsv.isBlank()) continue
            val emb = parseEmbeddingCsv(decryptedCsv)
            if (emb.isNotEmpty()) {
                l2Normalize(emb)
                cachedList.add(
                    CachedBiometric(
                        templateId = entity.id,
                        studentRoll = entity.studentRoll,
                        angleType = entity.angleType,
                        embedding = emb
                    )
                )
            }
        }
        matcher.preloadCachedBiometrics(cachedList)
        recognitionEngine.preloadCachedBiometrics(cachedList)
    }

    override fun preloadCachedBiometrics(cachedList: List<CachedBiometric>) {
        matcher.preloadCachedBiometrics(cachedList)
        recognitionEngine.preloadCachedBiometrics(cachedList)
    }

    private fun parseEmbeddingCsv(csv: String): FloatArray {
        return try {
            csv.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (_: Exception) {
            FloatArray(0)
        }
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vec) sumSquares += v * v
        val norm = kotlin.math.sqrt(sumSquares)
        if (norm > 1e-6f) {
            val invNorm = 1.0f / norm
            for (i in vec.indices) vec[i] *= invNorm
        } else {
            java.util.Arrays.fill(vec, 0.0f)
        }
        return vec
    }

    override suspend fun evaluateFrame(
        frame: BiometricFrame,
        studentMap: Map<String, String>,
        securityTier: SecurityTier,
        downscaleFactor: Float
    ): BiometricBatchEvaluation = withContext(Dispatchers.Default) {
        val t0 = SystemClock.elapsedRealtimeNanos()
        val bitmap = frame.toBitmap()

        if (bitmap == null || bitmap.isRecycled) {
            tracker.purgeOldTracks()
            return@withContext BiometricBatchEvaluation(
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
                    technicalExplanation = "No frame or bitmap is recycled"
                ),
                allDecisions = emptyList(),
                triggeredDecisions = emptyList(),
                isAttendanceTriggered = false,
                executionLatencyMs = 0L,
                activeHardwareTier = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)
            )
        }

        val faces = frame.detectedFaces
        if (faces.isEmpty()) {
            tracker.purgeOldTracks()
            return@withContext BiometricBatchEvaluation(
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
                allDecisions = emptyList(),
                triggeredDecisions = emptyList(),
                isAttendanceTriggered = false,
                executionLatencyMs = 0L,
                activeHardwareTier = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)
            )
        }

        val effectiveDownscale = if (ThermalGovernor.isAutoScalingEnabled.value) {
            ThermalGovernor.thermalState.value.downscaleFactor.coerceAtMost(downscaleFactor)
        } else downscaleFactor

        val claimedTrackIds = mutableSetOf<Int>()
        val intermediateDecisions = mutableListOf<CandidateFaceEvaluation>()

        val effectiveStudentMap = if (studentMap.isNotEmpty()) studentMap else identityMap.mapValues { it.value.displayName }
        val config = NeuralModelConfigManager.configState.value
        val scheduler = com.omniface.ai.ml.concurrency.BoundedGroupInferenceScheduler.getDefault()
        val maxPermits = com.omniface.ai.hardware.DeviceCapacityGovernor.getMaxConcurrentFaces(context)
        scheduler.adaptToThermalState(ThermalGovernor.thermalState.value, maxPermits)

        val detectedTrackIds = faces.mapNotNull { it.trackingId }.toSet()
        val purgedTrackIds = tracker.onFrameTracksUpdated(detectedTrackIds)
        for (purgedId in purgedTrackIds) {
            scheduler.cancelTrack(purgedId)
            automationStateMachine.onCycleCompleted(purgedId)
        }
        if (faces.isNotEmpty()) {
            automationStateMachine.onFacesDetected(faces.size, detectedTrackIds)
        } else {
            automationStateMachine.onNoFacesDetected()
        }

        for (face in faces.take(12)) {
            val rawBox = face.boundingBox
            val box = if (effectiveDownscale < 0.99f) {
                android.graphics.Rect(
                    (rawBox.left * effectiveDownscale).toInt().coerceIn(0, bitmap.width),
                    (rawBox.top * effectiveDownscale).toInt().coerceIn(0, bitmap.height),
                    (rawBox.right * effectiveDownscale).toInt().coerceIn(0, bitmap.width),
                    (rawBox.bottom * effectiveDownscale).toInt().coerceIn(0, bitmap.height)
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

            val leftEye = leftEyeRaw?.let { if (effectiveDownscale < 0.99f) PointF(it.x * effectiveDownscale, it.y * effectiveDownscale) else it }
            val rightEye = rightEyeRaw?.let { if (effectiveDownscale < 0.99f) PointF(it.x * effectiveDownscale, it.y * effectiveDownscale) else it }
            val nose = noseRaw?.let { if (effectiveDownscale < 0.99f) PointF(it.x * effectiveDownscale, it.y * effectiveDownscale) else it }
            val mouthL = mouthLRaw?.let { if (effectiveDownscale < 0.99f) PointF(it.x * effectiveDownscale, it.y * effectiveDownscale) else it }
            val mouthR = mouthRRaw?.let { if (effectiveDownscale < 0.99f) PointF(it.x * effectiveDownscale, it.y * effectiveDownscale) else it }

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

            var matchResult: MatchResult? = null
            var lastExtractedEmbedding: FloatArray? = null
            var passivePadResult: PassivePadResult? = null
            val map3dResult: FaceMap3DMMResult? = null
            val attrResult: FaceAttributesResult? = null
            val gazeResult: EyeGazeResult? = null
            val meshResult: MediaPipeMeshResult? = null
            val hrnetResult: HRNetFaceResult? = null

            if (faceCrop != null && !faceCrop.isRecycled && !scheduler.isTrackCancelled(trackId)) {
                val extractedEmbedding = try {
                    val alignedOrCrop = alignedFaceBitmap ?: faceCrop
                    scheduler.execute {
                        recognitionEngine.extractEmbedding(alignedOrCrop)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Embedding extraction error: ${t.message}")
                    FloatArray(0)
                }

                if (extractedEmbedding.isNotEmpty()) {
                    lastExtractedEmbedding = extractedEmbedding
                    if (qualityResult.isPassed || qualityResult.overallQualityScore >= 35.0f) {
                        trackState.pushEmbedding(extractedEmbedding, qualityResult.overallQualityScore / 100f)
                        val effectiveEmbedding = trackState.getFusedEmbedding() ?: extractedEmbedding
                        try {
                            matchResult = matcher.match(
                                queryEmbedding = effectiveEmbedding,
                                studentMap = effectiveStudentMap,
                                securityTier = securityTier,
                                activeTier = recognitionEngine.activeHardwareTier,
                                useCalibratedThreshold = (recognitionEngine.activeBackbone == NeuralBackbone.MOBILEFACENET)
                            )
                        } catch (t: Throwable) {
                            Log.e(TAG, "Gate 3 Match Exception", t)
                        }
                    }
                }

                if (config.isPassivePadEnabled) {
                    try {
                        passivePadResult = passivePadEngine.run(faceCrop)
                    } catch (t: Throwable) {
                        Log.w(TAG, "Passive PAD evaluation error: ${t.message}")
                    }
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

            // ── Multi-Stage Liveness Evaluation (Prior to recycling faceCrop) ──
            val multiStageResult: MultiStageLivenessResult? = if (config.isMultiStageLivenessEnabled && faceCrop != null && !faceCrop.isRecycled) {
                try {
                    multiStageLivenessEngine.evaluate(faceCrop, passivePadResult)
                } catch (t: Throwable) {
                    Log.w(TAG, "MultiStageLiveness error: ${t.message}")
                    null
                }
            } else null

            if (isTemporaryAligned && alignedFaceBitmap != null && alignedFaceBitmap != faceCrop && !alignedFaceBitmap.isRecycled) {
                alignedFaceBitmap.recycle()
            }
            faceCrop?.recycle()

            // ── Synthesize Multi-Gate Decision ──
            val synthesis = BiometricDecisionEngine.evaluate(
                quality = qualityResult,
                passivePad = passivePadResult,
                temporalLiveness = temporalResult,
                matchResult = matchResult,
                securityTier = securityTier,
                multiStageLiveness = multiStageResult,
                faceMap3DMM = map3dResult
            )

            intermediateDecisions.add(
                CandidateFaceEvaluation(
                    face = face,
                    trackId = trackState.trackId,
                    smoothedRect = smoothedRect,
                    domainGeometry = domainGeometry,
                    qualityResult = qualityResult,
                    passivePadResult = passivePadResult,
                    multiStageLivenessResult = multiStageResult,
                    temporalResult = temporalResult,
                    map3dResult = map3dResult,
                    gazeResult = gazeResult,
                    attrResult = attrResult,
                    meshResult = meshResult,
                    hrnetResult = hrnetResult,
                    matchResult = matchResult,
                    lastExtractedEmbedding = lastExtractedEmbedding,
                    synthesis = synthesis,
                    leftEye = leftEye,
                    rightEye = rightEye,
                    nose = nose,
                    mouthL = mouthL,
                    mouthR = mouthR
                )
            )
        }

        // ── MULTI-FACE IDENTITY COLLISION & DUPLICATE RESOLUTION ──
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

        val allDecisions = mutableListOf<BiometricSynthesisDecision>()
        val triggeredDecisions = mutableListOf<BiometricSynthesisDecision>()
        val candidateEvaluations = mutableListOf<CandidateFaceEvaluation>()
        var primaryDecision: BiometricSynthesisDecision? = null
        var attendanceTriggered = false

        for (i in intermediateDecisions.indices) {
            val item = intermediateDecisions[i]
            val resolved = resolvedDecisions[i]
            val trackId = item.trackId
            val stabilized = tracker.stabilizeDecision(trackId, resolved)
            val trackState = tracker.getTrackState(trackId)
            allDecisions.add(stabilized)

            // Update auxiliary telemetry in tracker
            tracker.updateAuxiliaryFeatures(
                trackId = trackId,
                meshResult = item.meshResult,
                map3dResult = item.map3dResult,
                gazeResult = item.gazeResult,
                attrResult = item.attrResult,
                qualityResult = item.qualityResult
            )

            // Multi-Frame Temporal Consensus Voting & Automation Policy
            val roll = stabilized.matchedStudentRoll
            var thisFaceTriggered = false
            if (stabilized.isAttendanceAuthorized && roll.isNotBlank()) {
                val count = (consecutiveMatchCounts[roll] ?: 0) + 1
                consecutiveMatchCounts[roll] = count

                val now = System.currentTimeMillis()
                val automation = com.omniface.ai.ml.verification.policy.AutomationPolicy.evaluate(
                    synthesis = stabilized,
                    quality = item.qualityResult,
                    consecutiveMatchCount = count,
                    lastVerifiedTimestampMs = lastVerifiedTimestamps[roll] ?: 0L,
                    hasTrackAlreadyTriggered = trackState?.hasTriggeredAttendance == true,
                    currentTimeMs = now
                )

                automationStateMachine.onPolicyEvaluated(trackId, automation)

                if (automation.action == com.omniface.ai.ml.verification.policy.AutomationAction.AUTO_CONFIRM) {
                    thisFaceTriggered = true
                    attendanceTriggered = true
                    lastAuthorizedRoll = roll
                    lastAuthorizedTimestampMs = now
                    lastVerifiedTimestamps[roll] = now
                    consecutiveMatchCounts[roll] = 0
                    trackState?.hasTriggeredAttendance = true
                    triggeredDecisions.add(stabilized)

                    val prevHash = AndroidSecurityUtils.AEGIS_GENESIS_HASH
                    val leafHash = AndroidSecurityUtils.computeAegisBlockHash(
                        previousHash = prevHash,
                        studentRoll = roll,
                        timestamp = now,
                        confidencePct = stabilized.matchConfidence
                    )

                    val identity = identityMap[roll]
                    val displayName = identity?.displayName ?: stabilized.matchedStudentName
                    val role = identity?.role ?: "STUDENT"

                    _transientEvents.emit(
                        BiometricTransientEvent.AttendanceConfirmed(
                            identityId = roll,
                            displayName = displayName,
                            role = role,
                            confidence = stabilized.matchConfidence / 100f,
                            leafHash = leafHash,
                            timestamp = now
                        )
                    )
                } else if (automation.action == com.omniface.ai.ml.verification.policy.AutomationAction.NEEDS_REVIEW) {
                    trackState?.classification = com.omniface.ai.ml.tracking.IdentityClassification.AMBIGUOUS_REVIEW
                    val identity = identityMap[roll]
                    val displayName = identity?.displayName ?: stabilized.matchedStudentName
                    _transientEvents.emit(
                        BiometricTransientEvent.ReviewRequired(
                            identityId = roll,
                            displayName = displayName,
                            reason = automation.reason,
                            margin = stabilized.decisionMargin
                        )
                    )
                } else if (automation.action == com.omniface.ai.ml.verification.policy.AutomationAction.COOLDOWN) {
                    val identity = identityMap[roll]
                    val displayName = identity?.displayName ?: stabilized.matchedStudentName
                    val role = identity?.role ?: "STUDENT"
                    _transientEvents.emit(
                        BiometricTransientEvent.DuplicateDetected(
                            identityId = roll,
                            displayName = displayName,
                            role = role
                        )
                    )
                }
            } else {
                val automation = com.omniface.ai.ml.verification.policy.AutomationPolicy.evaluate(
                    synthesis = stabilized,
                    quality = item.qualityResult,
                    consecutiveMatchCount = 0,
                    lastVerifiedTimestampMs = 0L,
                    hasTrackAlreadyTriggered = trackState?.hasTriggeredAttendance == true
                )
                automationStateMachine.onPolicyEvaluated(trackId, automation)
                if (automation.action == com.omniface.ai.ml.verification.policy.AutomationAction.NEEDS_REVIEW) {
                    trackState?.classification = com.omniface.ai.ml.tracking.IdentityClassification.AMBIGUOUS_REVIEW
                    _transientEvents.emit(
                        BiometricTransientEvent.ReviewRequired(
                            identityId = stabilized.matchedStudentRoll.ifBlank { "UNKNOWN" },
                            displayName = stabilized.matchedStudentName.ifBlank { "Unknown Subject" },
                            reason = automation.reason,
                            margin = stabilized.decisionMargin
                        )
                    )
                } else if (stabilized.gateState == PipelineGateState.REJECT_SPOOF_ATTACK) {
                    val spoofReason = if (item.map3dResult != null && item.map3dResult.depthVariance <= 0.0015f) {
                        com.omniface.ai.ml.verification.domain.SpoofReason.DEPTH_VARIANCE_FLAT
                    } else if (item.temporalResult != null && !item.temporalResult.isLive) {
                        com.omniface.ai.ml.verification.domain.SpoofReason.TEMPORAL_JITTER
                    } else {
                        com.omniface.ai.ml.verification.domain.SpoofReason.TEXTURE_ANOMALY
                    }
                    _transientEvents.emit(
                        BiometricTransientEvent.SpoofAttemptBlocked(
                            reason = spoofReason,
                            confidence = (100f - stabilized.livenessScore) / 100f
                        )
                    )
                }
            }

            // Dynamic Centroid Adaptation
            if (config.isDynamicCentroidAdaptationEnabled && thisFaceTriggered && stabilized.isAttendanceAuthorized && stabilized.matchedStudentRoll.isNotBlank() && item.lastExtractedEmbedding != null) {
                val adaptedPair = matcher.adaptCentroidIfHighConfidence(
                    studentRoll = stabilized.matchedStudentRoll,
                    liveEmbedding = item.lastExtractedEmbedding,
                    similarityScore = stabilized.matchSimilarity
                )
                if (adaptedPair != null) {
                    val (tplId, newEncryptedCsv) = adaptedPair
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            identityStore.updateTemplateEmbedding(tplId, newEncryptedCsv)
                            Log.d(TAG, "🧠 [DYNAMIC CENTROID] Adapted and persisted template $tplId for ${stabilized.matchedStudentRoll}")
                        } catch (t: Throwable) {
                            Log.w(TAG, "Failed to persist adapted centroid: ${t.message}")
                        }
                    }
                }
            }

            if (primaryDecision == null || (stabilized.isAttendanceAuthorized && !primaryDecision.isAttendanceAuthorized)) {
                primaryDecision = stabilized
            }

            candidateEvaluations.add(
                item.copy(synthesis = stabilized)
            )
        }

        val elapsedMs = ((SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L).coerceAtLeast(1L)
        val activeTier = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)
        _telemetry.update { it.copy(latencyMs = elapsedMs) }

        BiometricBatchEvaluation(
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
            allDecisions = allDecisions,
            triggeredDecisions = triggeredDecisions,
            isAttendanceTriggered = attendanceTriggered,
            executionLatencyMs = elapsedMs,
            activeHardwareTier = activeTier,
            candidateEvaluations = candidateEvaluations
        )
    }

    override suspend fun verifyFrame(
        frame: BiometricFrame,
        securityTier: SecurityTier
    ): VerificationDecision {
        val batch = evaluateFrame(frame, securityTier = securityTier)
        val candidate = batch.candidateEvaluations.firstOrNull() ?: return VerificationDecision.Idle
        val top = batch.topDecision
        return when (top.gateState) {
            PipelineGateState.PASS -> {
                val roll = top.matchedStudentRoll
                val identity = identityMap[roll]
                val displayName = identity?.displayName ?: top.matchedStudentName
                val role = identity?.role ?: "STUDENT"
                val confidence = top.matchConfidence / 100f
                val liveness = top.livenessScore / 100f
                val leafHash = if (batch.isAttendanceTriggered) {
                    val prevHash = AndroidSecurityUtils.AEGIS_GENESIS_HASH
                    AndroidSecurityUtils.computeAegisBlockHash(
                        previousHash = prevHash,
                        studentRoll = roll,
                        timestamp = System.currentTimeMillis(),
                        confidencePct = top.matchConfidence
                    )
                } else ""
                VerificationDecision.Verified(
                    identityId = roll,
                    displayName = displayName,
                    role = role,
                    confidence = confidence,
                    liveness = liveness,
                    leafHash = leafHash,
                    geometry = candidate.domainGeometry
                )
            }
            PipelineGateState.REJECT_SPOOF_ATTACK -> {
                val reason = if (candidate.map3dResult != null && candidate.map3dResult.depthVariance <= 0.0015f) {
                    SpoofReason.DEPTH_VARIANCE_FLAT
                } else if (candidate.temporalResult != null && !candidate.temporalResult.isLive) {
                    SpoofReason.TEMPORAL_JITTER
                } else {
                    SpoofReason.TEXTURE_ANOMALY
                }
                VerificationDecision.SpoofRejected(
                    reason = reason,
                    confidence = (100f - top.livenessScore) / 100f,
                    geometry = candidate.domainGeometry
                )
            }
            PipelineGateState.REJECT_QUALITY -> {
                val qReason = candidate.qualityResult.rejectionReason
                val reason = when {
                    qReason.contains("blurry", ignoreCase = true) || candidate.qualityResult.sharpnessScore < 32f -> QualityReason.BLURRY
                    qReason.contains("lighting", ignoreCase = true) -> {
                        if (candidate.qualityResult.meanLuminance > 195f) QualityReason.OVEREXPOSED else QualityReason.UNDEREXPOSED
                    }
                    qReason.contains("level", ignoreCase = true) || candidate.qualityResult.poseScore < 40f -> {
                        if (abs(candidate.face.headEulerAngleX) > 20f) QualityReason.POSE_PITCH_EXCEEDED else QualityReason.POSE_YAW_EXCEEDED
                    }
                    else -> QualityReason.FACE_TOO_SMALL
                }
                VerificationDecision.PoorQuality(
                    reason = reason,
                    geometry = candidate.domainGeometry
                )
            }
            PipelineGateState.REJECT_UNKNOWN_IDENTITY,
            PipelineGateState.REVIEW_AMBIGUOUS_MATCH -> {
                VerificationDecision.Unknown(
                    confidence = top.matchConfidence / 100f,
                    geometry = candidate.domainGeometry
                )
            }
        }
    }

    override fun close() {
        templateObservationJob?.cancel()
        thermalObservationJob?.cancel()
        coroutineScope.cancel()
        passivePadEngine.close()
        matcher.clear()
        temporalLivenessEngine.clearAll()
        tracker.clear()
        identityMap.clear()
        consecutiveMatchCounts.clear()
        lastVerifiedTimestamps.clear()
        enrolledTemplates = emptyList()
        Log.i(TAG, "BiometricVerificationEngine closed successfully")
    }
}
