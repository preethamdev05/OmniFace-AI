package com.omniface.ai.ui.enrollment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import com.omniface.ai.hardware.NpuHardwareDetector
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.util.Range
import android.util.Size
import android.widget.Toast
import androidx.annotation.OptIn
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
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
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.ml.*
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import androidx.lifecycle.compose.collectAsStateWithLifecycle

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
    val department: String = "AI & Biometrics",
    val semester: String = "VI",
    val enrolledStudentsList: List<StudentEntity> = emptyList(),
    val lensFacing: Int = CameraSelector.LENS_FACING_FRONT,
    val isQualcommDevice: Boolean = NpuHardwareDetector.isQualcommAiHubDevice()
)

class EnrollmentViewModel : ViewModel() {
    private val db = OmniFaceApplication.instance.database
    private val _uiState = MutableStateFlow(EnrollmentUiState())
    val uiState: StateFlow<EnrollmentUiState> = _uiState.asStateFlow()

    private var recognitionEngine: FaceRecognitionEngine? = null
    private val qualcommIntelligenceEngine = try {
        QualcommFaceIntelligenceEngine(OmniFaceApplication.instance)
    } catch (_: Throwable) {
        null
    }
    private val qualityChecker = QualityChecker()
    val cameraExecutor = Executors.newSingleThreadExecutor()


    private var latestFullBitmap: Bitmap? = null
    private var latestFaceCrop: Bitmap? = null
    private var latestFaceYaw: Float = 0.0f
    private var latestFacePitch: Float = 0.0f
    private var isCapturing = false
    private var alignmentStartTime: Long = 0L

    private val capturedTemplates = mutableListOf<FaceTemplateEntity>()
    private val capturedEmbeddings = mutableListOf<FloatArray>()
    private val capturedQualityScores = mutableListOf<Float>()

    init {
        observeEnrolledStudents()
    }

    private fun observeEnrolledStudents() {
        viewModelScope.launch {
            db.studentDao().getAllStudentsFlow().collect { list ->
                _uiState.update { it.copy(enrolledStudentsList = list) }
            }
        }
    }

    fun initEngine(context: Context) {
        if (recognitionEngine == null) {
            recognitionEngine = FaceRecognitionEngine(context)
        }
    }

    fun updateForm(roll: String? = null, name: String? = null, dept: String? = null, sem: String? = null) {
        _uiState.update {
            it.copy(
                rollNumber = roll ?: it.rollNumber,
                fullName = name ?: it.fullName,
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

    fun startBiometricStudio(context: Context) {
        val roll = _uiState.value.rollNumber.trim()
        val name = _uiState.value.fullName.trim()

        if (roll.isBlank() || name.isBlank()) {
            Toast.makeText(context, "Please enter Student Full Name and Roll Number", Toast.LENGTH_SHORT).show()
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
            1 -> kotlin.math.abs(yaw) <= 15.0f && kotlin.math.abs(pitch) <= 15.0f // Frontal
            2 -> yaw <= -10.0f && kotlin.math.abs(pitch) <= 22.0f                 // Left ~15-30°
            3 -> yaw >= 10.0f && kotlin.math.abs(pitch) <= 22.0f                  // Right ~15-30°
            4 -> pitch >= 8.0f && kotlin.math.abs(yaw) <= 22.0f                   // Up ~10-25°
            5 -> pitch <= -8.0f && kotlin.math.abs(yaw) <= 22.0f                  // Down ~10-25°
            else -> false
        }
    }

    fun processCameraFrame(face: Face, fullBitmap: Bitmap?) {
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
            latestFaceYaw = if (isFrontCamera) -rawYaw else rawYaw
            latestFacePitch = face.headEulerAngleX

            val step = _uiState.value.currentStep
            val isAligned = isPoseInTargetEnvelope(latestFaceYaw, latestFacePitch, step)

            val box = face.boundingBox
            val newCrop = BiometricCropUtils.extractSquareFaceCrop(fullBitmap, box, 1.25f)
            if (newCrop != null) {
                synchronized(this) {
                    val oldCrop = latestFaceCrop
                    latestFaceCrop = newCrop
                    if (oldCrop != null && !oldCrop.isRecycled) {
                        oldCrop.recycle()
                    }
                }
            }

            var meshResult: MediaPipeMeshResult? = null
            var map3dResult: FaceMap3DMMResult? = null
            var gazeResult: EyeGazeResult? = null
            var attrResult: FaceAttributesResult? = null

            if (newCrop != null && qualcommIntelligenceEngine != null && qualcommIntelligenceEngine.isSuiteLoaded) {
                try {
                    meshResult = qualcommIntelligenceEngine.estimateMediaPipeFaceMesh(newCrop)
                    map3dResult = qualcommIntelligenceEngine.estimate3dFaceMap(newCrop)
                    gazeResult = qualcommIntelligenceEngine.estimateEyeGaze(newCrop)
                    attrResult = qualcommIntelligenceEngine.detectFaceAttributes(newCrop)
                } catch (_: Throwable) {}
            }

            val visualItem = FaceGeometryVisualData(
                bounds = androidx.compose.ui.geometry.Rect(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()),
                yaw = latestFaceYaw,
                pitch = latestFacePitch,
                roll = face.headEulerAngleZ,
                meshResult = meshResult,
                faceMap3DMM = map3dResult,
                gazeResult = gazeResult,
                attributes = attrResult,
                isLive = true
            )

            val yawStr = "%.1f".format(latestFaceYaw)
            val pitchStr = "%.1f".format(latestFacePitch)

            if (isAligned) {
                val now = System.currentTimeMillis()
                if (alignmentStartTime == 0L) {
                    alignmentStartTime = now
                }
                val elapsed = now - alignmentStartTime
                val remaining = (350L - elapsed).coerceAtLeast(0L)

                _uiState.update {
                    it.copy(
                        visualGeometryData = listOf(visualItem),
                        isPoseAligned = true,
                        yawGaugeText = "Yaw: ${yawStr}° • Pitch: ${pitchStr}° • Aligned! (${remaining}ms)",
                        autoCaptureCountdownMs = remaining
                    )
                }

                if (elapsed >= 350L && !isCapturing) {
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
                        autoCaptureCountdownMs = 350L
                    )
                }
            }
        } catch (t: Throwable) {
            // Guard against frame concurrency
        } finally {
            fullBitmap.recycle()
        }
    }

    fun captureCurrentAngle() {
        if (isCapturing) return
        val roll = _uiState.value.rollNumber.trim()
        val step = _uiState.value.currentStep
        if (step > 5) return

        val cropSnapshot = synchronized(this) {
            val src = latestFaceCrop
            if (src != null && !src.isRecycled) {
                try {
                    Bitmap.createScaledBitmap(src, 112, 112, true)
                } catch (e: Exception) {
                    null
                }
            } else null
        } ?: return

        isCapturing = true
        alignmentStartTime = 0L
        BiometricSoundboard.playAngleCaptured()

        viewModelScope.launch(Dispatchers.Default) {
            try {
                val engine = recognitionEngine ?: return@launch
                // Use flip-augmented embedding at enrollment for richer templates.
                // This averages original + horizontally flipped inference — enrollment
                // runs only once per angle so the 2x inference cost is acceptable.
                val embedding = engine.extractEmbeddingWithFlipAugmentation(cropSnapshot)
                val csv = embedding.joinToString(",")
                val encryptedCsv = AndroidSecurityUtils.encrypt(csv)

                val qualityReport = qualityChecker.checkFaceQuality(cropSnapshot)
                val sharpness = (qualityReport.blurScore * 10f).coerceIn(0f, 100f)
                val lighting = (100f - kotlin.math.abs(qualityReport.brightnessScore - 128f) * 0.78f).coerceIn(0f, 100f)
                val angleQuality = if (qualityReport.isGoodQuality) 96.0f else 80.0f

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

                val thumb = Bitmap.createScaledBitmap(cropSnapshot, 120, 120, true)
                val updatedThumbnails = _uiState.value.capturedThumbnails + (angleLabel to thumb)

                capturedEmbeddings.add(embedding)
                capturedQualityScores.add(angleQuality)

                _uiState.update {
                    when (step) {
                        1 -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 2,
                            angleGuideText = "Step 2 of 5: Turn head Left (~22.5° angle)",
                            isPoseAligned = false
                        )
                        2 -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 3,
                            angleGuideText = "Step 3 of 5: Turn head Right (~22.5° angle)",
                            isPoseAligned = false
                        )
                        3 -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 4,
                            angleGuideText = "Step 4 of 5: Tilt head Up (~16° angle)",
                            isPoseAligned = false
                        )
                        4 -> it.copy(
                            capturedThumbnails = updatedThumbnails,
                            currentStep = 5,
                            angleGuideText = "Step 5 of 5: Tilt head Down (~16° angle)",
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
        recognitionEngine?.close()
        synchronized(this) {
            latestFaceCrop?.let { if (!it.isRecycled) it.recycle() }
            latestFaceCrop = null
            latestFullBitmap?.let { if (!it.isRecycled) it.recycle() }
            latestFullBitmap = null
        }
    }
}

@Composable
fun EnrollmentScreen(
    viewModel: EnrollmentViewModel,
    onNavigateToScanner: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val isDark = LocalThemeIsDark.current
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.initEngine(context)
    }

    when (state.stage) {
        EnrollmentStage.REGISTRATION_FORM -> {
            RegistrationFormView(viewModel = viewModel, state = state, isDark = isDark, context = context)
        }
        EnrollmentStage.BIOMETRIC_STUDIO -> {
            BiometricStudioView(viewModel = viewModel, state = state, isDark = isDark)
        }
        EnrollmentStage.ENROLLMENT_SUCCESS -> {
            EnrollmentSuccessView(
                viewModel = viewModel,
                state = state,
                isDark = isDark,
                onNavigateToScanner = onNavigateToScanner
            )
        }
    }
}

@Composable
private fun RegistrationFormView(
    viewModel: EnrollmentViewModel,
    state: EnrollmentUiState,
    isDark: Boolean,
    context: Context
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 20.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Column {
                Text(
                    text = "STUDENTS",
                    color = omniTextMuted(isDark),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Students & Enrollment",
                    color = omniTextPrimary(isDark),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold
                )
            }
        }

        // Registration Input Card
        item {
            IOSCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "STUDENT DETAILS",
                    color = omniTextMuted(isDark),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(14.dp))

                // Full Name
                OutlinedTextField(
                    value = state.fullName,
                    onValueChange = { viewModel.updateForm(name = it) },
                    label = { Text("Full Name", fontSize = 12.sp) },
                    placeholder = { Text("e.g. John Doe", color = omniTextMuted(isDark), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = omniCyan(isDark),
                        unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                        focusedTextColor = omniTextPrimary(isDark),
                        unfocusedTextColor = omniTextPrimary(isDark)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Roll Number
                OutlinedTextField(
                    value = state.rollNumber,
                    onValueChange = { viewModel.updateForm(roll = it) },
                    label = { Text("Roll Number / Student ID", fontSize = 12.sp) },
                    placeholder = { Text("e.g. CS2024-042", color = omniTextMuted(isDark), fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = omniCyan(isDark),
                        unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                        focusedTextColor = omniTextPrimary(isDark),
                        unfocusedTextColor = omniTextPrimary(isDark)
                    )
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedTextField(
                        value = state.department,
                        onValueChange = { viewModel.updateForm(dept = it) },
                        label = { Text("Department", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1.5f),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = omniCyan(isDark),
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                            focusedTextColor = omniTextPrimary(isDark),
                            unfocusedTextColor = omniTextPrimary(isDark)
                        )
                    )

                    OutlinedTextField(
                        value = state.semester,
                        onValueChange = { viewModel.updateForm(sem = it) },
                        label = { Text("Semester", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = omniCyan(isDark),
                            unfocusedBorderColor = if (isDark) Color(0x33FFFFFF) else Color(0x22000000),
                            focusedTextColor = omniTextPrimary(isDark),
                            unfocusedTextColor = omniTextPrimary(isDark)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Quick Department Suggestion Chips
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val depts = listOf("AI & Biometrics", "Computer Science", "Info Science", "Electronics")
                    items(depts) { dept ->
                        val isSelected = state.department == dept
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSelected) omniCyan(isDark).copy(alpha = 0.20f) else (if (isDark) Color(0x1AFFFFFF) else Color(0x0D000000)))
                                .border(0.5.dp, if (isSelected) omniCyan(isDark) else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable { viewModel.updateForm(dept = dept) }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(dept, color = if (isSelected) omniCyan(isDark) else omniTextMuted(isDark), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Action Button: Continue to Face Enrollment
                CupertinoButton(
                    text = "Continue to Face Enrollment",
                    onClick = { viewModel.startBiometricStudio(context) }
                )
            }
        }

        // Registered Directory List
        item {
            IOSCard(modifier = Modifier.fillMaxWidth()) {
                SectionHeader(
                    text = "REGISTERED STUDENTS (${state.enrolledStudentsList.size})"
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (state.enrolledStudentsList.isEmpty()) {
                    EmptyState(
                        icon = Icons.Default.PersonAdd,
                        title = "No identities enrolled yet",
                        subtitle = "Your enrolled students will appear here."
                    )
                } else {
                    state.enrolledStudentsList.forEachIndexed { index, student ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(omniCyan(isDark).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = student.fullName.take(1).uppercase(),
                                        color = omniCyan(isDark),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = student.fullName,
                                        color = omniTextPrimary(isDark),
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${student.rollNumber} • ${student.department} (${student.semester})",
                                        color = omniTextMuted(isDark),
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            IconButton(
                                onClick = { viewModel.deleteStudent(student) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DeleteOutline,
                                    contentDescription = "Delete Student",
                                    tint = CrimsonCore,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        if (index < state.enrolledStudentsList.size - 1) {
                            HorizontalDivider(color = if (isDark) Color(0x14FFFFFF) else Color(0x14000000), thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun BiometricStudioView(
    viewModel: EnrollmentViewModel,
    state: EnrollmentUiState,
    isDark: Boolean
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(state.currentStep) {
        if (state.currentStep > 1) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // Dual-Camera Viewport
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
                                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                                .enableTracking()
                                .build()
                        )

                        val analysisBuilder = ImageAnalysis.Builder()
                            .setResolutionSelector(highResSelector)
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                        val ext = Camera2Interop.Extender(analysisBuilder)
                        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        ext.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                        ext.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                        ext.setCaptureRequestOption(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_HIGH_QUALITY)
                        ext.setCaptureRequestOption(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                        ext.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
                        ext.setCaptureRequestOption(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                        ext.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)
                        ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))

                        val imageAnalysis = analysisBuilder.build()

                        imageAnalysis.setAnalyzer(viewModel.cameraExecutor) { imageProxy ->
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                val fullBitmap = imageProxyToBitmap(imageProxy)

                                faceDetector.process(image)
                                    .addOnSuccessListener { faces ->
                                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                                        if (face != null && fullBitmap != null) {
                                            viewModel.processCameraFrame(face, fullBitmap)
                                        } else {
                                            fullBitmap?.recycle()
                                        }
                                    }
                                    .addOnFailureListener {
                                        fullBitmap?.recycle()
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
                        } catch (e: Exception) {
                            // Bind error
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )
        }

        // 60 FPS Real-Time 3D Face Mesh Wireframe & Explainable Diagnostics
        FaceDiagnosticsOverlay(
            visualData = state.visualGeometryData,
            showMeshWireframe = true,
            showPoseAxes = true,
            showGazeRays = true,
            show3DMMTopography = true,
            modifier = Modifier.fillMaxSize()
        )

        // Face ID Spherical Reticle Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height * 0.40f)
            val radius = size.width * 0.38f

            // Background Dim Mask
            drawCircle(
                color = Color.Black.copy(alpha = 0.55f),
                radius = size.width * 1.5f,
                center = center
            )

            // Clear Viewport Circle
            drawCircle(
                color = Color.Transparent,
                radius = radius,
                center = center,
                blendMode = androidx.compose.ui.graphics.BlendMode.Clear
            )

            // Segmented Progress Rings (5 Segments)
            val segmentAngle = 360f / 5f
            for (i in 0 until 5) {
                val isCompleted = i < state.currentStep - 1
                val isCurrent = i == state.currentStep - 1
                val color = when {
                    isCompleted -> Color(0xFF10B981)
                    isCurrent && state.isPoseAligned -> Color(0xFF38BDF8)
                    isCurrent -> Color(0xFFF59E0B)
                    else -> Color(0x33FFFFFF)
                }

                drawArc(
                    color = color,
                    startAngle = (i * segmentAngle) - 90f + 4f,
                    sweepAngle = segmentAngle - 8f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
        }

        // Top Navigation Bar (Cancel / Title / Flip Camera)
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xCC0F172A))
                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                    .clickable { viewModel.cancelBiometricStudio() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("✕ Cancel", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.isQualcommDevice) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xCC0F172A))
                            .border(1.dp, Color(0xFF10B981).copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "⚡ HEXAGON NPU • 45 TOPS",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Step Indicator Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xCC0F172A))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Angle ${state.currentStep.coerceAtMost(5)} of 5",
                        color = Color(0xFF38BDF8),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Front / Back Dual-Camera Switcher
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color(0xCC0F172A))
                        .border(1.dp, Color(0x33FFFFFF), CircleShape)
                        .clickable { viewModel.toggleLensFacing() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FlipCameraAndroid,
                        contentDescription = "Flip Camera Lens",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Bottom Controls HUD
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 30.dp, start = 20.dp, end = 20.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Directional Guidance Pill
            FrostedGlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.angleGuideText,
                        color = omniTextPrimary(isDark),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.yawGaugeText,
                        color = if (state.isPoseAligned) omniEmerald(isDark) else omniTextMuted(isDark),
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Captured Thumbnails Row
            if (state.capturedThumbnails.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    items(state.capturedThumbnails) { (label, thumb) ->
                        if (!thumb.isRecycled) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(2.dp, Color(0xFF10B981), RoundedCornerShape(10.dp))
                            ) {
                                Image(
                                    bitmap = thumb.asImageBitmap(),
                                    contentDescription = label,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }

            // Action Buttons Row (Capture + Retake + Reset)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (state.currentStep > 1) {
                    Button(
                        onClick = { viewModel.retakeCurrentAngle() },
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF334155),
                            contentColor = Color.White
                        )
                    ) {
                        Text("🔄 Retake", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Button(
                    onClick = { viewModel.captureCurrentAngle() },
                    modifier = Modifier
                        .weight(2f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.isPoseAligned) Color(0xFF10B981) else Color(0xFF0284C7)
                    )
                ) {
                    Text(
                        text = if (state.isPoseAligned) "📸 Capture (Aligned)" else "📸 Capture Angle",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun EnrollmentSuccessView(
    viewModel: EnrollmentViewModel,
    state: EnrollmentUiState,
    isDark: Boolean,
    onNavigateToScanner: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(omniBackgroundBrush(isDark))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(90.dp)
                .clip(CircleShape)
                .background(omniEmerald(isDark).copy(alpha = 0.18f))
                .border(2.dp, omniEmerald(isDark), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = omniEmerald(isDark),
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Face ID Enrolled Successfully",
            color = omniTextPrimary(isDark),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${state.fullName} (${state.rollNumber})",
            color = omniCyan(isDark),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = "5-Angle Biometric Templates encrypted with Hardware KeyStore AES-256-GCM.",
            color = omniTextMuted(isDark),
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        CupertinoButton(
            text = "📹 Test in Live Scanner",
            onClick = {
                viewModel.resetForNextStudent()
                onNavigateToScanner()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        CupertinoButton(
            text = "➕ Enroll Another Identity",
            isSecondary = true,
            onClick = { viewModel.resetForNextStudent() }
        )
    }
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
    } catch (e: Exception) {
        null
    }
}
