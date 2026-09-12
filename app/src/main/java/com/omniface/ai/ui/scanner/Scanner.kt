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
import com.omniface.ai.hardware.QrBarcode2FaScanner
import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.hardware.TwoFactorStatus
import com.omniface.ai.ml.BiometricCropUtils
import android.util.Range
import android.util.Size
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
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
import com.omniface.ai.data.local.ScannerMode
import com.omniface.ai.data.local.ScannerPreferences
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.hardware.TurnstileRelayController
import com.omniface.ai.ml.*
import com.omniface.ai.ml.tracking.FaceTracker
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.ui.components.DynamicIslandEvent
import com.omniface.ai.ui.components.LocalDynamicIslandController
import com.omniface.ai.ui.components.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ml.tracking.IdentityClassification
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
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
    DUPLICATE_ATTENDANCE,
    REVIEW_REQUIRED,
    UNKNOWN_IDENTITY,
    POOR_QUALITY,
    SPOOF_ALERT,
    SENSOR_TEST_MODE
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
    val activeTier: SecurityTier = SecurityTier.STANDARD,
    val isTwoFactorQrActive: Boolean = QrBarcode2FaScanner.isTwoFactorModeEnabled,
    val scanState: ScannerScanState = ScannerScanState.READY_TO_SCAN,
    val matchTitle: String = "READY TO SCAN",
    val matchSubtitle: String = "Tap 'START SCAN' to begin",
    val matchedRoll: String = "",
    val matchedName: String = "",
    val matchedRole: String = "STUDENT",
    val orgType: String = LocalizationManager.currentOrgType.value,
    val matchedTimeFormatted: String = "",
    val lastConfidence: Float = 0f,
    val matchedMargin: Float = 0f,
    val matchedZone: ConfidenceZone = ConfidenceZone.REJECT,
    val matchedExplanation: String = "",
    val isMultiFaceMode: Boolean = false,
    val isScanningPaused: Boolean = true,
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val isDatabaseEmpty: Boolean = false,
    val enrolledCount: Int = 0,
    val hardwareTierLabel: String = "Hexagon NPU",
    val benchmarkLatencyMs: Long = 6L,
    val showManualOverrideDialog: Boolean = false,
    val showHardwareSwitcher: Boolean = false,
    val isDeveloperOverlayEnabled: Boolean = true,
    val isQualcommDevice: Boolean = NpuHardwareDetector.isQualcommAiHubDevice(),
    val qualcommTelemetry: QualcommIntelligenceTelemetry? = null,
    val modelDownloadState: ModelDownloadState = ModelDownloadState.Idle(false, "OmniFace Deep AI Engine"),
    val activeModelDisplayName: String = "OmniFace Deep AI Engine",
    val thermalState: ThermalState = ThermalState.NOMINAL,
    val deviceTemperature: Float = 33.5f,
    val isAutoScalingEnabled: Boolean = true,
    val showThermalDialog: Boolean = false,
    val showModelManagerDialog: Boolean = false,
    val neuralModelConfig: com.omniface.ai.ml.NeuralModelConfig = com.omniface.ai.ml.NeuralModelConfigManager.configState.value,
    val engineLoadingProgress: com.omniface.ai.ml.EngineLoadingProgress = com.omniface.ai.ml.EngineLoadingProgress(),
    val isEngineLoaded: Boolean = false,
    val isEngineLoading: Boolean = false,
    val isModelAvailable: Boolean = false,
    val isCameraBound: Boolean = false,
    val scannerMode: ScannerMode = ScannerPreferences.getScannerMode(),
    val manualShutterRemainingSec: Int? = null,
    val isAutoScanOnOpen: Boolean = ScannerPreferences.isAutoScanOnOpen(),
    val autoPauseOnMatch: Boolean = ScannerPreferences.isAutoPauseOnMatch(),
    val selectedStudentForInfo: StudentEntity? = null,
    val studentTemplatesForInfo: List<FaceTemplateEntity> = emptyList(),
    val studentAttendanceCountForInfo: Int = 0,
    val studentRecentRecordsForInfo: List<AttendanceRecordEntity> = emptyList(),
    val isDatabaseRefreshedMessage: String? = null
)

class ScannerViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val attendanceService = OmniFaceApplication.instance.attendanceService
    private val verificationEngine = OmniFaceApplication.instance.verificationEngine
    private val downloadManager = ModelDownloadManager.getInstance(OmniFaceApplication.instance)
    private val unifiedEngine = com.omniface.ai.ml.UnifiedFaceIntelligenceEngine.getInstance(OmniFaceApplication.instance)
    private val securityPipeline: com.omniface.ai.ml.pipeline.FaceSecurityPipeline =
        com.omniface.ai.ml.pipeline.FaceSecurityPipeline.getInstance(OmniFaceApplication.instance)
    private val recognitionEngine: FaceRecognitionEngine = securityPipeline.recognitionEngine
    private val omniFaceIntelligenceEngine: OmniFaceIntelligenceEngine? = securityPipeline.omniFaceEngine
    private val qualcommIntelligenceEngine: OmniFaceIntelligenceEngine? get() = omniFaceIntelligenceEngine
    val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val faceTracker: FaceTracker = securityPipeline.tracker

    @Volatile
    private var cachedStudentMap: Map<String, String> = OmniFaceApplication.cachedStudentMap
    @Volatile
    private var cachedStudentRoleMap: Map<String, String> = emptyMap()
    @Volatile
    private var cachedTemplates: List<FaceTemplateEntity> = OmniFaceApplication.cachedTemplates

    private val initialModelName = downloadManager.getActiveModelDisplayName().let {
        if (it.isBlank() || it.contains("No Model", ignoreCase = true)) "OmniFace Deep AI Engine" else it
    }
    private val initialTierLabel = recognitionEngine.activeHardwareTier.getResolvedLabel(recognitionEngine.npuHardwareInfo)
    private var shutterJob: kotlinx.coroutines.Job? = null
    private val _uiState = MutableStateFlow(
        ScannerUiState(
            activeModelDisplayName = initialModelName,
            scannerMode = ScannerPreferences.getScannerMode(),
            isScanningPaused = (ScannerPreferences.getScannerMode() == ScannerMode.MANUAL_HANDHELD),
            isAutoScanOnOpen = (ScannerPreferences.getScannerMode() == ScannerMode.AUTO_KIOSK),
            autoPauseOnMatch = (ScannerPreferences.getScannerMode() == ScannerMode.MANUAL_HANDHELD),
            modelDownloadState = downloadManager.downloadState.value,
            thermalState = ThermalGovernor.thermalState.value,
            deviceTemperature = ThermalGovernor.currentTemperature.value,
            isAutoScalingEnabled = ThermalGovernor.isAutoScalingEnabled.value,
            neuralModelConfig = com.omniface.ai.ml.NeuralModelConfigManager.configState.value,
            isEngineLoaded = unifiedEngine.isModelLoaded || recognitionEngine.isEngineReady,
            isModelAvailable = downloadManager.isModelAvailable(),
            hardwareTierLabel = if (unifiedEngine.isModelLoaded) unifiedEngine.activeBackend else initialTierLabel,
            enrolledCount = cachedTemplates.map { it.studentRoll }.distinct().size,
            isDatabaseEmpty = cachedTemplates.isEmpty(),
            engineLoadingProgress = com.omniface.ai.ml.EngineLoadingProgress(
                isReady = unifiedEngine.isModelLoaded || recognitionEngine.isEngineReady,
                stage = if (unifiedEngine.isModelLoaded || recognitionEngine.isEngineReady) "Ready" else if (!downloadManager.isModelAvailable()) "Model Not Downloaded" else "Initializing Neural Engine...",
                progress = if (unifiedEngine.isModelLoaded || recognitionEngine.isEngineReady) 1.0f else 0.2f,
                activeModelName = initialModelName,
                hardwareTarget = initialTierLabel
            )
        )
    )
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()

    private val lastVerifiedTimestamps = ConcurrentHashMap<String, Long>()
    private val emaBoundingBoxes = ConcurrentHashMap<Int, androidx.compose.ui.geometry.Rect>()
    @Volatile
    var isProcessingFrame = false
    private var lastAnalysisTimestamp = 0L
    private var activeCameraControl: CameraControl? = null
    private var activeExposureState: ExposureState? = null
    private var lastExposureAdjustmentTime = 0L

    fun onScannerEntered() {
        val mode = ScannerPreferences.getScannerMode()
        shutterJob?.cancel()
        shutterJob = null
        _uiState.update {
            it.copy(
                scannerMode = mode,
                isScanningPaused = (mode == ScannerMode.MANUAL_HANDHELD),
                manualShutterRemainingSec = null,
                isAutoScanOnOpen = (mode == ScannerMode.AUTO_KIOSK),
                autoPauseOnMatch = (mode == ScannerMode.MANUAL_HANDHELD)
            )
        }
    }

    fun onScannerExited() {
        shutterJob?.cancel()
        shutterJob = null
        _uiState.update {
            it.copy(
                isScanningPaused = true,
                manualShutterRemainingSec = null,
                isCameraBound = false,
                detectedFaces = emptyList(),
                visualGeometryData = emptyList()
            )
        }
        faceTracker.purgeOldTracks()
    }

    fun setCameraBound(bound: Boolean) {
        _uiState.update { it.copy(isCameraBound = bound) }
    }

    fun bindCameraControl(cameraControl: CameraControl, exposureState: ExposureState?) {
        activeCameraControl = cameraControl
        activeExposureState = exposureState
        _uiState.update { it.copy(isCameraBound = true) }
    }

    private fun adjustExposureForLuminance(meanLuminance: Float) {
        val control = activeCameraControl ?: return
        val expState = activeExposureState ?: return
        if (!expState.isExposureCompensationSupported) return

        val now = System.currentTimeMillis()
        if (now - lastExposureAdjustmentTime < 1200L) return // Smooth 1.2s damping between exposure shifts

        val range = expState.exposureCompensationRange
        val current = expState.exposureCompensationIndex

        val targetIndex = when {
            meanLuminance < 65.0f -> (current + 2).coerceAtMost(range.upper) // Low light: step up exposure
            meanLuminance < 90.0f -> (current + 1).coerceAtMost(range.upper)
            meanLuminance > 195.0f -> (current - 2).coerceAtLeast(range.lower) // Overexposed glare: step down
            meanLuminance > 165.0f -> (current - 1).coerceAtLeast(range.lower)
            else -> 0 // Well-balanced lighting: neutral
        }

        if (targetIndex != current) {
            lastExposureAdjustmentTime = now
            try {
                control.setExposureCompensationIndex(targetIndex)
            } catch (_: Throwable) {}
        }
    }

    fun toggleHardwareSwitcher() {
        _uiState.update { it.copy(showHardwareSwitcher = !it.showHardwareSwitcher) }
    }

    fun toggleModelManagerDialog() {
        _uiState.update { it.copy(showModelManagerDialog = !it.showModelManagerDialog) }
    }

    fun dismissModelManagerDialog() {
        _uiState.update { it.copy(showModelManagerDialog = false) }
    }

    fun toggleThermalDialog() {
        _uiState.update { it.copy(showThermalDialog = !it.showThermalDialog) }
    }

    fun dismissThermalDialog() {
        _uiState.update { it.copy(showThermalDialog = false) }
    }

    fun setAutoScalingEnabled(enabled: Boolean) {
        ThermalGovernor.setAutoScalingEnabled(enabled)
        _uiState.update { it.copy(isAutoScalingEnabled = enabled) }
    }

    fun setThermalSimulationOverride(override: ThermalState?) {
        ThermalGovernor.setSimulationOverride(override)
    }

    fun toggleDeveloperOverlay() {
        _uiState.update { it.copy(isDeveloperOverlayEnabled = !it.isDeveloperOverlayEnabled) }
    }

    fun selectHardwareBackend(tier: HardwareTier) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update {
                it.copy(
                    showHardwareSwitcher = false,
                    engineLoadingProgress = com.omniface.ai.ml.EngineLoadingProgress(
                        isReady = false,
                        stage = "Attaching ${tier.label} Accelerator...",
                        progress = 0.35f,
                        activeModelName = it.activeModelDisplayName,
                        hardwareTarget = tier.label
                    )
                )
            }
            recognitionEngine.switchHardwareTier(tier)
            val latency = recognitionEngine.benchmarkInferenceLatency()
            val npuInfo = recognitionEngine.npuHardwareInfo
            val label = tier.getResolvedLabel(npuInfo)

            _uiState.update {
                it.copy(
                    hardwareTierLabel = label,
                    benchmarkLatencyMs = latency,
                    engineLoadingProgress = com.omniface.ai.ml.EngineLoadingProgress(
                        isReady = true,
                        stage = "Ready",
                        progress = 1.0f,
                        activeModelName = it.activeModelDisplayName,
                        hardwareTarget = label
                    )
                )
            }
        }
    }

    init {
        checkDatabaseStatus()
        observeModelDownloads()
        observeNeuralModelConfig()
        observeUnifiedEngine()
    }

    private fun observeUnifiedEngine() {
        viewModelScope.launch {
            unifiedEngine.isModelLoadedState.collect { loaded ->
                _uiState.update {
                    it.copy(
                        isEngineLoaded = loaded,
                        hardwareTierLabel = if (loaded) unifiedEngine.activeBackend else "Engine Standby",
                        engineLoadingProgress = com.omniface.ai.ml.EngineLoadingProgress(
                            isReady = loaded || !it.isModelAvailable,
                            stage = if (loaded) "Ready (${unifiedEngine.activeBackend})" else if (!it.isModelAvailable) "Sensor Test Mode" else "Standby",
                            progress = if (loaded || !it.isModelAvailable) 1.0f else 0.0f,
                            activeModelName = it.activeModelDisplayName,
                            hardwareTarget = if (loaded) unifiedEngine.activeBackend else "CPU"
                        )
                    )
                }
            }
        }
    }

    fun loadEngineExplicitly(context: Context) {
        if (_uiState.value.isEngineLoading) return
        if (!downloadManager.isModelAvailable()) {
            android.widget.Toast.makeText(context, "☁️ Downloading AI Face Pack (380 MB)...", android.widget.Toast.LENGTH_SHORT).show()
            downloadManager.startDownload {
                loadEngineExplicitly(context)
            }
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isEngineLoading = true) }
            val loaded = withContext(Dispatchers.Default) {
                unifiedEngine.loadUnifiedModelExplicit(context)
            }
            _uiState.update {
                it.copy(
                    isEngineLoading = false,
                    isEngineLoaded = loaded,
                    hardwareTierLabel = if (loaded) unifiedEngine.activeBackend else "Engine Standby"
                )
            }
            if (loaded) {
                initEngine(context)
            }
        }
    }

    private fun observeNeuralModelConfig() {
        viewModelScope.launch {
            com.omniface.ai.ml.NeuralModelConfigManager.configState.collect { config ->
                _uiState.update { it.copy(neuralModelConfig = config) }
            }
        }
    }

    private fun observeModelDownloads() {
        viewModelScope.launch {
            downloadManager.downloadState.collect { state ->
                val name = downloadManager.getActiveModelDisplayName()
                val available = downloadManager.isModelAvailable()
                _uiState.update {
                    it.copy(
                        modelDownloadState = state,
                        activeModelDisplayName = name,
                        isModelAvailable = available
                    )
                }
                if (state is ModelDownloadState.Ready) {
                    recognitionEngine.reloadEngine()
                }
            }
        }
    }

    private fun getEmptySubtitle(isModelAvailable: Boolean, isEmpty: Boolean, isPaused: Boolean = false): String {
        val orgType = LocalizationManager.currentOrgType.value
        val entityPlural = LocalizationManager.getEntityPlural(orgType).lowercase()
        val tabTitle = LocalizationManager.getDirectoryTabTitle(orgType)
        return when {
            !isModelAvailable -> "Download AI Face Pack to identify people"
            isEmpty -> "No $entityPlural enrolled • Enroll in $tabTitle tab"
            isPaused -> "Align face within frame • Tap 'START SCAN'"
            else -> "Position face within frame to scan"
        }
    }

    fun refreshEnrolledTemplates() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val students = db.studentDao().getAllStudents()
                val templates = db.studentDao().getAllTemplates()
                val map = students.associate { it.rollNumber to it.fullName }
                val roleMap = students.associate { it.rollNumber to it.role }
                cachedStudentMap = map
                cachedStudentRoleMap = roleMap
                cachedTemplates = templates
                OmniFaceApplication.cachedStudentMap = map
                OmniFaceApplication.cachedTemplates = templates
                securityPipeline.preloadTemplates(templates)
                val isEmpty = students.isEmpty()
                val count = students.size
                val orgType = LocalizationManager.currentOrgType.value
                val entityPlural = LocalizationManager.getEntityPlural(orgType)
                _uiState.update {
                    it.copy(
                        isDatabaseEmpty = isEmpty,
                        enrolledCount = count,
                        orgType = orgType,
                        isTwoFactorQrActive = QrBarcode2FaScanner.isTwoFactorModeEnabled,
                        isDatabaseRefreshedMessage = "Database Refreshed ($count $entityPlural)",
                        matchTitle = if (isEmpty) "DATABASE EMPTY" else (if (it.isScanningPaused) "READY TO SCAN" else it.matchTitle),
                        matchSubtitle = getEmptySubtitle(it.isModelAvailable, isEmpty, it.isScanningPaused)
                    )
                }
                kotlinx.coroutines.delay(2000)
                _uiState.update { it.copy(isDatabaseRefreshedMessage = null) }
            } catch (e: Exception) {
                android.util.Log.e("Scanner", "Failed to refresh templates", e)
            }
        }
    }

    private fun checkDatabaseStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            db.studentDao().getAllStudentsFlow().collect { students ->
                val templates = db.studentDao().getAllTemplates()
                val map = students.associate { it.rollNumber to it.fullName }
                val roleMap = students.associate { it.rollNumber to it.role }
                cachedStudentMap = map
                cachedStudentRoleMap = roleMap
                cachedTemplates = templates
                OmniFaceApplication.cachedStudentMap = map
                OmniFaceApplication.cachedTemplates = templates
                securityPipeline.preloadTemplates(templates)
                val isEmpty = students.isEmpty()
                val count = students.size
                val orgType = LocalizationManager.currentOrgType.value
                _uiState.update {
                    it.copy(
                        isDatabaseEmpty = isEmpty,
                        enrolledCount = count,
                        orgType = orgType,
                        scanState = if (isEmpty) ScannerScanState.EMPTY_DATABASE else (if (it.scanState == ScannerScanState.EMPTY_DATABASE) ScannerScanState.READY_TO_SCAN else it.scanState),
                        matchTitle = if (!it.isModelAvailable) "CAMERA PREVIEW" else if (isEmpty) "DATABASE EMPTY" else (if (it.isScanningPaused) "READY TO SCAN" else it.matchTitle),
                        matchSubtitle = getEmptySubtitle(it.isModelAvailable, isEmpty, it.isScanningPaused)
                    )
                }
            }
        }
    }

    fun initEngine(context: Context) {
        BiometricSoundboard.initTts(context)
        ThermalGovernor.startMonitoring(context, viewModelScope)
        viewModelScope.launch {
            ThermalGovernor.thermalState.collect { thermal ->
                _uiState.update { it.copy(thermalState = thermal) }
            }
        }
        viewModelScope.launch {
            ThermalGovernor.currentTemperature.collect { temp ->
                _uiState.update { it.copy(deviceTemperature = temp) }
            }
        }
        viewModelScope.launch {
            ThermalGovernor.isAutoScalingEnabled.collect { enabled ->
                _uiState.update { it.copy(isAutoScalingEnabled = enabled) }
            }
        }

        // Auto-load unified model in background if model is installed on disk
        if (!unifiedEngine.isModelLoaded && downloadManager.isModelAvailable()) {
            viewModelScope.launch(Dispatchers.Default) {
                val loaded = unifiedEngine.loadUnifiedModelExplicit(context)
                if (loaded) {
                    _uiState.update {
                        it.copy(
                            isEngineLoaded = true,
                            hardwareTierLabel = unifiedEngine.activeBackend
                        )
                    }
                }
            }
        }

        // Asynchronously benchmark inference latency in background for UI telemetry (never blocks recognition)
        viewModelScope.launch(Dispatchers.Default) {
            val latency = recognitionEngine.benchmarkInferenceLatency()
            val npuInfo = recognitionEngine.npuHardwareInfo
            val tier = recognitionEngine.activeHardwareTier.getResolvedLabel(npuInfo)
            _uiState.update {
                it.copy(
                    hardwareTierLabel = tier,
                    benchmarkLatencyMs = latency,
                    engineLoadingProgress = com.omniface.ai.ml.EngineLoadingProgress(
                        isReady = true,
                        stage = "Ready",
                        progress = 1.0f,
                        activeModelName = it.activeModelDisplayName,
                        hardwareTarget = tier
                    )
                )
            }
        }
    }

    fun setSecurityTier(tier: SecurityTier) {
        _uiState.update { it.copy(activeTier = tier) }
    }

    fun deleteStudent(rollNumber: String) {
        viewModelScope.launch(Dispatchers.IO) {
            db.studentDao().deleteTemplatesForStudent(rollNumber)
            db.studentDao().deleteStudentByRoll(rollNumber)
            refreshEnrolledTemplates()
            withContext(Dispatchers.Main) {
                closeStudentInfo()
            }
        }
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
        faceTracker.purgeOldTracks()
        _uiState.update {
            it.copy(
                lensFacing = newFacing,
                isCameraBound = false,
                detectedFaces = emptyList(),
                visualGeometryData = emptyList()
            )
        }
    }

    fun togglePauseScan() {
        val willPause = !_uiState.value.isScanningPaused
        if (willPause) {
            faceTracker.purgeOldTracks()
            _uiState.update {
                it.copy(
                    isScanningPaused = true,
                    detectedFaces = emptyList(),
                    visualGeometryData = emptyList(),
                    lastConfidence = 0f,
                    scanState = ScannerScanState.READY_TO_SCAN
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    isScanningPaused = false,
                    detectedFaces = emptyList(),
                    visualGeometryData = emptyList(),
                    scanState = if (it.isDatabaseEmpty) ScannerScanState.EMPTY_DATABASE else ScannerScanState.READY_TO_SCAN,
                    matchTitle = if (!it.isModelAvailable) "CAMERA PREVIEW" else if (it.isDatabaseEmpty) "DATABASE EMPTY" else "READY TO SCAN",
                    matchSubtitle = getEmptySubtitle(it.isModelAvailable, it.isDatabaseEmpty, false)
                )
            }
        }
    }

    fun toggleScannerMode() {
        val newMode = if (_uiState.value.scannerMode == ScannerMode.AUTO_KIOSK) {
            ScannerMode.MANUAL_HANDHELD
        } else {
            ScannerMode.AUTO_KIOSK
        }
        ScannerPreferences.setScannerMode(newMode)
        shutterJob?.cancel()
        shutterJob = null
        _uiState.update {
            it.copy(
                scannerMode = newMode,
                isScanningPaused = (newMode == ScannerMode.MANUAL_HANDHELD),
                manualShutterRemainingSec = null,
                isAutoScanOnOpen = (newMode == ScannerMode.AUTO_KIOSK),
                autoPauseOnMatch = (newMode == ScannerMode.MANUAL_HANDHELD),
                detectedFaces = emptyList(),
                visualGeometryData = emptyList(),
                scanState = if (it.isDatabaseEmpty) ScannerScanState.EMPTY_DATABASE else ScannerScanState.READY_TO_SCAN,
                matchTitle = if (newMode == ScannerMode.AUTO_KIOSK) "AUTO KIOSK SCANNING" else "MANUAL STANDBY",
                matchSubtitle = if (newMode == ScannerMode.AUTO_KIOSK) "Position face within frame" else "Tap 'START SCAN' or volume key"
            )
        }
    }

    fun triggerManualShutterOrTogglePause() {
        val current = _uiState.value
        if (current.scannerMode == ScannerMode.MANUAL_HANDHELD) {
            if (current.isScanningPaused) {
                startManualBurstShutter()
            } else {
                cancelManualBurstShutter()
            }
        } else {
            togglePauseScan()
        }
    }

    private fun startManualBurstShutter() {
        shutterJob?.cancel()
        _uiState.update {
            it.copy(
                isScanningPaused = false,
                manualShutterRemainingSec = 5,
                scanState = ScannerScanState.READY_TO_SCAN,
                matchTitle = "SEARCHING (5s)",
                matchSubtitle = "Align face within frame to scan"
            )
        }
        shutterJob = viewModelScope.launch {
            for (sec in 4 downTo 1) {
                kotlinx.coroutines.delay(1000)
                _uiState.update {
                    if (!it.isScanningPaused && it.scannerMode == ScannerMode.MANUAL_HANDHELD) {
                        it.copy(manualShutterRemainingSec = sec, matchTitle = "SEARCHING (${sec}s)")
                    } else it
                }
            }
            kotlinx.coroutines.delay(1000)
            if (!_uiState.value.isScanningPaused && _uiState.value.scannerMode == ScannerMode.MANUAL_HANDHELD) {
                _uiState.update {
                    it.copy(
                        isScanningPaused = true,
                        manualShutterRemainingSec = null,
                        detectedFaces = emptyList(),
                        visualGeometryData = emptyList(),
                        matchTitle = "STANDBY",
                        matchSubtitle = "Tap 'START SCAN' to trigger"
                    )
                }
            }
        }
    }

    private fun cancelManualBurstShutter() {
        shutterJob?.cancel()
        shutterJob = null
        _uiState.update {
            it.copy(
                isScanningPaused = true,
                manualShutterRemainingSec = null,
                detectedFaces = emptyList(),
                visualGeometryData = emptyList(),
                matchTitle = "STANDBY",
                matchSubtitle = "Tap 'START SCAN' to trigger"
            )
        }
    }

    fun toggleAutoScanOnOpen() {
        toggleScannerMode()
    }

    fun toggleAutoPauseOnMatch() {
        val next = !_uiState.value.autoPauseOnMatch
        ScannerPreferences.setAutoPauseOnMatch(next)
        _uiState.update { it.copy(autoPauseOnMatch = next) }
    }

    fun retryScan() {
        refreshEnrolledTemplates()
        _uiState.update {
            it.copy(
                lastConfidence = 0f,
                matchedRoll = "",
                matchedName = "",
                matchedTimeFormatted = "",
                scanState = if (it.isDatabaseEmpty) ScannerScanState.EMPTY_DATABASE else ScannerScanState.READY_TO_SCAN,
                matchTitle = if (!it.isModelAvailable) "CAMERA PREVIEW" else if (it.isDatabaseEmpty) "DATABASE EMPTY" else "READY TO SCAN",
                matchSubtitle = getEmptySubtitle(it.isModelAvailable, it.isDatabaseEmpty, it.isScanningPaused)
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
                val prevHash = db.attendanceDao().getLatestHash() ?: AndroidSecurityUtils.AEGIS_GENESIS_HASH
                val sha256 = AndroidSecurityUtils.computeAegisBlockHash(
                    previousHash = prevHash,
                    studentRoll = current.matchedRoll,
                    timestamp = nowMs,
                    confidencePct = current.lastConfidence
                )
                TurnstileRelayController.triggerDoorUnlock(
                    durationMs = 2000L,
                    studentRoll = current.matchedRoll,
                    studentName = current.matchedName,
                    confidencePct = current.lastConfidence,
                    sha256Proof = sha256
                )

                val domainVerified = com.omniface.ai.ml.verification.domain.VerificationDecision.Verified(
                    identityId = current.matchedRoll,
                    displayName = current.matchedName,
                    role = cachedStudentRoleMap[current.matchedRoll] ?: "STUDENT",
                    confidence = current.lastConfidence / 100f,
                    liveness = 1.0f,
                    leafHash = sha256
                )
                attendanceService.recordVerifiedAttendance(domainVerified, current.activeTier.name, nowMs)
                com.omniface.ai.attendance.AegisMintingWorker.enqueue(OmniFaceApplication.instance)
                _uiState.update {
                    val shouldPause = it.scannerMode == ScannerMode.MANUAL_HANDHELD || it.autoPauseOnMatch
                    if (shouldPause) {
                        shutterJob?.cancel()
                        shutterJob = null
                    }
                    it.copy(
                        scanState = ScannerScanState.ATTENDANCE_RECORDED,
                        matchTitle = "✓ ATTENDANCE RECORDED",
                        matchSubtitle = "${current.matchedName} • $timeStr",
                        matchedTimeFormatted = timeStr,
                        isScanningPaused = if (shouldPause) true else false,
                        manualShutterRemainingSec = null
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
            val prevHash = db.attendanceDao().getLatestHash() ?: AndroidSecurityUtils.AEGIS_GENESIS_HASH
            val sha256 = AndroidSecurityUtils.computeAegisBlockHash(
                previousHash = prevHash,
                studentRoll = roll,
                timestamp = nowMs,
                confidencePct = 100f
            )
            TurnstileRelayController.triggerDoorUnlock(
                durationMs = 2000L,
                studentRoll = roll,
                studentName = name,
                confidencePct = 100f,
                sha256Proof = sha256
            )

            val domainVerified = com.omniface.ai.ml.verification.domain.VerificationDecision.Verified(
                identityId = roll,
                displayName = name,
                role = cachedStudentRoleMap[roll] ?: "STUDENT",
                confidence = 1.0f,
                liveness = 1.0f,
                leafHash = sha256
            )
            attendanceService.recordVerifiedAttendance(domainVerified, "MANUAL_OVERRIDE", nowMs)
            com.omniface.ai.attendance.AegisMintingWorker.enqueue(OmniFaceApplication.instance)
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

    fun openStudentInfo(roll: String) {
        if (roll.isBlank() || roll.equals("GUEST", ignoreCase = true) || roll.equals("Unregistered", ignoreCase = true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val student = db.studentDao().getStudentByRoll(roll) ?: return@launch
            val templates = db.studentDao().getTemplatesForStudent(roll)
            val count = db.attendanceDao().getAttendanceCountForStudent(roll)
            val recent = db.attendanceDao().getRecordsForStudentFlow(roll).firstOrNull() ?: emptyList()
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        selectedStudentForInfo = student,
                        studentTemplatesForInfo = templates,
                        studentAttendanceCountForInfo = count,
                        studentRecentRecordsForInfo = recent
                    )
                }
            }
        }
    }

    fun closeStudentInfo() {
        _uiState.update {
            it.copy(
                selectedStudentForInfo = null,
                studentTemplatesForInfo = emptyList(),
                studentAttendanceCountForInfo = 0,
                studentRecentRecordsForInfo = emptyList()
            )
        }
    }

    private var lastAttendanceRecordTimeMs = 0L

    fun handleEmptyFaces() {
        val isEmpty = _uiState.value.isDatabaseEmpty
        val count = _uiState.value.enrolledCount
        val now = System.currentTimeMillis()
        _uiState.update {
            if (it.scanState == ScannerScanState.ATTENDANCE_RECORDED && now - lastAttendanceRecordTimeMs < 3500L) {
                it.copy(
                    detectedFaces = emptyList(),
                    visualGeometryData = emptyList()
                )
            } else {
                it.copy(
                    detectedFaces = emptyList(),
                    visualGeometryData = emptyList(),
                    scanState = if (isEmpty) ScannerScanState.EMPTY_DATABASE else ScannerScanState.READY_TO_SCAN,
                    matchTitle = if (!it.isModelAvailable) "CAMERA PREVIEW" else if (isEmpty) "DATABASE EMPTY" else "READY TO SCAN",
                    matchSubtitle = getEmptySubtitle(it.isModelAvailable, isEmpty, it.isScanningPaused),
                    matchedRoll = "",
                    matchedName = "",
                    lastConfidence = 0f,
                    matchedMargin = 0f,
                    matchedExplanation = "",
                    matchedZone = ConfidenceZone.REJECT
                )
            }
        }
    }

    /**
     * Fast-Path Visual Processing (60 FPS):
     * Runs synchronously on every camera frame in <2ms, projecting bounding boxes,
     * 1€ velocity-filtered landmarks, and dense facial contours to preview canvas coordinates.
     * Guarantees instantaneous, zero-lag mesh attachment without waiting for ArcFace/PAD inference.
     */
    fun processVisualFastPath(
        faces: List<Face>,
        frameWidth: Int,
        frameHeight: Int,
        previewWidth: Float,
        previewHeight: Float,
        isFrontCamera: Boolean
    ) {
        if (_uiState.value.isScanningPaused || !_uiState.value.isCameraBound || faces.isEmpty()) {
            if (faces.isEmpty()) handleEmptyFaces()
            return
        }

        val scale = maxOf(previewWidth / frameWidth.toFloat(), previewHeight / frameHeight.toFloat())
        val dx = (previewWidth - frameWidth * scale) / 2f
        val dy = (previewHeight - frameHeight * scale) / 2f

        val visualGeometries = mutableListOf<FaceGeometryVisualData>()
        val faceBoxes = mutableListOf<FaceBoxUi>()
        val tracker = faceTracker
        val claimedTrackIds = mutableSetOf<Int>()

        for (face in faces.take(6)) {
            val box = face.boundingBox
            val rawRect = if (isFrontCamera) {
                androidx.compose.ui.geometry.Rect(
                    left = (frameWidth - box.right) * scale + dx,
                    top = box.top * scale + dy,
                    right = (frameWidth - box.left) * scale + dx,
                    bottom = box.bottom * scale + dy
                )
            } else {
                androidx.compose.ui.geometry.Rect(
                    left = box.left * scale + dx,
                    top = box.top * scale + dy,
                    right = box.right * scale + dx,
                    bottom = box.bottom * scale + dy
                )
            }

            val trackId = face.trackingId ?: 0
            val trackState = tracker.getOrCreateTrackState(trackId, rawRect, claimedTrackIds)
            val smoothedRect = trackState.smoothedRect

            // Map 5 canonical fiducials to preview space
            val landmarksRaw = arrayOf(
                face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE)?.position,
                face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE)?.position,
                face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)?.position,
                face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT)?.position,
                face.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT)?.position
            )
            val mapped5Pts = landmarksRaw.mapNotNull { pt ->
                pt?.let {
                    val px = if (isFrontCamera) (frameWidth - it.x) * scale + dx else it.x * scale + dx
                    val py = it.y * scale + dy
                    android.graphics.PointF(px, py)
                }
            }.toTypedArray()

            val smoothed5Pts = tracker.filterLandmarks(trackState.persistentTrackId, mapped5Pts)

            // Map ML Kit dense facial contours to preview space
            val contoursMap = HashMap<Int, List<androidx.compose.ui.geometry.Offset>>()
            for (contour in face.allContours) {
                val pts = contour.points.map { p ->
                    val cx = if (isFrontCamera) (frameWidth - p.x) * scale + dx else p.x * scale + dx
                    val cy = p.y * scale + dy
                    androidx.compose.ui.geometry.Offset(cx, cy)
                }
                contoursMap[contour.faceContourType] = pts
            }

            val currentUi = _uiState.value
            val isSystemVerified = (currentUi.scanState == ScannerScanState.RECOGNIZED ||
                                    currentUi.scanState == ScannerScanState.ATTENDANCE_RECORDED ||
                                    currentUi.scanState == ScannerScanState.DUPLICATE_ATTENDANCE) && currentUi.matchedName.isNotBlank()

            val isTrackKnown = trackState.classification == IdentityClassification.KNOWN && trackState.studentName.isNotBlank()
            val isThisTrackMatched = isTrackKnown || (isSystemVerified && trackState.studentRoll.isNotBlank() && trackState.studentRoll == currentUi.matchedRoll)

            val isSpoof = trackState.classification == IdentityClassification.SPOOF_ATTACK
            val isReview = trackState.classification == IdentityClassification.AMBIGUOUS_REVIEW
            val isUnknownFace = trackState.classification == IdentityClassification.UNKNOWN

            val effectiveName = when {
                isSpoof -> ""
                isTrackKnown -> trackState.studentName
                isThisTrackMatched -> if (trackState.studentName.isNotBlank()) trackState.studentName else currentUi.matchedName
                isUnknownFace -> "Visitor / Unregistered"
                else -> ""
            }

            val effectiveRoll = when {
                isSpoof -> ""
                isTrackKnown -> trackState.studentRoll
                isThisTrackMatched -> if (trackState.studentRoll.isNotBlank()) trackState.studentRoll else currentUi.matchedRoll
                isUnknownFace -> "No Enrolled Match"
                else -> ""
            }

            val effectiveSimilarity = when {
                isSpoof -> 0f
                trackState.matchSimilarity > 0f -> trackState.matchSimilarity
                isThisTrackMatched -> currentUi.lastConfidence / 100f
                else -> 0f
            }

            val effectiveZone = when {
                isSpoof -> ConfidenceZone.REJECT
                isThisTrackMatched -> ConfidenceZone.ACCEPT
                isReview -> ConfidenceZone.REVIEW
                else -> ConfidenceZone.REJECT
            }

            val visualItem = FaceGeometryVisualData(
                bounds = smoothedRect,
                yaw = if (isFrontCamera) -face.headEulerAngleY else face.headEulerAngleY,
                pitch = face.headEulerAngleX,
                roll = face.headEulerAngleZ,
                landmarks5Pts = smoothed5Pts,
                contours = contoursMap,
                gazeResult = trackState.lastGazeResult,
                faceMap3DMM = trackState.lastMap3dResult,
                attributes = trackState.lastAttrResult,
                meshResult = trackState.lastMeshResult,
                qualityResult = trackState.lastQualityResult,
                confidenceZone = effectiveZone,
                decisionMargin = if (trackState.decisionMargin > 0f) trackState.decisionMargin else currentUi.matchedMargin,
                similarityScore = effectiveSimilarity,
                studentName = effectiveName,
                studentRoll = effectiveRoll,
                isLive = !isSpoof,
                isFrontCamera = isFrontCamera
            )
            visualGeometries.add(visualItem)

            val widthFraction = face.boundingBox.width().toFloat() / frameWidth.toFloat().coerceAtLeast(1f)
            val faceYaw = kotlin.math.abs(face.headEulerAngleY)
            val facePitch = kotlin.math.abs(face.headEulerAngleX)
            val poseHint = when {
                widthFraction < 0.16f -> "Move Closer"
                faceYaw > 22f || facePitch > 20f -> "Look Straight"
                else -> null
            }

            val defaultPendingName = if (!currentUi.isModelAvailable) {
                "Sensor Test Mode"
            } else if (isUnknownFace && trackState.frameCount > 3) {
                "Visitor / Unregistered"
            } else if (poseHint != null) {
                poseHint
            } else {
                "Scanning..."
            }
            val defaultPendingRoll = if (!currentUi.isModelAvailable) {
                "Camera & Face Tracking OK"
            } else if (isUnknownFace && trackState.frameCount > 3) {
                "No Enrolled Match"
            } else if (poseHint != null) {
                "Center Face"
            } else {
                "Aligning"
            }

            faceBoxes.add(
                FaceBoxUi(
                    rect = smoothedRect,
                    name = if (isThisTrackMatched) effectiveName else if (isSpoof) "Spoof Rejected" else defaultPendingName,
                    roll = if (isThisTrackMatched) effectiveRoll else if (isSpoof) "Attack Defeated" else defaultPendingRoll,
                    isVerified = isThisTrackMatched,
                    isGuest = !isThisTrackMatched,
                    isSpoof = isSpoof,
                    isReview = isReview,
                    similarity = effectiveSimilarity,
                    decisionMargin = visualItem.decisionMargin,
                    confidenceZone = if (!currentUi.isModelAvailable) ConfidenceZone.REVIEW else effectiveZone,
                    explanation = if (!currentUi.isModelAvailable) "Sensor Test Mode • Biometric Face Model Required for Attendance" else (trackState.lastDecision?.technicalExplanation ?: "")
                )
            )
        }

        _uiState.update { current ->
            current.copy(
                detectedFaces = faceBoxes,
                visualGeometryData = visualGeometries,
                scanState = if (!current.isModelAvailable) ScannerScanState.SENSOR_TEST_MODE else current.scanState,
                matchTitle = if (!current.isModelAvailable) "SENSOR TEST MODE • ATTENDANCE DISABLED" else current.matchTitle,
                matchSubtitle = if (!current.isModelAvailable) "Camera & Face Tracking OK • Biometric Model Required for Attendance" else current.matchSubtitle
            )
        }
    }

    fun processCameraFaces(
        faces: List<Face>,
        fullBitmap: Bitmap?,
        previewWidth: Float,
        previewHeight: Float,
        downscaleFactor: Float = 1.0f
    ) {
        val isLoaded = unifiedEngine.isModelLoaded || recognitionEngine.isEngineReady
        if (_uiState.value.isScanningPaused || !_uiState.value.isCameraBound || !isLoaded || fullBitmap == null) {
            if (!_uiState.value.isScanningPaused && _uiState.value.isCameraBound && !isLoaded && fullBitmap != null) {
                if (_uiState.value.isModelAvailable) {
                    _uiState.update { current ->
                        current.copy(
                            scanState = ScannerScanState.VERIFYING,
                            matchTitle = "INITIALIZING NEURAL ENGINE",
                            matchSubtitle = "Warming up biometric pipeline... Please wait a moment"
                        )
                    }
                } else if (QrBarcode2FaScanner.isTwoFactorModeEnabled) {
                    // In Sensor Test Mode (without deep model): test ML Kit QR barcode scanning
                    QrBarcode2FaScanner.scanCardQrFromBitmap(fullBitmap) { detectedRoll ->
                        if (!detectedRoll.isNullOrBlank()) {
                            _uiState.update { current ->
                                current.copy(
                                    scanState = ScannerScanState.SENSOR_TEST_MODE,
                                    matchTitle = "SENSOR TEST: QR SCANNER OK",
                                    matchSubtitle = "Card Read ($detectedRoll) • Biometric Model Required for Attendance"
                                )
                            }
                        }
                    }
                }
            }
            fullBitmap?.recycle()
            return
        }

        if (isProcessingFrame || faces.isEmpty()) {
            fullBitmap.recycle()
            if (faces.isEmpty()) handleEmptyFaces()
            return
        }

        lastAnalysisTimestamp = System.currentTimeMillis()
        isProcessingFrame = true
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val templates = cachedTemplates
                val studentMap = cachedStudentMap

                val pipeline = securityPipeline
                val isFront = _uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT
                val candidateFaces = if (_uiState.value.scannerMode == ScannerMode.MANUAL_HANDHELD) {
                    val filtered = faces.filter { face ->
                        val box = face.boundingBox
                        val centerX = box.centerX().toFloat()
                        val centerY = box.centerY().toFloat()
                        val normX = centerX / fullBitmap.width.toFloat().coerceAtLeast(1f)
                        val normY = centerY / fullBitmap.height.toFloat().coerceAtLeast(1f)
                        normX in 0.15f..0.85f && normY in 0.15f..0.85f
                    }
                    if (filtered.isNotEmpty()) filtered else faces
                } else {
                    faces
                }
                val output = pipeline.processFrame(
                    faces = candidateFaces,
                    fullBitmap = fullBitmap,
                    previewWidth = previewWidth,
                    previewHeight = previewHeight,
                    isFrontCamera = isFront,
                    studentMap = studentMap,
                    securityTier = _uiState.value.activeTier,
                    downscaleFactor = downscaleFactor
                )

                val decision = output.topDecision
                val currentTimestamp = System.currentTimeMillis()
                val timeStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(currentTimestamp))

                // Autonomous Auto-Exposure Adjustment for low-light / harsh-glare conditions
                output.visualGeometries.firstOrNull()?.qualityResult?.let { q ->
                    adjustExposureForLuminance(q.meanLuminance)
                }

                val scanState: ScannerScanState
                val topMatchTitle: String
                val topMatchSubtitle: String

                if (templates.isEmpty()) {
                    scanState = ScannerScanState.EMPTY_DATABASE
                    topMatchTitle = "FACE DETECTED"
                    topMatchSubtitle = "Database empty • Enroll in ${LocalizationManager.getDirectoryTabTitle(_uiState.value.orgType)} tab"
                } else {
                    when (decision.gateState) {
                        com.omniface.ai.ml.pipeline.PipelineGateState.PASS -> {
                            var twoFaBadgeSuffix = ""
                            if (QrBarcode2FaScanner.isTwoFactorModeEnabled) {
                                var scannedRollFromFrame: String? = null
                                QrBarcode2FaScanner.scanCardQrFromBitmap(fullBitmap) { detectedRoll ->
                                    scannedRollFromFrame = detectedRoll
                                }
                                val twoFa = QrBarcode2FaScanner.correlateBiometricAndCard(
                                    scannedCardRoll = scannedRollFromFrame,
                                    matchedFaceRoll = decision.matchedStudentRoll
                                )
                                if (twoFa.status == TwoFactorStatus.TWO_FA_MISMATCH_FRAUD) {
                                    BiometricSoundboard.playSpoofAlert()
                                    _uiState.update {
                                        it.copy(
                                            scanState = ScannerScanState.SPOOF_ALERT,
                                            matchTitle = "🚨 2FA FRAUD DETECTED",
                                            matchSubtitle = "ID Badge ($scannedRollFromFrame) does not match Face (${decision.matchedStudentName})"
                                        )
                                    }
                                    return@launch
                                } else if (twoFa.status == TwoFactorStatus.TWO_FA_PASS) {
                                    twoFaBadgeSuffix = " • 2FA Badge Verified"
                                }
                            }

                            if (output.isAttendanceTriggered) {
                                lastVerifiedTimestamps[decision.matchedStudentRoll] = currentTimestamp
                                BiometricSoundboard.playMatchSuccess(decision.matchedStudentName)

                                val prevHash = db.attendanceDao().getLatestHash() ?: AndroidSecurityUtils.AEGIS_GENESIS_HASH
                                val sha256 = AndroidSecurityUtils.computeAegisBlockHash(
                                    previousHash = prevHash,
                                    studentRoll = decision.matchedStudentRoll,
                                    timestamp = currentTimestamp,
                                    confidencePct = decision.matchConfidence
                                )
                                TurnstileRelayController.triggerDoorUnlock(
                                    durationMs = 2000L,
                                    studentRoll = decision.matchedStudentRoll,
                                    studentName = decision.matchedStudentName,
                                    confidencePct = decision.matchConfidence,
                                    sha256Proof = sha256
                                )

                                val domainVerified = com.omniface.ai.ml.verification.domain.VerificationDecision.Verified(
                                    identityId = decision.matchedStudentRoll,
                                    displayName = decision.matchedStudentName,
                                    role = cachedStudentRoleMap[decision.matchedStudentRoll] ?: "STUDENT",
                                    confidence = decision.matchConfidence / 100f,
                                    liveness = decision.livenessScore / 100f,
                                    leafHash = sha256
                                )
                                val isNewlyRecorded = attendanceService.recordVerifiedAttendance(domainVerified, _uiState.value.activeTier.name, currentTimestamp)
                                com.omniface.ai.attendance.AegisMintingWorker.enqueue(OmniFaceApplication.instance)
                                lastAttendanceRecordTimeMs = currentTimestamp

                                if (isNewlyRecorded) {
                                    scanState = ScannerScanState.ATTENDANCE_RECORDED
                                    topMatchTitle = "✓ ATTENDANCE RECORDED"
                                    topMatchSubtitle = "${decision.matchedStudentName} • $timeStr (${"%.1f".format(decision.matchConfidence)}% Match)$twoFaBadgeSuffix"
                                    val shouldPause = _uiState.value.scannerMode == ScannerMode.MANUAL_HANDHELD || _uiState.value.autoPauseOnMatch
                                    if (shouldPause) {
                                        shutterJob?.cancel()
                                        shutterJob = null
                                        _uiState.update { it.copy(isScanningPaused = true, manualShutterRemainingSec = null) }
                                    }
                                } else {
                                    scanState = ScannerScanState.DUPLICATE_ATTENDANCE
                                    topMatchTitle = "⚠️ ALREADY CHECKED IN"
                                    topMatchSubtitle = "${decision.matchedStudentName} (${decision.matchedStudentRoll}) • Attendance already logged today$twoFaBadgeSuffix"
                                }
                            } else {
                                scanState = ScannerScanState.RECOGNIZED
                                topMatchTitle = decision.matchedStudentName.uppercase()
                                topMatchSubtitle = "${"%.1f".format(decision.matchConfidence)}% Match • Live 3D Face Verified$twoFaBadgeSuffix"
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
                        com.omniface.ai.ml.pipeline.PipelineGateState.REVIEW_AMBIGUOUS_MATCH,
                        com.omniface.ai.ml.pipeline.PipelineGateState.REJECT_UNKNOWN_IDENTITY -> {
                            var scannedCardRoll: String? = null
                            if (QrBarcode2FaScanner.isTwoFactorModeEnabled) {
                                QrBarcode2FaScanner.scanCardQrFromBitmap(fullBitmap) { detectedRoll ->
                                    scannedCardRoll = detectedRoll
                                }
                            }

                            if (!scannedCardRoll.isNullOrBlank()) {
                                val cardClean = scannedCardRoll!!.trim().uppercase()
                                val faceRollClean = decision.matchedStudentRoll.trim().uppercase()
                                val studentNameFromMap = studentMap[cardClean]

                                if (studentNameFromMap != null && (cardClean == faceRollClean || faceRollClean.isBlank() || decision.matchConfidence >= 55.0f)) {
                                    val resolvedName = studentNameFromMap
                                    lastVerifiedTimestamps[cardClean] = currentTimestamp
                                    BiometricSoundboard.playMatchSuccess(resolvedName)

                                    val prevHash = db.attendanceDao().getLatestHash() ?: AndroidSecurityUtils.AEGIS_GENESIS_HASH
                                    val sha256 = AndroidSecurityUtils.computeAegisBlockHash(
                                        previousHash = prevHash,
                                        studentRoll = cardClean,
                                        timestamp = currentTimestamp,
                                        confidencePct = Math.max(decision.matchConfidence, 78.0f)
                                    )
                                    TurnstileRelayController.triggerDoorUnlock(
                                        durationMs = 2000L,
                                        studentRoll = cardClean,
                                        studentName = resolvedName,
                                        confidencePct = Math.max(decision.matchConfidence, 78.0f),
                                        sha256Proof = sha256
                                    )

                                    val domainVerified = com.omniface.ai.ml.verification.domain.VerificationDecision.Verified(
                                        identityId = cardClean,
                                        displayName = resolvedName,
                                        role = cachedStudentRoleMap[cardClean] ?: "STUDENT",
                                        confidence = Math.max(decision.matchConfidence, 78.0f) / 100f,
                                        liveness = 1.0f,
                                        leafHash = sha256
                                    )
                                    val isNewlyRecorded = attendanceService.recordVerifiedAttendance(domainVerified, "2FA_CORRELATED", currentTimestamp)
                                    com.omniface.ai.attendance.AegisMintingWorker.enqueue(OmniFaceApplication.instance)
                                    lastAttendanceRecordTimeMs = currentTimestamp

                                    if (isNewlyRecorded) {
                                        scanState = ScannerScanState.ATTENDANCE_RECORDED
                                        topMatchTitle = "✓ 2FA ATTENDANCE RECORDED"
                                        topMatchSubtitle = "$resolvedName ($cardClean) • ID Card Correlated with Face"
                                        val shouldPause = _uiState.value.scannerMode == ScannerMode.MANUAL_HANDHELD || _uiState.value.autoPauseOnMatch
                                        if (shouldPause) {
                                            shutterJob?.cancel()
                                            shutterJob = null
                                            _uiState.update { it.copy(isScanningPaused = true, manualShutterRemainingSec = null) }
                                        }
                                    } else {
                                        scanState = ScannerScanState.DUPLICATE_ATTENDANCE
                                        topMatchTitle = "⚠️ ALREADY CHECKED IN"
                                        topMatchSubtitle = "$resolvedName ($cardClean) • Attendance already logged today"
                                    }
                                } else {
                                    BiometricSoundboard.playSpoofAlert()
                                    scanState = ScannerScanState.SPOOF_ALERT
                                    topMatchTitle = "🚨 2FA FRAUD DETECTED"
                                    topMatchSubtitle = "ID Badge ($cardClean) does not match Face"
                                }
                            } else {
                                if (decision.gateState == com.omniface.ai.ml.pipeline.PipelineGateState.REJECT_UNKNOWN_IDENTITY) {
                                    scanState = ScannerScanState.UNKNOWN_IDENTITY
                                    topMatchTitle = decision.title
                                    topMatchSubtitle = if (QrBarcode2FaScanner.isTwoFactorModeEnabled) {
                                        "Hold Up ID Card QR or Enroll Student"
                                    } else {
                                        decision.subtitle
                                    }
                                } else {
                                    scanState = ScannerScanState.REVIEW_REQUIRED
                                    topMatchTitle = decision.title
                                    topMatchSubtitle = if (QrBarcode2FaScanner.isTwoFactorModeEnabled) {
                                        "Hold Up ID Card QR to Confirm Identity"
                                    } else {
                                        decision.subtitle
                                    }
                                }
                            }
                        }
                    }
                }

                val activeOrgType = LocalizationManager.currentOrgType.value
                val matchedRoleVal = cachedStudentRoleMap[decision.matchedStudentRoll] ?: "STUDENT"
                _uiState.update { current ->
                    current.copy(
                        scanState = scanState,
                        matchedRoll = decision.matchedStudentRoll,
                        matchedName = decision.matchedStudentName,
                        matchedRole = matchedRoleVal,
                        orgType = activeOrgType,
                        matchedTimeFormatted = timeStr,
                        matchTitle = topMatchTitle,
                        matchSubtitle = topMatchSubtitle,
                        lastConfidence = decision.matchConfidence,
                        matchedMargin = decision.decisionMargin,
                        matchedZone = if (decision.isAttendanceAuthorized) ConfidenceZone.ACCEPT else ConfidenceZone.REJECT,
                        matchedExplanation = decision.technicalExplanation,
                        benchmarkLatencyMs = output.executionLatencyMs,
                        hardwareTierLabel = output.activeHardwareTier,
                        visualGeometryData = if (output.visualGeometries.isNotEmpty()) output.visualGeometries else current.visualGeometryData,
                        qualcommTelemetry = output.visualGeometries.firstOrNull()?.let { geo ->
                            val g = geo.gazeResult
                            val a = geo.attributes
                            QualcommIntelligenceTelemetry(
                                isEyeGazeActive = g != null,
                                gazeAttentive = g?.isGazeAttentive ?: true,
                                gazePitch = g?.pitch ?: 0f,
                                gazeYaw = g?.yaw ?: 0f,
                                depthVariance = geo.faceMap3DMM?.depthVariance ?: 0.182f,
                                smileScore = a?.smileScore ?: 0f,
                                eyeglassesScore = a?.rawProbabilities?.getOrNull(1) ?: 0f
                            )
                        }
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
        // FaceSecurityPipeline, FaceRecognitionEngine, and QualcommEngine
        // are persistent singletons managed at Application scope to eliminate
        // warm-up / cold-start lag when re-entering the Scanner tab.
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
    val dynamicIslandController = LocalDynamicIslandController.current

    // Intercept back gesture on active dialogs/modals before leaving the scanner
    BackHandler(enabled = state.selectedStudentForInfo != null) {
        viewModel.closeStudentInfo()
    }
    BackHandler(enabled = state.showManualOverrideDialog) {
        viewModel.dismissManualOverrideDialog()
    }
    BackHandler(enabled = state.showThermalDialog) {
        viewModel.dismissThermalDialog()
    }
    BackHandler(enabled = state.showModelManagerDialog) {
        viewModel.dismissModelManagerDialog()
    }

    DisposableEffect(lifecycleOwner) {
        viewModel.onScannerEntered()
        onDispose {
            viewModel.onScannerExited()
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (_: Throwable) {}
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initEngine(context)
        viewModel.refreshEnrolledTemplates()
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        viewModel.refreshEnrolledTemplates()
        viewModel.onScannerEntered()
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.scanState) {
        when (state.scanState) {
            ScannerScanState.ATTENDANCE_RECORDED -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val lang = LocalizationManager.currentLanguage.value
                val personName = if (state.matchedName.isNotBlank()) state.matchedName else LocalizationManager.getEntitySingular(state.orgType)
                val roleSuffix = if (state.matchedRole.isNotBlank()) " [${LocalizationManager.getRoleBadgeLabel(state.matchedRole).uppercase()}]" else ""
                dynamicIslandController.postEvent(
                    DynamicIslandEvent(
                        title = "Attendance Recorded$roleSuffix",
                        subtitle = "$personName • ${state.matchedRoll}",
                        accentColor = Color(0xFF34C759)
                    )
                )
                snackbarHostState.showSnackbar(
                    message = "✓ ${LocalizationManager.getString(StringKey.RECOGNITION_CONFIRMED, lang)}: $personName$roleSuffix",
                    duration = SnackbarDuration.Short
                )
            }
            ScannerScanState.DUPLICATE_ATTENDANCE -> {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val lang = LocalizationManager.currentLanguage.value
                val personName = if (state.matchedName.isNotBlank()) state.matchedName else LocalizationManager.getEntitySingular(state.orgType)
                val roleSuffix = if (state.matchedRole.isNotBlank()) " [${LocalizationManager.getRoleBadgeLabel(state.matchedRole).uppercase()}]" else ""
                dynamicIslandController.postEvent(
                    DynamicIslandEvent(
                        title = "Already Checked In$roleSuffix",
                        subtitle = "$personName (${state.matchedRoll})",
                        accentColor = Color(0xFFF59E0B)
                    )
                )
                snackbarHostState.showSnackbar(
                    message = "⚠️ Already Checked In: $personName$roleSuffix",
                    duration = SnackbarDuration.Short
                )
            }
            ScannerScanState.SPOOF_ALERT -> {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                dynamicIslandController.postEvent(
                    DynamicIslandEvent(
                        title = "Spoof Attack Defeated",
                        subtitle = state.matchSubtitle.ifBlank { "Presentation Attack Rejected" },
                        accentColor = Color(0xFFFF3B30)
                    )
                )
            }
            ScannerScanState.UNKNOWN_IDENTITY -> {
                dynamicIslandController.postEvent(
                    DynamicIslandEvent(
                        title = "Unregistered Visitor",
                        subtitle = "Identity not found in ledger",
                        accentColor = Color(0xFFFF9500)
                    )
                )
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
        ScannerScanState.DUPLICATE_ATTENDANCE -> Color(0xFFF59E0B)// Amber = Duplicate Checked In
        ScannerScanState.REVIEW_REQUIRED -> Color(0xFFFF9500)    // Amber = Review Required
        ScannerScanState.UNKNOWN_IDENTITY -> Color(0xFFFF9500)   // Orange = Unknown
        ScannerScanState.POOR_QUALITY,
        ScannerScanState.SPOOF_ALERT -> Color(0xFFFF3B30)        // Red = Failure / Spoof
        ScannerScanState.SENSOR_TEST_MODE -> Color(0xFF38BDF8)   // Cyan = Hardware & Sensor Test Mode
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

    var showNeuralDiagnostics by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            contentPadding = PaddingValues(top = 20.dp, bottom = 156.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Apple Minimalist Top Header (Camera-First Layout)
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isScanningPaused) Color(0xFFFF9500) else OmniEmerald)
                            )
                            Text(
                                text = if (state.scannerMode == ScannerMode.AUTO_KIOSK) "AUTO KIOSK SCANNER" else "MANUAL TAP SCANNER",
                                color = OmniViolet,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.8.sp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Smart Scanner",
                            color = omniTextPrimary(isDark),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = (-0.5).sp,
                            maxLines = 1
                        )
                    }

                    // Top Bar Action Controls: Flip Camera, Multi-Face, Tune Settings
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Flip Camera (Front / Rear)
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(if (isDark) 4.dp else 2.dp, CircleShape, spotColor = OmniViolet.copy(alpha = 0.2f))
                                .clip(CircleShape)
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                                .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleLensFacing()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FlipCameraAndroid,
                                contentDescription = "Flip Camera",
                                tint = if (state.lensFacing == CameraSelector.LENS_FACING_FRONT) OmniSky else omniTextPrimary(isDark),
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Multi / Single Face Mode Toggle
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(if (isDark) 4.dp else 2.dp, CircleShape, spotColor = OmniViolet.copy(alpha = 0.2f))
                                .clip(CircleShape)
                                .background(if (state.isMultiFaceMode) OmniViolet.copy(alpha = 0.25f) else (if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9)))
                                .border(0.75.dp, if (state.isMultiFaceMode) SolidColor(OmniViolet) else omniLiquidSpecularBorder(isDark), CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleMultiFaceMode()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.isMultiFaceMode) Icons.Default.Groups else Icons.Default.Person,
                                contentDescription = "Face Mode",
                                tint = if (state.isMultiFaceMode) OmniSky else omniTextPrimary(isDark),
                                modifier = Modifier.size(19.dp)
                            )
                        }

                        // Tune / Neural Settings Button
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(if (isDark) 4.dp else 2.dp, CircleShape, spotColor = OmniViolet.copy(alpha = 0.2f))
                                .clip(CircleShape)
                                .background(if (isDark) Color(0xFF1E293B) else Color(0xFFF1F5F9))
                                .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                                .clickable {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    viewModel.toggleModelManagerDialog()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Tune Models",
                                tint = omniTextPrimary(isDark),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            }


            // Active Thermal Resolution Scaling Banner (when throttled)
            if (state.thermalState != ThermalState.NOMINAL) {
                item {
                    val isCrit = state.thermalState == ThermalState.CRITICAL
                    val bannerBg = if (isCrit) (if (isDark) Color(0x33EF4444) else Color(0x1AEF4444)) else (if (isDark) Color(0x33F59E0B) else Color(0x1AF59E0B))
                    val bannerBorder = if (isCrit) Color(0xFFEF4444) else Color(0xFFF59E0B)
                    val bannerIcon = if (isCrit) "🔥" else "⚡"
                    val bannerTitle = if (isCrit) "CRITICAL THERMAL THROTTLE (320p ECO)" else "WARM HARDWARE THROTTLE (480p)"
                    val bannerDesc = if (isCrit) {
                        "Device temperature (%.1f°C) exceeded safety ceiling. Face detector scaled to 320×240 (10 FPS) to prevent overheating.".format(state.deviceTemperature)
                    } else {
                        "Thermal threshold reached (%.1f°C). Face detector scaled to 480×360 (20 FPS) with 45%% lower CPU load.".format(state.deviceTemperature)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(bannerBg)
                            .border(0.75.dp, bannerBorder.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                            .clickable { viewModel.toggleThermalDialog() }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = bannerIcon, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bannerTitle,
                                color = bannerBorder,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = bannerDesc,
                                color = bannerBorder.copy(alpha = 0.9f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // Cloud Model Background Download Banner
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
                                    text = "⚡ Background Download: OmniFace Custom Neural Model",
                                    color = omniTextPrimary(isDark),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${(download.progress * 100).toInt()}% • ${String.format(java.util.Locale.US, "%.1f", download.downloadedMb)}/${String.format(java.util.Locale.US, "%.1f", download.totalMb)} MB • ${download.speedKbps} KB/s",
                                    color = omniTextMuted(isDark),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }

            // 2. Focused Square Camera Face Window (Apple Concentric Doppelrand Lens Bezel)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .shadow(
                            elevation = if (isDark) 20.dp else 12.dp,
                            shape = RoundedCornerShape(32.dp),
                            ambientColor = if (isDark) Color(0x80000000) else Color(0x22000000),
                            spotColor = stateColor.copy(alpha = if (isDark) 0.35f else 0.20f)
                        )
                        .clip(RoundedCornerShape(32.dp))
                        .background(if (isDark) Color(0xFF070A14) else Color(0xFF1E293B))
                        .border(
                            width = 1.25.dp,
                            brush = Brush.verticalGradient(
                                listOf(stateColor.copy(alpha = 0.9f), stateColor.copy(alpha = 0.35f))
                            ),
                            shape = RoundedCornerShape(32.dp)
                        )
                        .padding(3.dp)
                        .clip(RoundedCornerShape(29.dp))
                        .background(Color.Black)
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
                                            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                                            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                                            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                                            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                                            .setMinFaceSize(0.08f)
                                            .enableTracking()
                                            .build()
                                    )

                                    val analysisSelector = ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
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
                                        val currentUi = viewModel.uiState.value
                                        if (mediaImage != null && !currentUi.isScanningPaused && currentUi.isCameraBound) {
                                            if (previewView.width <= 0 || previewView.height <= 0) {
                                                imageProxy.close()
                                                return@setAnalyzer
                                            }
                                            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                            val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

                                            faceDetector.process(image)
                                                .addOnSuccessListener(viewModel.cameraExecutor) { faces ->
                                                    if (faces.isNotEmpty()) {
                                                        val latestUi = viewModel.uiState.value
                                                        val isFront = latestUi.lensFacing == CameraSelector.LENS_FACING_FRONT
                                                        val imgW = if (rotationDegrees == 90 || rotationDegrees == 270) mediaImage.height else mediaImage.width
                                                        val imgH = if (rotationDegrees == 90 || rotationDegrees == 270) mediaImage.width else mediaImage.height

                                                        // FAST-PATH 1: Instant visual geometry & mesh projection at 60 FPS (0ms lag)
                                                        viewModel.processVisualFastPath(
                                                            faces = faces,
                                                            frameWidth = imgW,
                                                            frameHeight = imgH,
                                                            previewWidth = previewView.width.toFloat().coerceAtLeast(1f),
                                                            previewHeight = previewView.height.toFloat().coerceAtLeast(1f),
                                                            isFrontCamera = isFront
                                                        )

                                                        // ASYNC PATH 2: Background Biometric Verification without stalling camera
                                                        if (!viewModel.isProcessingFrame) {
                                                            val rawBitmap = BiometricCropUtils.imageProxyToBitmap(imageProxy)
                                                            if (rawBitmap != null) {
                                                                val activeThermal = ThermalGovernor.thermalState.value
                                                                val (scaledBitmap, downscaleFactor) = if (latestUi.isAutoScalingEnabled) {
                                                                    ThermalGovernor.scaleBitmapForThermal(rawBitmap, activeThermal)
                                                                } else {
                                                                    Pair(rawBitmap, 1.0f)
                                                                }
                                                                if (scaledBitmap != rawBitmap && !rawBitmap.isRecycled) {
                                                                    rawBitmap.recycle()
                                                                }
                                                                viewModel.processCameraFaces(
                                                                    faces = faces,
                                                                    fullBitmap = scaledBitmap,
                                                                    previewWidth = previewView.width.toFloat().coerceAtLeast(1f),
                                                                    previewHeight = previewView.height.toFloat().coerceAtLeast(1f),
                                                                    downscaleFactor = downscaleFactor
                                                                )
                                                            }
                                                        }
                                                    } else {
                                                        viewModel.handleEmptyFaces()
                                                    }
                                                }
                                                .addOnFailureListener(viewModel.cameraExecutor) {
                                                    viewModel.handleEmptyFaces()
                                                }
                                                .addOnCompleteListener(viewModel.cameraExecutor) {
                                                    imageProxy.close()
                                                }
                                        } else {
                                            imageProxy.close()
                                        }
                                    }

                                    val currentFacing = viewModel.uiState.value.lensFacing
                                    val selector = CameraSelector.Builder()
                                        .requireLensFacing(currentFacing)
                                        .build()

                                    try {
                                        cameraProvider.unbindAll()
                                        val cam = cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                                        cam.cameraControl.setLinearZoom(0.0f)
                                        viewModel.bindCameraControl(cam.cameraControl, cam.cameraInfo.exposureState)
                                    } catch (_: Exception) {}
                                }, ContextCompat.getMainExecutor(ctx))

                                previewView
                            },
                            onRelease = { previewView ->
                                try {
                                    val cameraProvider = ProcessCameraProvider.getInstance(previewView.context).get()
                                    cameraProvider.unbindAll()
                                    viewModel.setCameraBound(false)
                                } catch (_: Throwable) {}
                            }
                        )
                    }

                    // Clean, Real-Time Biometric Reticle & Identity Overlay
                    FaceDiagnosticsOverlay(
                        visualData = state.visualGeometryData,
                        isDeveloperMode = state.isDeveloperOverlayEnabled && state.neuralModelConfig.isCyberneticHudOverlayEnabled,
                        showMeshWireframe = state.neuralModelConfig.isMediaPipeMeshEnabled || state.neuralModelConfig.isMeshOverlayEnabled,
                        showPoseAxes = state.neuralModelConfig.isPoseAxesOverlayEnabled,
                        showGazeRays = state.neuralModelConfig.isGazeRaysOverlayEnabled,
                        show3DMMTopography = state.neuralModelConfig.is3DMMOverlayEnabled,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Stitch Liquid Obsidian Viewfinder Top Telemetry Markers
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Left: FOV & 3D Depth
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x990B0F19))
                                .border(0.5.dp, Color(0x4006B6D4), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF06B6D4))
                            )
                            Text(
                                text = "FOV: 84° • 3D DEPTH",
                                color = Color(0xFFE2E8F0),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        // Right: Live Silicon NPU
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0x990B0F19))
                                .border(0.5.dp, Color(0x4010B981), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF10B981))
                            )
                            Text(
                                text = "NPU: 45 TOPS",
                                color = Color(0xFF10B981),
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    // Stitch Dynamic Island Biometric Live Telemetry Capsule (Bottom Overlay)
                    val firstFace = state.visualGeometryData.firstOrNull()
                    val hasFace = firstFace != null
                    val depthVar = firstFace?.faceMap3DMM?.depthVariance ?: (state.qualcommTelemetry?.depthVariance ?: 0.182f)
                    val gazeAttentive = firstFace?.gazeResult?.isGazeAttentive ?: (state.qualcommTelemetry?.gazeAttentive ?: true)
                    val livenessScore = if (firstFace?.isLive == true) 0.994f else (if (hasFace) 0.55f else 0f)

                    BiometricLiveTelemetryCapsule(
                        depthVariance = depthVar,
                        isGazeAttentive = gazeAttentive,
                        livenessProbability = livenessScore,
                        isDark = isDark,
                        hasFace = hasFace,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    )

                    // Non-blocking ambient status pill at top of viewfinder
                    if (!state.isModelAvailable) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isDark) Color(0xD90B0F19) else Color(0xEBFFFFFF))
                                .border(0.75.dp, if (isDark) Color(0x4D38BDF8) else Color(0x330284C7), RoundedCornerShape(999.dp))
                                .clickable { viewModel.loadEngineExplicitly(context) }
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Science,
                                contentDescription = "Sensor Test Mode",
                                tint = Color(0xFF38BDF8),
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = "Sensor Test Mode • Attendance Disabled • Tap to Download AI Model",
                                color = omniTextPrimary(isDark),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    } else if (state.isEngineLoading && state.isModelAvailable && !state.isEngineLoaded) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 16.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isDark) Color(0xD90B0F19) else Color(0xEBFFFFFF))
                                .border(0.75.dp, if (isDark) Color(0x3338BDF8) else Color(0x1F0284C7), RoundedCornerShape(999.dp))
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                color = Color(0xFF0284C7),
                                strokeWidth = 2.dp
                            )
                            Text(
                                text = "Preparing AI Engine...",
                                color = omniTextPrimary(isDark),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Quantum Biometric Standby Visual matching the mockups!
                    if (state.isScanningPaused) {
                        if (state.scannerMode == ScannerMode.AUTO_KIOSK) {
                            // Kiosk paused overlay
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(if (isDark) Color(0xFF070A14) else Color(0xFF0F1527))
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                BiometricEnergyOrb(
                                    size = 160.dp,
                                    showRings = true
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Scan. Recognize. Empower.",
                                    color = Color.White,
                                    fontSize = 16.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.2.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Tap START ATTENDANCE SCAN to activate kiosk",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.5.sp
                                )
                            }
                        } else {
                            // Handheld mode non-occluding cybernetic standby reticle
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(Color(0xD00B0F19))
                                        .border(1.dp, Color(0x336366F1), RoundedCornerShape(20.dp))
                                        .padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.TouchApp,
                                        contentDescription = null,
                                        tint = Color(0xFF38BDF8),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "STANDBY • TAP SHUTTER OR VOLUME KEY",
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 0.4.sp
                                    )
                                }
                            }
                        }
                    }

                    if (state.manualShutterRemainingSec != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 16.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color(0xDDDC2626))
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(12.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Text(
                                    text = "ACTIVE BURST: ${state.manualShutterRemainingSec}s",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp
                                )
                            }
                        }
                    }
                }
            }

            // 6. Prominent Manual Scan Action Button (Apple Liquid Glass Centerpiece)
            item {
                val isPaused = state.isScanningPaused
                val isReady = (!state.isModelAvailable || state.engineLoadingProgress.isReady || state.isEngineLoaded) && state.isCameraBound
                val pulseTransition = rememberInfiniteTransition(label = "pulse")
                val pulseAlpha by pulseTransition.animateFloat(
                    initialValue = 0.88f,
                    targetValue = 1.0f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(900, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pulseAlpha"
                )

                val interactionSource = remember { MutableInteractionSource() }
                val isPressed by interactionSource.collectIsPressedAsState()
                val buttonScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.965f else 1.0f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "scanButtonScale"
                )

                val buttonBrush = when {
                    !state.isModelAvailable -> Brush.horizontalGradient(listOf(Color(0xFF0284C7), Color(0xFF0EA5E9)))
                    isPaused -> OmniButtonBrush
                    else -> Brush.horizontalGradient(listOf(Color(0xFFEF4444), Color(0xFFDC2626)))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .scale(buttonScale)
                            .fillMaxWidth()
                            .height(56.dp)
                            .shadow(
                                elevation = if (isPaused || !state.isModelAvailable) 14.dp else 6.dp,
                                shape = RoundedCornerShape(20.dp),
                                ambientColor = if (isPaused) Color(0x666366F1) else Color(0x66EF4444),
                                spotColor = if (isPaused) Color(0x996366F1) else Color(0x99EF4444)
                            )
                            .clip(RoundedCornerShape(20.dp))
                            .background(buttonBrush)
                            .border(
                                width = 0.75.dp,
                                brush = Brush.verticalGradient(
                                    listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0.08f))
                                ),
                                shape = RoundedCornerShape(20.dp)
                            )
                            .graphicsLayer {
                                alpha = if ((isPaused || !state.isModelAvailable) && isReady) pulseAlpha else 1.0f
                            }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null
                            ) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                if (!state.isModelAvailable) {
                                    viewModel.loadEngineExplicitly(context)
                                } else {
                                    viewModel.triggerManualShutterOrTogglePause()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        // Inner top specular highlight sheen
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(22.dp)
                                .align(Alignment.TopCenter)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)
                                    )
                                )
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            // Button-in-Button circular icon wrapper
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.18f))
                                    .border(0.5.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = when {
                                        !state.isModelAvailable -> Icons.Default.CloudDownload
                                        isPaused -> Icons.Default.PlayArrow
                                        else -> Icons.Default.Pause
                                    },
                                    contentDescription = if (!state.isModelAvailable) "Download AI Pack" else if (isPaused) "Start Scan" else "Pause Scan",
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = when {
                                    !state.isModelAvailable -> "DOWNLOAD AI PACK TO SCAN"
                                    state.manualShutterRemainingSec != null -> "SCANNING... (${state.manualShutterRemainingSec}s)"
                                    isPaused && state.scannerMode == ScannerMode.MANUAL_HANDHELD -> "START SCAN (HANDHELD)"
                                    isPaused -> "START ATTENDANCE SCAN"
                                    else -> "PAUSE SCANNING"
                                },
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.5.sp,
                                letterSpacing = 0.4.sp
                            )
                        }
                    }
                }
            }

            // 5. State-Driven Adaptive Glass Verification Card
            item {
                IOSCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state.matchedRoll.isNotBlank()) {
                            viewModel.openStudentInfo(state.matchedRoll)
                        }
                ) {
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
                                        ScannerScanState.DUPLICATE_ATTENDANCE -> Icons.Default.Warning
                                        ScannerScanState.UNKNOWN_IDENTITY -> Icons.Default.PersonOff
                                        ScannerScanState.SPOOF_ALERT -> Icons.Default.GppBad
                                        ScannerScanState.POOR_QUALITY -> Icons.Default.CenterFocusWeak
                                        ScannerScanState.EMPTY_DATABASE -> Icons.Default.PersonAdd
                                        ScannerScanState.SENSOR_TEST_MODE -> Icons.Default.Science
                                        else -> Icons.Default.Face
                                    },
                                    contentDescription = null,
                                    tint = stateColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = state.matchTitle,
                                        color = omniTextPrimary(isDark),
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    if (state.matchedRoll.isNotBlank() && (state.scanState == ScannerScanState.RECOGNIZED || state.scanState == ScannerScanState.ATTENDANCE_RECORDED || state.scanState == ScannerScanState.DUPLICATE_ATTENDANCE)) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        val roleBadge = LocalizationManager.getRoleBadgeLabel(state.matchedRole)
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(OmniViolet.copy(alpha = 0.15f))
                                                .border(0.5.dp, OmniViolet.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 5.dp, vertical = 1.dp)
                                        ) {
                                            Text(
                                                text = roleBadge.uppercase(),
                                                color = OmniSky,
                                                fontSize = 8.5.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = state.matchSubtitle,
                                    color = omniTextSecondary(isDark),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 2,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Contextual Action Button (Apple Liquid Glass)
                        if (!state.isModelAvailable) {
                            CupertinoButton(
                                modifier = Modifier.width(140.dp),
                                text = "Download Pack",
                                icon = Icons.Default.CloudDownload,
                                brush = Brush.horizontalGradient(listOf(Color(0xFF0284C7), Color(0xFF0EA5E9))),
                                height = 36.dp,
                                onClick = { viewModel.loadEngineExplicitly(context) }
                            )
                        } else {
                            when (state.scanState) {
                                ScannerScanState.EMPTY_DATABASE -> {
                                    CupertinoButton(
                                        modifier = Modifier.width(110.dp),
                                        text = "+ Enroll",
                                        brush = OmniButtonBrush,
                                        height = 36.dp,
                                        onClick = onNavigateToEnroll
                                    )
                                }
                                ScannerScanState.DUPLICATE_ATTENDANCE -> {
                                    CupertinoButton(
                                        modifier = Modifier.width(105.dp),
                                        text = "Profile",
                                        icon = Icons.Default.Info,
                                        brush = Brush.horizontalGradient(listOf(Color(0xFFD97706), Color(0xFFF59E0B))),
                                        height = 36.dp,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.openStudentInfo(state.matchedRoll)
                                        }
                                    )
                                }
                                ScannerScanState.RECOGNIZED -> {
                                    CupertinoButton(
                                        modifier = Modifier.width(115.dp),
                                        text = LocalizationManager.get(StringKey.CONFIRM_ACTION),
                                        icon = Icons.Default.Check,
                                        brush = Brush.horizontalGradient(listOf(Color(0xFF059669), Color(0xFF10B981))),
                                        height = 36.dp,
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            viewModel.markManualAttendance()
                                        }
                                    )
                                }
                                ScannerScanState.ATTENDANCE_RECORDED -> {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0x3334C759))
                                                .border(0.5.dp, Color(0xFF34C759).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                                .padding(horizontal = 9.dp, vertical = 5.dp)
                                        ) {
                                            Text(
                                                text = LocalizationManager.get(StringKey.VERIFIED_BADGE),
                                                color = Color(0xFF34C759),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        if (state.matchedRoll.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .size(32.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isDark) Color(0x331E293B) else Color(0x1A0284C7))
                                                    .border(0.5.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                                                    .clickable { viewModel.openStudentInfo(state.matchedRoll) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Info,
                                                    contentDescription = "Profile",
                                                    tint = omniCyan(isDark),
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                                ScannerScanState.UNKNOWN_IDENTITY -> {
                                    CupertinoButton(
                                        modifier = Modifier.width(105.dp),
                                        text = "+ Enroll",
                                        brush = Brush.horizontalGradient(listOf(Color(0xFFD97706), Color(0xFFF59E0B))),
                                        height = 36.dp,
                                        onClick = onNavigateToEnroll
                                    )
                                }
                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (isDark) Color(0x331E293B) else Color(0x1A0284C7))
                                            .border(0.5.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                                            .clickable {
                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                viewModel.retryScan()
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = LocalizationManager.get(StringKey.RETRY_ACTION),
                                            tint = omniCyan(isDark),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Text(
                                text = "${state.enrolledCount} ${LocalizationManager.getEntityPlural(state.orgType)} Enrolled",
                                color = omniTextMuted(isDark),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            // Explicit Database Reload Button
                            Box(
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .background(omniCyan(isDark).copy(alpha = if (isDark) 0.20f else 0.12f))
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        viewModel.refreshEnrolledTemplates()
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Reload Database",
                                    tint = omniCyan(isDark),
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (state.enrolledCount == 0) LocalizationManager.get(StringKey.BEGIN_FACE_ENROLLMENT) else LocalizationManager.get(StringKey.MANAGE_DATABASE),
                            color = omniCyan(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onNavigateToEnroll() }
                        )
                    }

                    state.isDatabaseRefreshedMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "✓ $msg",
                            color = omniEmerald(isDark),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // Quick Control Pills: Mode & Manual Trigger
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        CupertinoButton(
                            text = if (state.scannerMode == ScannerMode.AUTO_KIOSK) "Kiosk Mode" else "Handheld Mode",
                            icon = if (state.scannerMode == ScannerMode.AUTO_KIOSK) Icons.Default.Sensors else Icons.Default.TouchApp,
                            isSecondary = true,
                            height = 44.dp,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggleScannerMode()
                            }
                        )
                    }

                    Box(modifier = Modifier.weight(1f)) {
                        CupertinoButton(
                            text = LocalizationManager.get(StringKey.MANUAL_TRIGGER),
                            icon = Icons.Default.EditNote,
                            isSecondary = true,
                            height = 44.dp,
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.openManualOverrideDialog()
                            }
                        )
                    }
                }
            }

            // Security Accuracy Tier Selector
            item {
                CupertinoSegmentedControl(
                    items = listOf(
                        LocalizationManager.get(StringKey.TIER_STANDARD),
                        LocalizationManager.get(StringKey.TIER_HIGH),
                        LocalizationManager.get(StringKey.TIER_STRICT)
                    ),
                    selectedIndex = when (state.activeTier) {
                        SecurityTier.STANDARD -> 0
                        SecurityTier.HIGH -> 1
                        SecurityTier.STRICT -> 2
                    },
                    activeBrush = OmniButtonBrush,
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

            // Collapsible On-Device Neural Suite Card
            item {
                IOSCard(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { showNeuralDiagnostics = !showNeuralDiagnostics }
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(omniEmerald(isDark))
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "NEURAL SUITE TELEMETRY",
                                    color = omniTextMuted(isDark),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                    maxLines = 1
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isDark) Color(0x330284C7) else Color(0x1A0284C7))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (showNeuralDiagnostics) "Hide" else "Inspect",
                                        color = omniCyan(isDark),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = if (showNeuralDiagnostics) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = omniTextMuted(isDark),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (showNeuralDiagnostics) {
                            val qc = state.qualcommTelemetry
                            HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0xFF141926) else Color(0x08000000))
                                        .padding(8.dp)
                                ) {
                                    Text("3DMM Depth", fontSize = 10.sp, color = omniTextMuted(isDark))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (qc?.is3DMMActive == true) "%.3f Var".format(qc.depthVariance) else "Ready",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = omniTextPrimary(isDark)
                                    )
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0xFF141926) else Color(0x08000000))
                                        .padding(8.dp)
                                ) {
                                    Text("Eye Gaze", fontSize = 10.sp, color = omniTextMuted(isDark))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (qc?.isEyeGazeActive == true) (if (qc.gazeAttentive) "✓ Attentive" else "Off-Axis") else "Ready",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (qc?.gazeAttentive == true) omniEmerald(isDark) else Color(0xFFFF9500)
                                    )
                                }

                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isDark) Color(0xFF141926) else Color(0x08000000))
                                        .padding(8.dp)
                                ) {
                                    Text("Attrib Net", fontSize = 10.sp, color = omniTextMuted(isDark))
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (qc?.isFaceAttribActive == true) "${(qc.smileScore * 100).toInt()}% Smile" else "Ready",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = omniTextPrimary(isDark)
                                    )
                                }
                            }

                            if (!state.isEngineLoaded) {
                                Spacer(modifier = Modifier.height(8.dp))
                                CupertinoButton(
                                    text = if (!state.isModelAvailable) "Download Neural Model (380 MB)" else "Initialize Silicon NPU Core",
                                    icon = if (!state.isModelAvailable) Icons.Default.CloudDownload else Icons.Default.PlayArrow,
                                    height = 42.dp,
                                    onClick = { viewModel.loadEngineExplicitly(context) }
                                )
                            }
                        }
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp, start = 20.dp, end = 20.dp)
        ) { snackbarData ->
            Box(
                modifier = Modifier
                    .shadow(12.dp, RoundedCornerShape(20.dp), spotColor = Color(0x33000000))
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (isDark) Color(0xDD1E293B) else Color(0xEEFFFFFF))
                    .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Text(
                    text = snackbarData.visuals.message,
                    color = omniTextPrimary(isDark),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
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

    if (state.showThermalDialog) {
        ThermalGovernorDialog(
            isDark = isDark,
            thermalState = state.thermalState,
            temperature = state.deviceTemperature,
            isAutoScalingEnabled = state.isAutoScalingEnabled,
            onDismiss = { viewModel.dismissThermalDialog() },
            onToggleAutoScaling = { viewModel.setAutoScalingEnabled(it) },
            onSimulateState = { viewModel.setThermalSimulationOverride(it) }
        )
    }

    if (state.showModelManagerDialog) {
        ModelManagerDialog(
            isDark = isDark,
            config = state.neuralModelConfig,
            onDismiss = { viewModel.dismissModelManagerDialog() }
        )
    }

    state.selectedStudentForInfo?.let { student ->
        StudentInfoSheet(
            student = student,
            templates = state.studentTemplatesForInfo,
            attendanceCount = state.studentAttendanceCountForInfo,
            recentRecords = state.studentRecentRecordsForInfo,
            isDark = isDark,
            onDismiss = { viewModel.closeStudentInfo() },
            onEditClick = {
                viewModel.closeStudentInfo()
                onNavigateToEnroll()
            },
            onViewAttendanceClick = {
                viewModel.closeStudentInfo()
            },
            onReEnrollClick = {
                viewModel.closeStudentInfo()
                onNavigateToEnroll()
            },
            onDeleteClick = {
                viewModel.deleteStudent(student.rollNumber)
            }
        )
    }
}

@Composable
private fun ModelManagerDialog(
    isDark: Boolean,
    config: com.omniface.ai.ml.NeuralModelConfig,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF8B5CF6))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "NEURAL MODEL MANAGER",
                    color = omniTextPrimary(isDark),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
        },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        text = "Toggle auxiliary neural models on/off in real-time. Core Face Detection & ArcFace 512-D remain active.",
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                }

                // Core Baseline
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isDark) Color(0x1F1E293B) else Color(0x0A000000))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Core Face Detection & Recognition", color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("ML Kit Face + ArcFace 512-D Embedding", color = omniTextMuted(isDark), fontSize = 10.sp)
                        }
                        Text("ACTIVE", color = Color(0xFF10B981), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // MiniFASNet PAD
                item {
                    ModelToggleRow(
                        title = "MiniFASNetV2 Passive PAD",
                        subtitle = "Neural Screen / Photo Anti-Spoofing",
                        checked = config.isPassivePadEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setPassivePadEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF007AFF)
                    )
                }

                // Multi-Stage Texture & Glare
                item {
                    ModelToggleRow(
                        title = "Multi-Stage Texture / Glare",
                        subtitle = "LBP Texture Entropy & Specular Analysis",
                        checked = config.isMultiStageLivenessEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setMultiStageLivenessEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF06B6D4)
                    )
                }

                // Temporal Liveness
                item {
                    ModelToggleRow(
                        title = "Temporal Micro-Motion",
                        subtitle = "Optical Flow & Blink Continuity",
                        checked = config.isTemporalLivenessEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setTemporalLivenessEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF3B82F6)
                    )
                }

                // FaceMap 3DMM
                item {
                    ModelToggleRow(
                        title = "FaceMap 3DMM",
                        subtitle = "265-D 3D Surface Depth Reconstruction",
                        checked = config.isFaceMap3DMMEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setFaceMap3DMMEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF8B5CF6)
                    )
                }

                // EyeGazeNet
                item {
                    ModelToggleRow(
                        title = "EyeGazeNet Tracker",
                        subtitle = "Pupil Vector & Attention Tracking",
                        checked = config.isEyeGazeEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setEyeGazeEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFFEC4899)
                    )
                }

                // FaceAttribNet
                item {
                    ModelToggleRow(
                        title = "FaceAttribNet Classifier",
                        subtitle = "Smile, Eyeglasses & Mask Detection",
                        checked = config.isFaceAttribEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setFaceAttribEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFFF59E0B)
                    )
                }

                // MediaPipe Mesh
                item {
                    ModelToggleRow(
                        title = "MediaPipe 468-Point 3D Mesh",
                        subtitle = "Dense Facial Surface Point Cloud",
                        checked = config.isMediaPipeMeshEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setMediaPipeMeshEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF14B8A6)
                    )
                }

                // HRNet Landmarks
                item {
                    ModelToggleRow(
                        title = "HRNet Deep Landmarks",
                        subtitle = "29-Point Landmark Heatmap Extractor",
                        checked = config.isHrnetLandmarksEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setHrnetLandmarksEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF6366F1)
                    )
                }

                // Dynamic Centroid Adaptation
                item {
                    ModelToggleRow(
                        title = "Dynamic Centroid Adaptation",
                        subtitle = "Continuous Learning on Verified Scans",
                        checked = config.isDynamicCentroidAdaptationEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setDynamicCentroidAdaptationEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF10B981)
                    )
                }

                // FAISS HNSW Index
                item {
                    ModelToggleRow(
                        title = "FAISS / HNSW Vector Index",
                        subtitle = "Sub-Millisecond Candidate Search",
                        checked = config.isFaissHnswIndexEnabled,
                        onCheckedChange = { com.omniface.ai.ml.NeuralModelConfigManager.setFaissHnswIndexEnabled(it) },
                        isDark = isDark,
                        tint = Color(0xFF8B5CF6)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(LocalizationManager.get(StringKey.CLOSE_ACTION), color = omniCyan(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = { com.omniface.ai.ml.NeuralModelConfigManager.resetToDefaults() }
            ) {
                Text("Reset Defaults", color = Color(0xFFEF4444), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun ModelToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    isDark: Boolean,
    tint: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isDark) Color(0x1F1E293B) else Color(0x0A000000))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(1.dp))
            Text(subtitle, color = omniTextMuted(isDark), fontSize = 10.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = tint
            ),
            modifier = Modifier.height(28.dp)
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
                Text("Close", color = omniCyan(isDark), fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        },
        containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp)
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
            Text(LocalizationManager.get(StringKey.MANUAL_TRIGGER), color = omniTextPrimary(isDark), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    LocalizationManager.get(StringKey.ADMIN_PIN),
                    color = omniTextSecondary(isDark),
                    fontSize = 12.sp
                )
                OutlinedTextField(
                    value = roll,
                    onValueChange = { roll = it },
                    label = { Text(LocalizationManager.get(StringKey.ROLL_NUMBER), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(LocalizationManager.get(StringKey.FULL_NAME), fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text(LocalizationManager.get(StringKey.ADMIN_PIN), fontSize = 12.sp) },
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
            CupertinoButton(
                modifier = Modifier.width(110.dp),
                text = LocalizationManager.get(StringKey.CONFIRM_ACTION),
                brush = OmniButtonBrush,
                height = 38.dp,
                onClick = {
                    // Unified PBKDF2 + constant-time verification via KioskLockController
                    val lockoutActive = com.omniface.ai.hardware.KioskLockController.isLockedOut()
                    val pinOk = !lockoutActive &&
                        com.omniface.ai.hardware.KioskLockController.verifyAdminPin(context, pin)
                    if (lockoutActive) {
                        errorText = "Too many attempts — wait for lockout to expire"
                    } else if (!pinOk) {
                        errorText = "Invalid Admin PIN"
                    } else if (roll.isBlank() || name.isBlank()) {
                        errorText = "Please fill in Roll and Name"
                    } else {
                        onConfirm(roll.trim(), name.trim())
                    }
                }
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(LocalizationManager.get(StringKey.CANCEL_ACTION), color = omniTextMuted(isDark), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun ThermalGovernorDialog(
    isDark: Boolean,
    thermalState: ThermalState,
    temperature: Float,
    isAutoScalingEnabled: Boolean,
    onDismiss: () -> Unit,
    onToggleAutoScaling: (Boolean) -> Unit,
    onSimulateState: (ThermalState?) -> Unit
) {
    val stateColor = when (thermalState) {
        ThermalState.NOMINAL -> Color(0xFF34C759)
        ThermalState.WARM -> Color(0xFFFF9F0A)
        ThermalState.CRITICAL -> Color(0xFFFF453A)
    }

    val stateIcon = when (thermalState) {
        ThermalState.NOMINAL -> "❄️"
        ThermalState.WARM -> "⚡"
        ThermalState.CRITICAL -> "🔥"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = stateIcon, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "HARDWARE THERMAL GOVERNOR",
                        color = omniTextPrimary(isDark),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "Dynamic Face Detector Resolution Scaling",
                        color = omniTextMuted(isDark),
                        fontSize = 11.sp
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Current Temperature Gauge Card
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0x331E293B) else Color(0x0D000000))
                        .border(1.dp, stateColor.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "DEVICE TEMPERATURE",
                            color = omniTextMuted(isDark),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "%.1f°C".format(temperature),
                            color = stateColor,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }

                    Column(horizontalAlignment = Alignment.End) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(stateColor.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = thermalState.name,
                                color = stateColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${thermalState.targetResolution.width}×${thermalState.targetResolution.height} • ${thermalState.maxFps} FPS",
                            color = omniTextSecondary(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // 2. Auto Dynamic Resolution Scaling Switch
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isDark) Color(0x1F1E293B) else Color(0x08000000))
                        .border(0.75.dp, if (isDark) Color(0x38FFFFFF) else Color(0x1A000000), RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto Resolution Scaling",
                            color = omniTextPrimary(isDark),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Downscale ML input buffer dynamically to prevent thermal throttling and battery drain.",
                            color = omniTextMuted(isDark),
                            fontSize = 10.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isAutoScalingEnabled,
                        onCheckedChange = { onToggleAutoScaling(it) }
                    )
                }

                // 3. Operating Point Resolution Breakdown
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "SCALING PROFILES",
                        color = omniTextMuted(isDark),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    val profiles = listOf(
                        Triple(ThermalState.NOMINAL, "640×480 @ 30 FPS", "< 38.0°C • Peak Accuracy & Full Frame Rate"),
                        Triple(ThermalState.WARM, "480×360 @ 20 FPS", "38.0°C–42.0°C • -45% Compute Load"),
                        Triple(ThermalState.CRITICAL, "320×240 @ 10 FPS", "> 42.0°C • -75% Emergency Thermal Guard")
                    )

                    profiles.forEach { (state, title, desc) ->
                        val isCurrent = thermalState == state
                        val borderCol = if (isCurrent) stateColor else if (isDark) Color(0x1FFFFFFF) else Color(0x0A000000)
                        val bgCol = if (isCurrent) stateColor.copy(alpha = 0.08f) else Color.Transparent

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(bgCol)
                                .border(if (isCurrent) 1.dp else 0.5.dp, borderCol, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    text = "${state.name}: $title",
                                    color = if (isCurrent) stateColor else omniTextPrimary(isDark),
                                    fontSize = 11.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = desc,
                                    color = omniTextMuted(isDark),
                                    fontSize = 9.sp
                                )
                            }
                            if (isCurrent) {
                                Text(
                                    text = "ACTIVE",
                                    color = stateColor,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                }

                // 4. Hardware Simulation Mode
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "THERMAL TESTING & SIMULATION",
                        color = omniTextMuted(isDark),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { onSimulateState(ThermalState.NOMINAL) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0x3334C759) else Color(0x1A34C759)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("❄️ Nominal", color = Color(0xFF34C759), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSimulateState(ThermalState.WARM) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0x33FF9F0A) else Color(0x1AFF9F0A)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("⚡ Warm", color = Color(0xFFFF9F0A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSimulateState(ThermalState.CRITICAL) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0x33FF453A) else Color(0x1AFF453A)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🔥 Critical", color = Color(0xFFFF453A), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = { onSimulateState(null) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (isDark) Color(0x33007AFF) else Color(0x1A007AFF)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("🔄 Auto", color = Color(0xFF007AFF), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        },
        confirmButton = {
            CupertinoButton(
                modifier = Modifier.width(90.dp),
                text = "Close",
                brush = Brush.horizontalGradient(listOf(stateColor, stateColor.copy(alpha = 0.85f))),
                height = 38.dp,
                onClick = onDismiss
            )
        },
        containerColor = if (isDark) Color(0xFF141A29) else Color(0xFFFFFFFF),
        shape = RoundedCornerShape(24.dp)
    )
}

@Composable
fun NeuralEngineLoadingOverlay(
    loading: com.omniface.ai.ml.EngineLoadingProgress,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(if (isDark) Color(0xD90B0F19) else Color(0xD9FFFFFF)),
        contentAlignment = Alignment.Center
    ) {
        IOSCard(
            modifier = Modifier
                .fillMaxWidth(0.90f)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Pulsing circular indicator
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(if (isDark) Color(0x220284C7) else Color(0x1A0284C7)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { loading.progress },
                        modifier = Modifier.size(68.dp),
                        color = Color(0xFF0284C7),
                        trackColor = if (isDark) Color(0x330284C7) else Color(0x1A0284C7),
                        strokeWidth = 3.5.dp
                    )
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = "Neural Accelerator",
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Stage title & details
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "LOADING NEURAL MODEL",
                        color = Color(0xFF38BDF8),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = loading.activeModelName,
                        color = omniTextPrimary(isDark),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = loading.stage,
                        color = omniTextSecondary(isDark),
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                // Progress Bar with Percentage
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = loading.hardwareTarget,
                            color = omniTextMuted(isDark),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "${(loading.progress * 100).toInt()}%",
                            color = Color(0xFF38BDF8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { loading.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFF0284C7),
                        trackColor = if (isDark) Color(0x22FFFFFF) else Color(0x14000000)
                    )
                }

                Text(
                    text = "Compiling neural graph tensors on silicon hardware. Inference warmup in progress...",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    lineHeight = 14.sp
                )
            }
        }
    }
}

@Composable
private fun CupertinoDockIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    activeColor: Color,
    isCenterAccent: Boolean = false,
    onClick: () -> Unit
) {
    val isDark = LocalThemeIsDark.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "dockBtnScale"
    )

    val bgColor by animateColorAsState(
        targetValue = if (isCenterAccent) {
            Color.Transparent
        } else if (isActive) {
            activeColor.copy(alpha = if (isDark) 0.28f else 0.18f)
        } else {
            if (isDark) Color(0x1AFFFFFF) else Color(0x0A000000)
        },
        label = "dockBtnBg"
    )

    val iconTint by animateColorAsState(
        targetValue = if (isCenterAccent) Color.White else if (isActive) activeColor else omniTextPrimary(isDark),
        label = "dockBtnTint"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(16.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() }
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .then(
                    if (isCenterAccent) {
                        Modifier
                            .shadow(8.dp, RoundedCornerShape(15.dp), spotColor = Color(0x886366F1))
                            .clip(RoundedCornerShape(15.dp))
                            .background(OmniButtonBrush)
                    } else {
                        Modifier
                            .clip(RoundedCornerShape(13.dp))
                            .background(bgColor)
                            .border(
                                0.5.dp,
                                if (isActive) activeColor.copy(alpha = 0.6f) else (if (isDark) Color(0x22FFFFFF) else Color(0x12000000)),
                                RoundedCornerShape(13.dp)
                            )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            color = if (isCenterAccent) (if (isDark) Color.White else OmniDeepPurple) else if (isActive) activeColor else omniTextMuted(isDark),
            fontSize = 10.sp,
            fontWeight = if (isCenterAccent || isActive) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = (-0.1).sp
        )
    }
}
