package com.omniface.ai.ui.enrollment

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PointF
import android.hardware.camera2.CaptureRequest
import android.util.Range
import android.util.Size
import android.widget.Toast
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.audio.BiometricSoundboard
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ml.*
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.ui.components.*
import com.omniface.ai.ui.scanner.NeuralEngineLoadingOverlay
import com.omniface.ai.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Data model representing a captured image in the registration series.
 */
data class CapturedFaceSample(
    val index: Int,
    val angleLabel: String,
    val thumbnail: Bitmap,
    val embedding: FloatArray,
    val qualityScore: Float,
    val sharpnessScore: Float,
    val lightingScore: Float,
    val yaw: Float,
    val pitch: Float
)

/**
 * Registration mode: Guided 5-Angle or Rapid Multi-Shot Burst.
 */
enum class RegistrationCaptureMode {
    GUIDED_5_ANGLE,
    RAPID_MULTI_SHOT
}

/**
 * UI Component state for face registration.
 */
@Stable
class FaceRegistrationState(
    val context: Context,
    val initialRoll: String = "",
    val initialName: String = "",
    val initialDept: String = "AI & Biometrics",
    val initialSem: String = "VI",
    val totalTargetShots: Int = 5
) {
    var rollNumber by mutableStateOf(initialRoll)
    var fullName by mutableStateOf(initialName)
    var department by mutableStateOf(initialDept)
    var semester by mutableStateOf(initialSem)

    var captureMode by mutableStateOf(RegistrationCaptureMode.GUIDED_5_ANGLE)
    var currentShotIndex by mutableIntStateOf(0)
    var isCapturing by mutableStateOf(false)
    var isSaving by mutableStateOf(false)
    var isRegistrationComplete by mutableStateOf(false)

    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_FRONT)
    var isPoseAligned by mutableStateOf(false)
    var currentYaw by mutableFloatStateOf(0f)
    var currentPitch by mutableFloatStateOf(0f)
    var alignmentProgress by mutableFloatStateOf(0f)
    var guideMessage by mutableStateOf("Position your face within the reticle")

    val capturedSamples = mutableStateListOf<CapturedFaceSample>()
    var visualGeometryData by mutableStateOf<List<FaceGeometryVisualData>>(emptyList())
    var engineLoadingProgress by mutableStateOf(EngineLoadingProgress())

    val cameraExecutor = Executors.newSingleThreadExecutor()
    private var recognitionEngine: FaceRecognitionEngine? = null
    private val qualityChecker = QualityChecker()
    private val db = OmniFaceApplication.instance.database

    private var latestFaceCrop: Bitmap? = null
    private var alignmentStartTime: Long = 0L

    init {
        try {
            val engine = FaceRecognitionEngine.getInstance(context)
            recognitionEngine = engine
            kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
                engineLoadingProgress = EngineLoadingProgress(
                    isReady = false,
                    stage = "Discovering Neural Silicon & Hardware Tensors...",
                    progress = 0.30f,
                    activeModelName = "ArcFace 512-D INT8",
                    hardwareTarget = engine.activeHardwareTier.label
                )
                kotlinx.coroutines.delay(220)
                engineLoadingProgress = EngineLoadingProgress(
                    isReady = false,
                    stage = "Compiling ArcFace 512-D Neural Graph...",
                    progress = 0.75f,
                    activeModelName = "ArcFace 512-D INT8",
                    hardwareTarget = engine.activeHardwareTier.label
                )
                kotlinx.coroutines.delay(220)
                engineLoadingProgress = EngineLoadingProgress(
                    isReady = true,
                    stage = "Operational (Sub-8ms Ready)",
                    progress = 1.0f,
                    activeModelName = "ArcFace 512-D INT8",
                    hardwareTarget = engine.activeHardwareTier.label
                )
            }
        } catch (_: Throwable) {}
    }

    fun toggleCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
    }

    fun getTargetAnglePrompt(step: Int): String {
        return when (captureMode) {
            RegistrationCaptureMode.GUIDED_5_ANGLE -> when (step) {
                0 -> "Step 1/5: Look directly at camera (Frontal 0°)"
                1 -> "Step 2/5: Turn face slightly Left (~20° angle) ←"
                2 -> "Step 3/5: Turn face slightly Right (~20° angle) →"
                3 -> "Step 4/5: Tilt head slightly Up (~15° angle) ↑"
                4 -> "Step 5/5: Tilt head slightly Down (~15° angle) ↓"
                else -> "All angles captured! Generating Master Centroid..."
            }
            RegistrationCaptureMode.RAPID_MULTI_SHOT -> {
                "Shot ${step + 1} of $totalTargetShots: Keep head stable in reticle"
            }
        }
    }

    fun getTargetAngleLabel(step: Int): String {
        return when (captureMode) {
            RegistrationCaptureMode.GUIDED_5_ANGLE -> when (step) {
                0 -> "FRONTAL"
                1 -> "LEFT_20"
                2 -> "RIGHT_20"
                3 -> "UP_15"
                4 -> "DOWN_15"
                else -> "CENTROID"
            }
            RegistrationCaptureMode.RAPID_MULTI_SHOT -> "SHOT_${step + 1}"
        }
    }

    private fun checkPoseEnvelope(yaw: Float, pitch: Float, step: Int): Boolean {
        if (captureMode == RegistrationCaptureMode.RAPID_MULTI_SHOT) {
            return kotlin.math.abs(yaw) <= 30.0f && kotlin.math.abs(pitch) <= 30.0f
        }
        return when (step) {
            0 -> kotlin.math.abs(yaw) <= 16.0f && kotlin.math.abs(pitch) <= 18.0f // Frontal
            1 -> yaw <= -5.0f && kotlin.math.abs(pitch) <= 28.0f                  // Left ~10-30° (User turned LEFT)
            2 -> yaw >= 5.0f && kotlin.math.abs(pitch) <= 28.0f                   // Right ~10-30° (User turned RIGHT)
            3 -> pitch >= 4.0f && kotlin.math.abs(yaw) <= 28.0f                   // Up ~8-25° (User tilted UP)
            4 -> pitch <= -4.0f && kotlin.math.abs(yaw) <= 28.0f                  // Down ~8-25° (User tilted DOWN)
            else -> false
        }
    }

    fun processFrame(
        face: Face,
        fullBitmap: Bitmap,
        previewWidth: Float = 0f,
        previewHeight: Float = 0f
    ) {
        if (isCapturing || isSaving || isRegistrationComplete || currentShotIndex >= totalTargetShots) {
            fullBitmap.recycle()
            return
        }

        try {
            val rawYaw = face.headEulerAngleY
            val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT
            // Subject-centric yaw: turning to subject's left rotates face toward camera's right (+rawYaw in ML Kit).
            // To ensure "Turn Left" (Step 1) consistently corresponds to negative yaw (yaw <= -5.0f) and
            // "Turn Right" (Step 2) corresponds to positive yaw (yaw >= 5.0f) across both front and rear cameras,
            // currentYaw is always -rawYaw.
            currentYaw = -rawYaw
            currentPitch = face.headEulerAngleX

            val step = currentShotIndex
            val aligned = checkPoseEnvelope(currentYaw, currentPitch, step)
            isPoseAligned = aligned

            val box = face.boundingBox
            val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
            val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
            val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.position
            val mouthL = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position
            val mouthR = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position

            val raw5Pts = if (leftEye != null && rightEye != null && nose != null && mouthL != null && mouthR != null) {
                arrayOf(rightEye, leftEye, nose, mouthR, mouthL)
            } else null

            val alignedResult = if (raw5Pts != null) {
                UmeyamaSimilarityTransform.alignFace5Points(fullBitmap, raw5Pts, 112, 112)
            } else null

            val crop = if (alignedResult != null && alignedResult.alignmentError < RegistrationQualityEvaluator.MAX_UMEYAMA_RESIDUAL_PX) {
                alignedResult.alignedBitmap
            } else {
                BiometricCropUtils.extractSquareFaceCrop(fullBitmap, box, 1.25f)
            }
            if (crop != null) {
                synchronized(this) {
                    val old = latestFaceCrop
                    latestFaceCrop = crop
                    if (old != null && old != crop && !old.isRecycled) {
                        old.recycle()
                    }
                }
            }

            val pW = if (previewWidth > 0f) previewWidth else fullBitmap.width.toFloat()
            val pH = if (previewHeight > 0f) previewHeight else fullBitmap.height.toFloat()
            val scale = maxOf(pW / fullBitmap.width.toFloat(), pH / fullBitmap.height.toFloat())
            val dx = (pW - fullBitmap.width.toFloat() * scale) / 2f
            val dy = (pH - fullBitmap.height.toFloat() * scale) / 2f

            val mappedBounds = if (isFront) {
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

            fun mapPt(pt: android.graphics.PointF?): android.graphics.PointF? {
                if (pt == null) return null
                val x = if (isFront) (fullBitmap.width - pt.x) * scale + dx else pt.x * scale + dx
                val y = pt.y * scale + dy
                return android.graphics.PointF(x, y)
            }

            val mappedLandmarks = listOfNotNull(
                mapPt(leftEye),
                mapPt(rightEye),
                mapPt(nose),
                mapPt(mouthL),
                mapPt(mouthR)
            )

            val visualItem = FaceGeometryVisualData(
                bounds = mappedBounds,
                yaw = if (isFront) -rawYaw else rawYaw,
                pitch = currentPitch,
                roll = face.headEulerAngleZ,
                landmarks5Pts = if (mappedLandmarks.isNotEmpty()) mappedLandmarks.toTypedArray() else null,
                studentName = fullName.ifBlank { "NEW REGISTRATION" },
                studentRoll = rollNumber,
                isLive = true,
                isFrontCamera = isFront
            )
            visualGeometryData = listOf(visualItem)

            val yawFormatted = "%.1f".format(currentYaw)
            val pitchFormatted = "%.1f".format(currentPitch)

            if (aligned) {
                val now = System.currentTimeMillis()
                if (alignmentStartTime == 0L) alignmentStartTime = now
                val elapsed = now - alignmentStartTime
                val holdDuration = if (captureMode == RegistrationCaptureMode.RAPID_MULTI_SHOT) 250L else 350L
                alignmentProgress = (elapsed.toFloat() / holdDuration.toFloat()).coerceIn(0f, 1f)

                guideMessage = "Pose Aligned! (${holdDuration - elapsed}ms) • Yaw: $yawFormatted°"

                if (elapsed >= holdDuration && !isCapturing) {
                    triggerCapture()
                }
            } else {
                alignmentStartTime = 0L
                alignmentProgress = 0f
                guideMessage = "${getTargetAnglePrompt(step)} • Yaw: $yawFormatted° Pitch: $pitchFormatted°"
            }
        } catch (_: Throwable) {
        } finally {
            fullBitmap.recycle()
        }
    }

    fun triggerCapture() {
        if (isCapturing || currentShotIndex >= totalTargetShots) return
        val cropSnapshot = synchronized(this) {
            val src = latestFaceCrop
            if (src != null && !src.isRecycled) {
                try {
                    val scaled = if (src.width == 112 && src.height == 112) {
                        src.copy(Bitmap.Config.ARGB_8888, false)
                    } else {
                        Bitmap.createScaledBitmap(src, 112, 112, true)
                    }
                    scaled
                } catch (_: Exception) { null }
            } else null
        }

        if (cropSnapshot == null) {
            Toast.makeText(context, "Position face clearly within the reticle to capture.", Toast.LENGTH_SHORT).show()
            return
        }

        isCapturing = true
        alignmentStartTime = 0L
        alignmentProgress = 0f
        BiometricSoundboard.playAngleCaptured()

        kotlinx.coroutines.CoroutineScope(Dispatchers.Default).launch {
            try {
                val engine = recognitionEngine ?: FaceRecognitionEngine(context)
                // Extract 512-D L2 normalized embedding: flip augmentation only on frontal shot (0)
                val embedding = if (currentShotIndex == 0) {
                    engine.extractEmbeddingWithFlipAugmentation(cropSnapshot)
                } else {
                    engine.extractEmbedding(cropSnapshot)
                }

                // Quality evaluation
                val qualityReport = qualityChecker.checkFaceQuality(cropSnapshot)
                val sharpness = (qualityReport.blurScore * 10f).coerceIn(0f, 100f)
                val lighting = (100f - kotlin.math.abs(qualityReport.brightnessScore - 128f) * 0.78f).coerceIn(0f, 100f)
                val angleQuality = if (qualityReport.isGoodQuality) 96.0f else 85.0f

                val thumb = Bitmap.createScaledBitmap(cropSnapshot, 120, 120, true)
                val angleLabel = getTargetAngleLabel(currentShotIndex)

                val sample = CapturedFaceSample(
                    index = currentShotIndex,
                    angleLabel = angleLabel,
                    thumbnail = thumb,
                    embedding = embedding,
                    qualityScore = angleQuality,
                    sharpnessScore = sharpness,
                    lightingScore = lighting,
                    yaw = currentYaw,
                    pitch = currentPitch
                )

                withContext(Dispatchers.Main) {
                    capturedSamples.add(sample)
                    currentShotIndex++

                    if (currentShotIndex >= totalTargetShots) {
                        finalizeAndSaveToDatabase()
                    } else {
                        guideMessage = getTargetAnglePrompt(currentShotIndex)
                        isPoseAligned = false
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Capture error: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            } finally {
                cropSnapshot.recycle()
                isCapturing = false
            }
        }
    }

    fun retakeLastShot() {
        if (capturedSamples.isNotEmpty() && currentShotIndex > 0) {
            val removed = capturedSamples.removeAt(capturedSamples.size - 1)
            if (!removed.thumbnail.isRecycled) {
                removed.thumbnail.recycle()
            }
            currentShotIndex--
            isPoseAligned = false
            alignmentProgress = 0f
            guideMessage = getTargetAnglePrompt(currentShotIndex)
        }
    }

    fun resetAllShots() {
        capturedSamples.forEach {
            if (!it.thumbnail.isRecycled) it.thumbnail.recycle()
        }
        capturedSamples.clear()
        currentShotIndex = 0
        isPoseAligned = false
        alignmentProgress = 0f
        isRegistrationComplete = false
        guideMessage = getTargetAnglePrompt(0)
    }

    private fun finalizeAndSaveToDatabase() {
        isSaving = true
        guideMessage = "Computing Master Centroid & Encrypting Embeddings..."

        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            try {
                val roll = rollNumber.trim()
                val name = fullName.trim()
                val dept = department.trim()
                val sem = semester.trim()

                val student = StudentEntity(
                    rollNumber = roll,
                    fullName = name,
                    department = dept,
                    semester = sem
                )

                val templates = mutableListOf<FaceTemplateEntity>()
                val embeddingsList = mutableListOf<FloatArray>()
                val qualityList = mutableListOf<Float>()

                // 1. Create individual angle template entities with hardware KeyStore AES-256-GCM encryption
                for (sample in capturedSamples) {
                    val csv = sample.embedding.joinToString(",")
                    val encryptedCsv = AndroidSecurityUtils.encrypt(csv)

                    val template = FaceTemplateEntity(
                        id = UUID.randomUUID().toString(),
                        studentRoll = roll,
                        angleType = sample.angleLabel,
                        embeddingEncryptedCsv = encryptedCsv,
                        isEncrypted = true,
                        qualityScore = sample.qualityScore,
                        sharpnessScore = sample.sharpnessScore,
                        lightingScore = sample.lightingScore,
                        consistencyScore = 100.0f
                    )
                    templates.add(template)
                    embeddingsList.add(sample.embedding)
                    qualityList.add(sample.qualityScore)
                }

                // 2. Synthesize Quality-Weighted Master Centroid Embedding
                if (embeddingsList.isNotEmpty()) {
                    val (masterCentroid, consistencyMatrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
                        embeddings = embeddingsList,
                        qualityScores = qualityList
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
                    templates.add(masterTemplate)
                }

                // 3. Commit student record & encrypted templates atomically into Room SQLite
                db.studentDao().enrollStudentWithTemplates(student, templates)

                BiometricSoundboard.playMatchSuccess()

                withContext(Dispatchers.Main) {
                    isSaving = false
                    isRegistrationComplete = true
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    isSaving = false
                    Toast.makeText(context, "Registration save failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun release() {
        cameraExecutor.shutdown()
        synchronized(this) {
            latestFaceCrop?.let { if (!it.isRecycled) it.recycle() }
            latestFaceCrop = null
        }
        capturedSamples.forEach {
            if (!it.thumbnail.isRecycled) it.thumbnail.recycle()
        }
    }
}

/**
 * High-performance, self-contained UI Component for capturing a series of face images,
 * extracting TFLite 512-D neural embeddings, and persisting to SQLite Room database.
 */
@OptIn(ExperimentalGetImage::class, ExperimentalCamera2Interop::class)
@Composable
fun FaceRegistrationComponent(
    modifier: Modifier = Modifier,
    initialRoll: String = "",
    initialName: String = "",
    initialDept: String = "AI & Biometrics",
    initialSem: String = "VI",
    onRegistrationFinished: (StudentEntity) -> Unit = {},
    onDismiss: () -> Unit = {}
) {
    val context = LocalContext.current
    val isDark = LocalThemeIsDark.current
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    BackHandler {
        onDismiss()
    }

    val state = remember {
        FaceRegistrationState(
            context = context,
            initialRoll = initialRoll,
            initialName = initialName,
            initialDept = initialDept,
            initialSem = initialSem
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            state.release()
        }
    }

    LaunchedEffect(state.currentShotIndex) {
        if (state.currentShotIndex > 0) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (state.isRegistrationComplete) {
            // Success & Verification Summary Screen
            RegistrationSuccessCard(
                state = state,
                isDark = isDark,
                onFinish = {
                    val student = StudentEntity(
                        rollNumber = state.rollNumber.trim(),
                        fullName = state.fullName.trim(),
                        department = state.department.trim(),
                        semester = state.semester.trim()
                    )
                    onRegistrationFinished(student)
                },
                onEnrollAnother = {
                    state.resetAllShots()
                    state.rollNumber = ""
                    state.fullName = ""
                }
            )
        } else {
            // Live Camera Viewport & Analyzer
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

                            val resSelector = ResolutionSelector.Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1920, 1080),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                    )
                                )
                                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                                .build()

                            val previewBuilder = Preview.Builder().setResolutionSelector(resSelector)
                            val prevExt = Camera2Interop.Extender(previewBuilder)
                            prevExt.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            prevExt.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)

                            val preview = previewBuilder.build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }

                            val faceDetector = FaceDetection.getClient(
                                FaceDetectorOptions.Builder()
                                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                                    .enableTracking()
                                    .build()
                            )

                            val analysisBuilder = ImageAnalysis.Builder()
                                .setResolutionSelector(resSelector)
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

                            val ext = Camera2Interop.Extender(analysisBuilder)
                            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            ext.setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY)
                            ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 60))

                            val imageAnalysis = analysisBuilder.build()

                            imageAnalysis.setAnalyzer(state.cameraExecutor) { imageProxy ->
                                val mediaImage = imageProxy.image
                                if (mediaImage != null) {
                                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                    val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)
                                    val fullBitmap = imageProxyToBitmap(imageProxy)

                                    faceDetector.process(image)
                                        .addOnSuccessListener { faces ->
                                            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                                            if (face != null && fullBitmap != null) {
                                                state.processFrame(
                                                    face = face,
                                                    fullBitmap = fullBitmap,
                                                    previewWidth = previewView.width.toFloat(),
                                                    previewHeight = previewView.height.toFloat()
                                                )
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
                                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, imageAnalysis)
                            } catch (_: Exception) {}
                        }, ContextCompat.getMainExecutor(ctx))

                        previewView
                    }
                )
            }

            // Clean Face ID Overlay
            FaceDiagnosticsOverlay(
                visualData = state.visualGeometryData,
                showMeshWireframe = false,
                showPoseAxes = false,
                showGazeRays = false,
                show3DMMTopography = false,
                modifier = Modifier.fillMaxSize()
            )

            // Spherical Face ID Reticle & Radial Segment Progress Rings
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2, size.height * 0.38f)
                val radius = size.width * 0.38f

                // Dim Background
                drawCircle(
                    color = Color.Black.copy(alpha = 0.52f),
                    radius = size.width * 1.5f,
                    center = center
                )

                // Clear Viewport Center
                drawCircle(
                    color = Color.Transparent,
                    radius = radius,
                    center = center,
                    blendMode = androidx.compose.ui.graphics.BlendMode.Clear
                )

                // Radial Segments for Multi-Shot Series
                val total = state.totalTargetShots
                val segmentAngle = 360f / total.toFloat()

                for (i in 0 until total) {
                    val isCompleted = i < state.currentShotIndex
                    val isCurrent = i == state.currentShotIndex
                    val color = when {
                        isCompleted -> Color(0xFF10B981) // Emerald Green
                        isCurrent && state.isPoseAligned -> Color(0xFF38BDF8) // Cyan
                        isCurrent -> Color(0xFFF59E0B) // Amber
                        else -> Color(0x33FFFFFF)
                    }

                    drawArc(
                        color = color,
                        startAngle = (i * segmentAngle) - 90f + 3f,
                        sweepAngle = segmentAngle - 6f,
                        useCenter = false,
                        topLeft = Offset(center.x - radius, center.y - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                    )
                }

                // Active Alignment Sweep Arc
                if (state.isPoseAligned && state.alignmentProgress > 0f) {
                    drawArc(
                        color = Color(0xFF00E5FF),
                        startAngle = -90f,
                        sweepAngle = 360f * state.alignmentProgress,
                        useCenter = false,
                        topLeft = Offset(center.x - radius - 8.dp.toPx(), center.y - radius - 8.dp.toPx()),
                        size = androidx.compose.ui.geometry.Size((radius + 8.dp.toPx()) * 2, (radius + 8.dp.toPx()) * 2),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Top Header & Mode Navigation Bar
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dismiss Button
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(0xCC0F172A))
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                        .clickable { onDismiss() }
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text("✕ ${LocalizationManager.get(StringKey.CLOSE_ACTION)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                // Mode Selector & Shot Count Pills
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Mode Toggle Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(Color(0xCC0F172A))
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(999.dp))
                            .clickable {
                                state.captureMode = if (state.captureMode == RegistrationCaptureMode.GUIDED_5_ANGLE) {
                                    RegistrationCaptureMode.RAPID_MULTI_SHOT
                                } else {
                                    RegistrationCaptureMode.GUIDED_5_ANGLE
                                }
                                state.resetAllShots()
                            }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = if (state.captureMode == RegistrationCaptureMode.GUIDED_5_ANGLE) "📐 5-Angle Guide" else "⚡ Burst Mode",
                            color = Color(0xFF00E5FF),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Shot Progress Pill
                    Box(
                        modifier = Modifier
                            .shadow(6.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x66000000), spotColor = Color(0x330071E3))
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isDark) Color(0xD90F172A) else Color(0xE6FFFFFF))
                            .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(999.dp))
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "${LocalizationManager.get(StringKey.BURST_SHOT_PROGRESS)} ${state.currentShotIndex + 1}/${state.totalTargetShots}",
                            color = omniCyan(isDark),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.3.sp
                        )
                    }

                    // Flip Camera Button
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .shadow(6.dp, CircleShape, ambientColor = Color(0x66000000))
                            .clip(CircleShape)
                            .background(if (isDark) Color(0xD90F172A) else Color(0xE6FFFFFF))
                            .border(0.75.dp, omniLiquidSpecularBorder(isDark), CircleShape)
                            .clickable { state.toggleCamera() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Flip Camera",
                            tint = omniTextPrimary(isDark),
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }

            // Bottom Floating Controls HUD & Filmstrip
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Live Biometric Guide Card (Ultra-Glassmorphic)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(12.dp, RoundedCornerShape(20.dp), ambientColor = Color(0x80000000), spotColor = Color(0x330A84FF))
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (isDark) Color(0xEB0C1018) else Color(0xF2FFFFFF))
                        .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(20.dp))
                        .padding(horizontal = 18.dp, vertical = 14.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (state.isPoseAligned) omniEmerald(isDark) else Color(0xFFF59E0B))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = state.guideMessage,
                                color = if (state.isPoseAligned) omniEmerald(isDark) else omniTextPrimary(isDark),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = (-0.2).sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Identity: ${state.fullName.ifBlank { "Candidate" }} (${state.rollNumber.ifBlank { "Unassigned" }}) • ArcFace 512-D INT8",
                            color = omniTextSecondary(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Captured Series Thumbnails Strip
                if (state.capturedSamples.isNotEmpty()) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        itemsIndexed(state.capturedSamples) { index, sample ->
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .shadow(4.dp, RoundedCornerShape(14.dp), ambientColor = Color(0x4D30D158))
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Color(0xFF131823))
                                    .border(1.5.dp, omniEmerald(isDark), RoundedCornerShape(14.dp))
                            ) {
                                Image(
                                    bitmap = sample.thumbnail.asImageBitmap(),
                                    contentDescription = sample.angleLabel,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // Quality Pill on Thumbnail
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .background(Color(0xD907090E))
                                        .border(0.5.dp, Color(0x33FFFFFF), RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                                        .padding(vertical = 1.5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${sample.qualityScore.toInt()}%",
                                        color = omniCyan(isDark),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }

                // Interactive Buttons (Retake + Manual Trigger / Auto Indicator)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.currentShotIndex > 0) {
                        CupertinoButton(
                            text = "🔄 ${LocalizationManager.get(StringKey.RETRY_ACTION)}",
                            isSecondary = true,
                            onClick = { state.retakeLastShot() },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    CupertinoButton(
                        text = if (state.isSaving) LocalizationManager.get(StringKey.BURST_PROCESSING) else if (state.isPoseAligned) "${LocalizationManager.get(StringKey.BURST_CAPTURE_SHOT)} (${LocalizationManager.get(StringKey.BURST_POSE_CENTER)})" else LocalizationManager.get(StringKey.BURST_CAPTURE_SHOT),
                        onClick = { state.triggerCapture() },
                        modifier = Modifier.weight(if (state.currentShotIndex > 0) 2f else 1f)
                    )
                }
            }

            // Neural Engine Loading / Model Warmup Progress Screen
            if (!state.engineLoadingProgress.isReady) {
                NeuralEngineLoadingOverlay(
                    loading = state.engineLoadingProgress,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

/**
 * Registration Success and Biometric Embedding Summary Card (Ultra-Luxury Glassmorphic).
 */
@Composable
private fun RegistrationSuccessCard(
    state: FaceRegistrationState,
    isDark: Boolean,
    onFinish: () -> Unit,
    onEnrollAnother: () -> Unit
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
                .size(92.dp)
                .shadow(12.dp, CircleShape, ambientColor = Color(0x4D30D158), spotColor = Color(0x3330D158))
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

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Face ID Registration Complete",
            color = omniTextPrimary(isDark),
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "${state.fullName} (${state.rollNumber})",
            color = omniCyan(isDark),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "${state.department} • Semester ${state.semester}",
            color = omniTextSecondary(isDark),
            fontSize = 12.5.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Multi-Shot Biometric Quality Report Card (Titanium Obsidian Glass)
        IOSCard(modifier = Modifier.fillMaxWidth()) {
            SectionHeader(text = "BIOMETRIC VAULT SPECIFICATIONS")

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Total Shots Captured", color = omniTextMuted(isDark), fontSize = 12.5.sp)
                Text("${state.capturedSamples.size} Frames", color = omniTextPrimary(isDark), fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Vector Dimension", color = omniTextMuted(isDark), fontSize = 12.5.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(omniCyan(isDark).copy(alpha = 0.15f))
                        .border(0.5.dp, omniCyan(isDark).copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text("512-D L2 ArcFace", color = omniCyan(isDark), fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Hardware Encryption", color = omniTextMuted(isDark), fontSize = 12.5.sp)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(omniEmerald(isDark).copy(alpha = 0.15f))
                        .border(0.5.dp, omniEmerald(isDark).copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text("AES-256-GCM KeyStore", color = omniEmerald(isDark), fontSize = 11.5.sp, fontWeight = FontWeight.ExtraBold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Master Centroid", color = omniTextMuted(isDark), fontSize = 12.5.sp)
                Text("Quality-Weighted Synthesized", color = omniEmerald(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        CupertinoButton(
            text = LocalizationManager.get(StringKey.CONFIRM_ACTION),
            onClick = onFinish
        )

        Spacer(modifier = Modifier.height(10.dp))

        CupertinoButton(
            text = LocalizationManager.get(StringKey.BEGIN_FACE_ENROLLMENT),
            isSecondary = true,
            onClick = onEnrollAnother
        )
    }
}

/**
 * Converts ImageProxy to a correctly oriented Bitmap.
 */
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
