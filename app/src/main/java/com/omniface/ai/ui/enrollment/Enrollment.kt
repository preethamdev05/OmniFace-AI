package com.omniface.ai.ui.enrollment

import com.omniface.ai.ui.enrollment.roster.RegistrationFormView
import com.omniface.ai.ui.enrollment.studio.BiometricStudioView
import com.omniface.ai.ui.enrollment.studio.EnrollmentSuccessView


import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.YuvImage
import com.google.mlkit.vision.face.FaceLandmark
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.QrBadgeGenerator
import com.omniface.ai.hardware.QrCodeExporter
import com.omniface.ai.ml.BiometricCropUtils
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import kotlin.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.ml.*
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.DeviceBiometricAuthManager
import com.omniface.ai.security.findFragmentActivity
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.dedup.BiometricDeduplicationStudio
import com.omniface.ai.ml.recognition.BiometricDeduplicationEngine
import com.omniface.ai.ml.recognition.DuplicateCheckResult
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omniface.ai.billing.SubscriptionTierManager
import com.omniface.ai.billing.PaywallTriggerReason
import com.omniface.ai.ui.billing.PaywallBottomSheet

enum class EnrollmentStage {
    REGISTRATION_FORM,
    BIOMETRIC_STUDIO,
    ENROLLMENT_SUCCESS
}

@Immutable
data class EnrollmentUiState(
    val stage: EnrollmentStage = EnrollmentStage.REGISTRATION_FORM,
    val currentStep: Int = 1, // 1=Frontal, 2=Left 15, 3=Right 15, 4=Up 10, 5=Down 10
    val angleGuideText: String = "Step 1 of 5: Look directly at camera (Frontal 0°)",
    val yawGaugeText: String = "Pose: Ready to capture",
    val isPoseAligned: Boolean = false,
    val autoCaptureCountdownMs: Long = 600L,
    val capturedThumbnails: List<Pair<String, Bitmap>> = emptyList(),
    val visualGeometryData: List<FaceGeometryVisualData> = emptyList(),
    val isSaving: Boolean = false,
    val isOcrScanning: Boolean = false,
    val rollNumber: String = "",
    val fullName: String = "",
    val role: String = "STUDENT",
    val selectedRoleFilter: String = "ALL",
    val department: String = "AI & Biometrics",
    val semester: String = "VI",
    val enrolledStudentsList: List<StudentEntity> = emptyList(),
    val filteredEnrolledStudents: List<StudentEntity> = emptyList(),
    val searchQuery: String = "",
    val selectedStudentForManage: StudentEntity? = null,
    val isEditProfileOpen: Boolean = false,
    val isDeleteConfirmOpen: Boolean = false,
    val editFullName: String = "",
    val editRole: String = "STUDENT",
    val editDepartment: String = "",
    val editSemester: String = "",
    val orgType: String = "SCHOOL",
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val npuInfo: com.omniface.ai.hardware.NpuHardwareInfo = NpuHardwareDetector.detectNpuHardware(),
    val isQualcommDevice: Boolean = NpuHardwareDetector.isQualcommAiHubDevice(),
    val isModelAvailable: Boolean = false,
    val isEngineLoaded: Boolean = false,
    val isEngineLoading: Boolean = false,
    val engineLoadingProgress: com.omniface.ai.ml.EngineLoadingProgress = com.omniface.ai.ml.EngineLoadingProgress(isReady = true, stage = "Ready", progress = 1.0f),
    val showPaywall: Boolean = false,
    val paywallReason: PaywallTriggerReason = PaywallTriggerReason.STUDENT_LIMIT_REACHED
)

class EnrollmentViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val downloadManager = com.omniface.ai.ml.ModelDownloadManager.getInstance(OmniFaceApplication.instance)
    private val unifiedEngine: UnifiedFaceIntelligenceEngine = UnifiedFaceIntelligenceEngine.getInstance(OmniFaceApplication.instance)
    private val _uiState = MutableStateFlow(
        EnrollmentUiState(
            isModelAvailable = downloadManager.isModelAvailable(),
            isEngineLoaded = unifiedEngine.isModelLoaded,
            engineLoadingProgress = com.omniface.ai.ml.EngineLoadingProgress(
                isReady = unifiedEngine.isModelLoaded || !downloadManager.isModelAvailable(),
                stage = if (unifiedEngine.isModelLoaded) "Ready" else "Standby",
                progress = if (unifiedEngine.isModelLoaded) 1.0f else 0.0f
            )
        )
    )
    val uiState: StateFlow<EnrollmentUiState> = _uiState.asStateFlow()

    private val qualityChecker = QualityChecker()
    val cameraExecutor = Executors.newSingleThreadExecutor()

    init {
        if (!unifiedEngine.isModelLoaded && downloadManager.isModelAvailable()) {
            _uiState.update { it.copy(isEngineLoading = true) }
            viewModelScope.launch(Dispatchers.Default) {
                val loaded = unifiedEngine.loadUnifiedModelExplicit(OmniFaceApplication.instance)
                _uiState.update { it.copy(isEngineLoading = false, isEngineLoaded = loaded) }
            }
        }
        viewModelScope.launch {
            unifiedEngine.isModelLoadedState.collect { isLoaded ->
                _uiState.update {
                    it.copy(
                        isEngineLoaded = isLoaded,
                        engineLoadingProgress = EngineLoadingProgress(
                            isReady = isLoaded,
                            stage = if (isLoaded) "Unified AI Engine Ready (${unifiedEngine.activeBackend})" else "Engine Standby",
                            progress = if (isLoaded) 1.0f else 0.0f
                        )
                    )
                }
            }
        }
        viewModelScope.launch {
            downloadManager.downloadState.collect {
                _uiState.update { current ->
                    current.copy(isModelAvailable = downloadManager.isModelAvailable())
                }
            }
        }
    }


    private var latestFullBitmap: Bitmap? = null
    private var latestLandmarks5Pts: Array<android.graphics.PointF>? = null
    private var latestFaceCrop: Bitmap? = null
    private var latestFaceYaw: Float = 0.0f
    private var latestFacePitch: Float = 0.0f
    private var isCapturing = false
    private var alignmentStartTime: Long = 0L

    private val capturedTemplates = mutableListOf<FaceTemplateEntity>()
    private val capturedEmbeddings = mutableListOf<FloatArray>()
    private val capturedQualityScores = mutableListOf<Float>()

    init {
        viewModelScope.launch {
            LocalizationManager.currentOrgType.collect { currentType ->
                val defaultRole = when (currentType.uppercase()) {
                    "CORPORATE" -> "EMPLOYEE"
                    "GYM_EVENT" -> "MEMBER"
                    else -> "STUDENT"
                }
                _uiState.update { it.copy(orgType = currentType, role = defaultRole) }
            }
        }
        observeEnrolledStudents()
    }

    private fun observeEnrolledStudents() {
        viewModelScope.launch {
            db.studentDao().getAllStudentsFlow().collect { list ->
                _uiState.update { current ->
                    current.copy(
                        enrolledStudentsList = list,
                        filteredEnrolledStudents = filterStudents(list, current.searchQuery, current.selectedRoleFilter)
                    )
                }
            }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update { current ->
            current.copy(
                searchQuery = query,
                filteredEnrolledStudents = filterStudents(current.enrolledStudentsList, query, current.selectedRoleFilter)
            )
        }
    }

    fun onRoleFilterChanged(roleFilter: String) {
        _uiState.update { current ->
            current.copy(
                selectedRoleFilter = roleFilter,
                filteredEnrolledStudents = filterStudents(current.enrolledStudentsList, current.searchQuery, roleFilter)
            )
        }
    }

    private fun filterStudents(
        list: List<StudentEntity>,
        query: String,
        roleFilter: String = _uiState.value.selectedRoleFilter
    ): List<StudentEntity> {
        val q = query.trim().lowercase(java.util.Locale.getDefault())
        return list.filter { s ->
            val matchesQuery = q.isEmpty() ||
                s.fullName.lowercase(java.util.Locale.getDefault()).contains(q) ||
                s.rollNumber.lowercase(java.util.Locale.getDefault()).contains(q) ||
                s.department.lowercase(java.util.Locale.getDefault()).contains(q)

            val matchesRole = when (roleFilter) {
                "ALL" -> true
                "STAFF" -> s.role.equals("FACULTY", ignoreCase = true) || s.role.equals("STAFF", ignoreCase = true) || s.role.equals("MANAGER", ignoreCase = true) || s.role.equals("TRAINER", ignoreCase = true)
                "PRIMARY" -> s.role.equals("STUDENT", ignoreCase = true) || s.role.equals("EMPLOYEE", ignoreCase = true) || s.role.equals("MEMBER", ignoreCase = true)
                "VISITOR" -> s.role.equals("VISITOR", ignoreCase = true) || s.role.equals("CONTRACTOR", ignoreCase = true)
                else -> s.role.equals(roleFilter, ignoreCase = true)
            }
            matchesQuery && matchesRole
        }
    }

    fun openStudentProfile(student: StudentEntity) {
        _uiState.update {
            it.copy(
                selectedStudentForManage = student,
                editFullName = student.fullName,
                editRole = student.role,
                editDepartment = student.department,
                editSemester = student.semester,
                isEditProfileOpen = false,
                isDeleteConfirmOpen = false
            )
        }
    }

    fun closeStudentProfile() {
        _uiState.update {
            it.copy(
                selectedStudentForManage = null,
                isEditProfileOpen = false,
                isDeleteConfirmOpen = false
            )
        }
    }

    fun openEditProfileDialog() {
        val s = _uiState.value.selectedStudentForManage ?: return
        _uiState.update {
            it.copy(
                isEditProfileOpen = true,
                editFullName = s.fullName,
                editRole = s.role,
                editDepartment = s.department,
                editSemester = s.semester
            )
        }
    }

    fun closeEditProfileDialog() {
        _uiState.update { it.copy(isEditProfileOpen = false) }
    }

    fun updateEditFields(name: String? = null, dept: String? = null, sem: String? = null, role: String? = null) {
        _uiState.update {
            it.copy(
                editFullName = name ?: it.editFullName,
                editRole = role ?: it.editRole,
                editDepartment = dept ?: it.editDepartment,
                editSemester = sem ?: it.editSemester
            )
        }
    }

    fun saveEditedProfile(context: Context) {
        val currentStudent = _uiState.value.selectedStudentForManage ?: return
        val newName = _uiState.value.editFullName.trim()
        val newRole = _uiState.value.editRole.trim()
        val newDept = _uiState.value.editDepartment.trim()
        val newSem = _uiState.value.editSemester.trim()

        if (newName.isBlank()) {
            Toast.makeText(context, "Full Name cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val updated = currentStudent.copy(
                fullName = newName,
                role = if (newRole.isNotBlank()) newRole else currentStudent.role,
                department = if (newDept.isNotBlank()) newDept else currentStudent.department,
                semester = if (newSem.isNotBlank()) newSem else currentStudent.semester
            )
            db.studentDao().updateStudent(updated)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        selectedStudentForManage = updated,
                        isEditProfileOpen = false
                    )
                }
                Toast.makeText(context, "Profile updated for ${updated.fullName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun openDeleteConfirmDialog() {
        _uiState.update { it.copy(isDeleteConfirmOpen = true) }
    }

    fun closeDeleteConfirmDialog() {
        _uiState.update { it.copy(isDeleteConfirmOpen = false) }
    }

    fun confirmDeleteSelectedStudent(context: Context) {
        val student = _uiState.value.selectedStudentForManage ?: return
        val act = context.findFragmentActivity()
        if (act != null && DeviceBiometricAuthManager.canAuthenticate(context)) {
            DeviceBiometricAuthManager.authenticate(
                activity = act,
                title = "Authorize Identity Deletion",
                subtitle = "Scan fingerprint or screen lock to delete ${student.fullName}",
                onSuccess = {
                    executeDeleteStudent(context, student)
                },
                onError = { err ->
                    Toast.makeText(context, "Authentication Failed: $err", Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            executeDeleteStudent(context, student)
        }
    }

    private fun executeDeleteStudent(context: Context, student: StudentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.studentDao().deleteStudent(student)
            db.studentDao().deleteTemplatesForStudent(student.rollNumber)
            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        selectedStudentForManage = null,
                        isDeleteConfirmOpen = false
                    )
                }
                Toast.makeText(context, "Removed profile for ${student.fullName}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun initEngine(context: Context) {
        // recognitionEngine is eagerly initialized in the constructor; no re-init needed.
        // Context is retained for future hot-swap delegate scenarios.
    }

    fun updateForm(roll: String? = null, name: String? = null, dept: String? = null, sem: String? = null, role: String? = null) {
        _uiState.update {
            it.copy(
                rollNumber = roll ?: it.rollNumber,
                fullName = name ?: it.fullName,
                role = role ?: it.role,
                department = dept ?: it.department,
                semester = sem ?: it.semester
            )
        }
    }

    fun toggleLensFacing() {
        val newFacing = if (_uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        _uiState.update { it.copy(lensFacing = newFacing) }
    }

    fun dismissPaywall() {
        _uiState.update { it.copy(showPaywall = false) }
    }

    fun triggerPaywall(reason: PaywallTriggerReason = PaywallTriggerReason.STUDENT_LIMIT_REACHED) {
        _uiState.update { it.copy(showPaywall = true, paywallReason = reason) }
    }

    fun startBiometricStudio(context: Context) {
        val roll = _uiState.value.rollNumber.trim()
        val name = _uiState.value.fullName.trim()

        if (!downloadManager.isModelAvailable()) {
            val entityPlural = LocalizationManager.getEntityPlural(_uiState.value.orgType).lowercase()
            Toast.makeText(context, "AI Face Pack required to enroll $entityPlural. Please download the model in Settings.", Toast.LENGTH_LONG).show()
            return
        }

        if (roll.isBlank() || name.isBlank()) {
            val idLabel = LocalizationManager.getIdLabel(_uiState.value.orgType)
            Toast.makeText(context, "Please enter Full Name and $idLabel", Toast.LENGTH_SHORT).show()
            return
        }

        val currentCount = _uiState.value.enrolledStudentsList.size
        if (!SubscriptionTierManager.canEnrollMore(currentCount)) {
            triggerPaywall(PaywallTriggerReason.STUDENT_LIMIT_REACHED)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val existing = db.studentDao().getStudentByRoll(roll)
            if (existing != null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Student with Roll Number '$roll' already exists", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            if (!unifiedEngine.isModelLoaded) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Initializing AI Engine...", Toast.LENGTH_SHORT).show()
                }
                val loaded = unifiedEngine.loadUnifiedModelExplicit(context)
                if (!loaded) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(context, "AI engine not ready. Please verify the AI model in Settings.", Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
            }

            withContext(Dispatchers.Main) {
                capturedTemplates.clear()
                _uiState.update {
                    it.copy(
                        stage = EnrollmentStage.BIOMETRIC_STUDIO,
                        currentStep = 1,
                        capturedThumbnails = emptyList(),
                        angleGuideText = "Step 1 of 5: Look directly at camera (Frontal 0°)",
                        yawGaugeText = "Pose: Align face in circle",
                        isPoseAligned = false
                    )
                }
            }
        }
    }

    fun cancelBiometricStudio() {
        capturedTemplates.clear()
        _uiState.update {
            it.copy(
                stage = EnrollmentStage.REGISTRATION_FORM,
                currentStep = 1,
                capturedThumbnails = emptyList()
            )
        }
    }

    fun retakeCurrentAngle() {
        val currentStep = _uiState.value.currentStep
        if (currentStep > 1 && capturedTemplates.isNotEmpty()) {
            capturedTemplates.removeAt(capturedTemplates.size - 1)
            val updatedThumbs = _uiState.value.capturedThumbnails.dropLast(1)
            val prevStep = currentStep - 1
            val guide = when (prevStep) {
                1 -> "Step 1 of 5: Look directly at camera (Frontal 0°)"
                2 -> "Step 2 of 5: Turn face slightly Left (~15° angle)"
                3 -> "Step 3 of 5: Turn face slightly Right (~15° angle)"
                4 -> "Step 4 of 5: Tilt head slightly Up (~10° angle)"
                else -> "Step 5 of 5: Tilt head slightly Down (~10° angle)"
            }
            _uiState.update {
                it.copy(
                    currentStep = prevStep,
                    capturedThumbnails = updatedThumbs,
                    angleGuideText = guide,
                    isPoseAligned = false
                )
            }
        }
    }

    fun resetAllAngles() {
        capturedTemplates.clear()
        _uiState.update {
            it.copy(
                currentStep = 1,
                capturedThumbnails = emptyList(),
                angleGuideText = "Step 1 of 5: Look directly at camera (Frontal 0°)",
                yawGaugeText = "Pose: Align face in circle",
                isPoseAligned = false
            )
        }
    }

    fun resetForNextStudent() {
        capturedTemplates.clear()
        _uiState.update {
            it.copy(
                stage = EnrollmentStage.REGISTRATION_FORM,
                currentStep = 1,
                capturedThumbnails = emptyList(),
                rollNumber = "",
                fullName = "",
                department = "AI & Biometrics",
                semester = "VI"
            )
        }
    }

    fun deleteStudent(student: StudentEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            db.studentDao().deleteStudent(student)
            db.studentDao().deleteTemplatesForStudent(student.rollNumber)
        }
    }

    private fun isPoseInTargetEnvelope(yaw: Float, pitch: Float, step: Int): Boolean {
        return when (step) {
            1 -> kotlin.math.abs(yaw) <= 15.0f && kotlin.math.abs(pitch) <= 15.0f // Frontal (within ±15°)
            2 -> yaw <= -7.0f && yaw >= -35.0f && kotlin.math.abs(pitch) <= 22.0f // Left angle (~10-30°)
            3 -> yaw >= 7.0f && yaw <= 35.0f && kotlin.math.abs(pitch) <= 22.0f   // Right angle (~10-30°)
            4 -> pitch >= 6.0f && pitch <= 30.0f && kotlin.math.abs(yaw) <= 22.0f  // Up angle (~8-25°)
            5 -> pitch <= -6.0f && pitch >= -30.0f && kotlin.math.abs(yaw) <= 22.0f // Down angle (~8-25°)
            else -> false
        }
    }

    fun processCameraFrame(
        face: Face,
        fullBitmap: Bitmap?,
        previewWidth: Float = 0f,
        previewHeight: Float = 0f
    ) {
        if (_uiState.value.stage != EnrollmentStage.BIOMETRIC_STUDIO || fullBitmap == null) {
            fullBitmap?.recycle()
            return
        }

        try {
            if (isCapturing || _uiState.value.currentStep > 5) {
                fullBitmap.recycle()
                return
            }

            val rawYaw = face.headEulerAngleY
            val isFrontCamera = _uiState.value.lensFacing == CameraSelector.LENS_FACING_FRONT
            // Subject-centric yaw: turning to subject's left rotates face toward camera's right (+rawYaw in ML Kit).
            // To ensure "Turn Left" (Step 2) consistently corresponds to negative yaw (yaw <= -7.0f) and
            // "Turn Right" (Step 3) corresponds to positive yaw (yaw >= 7.0f) across both front and rear cameras,
            // subjectYaw is always -rawYaw.
            latestFaceYaw = -rawYaw
            latestFacePitch = face.headEulerAngleX

            val step = _uiState.value.currentStep
            val isAligned = isPoseInTargetEnvelope(latestFaceYaw, latestFacePitch, step)

            val box = face.boundingBox
            val newCrop = BiometricCropUtils.extractSquareFaceCrop(fullBitmap, box, 1.25f)
            
            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            val mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

            val pW = if (previewWidth > 0f) previewWidth else fullBitmap.width.toFloat()
            val pH = if (previewHeight > 0f) previewHeight else fullBitmap.height.toFloat()
            val scale = maxOf(pW / fullBitmap.width.toFloat(), pH / fullBitmap.height.toFloat())
            val dx = (pW - fullBitmap.width.toFloat() * scale) / 2f
            val dy = (pH - fullBitmap.height.toFloat() * scale) / 2f

            fun mapPt(pt: android.graphics.PointF?): android.graphics.PointF? {
                if (pt == null) return null
                val x = if (isFrontCamera) (fullBitmap.width - pt.x) * scale + dx else pt.x * scale + dx
                val y = pt.y * scale + dy
                return android.graphics.PointF(x, y)
            }

            val pts5List = listOfNotNull(
                mapPt(leftEye),
                mapPt(rightEye),
                mapPt(nose),
                mapPt(mouthL),
                mapPt(mouthR)
            )

            val pts5Array = if (leftEye != null && rightEye != null && nose != null && mouthL != null && mouthR != null) {
                arrayOf(rightEye, leftEye, nose, mouthR, mouthL)
            } else null

            if (newCrop != null) {
                synchronized(this) {
                    val oldCrop = latestFaceCrop
                    latestFaceCrop = newCrop
                    if (oldCrop != null && oldCrop != newCrop && !oldCrop.isRecycled) {
                        oldCrop.recycle()
                    }
                    val oldFull = latestFullBitmap
                    latestFullBitmap = fullBitmap.copy(fullBitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    if (oldFull != null && oldFull != latestFullBitmap && !oldFull.isRecycled) {
                        oldFull.recycle()
                    }
                    latestLandmarks5Pts = pts5Array
                }
            }

            val mappedBounds = if (isFrontCamera) {
                androidx.compose.ui.geometry.Rect(
                    left = (fullBitmap.width - box.right) * scale + dx,
                    top = box.top * scale + dy,
                    right = (fullBitmap.width - box.left) * scale + dx,
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

            // Map ML Kit dense 133-point facial contours to preview coordinates
            val contoursMap = HashMap<Int, List<Offset>>()
            for (contour in face.allContours) {
                val pts = contour.points.map { p ->
                    val cx = if (isFrontCamera) (fullBitmap.width - p.x) * scale + dx else p.x * scale + dx
                    val cy = p.y * scale + dy
                    Offset(cx, cy)
                }
                contoursMap[contour.faceContourType] = pts
            }

            val visualItem = FaceGeometryVisualData(
                bounds = mappedBounds,
                yaw = if (isFrontCamera) -rawYaw else rawYaw,
                pitch = latestFacePitch,
                roll = face.headEulerAngleZ,
                landmarks5Pts = if (pts5List.isNotEmpty()) pts5List.toTypedArray() else null,
                contours = contoursMap,
                meshResult = null,
                faceMap3DMM = null,
                gazeResult = null,
                attributes = null,
                studentName = _uiState.value.fullName.ifBlank { "ENROLLING" },
                studentRoll = _uiState.value.rollNumber,
                isLive = true,
                isFrontCamera = isFrontCamera
            )

            val yawStr = "%.1f".format(latestFaceYaw)
            val pitchStr = "%.1f".format(latestFacePitch)

            if (isAligned) {
                val now = System.currentTimeMillis()
                if (alignmentStartTime == 0L) {
                    alignmentStartTime = now
                }
                val elapsed = now - alignmentStartTime
                val remaining = (300L - elapsed).coerceAtLeast(0L)

                _uiState.update {
                    it.copy(
                        visualGeometryData = listOf(visualItem),
                        isPoseAligned = true,
                        yawGaugeText = "Yaw: ${yawStr}° • Pitch: ${pitchStr}° • Aligned! (${remaining}ms)",
                        autoCaptureCountdownMs = remaining
                    )
                }

                if (elapsed >= 300L && !isCapturing) {
                    captureCurrentAngle()
                }
            } else {
                alignmentStartTime = 0L
                val hint = when (step) {
                    1 -> "Face Forward (0°)"
                    2 -> "Turn Left (~15°) ←"
                    3 -> "Turn Right (~15°) →"
                    4 -> "Tilt Up (~10°) ↑"
                    5 -> "Tilt Down (~10°) ↓"
                    else -> "Complete"
                }
                _uiState.update {
                    it.copy(
                        visualGeometryData = listOf(visualItem),
                        isPoseAligned = false,
                        yawGaugeText = "Yaw: ${yawStr}° • Pitch: ${pitchStr}° • $hint",
                        autoCaptureCountdownMs = 300L
                    )
                }
            }
        } catch (t: Throwable) {
            // Guard against frame concurrency
        } finally {
            fullBitmap.recycle()
        }
    }

    fun captureCurrentAngle(context: Context? = null) {
        if (isCapturing) return
        val roll = _uiState.value.rollNumber.trim()
        val step = _uiState.value.currentStep
        if (step > 5) return

        val (cropSnapshot, fullSnap, lmkSnap) = synchronized(this) {
            val src = latestFaceCrop
            val cropBmp = if (src != null && !src.isRecycled) {
                try {
                    Bitmap.createScaledBitmap(src, 112, 112, true)
                } catch (e: Exception) {
                    null
                }
            } else null
            val fullBmp = latestFullBitmap
            val copyFull = if (fullBmp != null && !fullBmp.isRecycled) {
                try {
                    fullBmp.copy(fullBmp.config ?: Bitmap.Config.ARGB_8888, false)
                } catch (_: Exception) {
                    null
                }
            } else null
            Triple(cropBmp, copyFull, latestLandmarks5Pts)
        }

        if (cropSnapshot == null) {
            if (context != null) {
                Toast.makeText(context, "No face detected in camera frame. Please center your face inside the circle.", Toast.LENGTH_SHORT).show()
            }
            return
        }

        isCapturing = true
        alignmentStartTime = 0L
        BiometricSoundboard.playAngleCaptured()

        viewModelScope.launch(Dispatchers.Default) {
            try {
                // 1. Umeyama 5-point alignment on full camera frame
                var embeddingInputBitmap: Bitmap = cropSnapshot
                var isTemporaryAligned = false
                var alignmentResidual = 0f

                if (fullSnap != null && lmkSnap != null) {
                    val alignmentResult = com.omniface.ai.ml.UmeyamaSimilarityTransform.alignFace5Points(fullSnap, lmkSnap, 112, 112)
                    if (alignmentResult != null) {
                        alignmentResidual = alignmentResult.alignmentError
                        if (alignmentResult.alignmentError < 18.0f) {
                            embeddingInputBitmap = alignmentResult.alignedBitmap
                            isTemporaryAligned = true
                        }
                    }
                }

                // 2. Execute Unified Registration ML Model (Anti-Spoof + CavaFace + 3DMM + Attrib + Gaze)
                val regResult = unifiedEngine.processRegistrationFace(
                    faceCrop = cropSnapshot,
                    alignedFace = embeddingInputBitmap
                )

                if (regResult == null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(OmniFaceApplication.instance, "AI Engine initialization error. Please retry.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 3. Security Gate 1: MiniFASNetV2 Anti-Spoofing (Passive PAD)
                if (!regResult.passivePad.isLive) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(OmniFaceApplication.instance, "❌ Enrollment Rejected: ${regResult.passivePad.attackTypeDescription} (Spoof: ${(regResult.passivePad.spoofProbability * 100).toInt()}%)", Toast.LENGTH_LONG).show()
                        BiometricSoundboard.playSpoofAlert()
                    }
                    return@launch
                }

                // 4. Security Gate 2: FaceMap 3DMM Depth Variance
                if (!regResult.map3d.isTrue3DSurface) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(OmniFaceApplication.instance, "❌ Enrollment Rejected: 2D flat presentation attack detected. Real face required.", Toast.LENGTH_LONG).show()
                        BiometricSoundboard.playSpoofAlert()
                    }
                    return@launch
                }

                // 5. Security Gate 3: FaceAttribNet Sunglasses / Mask Occlusion
                val sunglasses = regResult.attributes.sunglassesScore
                val mask = regResult.attributes.maskScore
                if (sunglasses > 0.75f) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(OmniFaceApplication.instance, "❌ Dark Sunglasses detected: Please remove sunglasses for enrollment.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (mask > 0.75f) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(OmniFaceApplication.instance, "❌ Face mask detected: Please remove face covering for enrollment.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 6. Security Gate 4: Photometric & Signal Quality Assessment
                val qualityReport = qualityChecker.checkFaceQuality(embeddingInputBitmap)
                if (!qualityReport.isGoodQuality) {
                    val msg = when {
                        qualityReport.blurScore < 1.5f -> "Image is blurry — hold still"
                        qualityReport.brightnessScore < 15f -> "Lighting too dark"
                        qualityReport.brightnessScore > 245f -> "Lighting too bright"
                        else -> "Insufficient image quality"
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(OmniFaceApplication.instance, "❌ Quality Gate Failed: $msg", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                // 7. Extract High-Precision CavaFace 512-D Embedding (with flip-augmentation on Step 1)
                val embedding = if (step == 1) {
                    val flipMatrix = Matrix().apply { preScale(-1.0f, 1.0f) }
                    val flipped = try {
                        Bitmap.createBitmap(embeddingInputBitmap, 0, 0, embeddingInputBitmap.width, embeddingInputBitmap.height, flipMatrix, true)
                    } catch (_: Throwable) { null }

                    if (flipped != null) {
                        val embFlipped = unifiedEngine.extractCavafaceEmbeddingOnly(flipped)
                        if (flipped != embeddingInputBitmap && !flipped.isRecycled) flipped.recycle()
                        val fused = FloatArray(512) { i -> (regResult.embedding512[i] + embFlipped[i]) * 0.5f }
                        unifiedEngine.l2Normalize(fused)
                    } else {
                        regResult.embedding512
                    }
                } else {
                    regResult.embedding512
                }

                val sharpness = (qualityReport.blurScore * 10f).coerceIn(0f, 100f)
                val lighting = (100f - kotlin.math.abs(qualityReport.brightnessScore - 128f) * 0.78f).coerceIn(0f, 100f)
                val angleQuality = if (qualityReport.isGoodQuality) 96.0f else 80.0f

                val csv = embedding.joinToString(",")
                val encryptedCsv = AndroidSecurityUtils.encrypt(csv)

                if (isTemporaryAligned && embeddingInputBitmap != cropSnapshot && !embeddingInputBitmap.isRecycled) {
                    embeddingInputBitmap.recycle()
                }
                fullSnap?.recycle()

                val angleLabel = when (step) {
                    1 -> "FRONTAL"
                    2 -> "LEFT_22"
                    3 -> "RIGHT_22"
                    4 -> "UP_16"
                    else -> "DOWN_16"
                }

                val template = FaceTemplateEntity(
                    id = UUID.randomUUID().toString(),
                    studentRoll = roll,
                    angleType = angleLabel,
                    embeddingEncryptedCsv = encryptedCsv,
                    isEncrypted = true,
                    qualityScore = angleQuality,
                    sharpnessScore = sharpness,
                    lightingScore = lighting,
                    consistencyScore = 100.0f
                )
                capturedTemplates.add(template)
                capturedEmbeddings.add(embedding)
                capturedQualityScores.add(angleQuality)

                val thumb = Bitmap.createScaledBitmap(cropSnapshot, 120, 120, true)
                val updatedThumbnails = _uiState.value.capturedThumbnails + (angleLabel to thumb)

                _uiState.update {
                    when (step) {
                        1 -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 2,
                            angleGuideText = "Step 2 of 5: Turn head Left (~15° angle)",
                            isPoseAligned = false
                        )
                        2 -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 3,
                            angleGuideText = "Step 3 of 5: Turn head Right (~15° angle)",
                            isPoseAligned = false
                        )
                        3 -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 4,
                            angleGuideText = "Step 4 of 5: Tilt head Up (~10° angle)",
                            isPoseAligned = false
                        )
                        4 -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 5,
                            angleGuideText = "Step 5 of 5: Tilt head Down (~10° angle)",
                            isPoseAligned = false
                        )
                        else -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 6,
                            angleGuideText = "All 5 angles captured! Computing Quality-Weighted Master Centroid...",
                            isPoseAligned = false
                        )
                    }
                }

                if (step == 5) {
                    if (capturedEmbeddings.isNotEmpty()) {
                        val (masterCentroid, consistencyMatrix) = com.omniface.ai.ml.RegistrationQualityEvaluator.computeQualityWeightedTemplate(
                            embeddings = capturedEmbeddings,
                            qualityScores = capturedQualityScores
                        )
                        val centroidCsv = masterCentroid.joinToString(",")
                        val encryptedCentroidCsv = AndroidSecurityUtils.encrypt(centroidCsv)
                        val masterTemplate = FaceTemplateEntity(
                            id = UUID.randomUUID().toString(),
                            studentRoll = roll,
                            angleType = "MASTER_CENTROID",
                            embeddingEncryptedCsv = encryptedCentroidCsv,
                            isEncrypted = true,
                            qualityScore = 100.0f,
                            sharpnessScore = 100.0f,
                            lightingScore = 100.0f,
                            consistencyScore = consistencyMatrix.averageSimilarity * 100.0f
                        )
                        capturedTemplates.add(masterTemplate)
                    }
                    saveCompletedEnrollment()
                }
            } catch (t: Throwable) {
                // Biometric capture fallback
            } finally {
                cropSnapshot.recycle()
                isCapturing = false
            }
        }
    }

    private fun saveCompletedEnrollment() {
        _uiState.update { it.copy(isSaving = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val roll = _uiState.value.rollNumber.trim()
            val name = _uiState.value.fullName.trim()
            val dept = _uiState.value.department.trim()
            val sem = _uiState.value.semester.trim()

            val student = StudentEntity(
                rollNumber = roll,
                fullName = name,
                role = _uiState.value.role,
                department = dept,
                semester = sem
            )

            db.studentDao().enrollStudentWithTemplates(student, capturedTemplates)

            BiometricSoundboard.playMatchSuccess()

            _uiState.update {
                it.copy(
                    isSaving = false,
                    stage = EnrollmentStage.ENROLLMENT_SUCCESS
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        cameraExecutor.shutdown()
        synchronized(this) {
            latestFaceCrop?.let { if (!it.isRecycled) it.recycle() }
            latestFaceCrop = null
            latestFullBitmap?.let { if (!it.isRecycled) it.recycle() }
            latestFullBitmap = null
        }
    }
}

@androidx.annotation.OptIn(
    androidx.camera.core.ExperimentalGetImage::class,
    androidx.camera.camera2.interop.ExperimentalCamera2Interop::class
)
@Composable
fun EnrollmentScreen(
    viewModel: EnrollmentViewModel,
    onNavigateToScanner: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Directory, 1: New Registration

    // Intercept back gesture on active modals, sheets, and registration studios in prioritized order
    BackHandler(enabled = state.isDeleteConfirmOpen) {
        viewModel.closeDeleteConfirmDialog()
    }
    BackHandler(enabled = state.isEditProfileOpen && !state.isDeleteConfirmOpen) {
        viewModel.closeEditProfileDialog()
    }
    BackHandler(enabled = state.selectedStudentForManage != null && !state.isEditProfileOpen && !state.isDeleteConfirmOpen) {
        viewModel.closeStudentProfile()
    }
    BackHandler(enabled = state.stage != EnrollmentStage.REGISTRATION_FORM) {
        viewModel.cancelBiometricStudio()
    }
    BackHandler(enabled = selectedTab == 1 && state.stage == EnrollmentStage.REGISTRATION_FORM && state.selectedStudentForManage == null && !state.isEditProfileOpen && !state.isDeleteConfirmOpen) {
        selectedTab = 0
    }

    LaunchedEffect(Unit) {
        viewModel.initEngine(context)
    }

    when (state.stage) {
        EnrollmentStage.REGISTRATION_FORM -> {
            RegistrationFormView(
                viewModel = viewModel,
                state = state,
                isDark = isDark,
                context = context,
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )
        }
        EnrollmentStage.BIOMETRIC_STUDIO -> {
            BiometricStudioView(viewModel = viewModel, state = state, isDark = isDark)
        }
        EnrollmentStage.ENROLLMENT_SUCCESS -> {
            EnrollmentSuccessView(
                viewModel = viewModel,
                state = state,
                isDark = isDark,
                onNavigateToScanner = onNavigateToScanner,
                onEnrollAnother = { selectedTab = 1 },
                onViewDirectory = { selectedTab = 0 }
            )
        }
    }

    if (state.showPaywall) {
        PaywallBottomSheet(
            triggerReason = state.paywallReason,
            onDismiss = { viewModel.dismissPaywall() },
            onUpgradeSuccess = { viewModel.dismissPaywall() }
        )
    }
}

