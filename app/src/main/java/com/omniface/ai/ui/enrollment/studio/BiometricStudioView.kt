package com.omniface.ai.ui.enrollment.studio

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

import com.omniface.ai.ui.enrollment.EnrollmentViewModel
import com.omniface.ai.ui.enrollment.EnrollmentUiState
import com.omniface.ai.ui.enrollment.EnrollmentStage
import com.omniface.ai.ui.scanner.NeuralEngineLoadingOverlay


@androidx.camera.core.ExperimentalGetImage
@androidx.camera.camera2.interop.ExperimentalCamera2Interop
@Composable
internal fun BiometricStudioView(
    viewModel: EnrollmentViewModel,
    state: EnrollmentUiState,
    isDark: Boolean
) {
    val context = LocalContext.current
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
                                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
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
                                if (previewView.width <= 0 || previewView.height <= 0) {
                                    imageProxy.close()
                                    return@setAnalyzer
                                }
                                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                                val image = InputImage.fromMediaImage(mediaImage, rotationDegrees)

                                faceDetector.process(image)
                                    .addOnSuccessListener(viewModel.cameraExecutor) { faces ->
                                        val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                                        if (face != null) {
                                            val fullBitmap = BiometricCropUtils.imageProxyToBitmap(imageProxy)
                                            if (fullBitmap != null) {
                                                viewModel.processCameraFrame(
                                                    face = face,
                                                    fullBitmap = fullBitmap,
                                                    previewWidth = previewView.width.toFloat(),
                                                    previewHeight = previewView.height.toFloat()
                                                )
                                            }
                                        }
                                    }
                                    .addOnFailureListener(viewModel.cameraExecutor) {
                                        // Ignore frame failure
                                    }
                                    .addOnCompleteListener(viewModel.cameraExecutor) {
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

        // Clean Biometric Reticle & Identity Overlay
        FaceDiagnosticsOverlay(
            visualData = state.visualGeometryData,
            showMeshWireframe = false,
            showPoseAxes = false,
            showGazeRays = false,
            show3DMMTopography = false,
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
                    .shadow(6.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x66000000))
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (isDark) Color(0xD90F172A) else Color(0xE6FFFFFF))
                    .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(999.dp))
                    .clickable { viewModel.cancelBiometricStudio() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text("✕ Cancel", color = omniTextPrimary(isDark), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.npuInfo.isGenuineNpuDetected) {
                    Box(
                        modifier = Modifier
                            .shadow(6.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x3300E5FF))
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (isDark) Color(0xD90F172A) else Color(0xE6FFFFFF))
                            .border(0.75.dp, omniEmerald(isDark).copy(alpha = 0.5f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "⚡ ${state.npuInfo.shortNpuLabel.uppercase()} • ${state.npuInfo.peakTops}",
                            color = omniCyan(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }

                // Step Indicator Pill
                Box(
                    modifier = Modifier
                        .shadow(6.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x66000000))
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isDark) Color(0xD90F172A) else Color(0xE6FFFFFF))
                        .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(999.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "Angle ${state.currentStep.coerceAtMost(5)} of 5",
                        color = omniCyan(isDark),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }

                // Front / Back Dual-Camera Switcher (Self-Enrollment vs Attendant Mode)
                val isFront = state.lensFacing == CameraSelector.LENS_FACING_FRONT
                Box(
                    modifier = Modifier
                        .shadow(6.dp, RoundedCornerShape(999.dp), ambientColor = Color(0x66000000))
                        .clip(RoundedCornerShape(999.dp))
                        .background(if (isDark) Color(0xD90F172A) else Color(0xE6FFFFFF))
                        .border(0.75.dp, omniLiquidSpecularBorder(isDark), RoundedCornerShape(999.dp))
                        .clickable { viewModel.toggleLensFacing() }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Icon(
                            imageVector = if (isFront) Icons.Default.Person else Icons.Default.CameraAlt,
                            contentDescription = if (isFront) "Switch to Attendant Mode (Rear Camera)" else "Switch to Self-Enrollment (Front Camera)",
                            tint = omniCyan(isDark),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (isFront) "Self-Enroll" else "Attendant",
                            color = omniTextPrimary(isDark),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Neural Engine Loading / Model Warmup Progress Screen
            if (state.isEngineLoading && state.isModelAvailable && !state.isEngineLoaded) {
                com.omniface.ai.ui.scanner.NeuralEngineLoadingOverlay(
                    loading = state.engineLoadingProgress,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize()
                )
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
            // Directional Guidance Pill (Ultra-Glassmorphic)
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
                    Text(
                        text = state.angleGuideText,
                        color = omniTextPrimary(isDark),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.2).sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.yawGaugeText,
                        color = if (state.isPoseAligned) omniEmerald(isDark) else omniTextSecondary(isDark),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Captured Thumbnails Row
            if (state.capturedThumbnails.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(bottom = 10.dp)
                ) {
                    items(state.capturedThumbnails) { (label, thumb) ->
                        if (!thumb.isRecycled) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .shadow(4.dp, CircleShape, ambientColor = Color(0x4D30D158))
                                    .clip(CircleShape)
                                    .background(Color(0xFF131823))
                                    .border(1.5.dp, omniEmerald(isDark), CircleShape)
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
                    CupertinoButton(
                        text = "🔄 Retake",
                        isSecondary = true,
                        onClick = { viewModel.retakeCurrentAngle() },
                        modifier = Modifier.weight(1f)
                    )
                }

                CupertinoButton(
                    text = if (state.isPoseAligned) "📸 Capture (Aligned)" else "📸 Capture Angle",
                    onClick = { viewModel.captureCurrentAngle(context) },
                    modifier = Modifier.weight(if (state.currentStep > 1) 2f else 1f)
                )
            }
        }
    }
}

