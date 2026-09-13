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


@Composable
internal fun EnrollmentSuccessView(
    viewModel: EnrollmentViewModel,
    state: EnrollmentUiState,
    isDark: Boolean,
    onNavigateToScanner: () -> Unit = {},
    onEnrollAnother: () -> Unit = {},
    onViewDirectory: () -> Unit = {}
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

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Face ID Enrolled Successfully",
            color = omniTextPrimary(isDark),
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.5).sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "${state.fullName} (${state.rollNumber})",
            color = omniCyan(isDark),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "5-Angle Biometric Templates encrypted with Hardware KeyStore AES-256-GCM.",
            color = omniTextSecondary(isDark),
            fontSize = 12.5.sp,
            modifier = Modifier.padding(horizontal = 20.dp)
        )

        Spacer(modifier = Modifier.height(28.dp))

        CupertinoButton(
            text = "📹 Test in Live Scanner",
            onClick = {
                viewModel.resetForNextStudent()
                onViewDirectory()
                onNavigateToScanner()
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        CupertinoButton(
            text = "➕ Enroll Another Identity",
            isSecondary = true,
            onClick = {
                viewModel.resetForNextStudent()
                onEnrollAnother()
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        TextButton(
            onClick = {
                viewModel.resetForNextStudent()
                onViewDirectory()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "View in Directory",
                color = omniTextMuted(isDark),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

