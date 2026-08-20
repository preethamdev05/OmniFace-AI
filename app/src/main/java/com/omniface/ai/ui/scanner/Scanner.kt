package com.omniface.ai.ui.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CaptureRequest
import android.media.Image
import com.omniface.ai.hardware.NpuHardwareDetector
import android.util.Range
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.audio.BiometricSoundboard
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.hardware.TurnstileRelayController
import com.omniface.ai.ml.*
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.ui.components.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Immutable
data class FaceBoxUi(
    val rect: androidx.compose.ui.geometry.Rect,
    val name: String,
    val roll: String,
    val isVerified: Boolean,
    val isGuest: Boolean,
    val isSpoof: Boolean = false,
    val isReview: Boolean = false,
    val similarity: Float,
    val decisionMargin: Float = 0.0f,
    val confidenceZone: ConfidenceZone = if (isVerified) ConfidenceZone.ACCEPT else ConfidenceZone.REJECT,
    val explanation: String = ""
)

enum class ScannerScanState {
    EMPTY_DATABASE,
    READY_TO_SCAN,
    FACE_DETECTED,
    VERIFYING,
    RECOGNIZED,
    ATTENDANCE_RECORDED,
    REVIEW_REQUIRED,
    UNKNOWN_IDENTITY,
    POOR_QUALITY,
    SPOOF_ALERT
}

@Immutable
data class QualcommIntelligenceTelemetry(
    val isSnapdragonDevice: Boolean = true,
    val isCavafaceActive: Boolean = false,
    val is3DMMActive: Boolean = false,
    val depthVariance: Float = 0f,
    val isEyeGazeActive: Boolean = false,
    val gazeAttentive: Boolean = true,
    val gazePitch: Float = 0f,
    val gazeYaw: Float = 0f,
    val isFaceAttribActive: Boolean = false,
    val smileScore: Float = 0f,
    val eyeglassesScore: Float = 0f,
    val isMeshActive: Boolean = false,
    val meshPointsCount: Int = 0
)

@Immutable
data class ScannerUiState(
    val detectedFaces: List<FaceBoxUi> = emptyList(),
    val visualGeometryData: List<FaceGeometryVisualData> = emptyList(),
    val isProcessing: Boolean = false,
    val activeTier: SecurityTier = SecurityTier.HIGH,
    val scanState: ScannerScanState = ScannerScanState.READY_TO_SCAN,
    val matchTitle: String = "READY TO SCAN",
    val matchSubtitle: String = "Align face inside frame",
    val matchedRoll: String = "",
    val matchedName: String = "",
    val matchedTimeFormatted: String = "",
    val lastConfidence: Float = 0f,
    val matchedMargin: Float = 0f,
    val matchedZone: ConfidenceZone = ConfidenceZone.REJECT,
    val matchedExplanation: String = "",
    val isMultiFaceMode: Boolean = false,
    val isScanningPaused: Boolean = false,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val isDatabaseEmpty: Boolean = false,
    val enrolledCount: Int = 0,
    val hardwareTierLabel: String = "Hexagon NPU",
    val benchmarkLatencyMs: Long = 6L,
    val showManualOverrideDialog: Boolean = false,
    val showHardwareSwitcher: Boolean = false,
    val isDeveloperOverlayEnabled: Boolean = true,
    val isQualcommDevice: Boolean = NpuHardwareDetector.isQualcommAiHubDevice(),
    val qualcommTelemetry: QualcommIntelligenceTelemetry? = null,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle(false, "Qualcomm Unified NPU Engine"),
    val activeModelDisplayName: String = "Qualcomm Unified NPU Engine"
)

class ScannerViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val downloadManager = ModelDownloadManager.getInstance(OmniFaceApplication.instance)
    private val _uiState = MutableStateFlow(
        ScannerUiState(
            activeModelDisplayName = downloadManager.getActiveModelDisplayName(),
            modelDownloadState = downloadManager.downloadState.value
        )
    )
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private var recognitionEngine: FaceRecognitionEngine? = null
    private val livenessDetector = LivenessDetector()
    private val qualcommIntelligenceEngine = try {
        QualcommFaceIntelligenceEngine(OmniFaceApplication.instance)
    } catch (_: Throwable) {
        null
    }
    val cameraExecutor = Executors.newSingleThreadExecutor()

    private val lastVerifiedTimestamps = ConcurrentHashMap<String, Long>()
    private val emaBoundingBoxes = ConcurrentHashMap<Int, androidx.compose.ui.geometry.Rect>()
    @Volatile
    var isProcessingFrame = false
    private var lastAnalysisTimestamp = 0L

    fun toggleHardwareSwitcher() {
        _uiState.update { it.copy(showHardwareSwitcher = !it.showHardwareSwitcher) }
    }

    fun toggleDeveloperOverlay() {
        _uiState.update { it.copy(isDeveloperOverlayEnabled = !it.isDeveloperOverlayEnabled) }
    }

    fun selectHardwareBackend(tier: HardwareTier) {
        recognitionEngine?.switchHardwareTier(tier)
        viewModelScope.launch(Dispatchers.Default) {
            val latency = recognitionEngine?.benchmarkInferenceLatency() ?: 6L
            _uiState.update {
                it.copy(
                    hardwareTierLabel = tier.label,
                    benchmarkLatencyMs = latency,
                    showHardwareSwitcher = false
                )
            }
        }
    }

    private var securityPipeline: com.omniface.ai.ml.pipeline.FaceSecurityPipeline? = null

    init {
        checkDatabaseStatus()
        observeModelDownloads()
    }

    private fun observeModelDownloads() {
        viewModelScope.launch {
            downloadManager.downloadState.collect { state ->
                val name = downloadManager.getActiveModelDisplayName()
                _uiState.update {
                    it.copy(
                        modelDownloadState = state,
                        activeModelDisplayName = name
                    )
                }
                if (state is ModelDownloadState.Ready) {
                    recognitionEngine?.reloadEngine()
                }
            }
        }
    }

    @Volatile
    private var cachedStudentMap: Map<String, String> = emptyMap()
    @Volatile
    private var cachedTemplates: List<FaceTemplateEntity> = emptyList()

    private fun checkDatabaseStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            db.studentDao().getAllStudentsFlow().collect { students ->
                val templates = db.studentDao().getAllTemplates()
                cachedStudentMap = students.associate { it.rollNumber to it.fullName }
                cachedTemplates = templates
                recognitionEngine?.preloadTemplates(templates)
                securityPipeline?.preloadTemplates(templates)
                val isEmpty = students.isEmpty()
                val count = students.size
                _uiState.update {
                    it.copy(
                        isDatabaseEmpty = isEmpty,
                        enrolledCount = count,
                        scanState = if (isEmpty) ScannerScanState.EMPTY_DATABASE else (if (it.scanState == ScannerScanState.EMPTY_DATABASE) ScannerScanState.READY_TO_SCAN else it.scanState),
                        matchTitle = if (isEmpty) "DATABASE EMPTY" else (if (it.matchTitle == "DATABASE EMPTY") "READY TO SCAN" else it.matchTitle),
                        matchSubtitle = if (isEmpty) "0 students enrolled" else (if (it.matchSubtitle == "0 students enrolled") "$count students enrolled" else it.matchSubtitle)
                    )
                }
            }
        }
    }

    fun initEngine(context: Context) {
        BiometricSoundboard.initTts(context)
        if (recognitionEngine == null) {
            val engine = FaceRecognitionEngine(context)
            if (cachedTemplates.isNotEmpty()) {
                engine.preloadTemplates(cachedTemplates)
            }
            recognitionEngine = engine
            securityPipeline = com.omniface.ai.ml.pipeline.FaceSecurityPipeline(context, engine, qualcommIntelligenceEngine)
            if (cachedTemplates.isNotEmpty()) {
                securityPipeline?.preloadTemplates(cachedTemplates)
            }
            viewModelScope.launch(Dispatchers.Default) {
                val latency = engine.benchmarkInferenceLatency()
                val npuInfo = engine.npuHardwareInfo
                val tier = if (engine.activeHardwareTier == HardwareTier.NPU_NNAPI) {
                    if (npuInfo.isGenuineNpuDetected) {
                        when {
                            npuInfo.npuName.contains("Hexagon") -> "Hexagon NPU"
                            npuInfo.npuName.contains("Tensor") -> "Tensor TPU"
                            npuInfo.npuName.contains("APU") -> "NeuroPilot APU"
                            else -> "NPU"
                        }
                    } else "NPU"
                } else engine.activeHardwareTier.label
                _uiState.update {
                    it.copy(
                        hardwareTierLabel = tier,
                        benchmarkLatencyMs = latency
                    )
                }
            }
        }
    }

    fun setSecurityTier(tier: SecurityTier) {
        _uiState.update { it.copy(activeTier = tier) }
    }

    fun toggleMultiFaceMode() {
        _uiState.update { it.copy(isMultiFaceMode = !it.isMultiFaceMode) }
    }

    fun toggleLensFacing() {
        val newFacing = if (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        _uiState.update { it.copy(lensFacing = newFacing) }
    }

    fun togglePauseScan() {
        _uiState.update { it.copy(isScanningPaused = !it.isScanningPaused) }
    }

    fun retryScan() {
        _uiState.update {
            it.copy(
                lastConfidence = 0f,
                matchedRoll = "",
                matchedName = "",
                matchedTimeFormatted = "",
                scanState = if (it.isDatabaseEmpty) ScannerScanState.EMPTY_DATABASE else ScannerScanState.READY_TO_SCAN,
                matchTitle = if (it.isDatabaseEmpty) "DATABASE EMPTY" else "READY TO SCAN",
                matchSubtitle = if (it.isDatabaseEmpty) "0 students enrolled" else "${it.enrolledCount} students enrolled"
            )
        }
    }

    fun markManualAttendance() {
        val current = _uiState.value
        if (current.matchedRoll.isNotEmpty()) {
            val nowMs = System.currentTimeMillis()
            val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(nowMs))
            lastVerifiedTimestamps[current.matchedRoll] = nowMs
            BiometricSoundboard.playMatchSuccess(current.matchedName)

            viewModelScope.launch(Dispatchers.IO) {
                val sha256 = AndroidSecurityUtils.computeSha256("${current.matchedRoll}_$nowMs")
                TurnstileRelayController.triggerDoorUnlock(
                    durationMs = 2000L,
                    studentRoll = current.matchedRoll,
                    studentName = current.matchedName,
                    confidencePct = current.lastConfidence,
                    sha256Proof = sha256
                )

                val record = AttendanceRecordEntity(
                    recordId = UUID.randomUUID().toString(),
                    studentRoll = current.matchedRoll,
                    studentName = current.matchedName,
                    timestamp = nowMs,
                    sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(nowMs)),
                    confidencePct = current.lastConfidence,
                    securityTier = current.activeTier.name,
                    sha256Hash = sha256,
                    isSynced = false
                )
                db.attendanceDao().insertRecord(record)
                _uiState.update {
                    it.copy(
                        scanState = ScannerScanState.ATTENDANCE_RECORDED,
                        matchTitle = "✓ ATTENDANCE RECORDED",
                        matchSubtitle = "${current.matchedName} • $timeStr",
                        matchedTimeFormatted = timeStr
                    )
                }
            }
        }
    }

    fun openManualOverrideDialog() {
        _uiState.update { it.copy(showManualOverrideDialog = true) }
    }

    fun dismissManualOverrideDialog() {
        _uiState.update { it.copy(showManualOverrideDialog = false) }
    }

    fun recordManualOverride(roll: String, name: String) {
        val nowMs = System.currentTimeMillis()
        val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(nowMs))
        lastVerifiedTimestamps[roll] = nowMs
        BiometricSoundboard.playMatchSuccess(name)

        viewModelScope.launch(Dispatchers.IO) {
            val sha256 = AndroidSecurityUtils.computeSha256("${roll}_${nowMs}_MANUAL_OVERRIDE")
            TurnstileRelayController.triggerDoorUnlock(
                durationMs = 2000L,
                studentRoll = roll,
                studentName = name,
                confidencePct = 100f,
                sha256Proof = sha256
            )

            val record = AttendanceRecordEntity(
                recordId = UUID.randomUUID().toString(),
                studentRoll = roll,
                studentName = name,
                timestamp = nowMs,
                sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(nowMs)),
                confidencePct = 100f,
                securityTier = "MANUAL_OVERRIDE",
                sha256Hash = sha256,
                isSynced = false
            )
            db.attendanceDao().insertRecord(record)
            _uiState.update {
                it.copy(
                    showManualOverrideDialog = false,
                    scanState = ScannerScanState.ATTENDANCE_RECORDED,
                    matchTitle = "✓ MANUAL OVERRIDE RECORDED",
                    matchSubtitle = "$name • $timeStr",
                    matchedTimeFormatted = timeStr
                )
            }
        }
    }

    fun handleEmptyFaces() {
        val isEmpty = _uiState.value.isDatabaseEmpty
        val count = _uiState.value.enrolledCount
        _uiState.update {
            if (it.scanState == ScannerScanState.ATTENDANCE_RECORDED) it else {
                it.copy(
                    detectedFaces = emptyList(),
                    visualGeometryData = emptyList(),
                    scanState = if (isEmpty) ScannerScanState.EMPTY_DATABASE else ScannerScanState.READY_TO_SCAN,
                    matchTitle = if (isEmpty) "DATABASE EMPTY" else "READY TO SCAN",
                    matchSubtitle = if (isEmpty) "0 students enrolled" else "$count students enrolled"
                )
            }
        }
    }

    fun processCameraFaces(
        faces: List<Face>,
        fullBitmap: Bitmap?,
        previewWidth: Float,
        previewHeight: Float
    ) {
        if (_uiState.value.isScanningPaused || fullBitmap == null) {
            fullBitmap?.recycle()
            return
        }

        if (isProcessingFrame || faces.isEmpty()) {
            fullBitmap.recycle()
            if (faces.isEmpty()) handleEmptyFaces()
            return
        }

        val nowMs = System.currentTimeMillis()
        if (nowMs - lastAnalysisTimestamp < 100L) {
            fullBitmap.recycle()
            return
        }
        lastAnalysisTimestamp = nowMs

        isProcessingFrame = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val templates = cachedTemplates
                val studentMap = cachedStudentMap

                if (templates.isEmpty()) {
                    _uiState.update {
                        it.copy(
                            detectedFaces = emptyList(),
                            scanState = ScannerScanState.EMPTY_DATABASE,
                            matchTitle = "DATABASE EMPTY",
                            matchSubtitle = "0 students enrolled"
                        )
                    }
                    return@launch
                }

                val pipeline = securityPipeline ?: return@launch
                val isFront = _uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT
                val output = pipeline.processFrame(
                    faces = faces,
                    fullBitmap = fullBitmap,
                    previewWidth = previewWidth,
                    previewHeight = previewHeight,
                    isFrontCamera = isFront,
                    studentMap = studentMap,
                    securityTier = _uiState.value.activeTier
                )

                val decision = output.topDecision
                val currentTimestamp = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(currentTimestamp))

                val scanState: ScannerScanState
                val topMatchTitle: String
                val topMatchSubtitle: String

                when (decision.gateState) {
                    com.omniface.ai.ml.pipeline.PipelineGateState.PASS -> {
                        if (output.isAttendanceTriggered) {
                            lastVerifiedTimestamps[decision.matchedStudentRoll] = currentTimestamp
                            BiometricSoundboard.playMatchSuccess(decision.matchedStudentName)

                            val sha256 = AndroidSecurityUtils.computeSha256("${decision.matchedStudentRoll}_$currentTimestamp")
                            TurnstileRelayController.triggerDoorUnlock(
                                durationMs = 2000L,
                                studentRoll = decision.matchedStudentRoll,
                                studentName = decision.matchedStudentName,
                                confidencePct = decision.matchConfidence,
                                sha256Proof = sha256
                            )

                            val record = AttendanceRecordEntity(
                                recordId = UUID.randomUUID().toString(),
                                studentRoll = decision.matchedStudentRoll,
                                studentName = decision.matchedStudentName,
                                timestamp = currentTimestamp,
                                sessionDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(currentTimestamp)),
                                confidencePct = decision.matchConfidence,
                                securityTier = _uiState.value.activeTier.name,
                                sha256Hash = sha256,
                                isSynced = false
                            )
                            db.attendanceDao().recordAttendanceIfNotExists(record)

                            scanState = ScannerScanState.ATTENDANCE_RECORDED
                            topMatchTitle = "✓ ATTENDANCE RECORDED"
                            topMatchSubtitle = "${decision.matchedStudentName} • $timeStr (Δ: ${"%.3f".format(decision.decisionMargin)})"
                        } else {
                            scanState = ScannerScanState.RECOGNIZED
                            topMatchTitle = decision.matchedStudentName.uppercase()
                            topMatchSubtitle = "${decision.matchConfidence.toInt()}% Match • Live 3D Face Verified"
                        }
                    }
                    com.omniface.ai.ml.pipeline.PipelineGateState.REJECT_SPOOF_ATTACK -> {
                        BiometricSoundboard.playSpoofAlert()
                        scanState = ScannerScanState.SPOOF_ALERT
                        topMatchTitle = decision.title
                        topMatchSubtitle = decision.subtitle
                    }
                    com.omniface.ai.ml.pipeline.PipelineGateState.REJECT_QUALITY -> {
                        scanState = ScannerScanState.POOR_QUALITY
                        topMatchTitle = decision.title
                        topMatchSubtitle = decision.subtitle
                    }
                    com.omniface.ai.ml.pipeline.PipelineGateState.REVIEW_AMBIGUOUS_MATCH -> {
                        scanState = ScannerScanState.REVIEW_REQUIRED
                        topMatchTitle = decision.title
                        topMatchSubtitle = decision.subtitle
                    }
                    com.omniface.ai.ml.pipeline.PipelineGateState.REJECT_UNKNOWN_IDENTITY -> {
                        scanState = ScannerScanState.UNKNOWN_IDENTITY
                        topMatchTitle = decision.title
                        topMatchSubtitle = decision.subtitle
                    }
                }

                val faceBoxes = output.visualGeometries.map { geo ->
                    FaceBoxUi(
                        rect = geo.bounds,
                        name = if (decision.isAttendanceAuthorized) decision.matchedStudentName else if (decision.gateState == com.omniface.ai.ml.pipeline.PipelineGateState.REJECT_SPOOF_ATTACK) "Spoof Rejected" else "Visitor",
                        roll = if (decision.isAttendanceAuthorized) decision.matchedStudentRoll else if (decision.gateState == com.omniface.ai.ml.pipeline.PipelineGateState.REJECT_SPOOF_ATTACK) decision.subtitle else "Unregistered",
                        isVerified = decision.isAttendanceAuthorized,
                        isGuest = !decision.isAttendanceAuthorized,
                        isSpoof = decision.gateState == com.omniface.ai.ml.pipeline.PipelineGateState.REJECT_SPOOF_ATTACK,
                        isReview = decision.gateState == com.omniface.ai.ml.pipeline.PipelineGateState.REVIEW_AMBIGUOUS_MATCH,
                        similarity = decision.matchSimilarity,
                        decisionMargin = decision.decisionMargin,
                        confidenceZone = geo.confidenceZone,
                        explanation = decision.technicalExplanation
                    )
                }

                _uiState.update {
                    it.copy(
                        detectedFaces = faceBoxes,
                        visualGeometryData = output.visualGeometries,
                        scanState = scanState,
                        matchedRoll = decision.matchedStudentRoll,
                        matchedName = decision.matchedStudentName,
                        matchedTimeFormatted = timeStr,
                        matchTitle = topMatchTitle,
                        matchSubtitle = topMatchSubtitle,
                        lastConfidence = decision.matchConfidence,
                        matchedMargin = decision.decisionMargin,
                        matchedZone = if (decision.isAttendanceAuthorized) ConfidenceZone.ACCEPT else ConfidenceZone.REJECT,
                        matchedExplanation = decision.technicalExplanation,
                        benchmarkLatencyMs = output.executionLatencyMs,
                        hardwareTierLabel = output.activeHardwareTier
                    )
                }
            } finally {
                fullBitmap.recycle()
                isProcessingFrame = false
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
        securityPipeline?.close()
        recognitionEngine?.close()
        qualcommIntelligenceEngine?.close()
    }
}

@OptIn(ExperimentalGetImage::class, ExperimentalCamera2Interop::class)
@Composable
fun ScannerScreen(
    viewModel: ScannerViewModel,
    onNavigateToEnroll: () -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) {
        viewModel.initEngine(context)
    }

    LaunchedEffect(state.scanState) {
        when (state.scanState) {
            ScannerScanState.RECOGNIZED,
            ScannerScanState.ATTENDANCE_RECORDED -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            ScannerScanState.SPOOF_ALERT,
            ScannerScanState.UNKNOWN_IDENTITY,
            ScannerScanState.POOR_QUALITY -> {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            else -> {}
        }
    }

    // Dynamic Frame Colors according to state
    val targetAccentColor = when (state.scanState) {
        ScannerScanState.EMPTY_DATABASE,
        ScannerScanState.READY_TO_SCAN -> Color(0xFF007AFF)      // Subtle Blue / Idle
        ScannerScanState.FACE_DETECTED,
        ScannerScanState.VERIFYING -> Color(0xFF0A84FF)          // Animated Blue
        ScannerScanState.RECOGNIZED,
        ScannerScanState.ATTENDANCE_RECORDED -> Color(0xFF34C759)// Green = Recognized / Recorded
        ScannerScanState.REVIEW_REQUIRED -> Color(0xFFFF9500)    // Amber = Review Required
        ScannerScanState.UNKNOWN_IDENTITY -> Color(0xFFFF9500)   // Orange = Unknown
        ScannerScanState.POOR_QUALITY,
        ScannerScanState.SPOOF_ALERT -> Color(0xFFFF3B30)        // Red = Failure / Spoof
    }

    val stateColor by animateColorAsState(
        targetValue = targetAccentColor,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "scannerStateColor"
    )

    // Smooth laser scanning animation
    val infiniteTransition = rememberInfiniteTransition(label = "scannerLaser")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laserProgress"
    )

    // Subtle Breathing Ring Animation
    val breathingPulse by infiniteTransition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.015f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingPulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Header (Title, Subtitle & Live NPU Telemetry Capsule)
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "SCANNER",
                            color = omniTextMuted(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Biometric Verification",
                            color = omniTextPrimary(isDark),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Live Telemetry Pill
                        Row(
                            modifier = Modifier
                                .shadow(4.dp, RoundedCornerShape(999.dp))
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isDark) Color(0x331E293B) else Color(0x0D000000))
                                .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(999.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isScanningPaused) Color(0xFFFF453A) else Color(0xFF34C759))
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (state.isScanningPaused) "PAUSED" else "${state.benchmarkLatencyMs}ms • ${state.hardwareTierLabel.take(3)}",
                                color = if (state.isScanningPaused) Color(0xFFFF453A) else omniEmerald(isDark),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Quick Settings Button
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .shadow(4.dp, CircleShape)
                                .clip(CircleShape)
                                .background(if (isDark) Color(0x331E293B) else Color(0x0D000000))
                                .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                                .clickable { onOpenSettings() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = "Scanner Settings",
                                tint = omniTextSecondary(isDark),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // 1.5 Cloud Model Background Download Banner
            if (state.modelDownloadState is ModelDownloadState.Downloading) {
                val download = state.modelDownloadState as ModelDownloadState.Downloading
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isDark) Color(0x330284C7) else Color(0x1A0284C7))
                            .border(0.75.dp, Color(0xFF0284C7).copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            CircularProgressIndicator(
                                progress = { download.progress },
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.5.dp,
                                color = Color(0xFF0284C7)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "⚡ Background Download: AntelopeV2 FP16",
                                    color = omniTextPrimary(isDark),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${(download.progress * 100).toInt()}% • %.1f/%.1f MB • ${download.speedKbps} KB/s".format(download.downloadedMb, download.totalMb),
                                    color = omniTextMuted(isDark),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            // 2. Focused Square Camera Face Window (Expanded 320dp Squircle)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .shadow(
                            elevation = if (isDark) 16.dp else 10.dp,
                            shape = RoundedCornerShape(32.dp),
                            ambientColor = if (isDark) Color(0x66000000) else Color(0x1A000000),
                            spotColor = if (isDark) Color(0x4D000000) else Color(0x14000000)
                        )
                        .clip(RoundedCornerShape(32.dp))
                        .background(Color.Black)
                        .border(2.dp, stateColor.copy(alpha = 0.85f), RoundedCornerShape(32.dp))
                ) {
                    // Camera View
                    key(state.lensFacing) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                val previewView = PreviewView(ctx).apply {
                                    scaleType = PreviewView.ScaleType.FILL_CENTER
                                }
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()

                                    val highResSelector = ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1920, 1080),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                        .build()

                                    val previewBuilder = Preview.Builder()
                                        .setResolutionSelector(highResSelector)

                                    val prevExt = Camera2Interop.Extender(previewBuilder)
                                    prevExt.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    prevExt.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                                    prevExt.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                                    prevExt.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                                    prevExt.setCaptureRequestOption(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                                    prevExt.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
                                    prevExt.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                                    prevExt.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)

                                    val preview = previewBuilder.build().also {
                                        it.setSurfaceProvider(previewView.surfaceProvider)
                                    }

                                    val faceDetector = FaceDetection.getClient(
                                        FaceDetectorOptions.Builder()
                                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                                            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                                            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                                            .setMinFaceSize(0.10f)
                                            .enableTracking()
                                            .build()
                                    )

                                    val analysisSelector = ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(640, 480),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                        .build()

                                    val analysisBuilder = ImageAnalysis.Builder()
                                        .setResolutionSelector(analysisSelector)
                                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                                    val ext = Camera2Interop.Extender(analysisBuilder)
                                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                                    ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)

                                    val imageAnalysis = analysisBuilder.build()
                                    imageAnalysis.setAnalyzer(viewModel.cameraExecutor) { imageProxy ->
                                        val mediaImage = imageProxy.image
                                        if (mediaImage != null && !state.isScanningPaused && !viewModel.isProcessingFrame) {
                                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

                                            faceDetector.process(image)
                                                .addOnSuccessListener { faces ->
                                                    if (faces.isNotEmpty() && !viewModel.isProcessingFrame) {
                                                        val fullBitmap = imageProxyToBitmap(imageProxy)
                                                        if (fullBitmap != null) {
                                                            viewModel.processCameraFaces(
                                                                faces = faces,
                                                                fullBitmap = fullBitmap,
                                                                previewWidth = previewView.width.toFloat().coerceAtLeast(1f),
                                                                previewHeight = previewView.height.toFloat().coerceAtLeast(1f)
                                                            )
                                                        }
                                                    } else if (faces.isEmpty()) {
                                                        viewModel.handleEmptyFaces()
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    viewModel.handleEmptyFaces()
                                                }
                                                .addOnCompleteListener {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }

                                    val selector = CameraSelector.Builder()
                                        .requireLensFacing(state.lensFacing)
                                        .build()

                                    try {
                                        cameraProvider.unbindAll()
                                        val cam = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                                        cam.cameraControl.setLinearZoom(0.0f)
                                    } catch (_: Exception) {}
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            }
                        )
                    }

                    // 60 FPS Real-Time 3D Face Mesh Wireframe & Explainable Diagnostics
                    FaceDiagnosticsOverlay(
                        visualData = state.visualGeometryData,
                        isDeveloperMode = state.isDeveloperOverlayEnabled,
                        showMeshWireframe = true,
                        showPoseAxes = true,
                        showGazeRays = true,
                        show3DMMTopography = true,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Cyber Reticle & Sweeping Laser Overlay inside Square Frame
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = breathingPulse
                                scaleY = breathingPulse
                            }
                    ) {
                        val w = size.width
                        val h = size.height

                        // Sweeping Laser Bar
                        if (!state.isScanningPaused) {
                            val laserY = 24f + ((h - 48f) * laserProgress)
                            drawLine(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color.Transparent,
                                        stateColor.copy(alpha = 0.3f),
                                        stateColor.copy(alpha = 0.95f),
                                        stateColor.copy(alpha = 0.3f),
                                        Color.Transparent
                                    )
                                ),
                                start = Offset(24f, laserY),
                                end = Offset(w - 24f, laserY),
                                strokeWidth = 2.5.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                        }
                    }

                    // Top Floating NPU Hardware Selector Pill
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 12.dp)
                            .shadow(8.dp, RoundedCornerShape(999.dp))
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isDark) Color(0xD90B0F19) else Color(0xF0FFFFFF))
                            .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(999.dp))
                            .clickable { viewModel.toggleHardwareSwitcher() }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (state.hardwareTierLabel.contains("NPU", ignoreCase = true) || state.hardwareTierLabel.contains("Hexagon", ignoreCase = true)) Color(0xFF00E5FF) else if (state.hardwareTierLabel.contains("GPU", ignoreCase = true)) Color(0xFF34C759) else Color(0xFFFF9500))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "⚡ ${state.hardwareTierLabel.uppercase()} • ${state.benchmarkLatencyMs}ms",
                            color = omniTextPrimary(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.4.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Switch Backend",
                            tint = omniTextMuted(isDark),
                            modifier = Modifier.size(14.dp)
                        )
                    }

                    // Stitch Dynamic Island Biometric Live Telemetry Capsule (Bottom Overlay)
                    val firstFace = state.visualGeometryData.firstOrNull()
                    val depthVar = firstFace?.faceMap3DMM?.depthVariance ?: (state.qualcommTelemetry?.depthVariance ?: 0.182f)
                    val gazeAttentive = firstFace?.gazeResult?.isGazeAttentive ?: (state.qualcommTelemetry?.gazeAttentive ?: true)
                    val livenessScore = if (firstFace?.isLive == true) 0.994f else 0.42f

                    BiometricLiveTelemetryCapsule(
                        depthVariance = depthVar,
                        isGazeAttentive = gazeAttentive,
                        livenessProbability = livenessScore,
                        isDark = isDark,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )
                }
            }

            // 3. Quick Action Controls Row
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // Multi-Face Mode Button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(3.dp, CircleShape)
                                .clip(CircleShape)
                                .background(if (state.isMultiFaceMode) omniCyan(isDark) else (if (isDark) Color(0x331E293B) else Color(0x0D000000)))
                                .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleMultiFaceMode()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.isMultiFaceMode) Icons.Default.Groups else Icons.Default.Person,
                                contentDescription = "Toggle Multi-Face Mode",
                                tint = if (state.isMultiFaceMode) Color.White else omniTextPrimary(isDark),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Flip Lens Button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(3.dp, CircleShape)
                            .clip(CircleShape)
                            .background(if (isDark) Color(0x331E293B) else Color(0x0D000000))
                            .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleLensFacing()
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = "Switch Camera Lens",
                                tint = omniTextPrimary(isDark),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Pause / Resume Button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(3.dp, CircleShape)
                            .clip(CircleShape)
                            .background(if (state.isScanningPaused) Color(0xFFFF3B30) else (if (isDark) Color(0x331E293B) else Color(0x0D000000)))
                            .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.togglePauseScan()
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.isScanningPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                contentDescription = if (state.isScanningPaused) "Resume Scanner" else "Pause Scanner",
                                tint = if (state.isScanningPaused) Color.White else omniTextPrimary(isDark),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Manual Override Button
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .shadow(3.dp, CircleShape)
                            .clip(CircleShape)
                            .background(if (isDark) Color(0x331E293B) else Color(0x0D000000))
                            .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.openManualOverrideDialog()
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.EditNote,
                                contentDescription = "Manual Override",
                                tint = omniCyan(isDark),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }

            // 4. Security Accuracy Tier Pill Selector
            item {
                CupertinoSegmentedControl(
                    items = listOf("Standard", "High", "Strict"),
                    selectedIndex = when (state.activeTier) {
                        SecurityTier.STANDARD -> 0
                        SecurityTier.HIGH -> 1
                        SecurityTier.STRICT -> 2
                    },
                    onItemSelected = { idx ->
                        val tier = when (idx) {
                            0 -> SecurityTier.STANDARD
                            1 -> SecurityTier.HIGH
                            else -> SecurityTier.STRICT
                        }
                        viewModel.setSecurityTier(tier)
                    }
                )
            }

            // 5. State-Driven Adaptive Glass Verification Card
            item {
                IOSCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(stateColor.copy(alpha = 0.18f))
                                    .border(1.dp, stateColor, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when (state.scanState) {
                                        ScannerScanState.RECOGNIZED,
                                        ScannerScanState.ATTENDANCE_RECORDED -> Icons.Default.CheckCircle
                                        ScannerScanState.UNKNOWN_IDENTITY -> Icons.Default.PersonOff
                                        ScannerScanState.SPOOF_ALERT -> Icons.Default.GppBad
                                        ScannerScanState.POOR_QUALITY -> Icons.Default.CenterFocusWeak
                                        ScannerScanState.EMPTY_DATABASE -> Icons.Default.PersonAdd
                                        else -> Icons.Default.Face
                                    },
                                    contentDescription = null,
                                    tint = stateColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column {
                                Text(
                                    text = state.matchTitle,
                                    color = omniTextPrimary(isDark),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = state.matchSubtitle,
                                    color = omniTextSecondary(isDark),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Contextual Action Button
                        when (state.scanState) {
                            ScannerScanState.EMPTY_DATABASE -> {
                                Button(
                                    onClick = onNavigateToEnroll,
                                    colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text("+ Enroll", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            ScannerScanState.RECOGNIZED -> {
                                Button(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.markManualAttendance()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF34C759)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                ) {
                                    Text("Mark Attendance", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }
                            }
                            ScannerScanState.ATTENDANCE_RECORDED -> {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0x3334C759))
                                        .padding(horizontal = 10.dp, vertical = 5.dp)
                                ) {
                                    Text(
                                        text = "Recorded",
                                        color = Color(0xFF34C759),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            ScannerScanState.UNKNOWN_IDENTITY -> {
                                Button(
                                    onClick = onNavigateToEnroll,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9500)),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Register", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                                }
                            }
                            else -> {
                                IconButton(
                                    onClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.retryScan()
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Retry Scan",
                                        tint = omniCyan(isDark),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Compact Database Status Row
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Database: ${state.enrolledCount} enrolled",
                            color = omniTextMuted(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )

                        Text(
                            text = if (state.enrolledCount == 0) "+ Enroll Student" else "Manage Database →",
                            color = omniCyan(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { onNavigateToEnroll() }
                        )
                    }
                }
            }

            // 5.5 Qualcomm AI Hub Neural Intelligence Telemetry HUD Card
            if (state.isQualcommDevice) {
                val qc = state.qualcommTelemetry
                item {
                    IOSCard(modifier = Modifier.fillMaxWidth()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(omniEmerald(isDark))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "SNAPDRAGON® FACE INTELLIGENCE",
                                        color = omniTextMuted(isDark),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.5.sp
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isDark) Color(0x330284C7) else Color(0x1A0284C7))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (qc?.isCavafaceActive == true) "CavaFace HD (65.5M)" else "Hexagon NPU Active",
                                        color = omniCyan(isDark),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                            // Telemetry Grid
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 3DMM Depth Variance
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0x1A1E293B) else Color(0x08000000))
                                        .padding(8.dp)
                                ) {
                                    Text("3DMM Depth", fontSize = 10.sp, color = omniTextMuted(isDark))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (qc?.is3DMMActive == true) "%.3f Var".format(qc.depthVariance) else "Standby",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = omniTextPrimary(isDark)
                                    )
                                }

                                // EyeGaze Attentive
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0x1A1E293B) else Color(0x08000000))
                                        .padding(8.dp)
                                ) {
                                    Text("Eye Gaze", fontSize = 10.sp, color = omniTextMuted(isDark))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (qc?.isEyeGazeActive == true) (if (qc.gazeAttentive) "✓ Attentive" else "Off-Axis") else "Standby",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (qc?.gazeAttentive == true) omniEmerald(isDark) else Color(0xFFFF9500)
                                    )
                                }

                                // FaceAttribNet Smile
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0x1A1E293B) else Color(0x08000000))
                                        .padding(8.dp)
                                ) {
                                    Text("Attrib Net", fontSize = 10.sp, color = omniTextMuted(isDark))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (qc?.isFaceAttribActive == true) "${(qc.smileScore * 100).toInt()}% Smile" else "Standby",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = omniTextPrimary(isDark)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.showManualOverrideDialog) {
        ManualOverrideDialog(
            isDark = isDark,
            onDismiss = { viewModel.dismissManualOverrideDialog() },
            onConfirm = { roll, name -> viewModel.recordManualOverride(roll, name) }
        )
    }

    if (state.showHardwareSwitcher) {
        HardwareSwitcherDialog(
            isDark = isDark,
            currentTier = state.hardwareTierLabel,
            onDismiss = { viewModel.toggleHardwareSwitcher() },
            onSelectTier = { viewModel.selectHardwareBackend(it) }
        )
    }
}

@Composable
private fun HardwareSwitcherDialog(
    isDark: Boolean,
    currentTier: String,
    onDismiss: () -> Unit,
    onSelectTier: (HardwareTier) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF00E5FF))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "NEURAL HARDWARE ACCELERATOR",
                    color = omniTextPrimary(isDark),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Select active silicon execution engine for unified multi-task biometrics:",
                    color = omniTextSecondary(isDark),
                    fontSize = 12.sp
                )

                val options = listOf(
                    Triple(
                        HardwareTier.NPU_NNAPI,
                        "⚡ Qualcomm Hexagon HTP NPU",
                        "Per-Channel INT8 Quantized • Sub-8ms • 45 TOPS peak"
                    ),
                    Triple(
                        HardwareTier.GPU_DELEGATE,
                        "🚀 Qualcomm Adreno GPU",
                        "FP16 Accelerated • OpenCL/Vulkan Hardware Delegate"
                    ),
                    Triple(
                        HardwareTier.CPU_XNNPACK,
                        "⚙️ ARM64 Multi-Core CPU",
                        "Multi-Threaded XNNPACK (4 Threads) • NEON DotProd"
                    )
                )

                options.forEach { (tier, title, desc) ->
                    val isSelected = currentTier.contains(tier.name.take(3), ignoreCase = true) ||
                        (tier == HardwareTier.NPU_NNAPI && (currentTier.contains("NPU", ignoreCase = true) || currentTier.contains("Hexagon", ignoreCase = true)))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isSelected) (if (isDark) Color(0x33007AFF) else Color(0x1A007AFF)) else (if (isDark) Color(0x1F1E293B) else Color(0x0A000000)))
                            .border(if (isSelected) 1.5.dp else 0.75.dp, if (isSelected) Color(0xFF007AFF) else (if (isDark) Color(0x38FFFFFF) else Color(0x1A000000)), RoundedCornerShape(14.dp))
                            .clickable { onSelectTier(tier) }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, color = omniTextPrimary(isDark), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(desc, color = omniTextMuted(isDark), fontSize = 10.sp)
                        }
                        if (isSelected) {
                            Icon(Icons.Default.CheckCircle, contentDescription = "Active", tint = Color(0xFF007AFF), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = omniCyan(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun ManualOverrideDialog(
    isDark: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var roll by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    // Hoist context here — LocalContext.current cannot be called inside onClick lambdas
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Manual Attendance Override", color = omniTextPrimary(isDark), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Admin authorization required to log manual verification.",
                    color = omniTextSecondary(isDark),
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = roll,
                    onValueChange = { roll = it },
                    label = { Text("Student Roll Number", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Student Full Name", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("Admin PIN", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                if (errorText != null) {
                    Text(errorText!!, color = Color(0xFFEF4444), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    // context is hoisted to composable scope above
                    val storedHash = AndroidSecurityUtils.getAdminPinHash(context)
                    val inputHash = AndroidSecurityUtils.computeSha256(pin)
                    if (inputHash != storedHash) {
                        errorText = "Invalid Admin PIN"
                    } else if (roll.isBlank() || name.isBlank()) {
                        errorText = "Please fill in Roll and Name"
                    } else {
                        onConfirm(roll.trim(), name.trim())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = omniCyan(isDark)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Confirm Override", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = omniTextMuted(isDark), fontSize = 12.sp)
            }
        },
        containerColor = if (isDark) Color(0xFF1E293B) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(18.dp)
    )
}

private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val bitmap = imageProxy.toBitmap()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        if (rotationDegrees != 0) {
            val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated != bitmap) {
                bitmap.recycle()
            }
            rotated
        } else {
            bitmap
        }
    } catch (_: Exception) {
        null
    }
}
