package com.omniface.ai.ui.components

import android.graphics.PointF
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.EyeGazeResult
import com.omniface.ai.ml.FaceAttributesResult
import com.omniface.ai.ml.FaceMap3DMMResult
import com.omniface.ai.ml.MediaPipeMeshResult
import com.omniface.ai.ml.RegistrationQualityScore
import kotlin.math.cos
import kotlin.math.sin

data class FaceGeometryVisualData(
    val bounds: androidx.compose.ui.geometry.Rect,
    val yaw: Float,
    val pitch: Float,
    val roll: Float,
    val landmarks5Pts: Array<PointF>? = null,
    val contours: Map<Int, List<Offset>>? = null,
    val gazeResult: EyeGazeResult? = null,
    val faceMap3DMM: FaceMap3DMMResult? = null,
    val attributes: FaceAttributesResult? = null,
    val meshResult: MediaPipeMeshResult? = null,
    val hrnetResult: com.omniface.ai.ml.HRNetFaceResult? = null,
    val qualityScore: RegistrationQualityScore? = null,
    val qualityResult: com.omniface.ai.ml.quality.QualityGateResult? = null,
    val confidenceZone: ConfidenceZone = ConfidenceZone.REJECT,
    val decisionMargin: Float = 0.0f,
    val similarityScore: Float = 0.0f,
    val studentName: String = "",
    val studentRoll: String = "",
    val isLive: Boolean = true,
    val pulseBpm: Int = 72,
    val isPulseValid: Boolean = false,
    val activeHardwareNpu: String = "Qualcomm Hexagon NPU",
    val isFrontCamera: Boolean = true
)

/**
 * Sovereign 60 FPS Real-Time Biometric Analysis & Explainable AI Visualizer.
 *
 * Renders:
 * 1. Apple Face ID-style Liquid Glassmorphic Bounding Box & Corner Brackets with smooth fade in/out transitions
 * 2. Real-Time Floating Identity, Confidence, and Security Status Pill
 * 3. Smooth animated Confidence & Scan Quality percentage score overlay
 * 4. 5-Point Canonical Landmark Fiducials (Eyes, Nose, Mouth Corners)
 * 5. 468-Point MediaPipe Dense 3D Facial Mesh Wireframe & Topological Contours
 * 6. 3D Head Pose Orientation Coordinate Frame (RGB Euler Vector Rays: Pitch, Yaw, Roll)
 * 7. Optical Eye Gaze Subpixel Vectors & Fixation Rays radiating from pupils
 * 8. Qualcomm FaceMap 3DMM Neural Depth Topography Contours & Surface Variance
 * 9. Real-Time Hardware NPU & Biometric Attribute Diagnostics HUD
 */
@Composable
fun FaceDiagnosticsOverlay(
    visualData: List<FaceGeometryVisualData>,
    isDeveloperMode: Boolean = true,
    showMeshWireframe: Boolean = true,
    showPoseAxes: Boolean = true,
    showGazeRays: Boolean = true,
    show3DMMTopography: Boolean = true,
    modifier: Modifier = Modifier
) {
    val hasFaces = visualData.isNotEmpty()

    // Retain cached visual data so bounding box and confidence score fade out smoothly when face leaves frame
    var cachedVisualData by remember { mutableStateOf<List<FaceGeometryVisualData>>(emptyList()) }
    LaunchedEffect(visualData) {
        if (visualData.isNotEmpty()) {
            cachedVisualData = visualData
        }
    }

    // Smooth fade-in / fade-out alpha transition based on current detection state (60ms fast lock)
    val overlayAlpha by animateFloatAsState(
        targetValue = if (hasFaces) 1.0f else 0.0f,
        animationSpec = tween(
            durationMillis = if (hasFaces) 60 else 180,
            easing = FastOutSlowInEasing
        ),
        label = "faceOverlayAlpha"
    )

    val activeFaces = if (hasFaces) visualData else cachedVisualData
    val primaryFace = activeFaces.firstOrNull()

    // Smooth state color transition based on detection and verification state
    val targetHaloColor = when {
        primaryFace == null -> Color(0xFF007AFF)
        !primaryFace.isLive -> Color(0xFFFF3B30) // Red (Spoof Attack)
        primaryFace.confidenceZone == ConfidenceZone.ACCEPT -> Color(0xFF34C759) // Emerald Green (Verified Match)
        primaryFace.confidenceZone == ConfidenceZone.REVIEW -> Color(0xFFFF9500) // Amber (Review Required)
        primaryFace.similarityScore > 0f -> Color(0xFF0A84FF) // Tracking / Searching
        else -> Color(0xFF007AFF) // Electric Blue (Detected)
    }

    val animatedHaloColor by animateColorAsState(
        targetValue = targetHaloColor,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "faceHaloColor"
    )

    // Smooth confidence percentage animation across detection states
    val targetConfidence = when {
        primaryFace == null -> 0f
        !primaryFace.isLive -> 0f
        primaryFace.similarityScore > 0f -> (primaryFace.similarityScore * 100f).coerceIn(0f, 100f)
        primaryFace.qualityResult?.overallQualityScore != null -> primaryFace.qualityResult.overallQualityScore.coerceIn(0f, 100f)
        primaryFace.qualityScore?.overallScore != null -> primaryFace.qualityScore.overallScore.coerceIn(0f, 100f)
        else -> 0f
    }

    val animatedConfidenceScore by animateFloatAsState(
        targetValue = targetConfidence,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "animatedConfidenceScore"
    )

    // Subtle optical breathing pulse for corner brackets
    val infiniteTransition = rememberInfiniteTransition(label = "faceOverlayPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.990f,
        targetValue = 1.010f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    if (overlayAlpha <= 0.005f && !hasFaces) return

    Canvas(modifier = modifier.fillMaxSize()) {
        for (face in activeFaces) {
            val rect = face.bounds
            val cx = rect.center.x
            val cy = rect.center.y
            val faceRadius = rect.width / 2f

            val faceHaloColor = if (activeFaces.size == 1) {
                animatedHaloColor
            } else {
                when {
                    !face.isLive -> Color(0xFFFF3B30)
                    face.confidenceZone == ConfidenceZone.ACCEPT -> Color(0xFF34C759)
                    face.confidenceZone == ConfidenceZone.REVIEW -> Color(0xFFFF9500)
                    else -> Color(0xFF007AFF)
                }
            }

            // 1. Core Bounding Box with Smooth Face ID Corner Brackets & Clean Highlight
            drawFaceBoundingBox(rect, faceHaloColor, overlayAlpha, pulseScale)

            // 2. 3D Morphable Model (FaceMap 3DMM) Depth Contours (Only when 3D mesh is not active to prevent duplicate rings)
            if (show3DMMTopography && face.faceMap3DMM != null && face.meshResult == null) {
                drawFaceMap3DMMContours(rect, face.faceMap3DMM.depthVariance, faceHaloColor, overlayAlpha)
            }

            // 3. Dense Facial Contour Wireframe & MediaPipe 468-point 3D Mesh (Mutually exclusive: only render ONE clean mesh)
            if (showMeshWireframe) {
                if (face.meshResult != null) {
                    drawMediaPipeMeshTessellation(face.meshResult.landmarks468x3, rect, faceHaloColor, overlayAlpha, face.isFrontCamera)
                } else if (face.contours != null && face.contours.isNotEmpty()) {
                    drawDenseContourMesh(face.contours, rect, faceHaloColor, overlayAlpha)
                }
            }

            // 4. Real-Time 5-Point Canonical Landmark Fiducials (Fallback when no 3D mesh is active to avoid duplicate dots)
            if (face.meshResult == null && face.landmarks5Pts != null && face.landmarks5Pts.isNotEmpty()) {
                drawLandmarkFiducials(face.landmarks5Pts, rect, faceHaloColor, overlayAlpha, face.isFrontCamera)
            }

            // 5. 3D Head Pose Coordinate Frame Axes (Pitch, Yaw, Roll)
            if (showPoseAxes) {
                drawHeadPoseAxes(cx, cy, faceRadius, face.yaw, face.pitch, face.roll, overlayAlpha, face.isFrontCamera)
            }

            // 6. Optical Eye Gaze Subpixel Vectors
            if (showGazeRays && face.gazeResult != null) {
                drawEyeGazeRays(cx, cy, faceRadius, face.gazeResult, overlayAlpha, face.isFrontCamera, face.landmarks5Pts)
            }

            // 7. Cybernetic Attribute HUD Tags (Developer mode only)
            if (isDeveloperMode && face.attributes != null) {
                drawQualcommAttributeTags(rect, face, overlayAlpha)
            }

            // 8. Real-Time Floating Identity / Status Capsule on Canvas
            drawFaceIdentityPill(rect, face, faceHaloColor, overlayAlpha)

            // 9. Real-Time Floating Confidence & Scan Quality Score Glass Badge
            drawFaceConfidenceOverlay(rect, face, faceHaloColor, overlayAlpha, animatedConfidenceScore)
        }
    }
}

/** Draws rounded Face ID reticle brackets with liquid specular glow and center crosshair with smooth alpha fade. */
private fun DrawScope.drawFaceBoundingBox(
    rect: androidx.compose.ui.geometry.Rect,
    color: Color,
    alpha: Float,
    scaleFactor: Float = 1.0f
) {
    val cornerLen = (rect.width * 0.20f).coerceIn(20f, 48f)
    val strokeW = 4.0f

    val cx = rect.center.x
    val cy = rect.center.y
    val scaledW = rect.width * scaleFactor
    val scaledH = rect.height * scaleFactor
    val sLeft = cx - scaledW / 2f
    val sTop = cy - scaledH / 2f
    val sRight = cx + scaledW / 2f
    val sBottom = cy + scaledH / 2f

    // Soft glass background tint
    drawRect(
        color = color.copy(alpha = 0.08f * alpha),
        topLeft = Offset(sLeft, sTop),
        size = Size(scaledW, scaledH)
    )

    // Soft surrounding bounding box hairline
    val glowColor = color.copy(alpha = 0.35f * alpha)
    drawRect(glowColor, Offset(sLeft, sTop), Size(scaledW, scaledH), style = Stroke(width = 1.4f))

    val cornerColor = color.copy(alpha = alpha)

    // Top-Left L-bracket
    drawLine(cornerColor, Offset(sLeft, sTop + cornerLen), Offset(sLeft, sTop), strokeW, StrokeCap.Round)
    drawLine(cornerColor, Offset(sLeft, sTop), Offset(sLeft + cornerLen, sTop), strokeW, StrokeCap.Round)

    // Top-Right L-bracket
    drawLine(cornerColor, Offset(sRight - cornerLen, sTop), Offset(sRight, sTop), strokeW, StrokeCap.Round)
    drawLine(cornerColor, Offset(sRight, sTop), Offset(sRight, sTop + cornerLen), strokeW, StrokeCap.Round)

    // Bottom-Left L-bracket
    drawLine(cornerColor, Offset(sLeft, sBottom - cornerLen), Offset(sLeft, sBottom), strokeW, StrokeCap.Round)
    drawLine(cornerColor, Offset(sLeft, sBottom), Offset(sLeft + cornerLen, sBottom), strokeW, StrokeCap.Round)

    // Bottom-Right L-bracket
    drawLine(cornerColor, Offset(sRight - cornerLen, sBottom), Offset(sRight, sBottom), strokeW, StrokeCap.Round)
    drawLine(cornerColor, Offset(sRight, sBottom), Offset(sRight, sBottom - cornerLen), strokeW, StrokeCap.Round)

    // Center Crosshair Target Guide
    val crossLen = 8f
    val crossColor = color.copy(alpha = 0.6f * alpha)
    drawLine(crossColor, Offset(cx - crossLen, cy), Offset(cx + crossLen, cy), strokeWidth = 1.5f, cap = StrokeCap.Round)
    drawLine(crossColor, Offset(cx, cy - crossLen), Offset(cx, cy + crossLen), strokeWidth = 1.5f, cap = StrokeCap.Round)
}

/** Draws floating Apple glassmorphic identity and confidence capsule on Canvas with smooth alpha fade. */
private fun DrawScope.drawFaceIdentityPill(
    rect: androidx.compose.ui.geometry.Rect,
    face: FaceGeometryVisualData,
    haloColor: Color,
    alpha: Float
) {
    if (alpha <= 0.01f) return
    val nativeCanvas = drawContext.canvas.nativeCanvas

    val isMatched = face.studentName.isNotBlank() || face.confidenceZone == ConfidenceZone.ACCEPT
    val titleText = when {
        !face.isLive -> "SPOOF ATTACK DETECTED"
        isMatched && face.studentName.isNotBlank() -> "✓ ${face.studentName.uppercase()}"
        face.confidenceZone == ConfidenceZone.ACCEPT -> "✓ VERIFIED MATCH"
        face.confidenceZone == ConfidenceZone.REVIEW -> "REVIEW REQUIRED"
        else -> "FACE DETECTED"
    }

    val subtitleText = when {
        !face.isLive -> "Presentation Attack Rejected"
        isMatched && face.similarityScore > 0f -> "${(face.similarityScore * 100).toInt()}% Match • Live 3D Face Verified"
        face.studentRoll.isNotBlank() -> "${face.studentRoll} • Live 3D"
        else -> "Real-time Tracking Active"
    }

    val aInt = (alpha * 255).toInt().coerceIn(0, 255)

    val titlePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 28f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        color = android.graphics.Color.argb(aInt, 255, 255, 255)
    }

    val subPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 20f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.NORMAL)
        color = android.graphics.Color.argb((aInt * 0.85f).toInt(), 226, 232, 240)
    }

    val dotPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
        color = android.graphics.Color.argb(
            aInt,
            (haloColor.red * 255).toInt(),
            (haloColor.green * 255).toInt(),
            (haloColor.blue * 255).toInt()
        )
    }

    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
        color = android.graphics.Color.argb((aInt * 0.92f).toInt(), 11, 15, 25) // Frosted Obsidian Slate
    }

    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.0f
        color = android.graphics.Color.argb(
            (aInt * 0.75f).toInt(),
            (haloColor.red * 255).toInt(),
            (haloColor.green * 255).toInt(),
            (haloColor.blue * 255).toInt()
        )
    }

    val titleWidth = titlePaint.measureText(titleText)
    val subWidth = subPaint.measureText(subtitleText)
    val contentWidth = maxOf(titleWidth, subWidth)
    val pillWidth = contentWidth + 56f
    val pillHeight = 58f

    val pillLeft = (rect.center.x - pillWidth / 2f).coerceIn(8f, size.width - pillWidth - 8f)
    val pillTop = (rect.top - pillHeight - 12f).let { if (it < 8f) rect.bottom + 12f else it }

    val pillRect = android.graphics.RectF(pillLeft, pillTop, pillLeft + pillWidth, pillTop + pillHeight)

    // Draw Frosted Capsule Background & Specular Border
    nativeCanvas.drawRoundRect(pillRect, 16f, 16f, bgPaint)
    nativeCanvas.drawRoundRect(pillRect, 16f, 16f, borderPaint)

    // Status Indicator Dot
    nativeCanvas.drawCircle(pillLeft + 20f, pillTop + 21f, 6.0f, dotPaint)

    // Title & Subtitle Text Lines
    nativeCanvas.drawText(titleText, pillLeft + 36f, pillTop + 28f, titlePaint)
    nativeCanvas.drawText(subtitleText, pillLeft + 36f, pillTop + 49f, subPaint)
}

/** Draws liquid glassmorphic Confidence & Scan Quality percentage score with smooth transition animation. */
private fun DrawScope.drawFaceConfidenceOverlay(
    rect: androidx.compose.ui.geometry.Rect,
    face: FaceGeometryVisualData,
    haloColor: Color,
    alpha: Float,
    animatedScore: Float
) {
    if (alpha <= 0.01f) return
    val nativeCanvas = drawContext.canvas.nativeCanvas

    val aInt = (alpha * 255).toInt().coerceIn(0, 255)

    // Animated confidence percentage & smooth display score
    val isMatched = face.studentName.isNotBlank() || face.confidenceZone == ConfidenceZone.ACCEPT || face.similarityScore > 0f
    val displayScore = if (face.similarityScore > 0f) (face.similarityScore * 100f).toInt() else animatedScore.toInt().coerceIn(0, 99)

    val scoreLabel = when {
        !face.isLive -> "0% (SPOOF)"
        isMatched -> "$displayScore% MATCH"
        else -> "$displayScore% SCAN"
    }

    val subLabel = when {
        !face.isLive -> "UNRELIABLE"
        displayScore >= 80 -> "EXCELLENT"
        displayScore >= 65 -> "GOOD"
        isMatched -> "VERIFIED"
        else -> "SCANNING"
    }

    val r = (haloColor.red * 255).toInt()
    val g = (haloColor.green * 255).toInt()
    val b = (haloColor.blue * 255).toInt()

    val scorePaint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 21f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
        color = android.graphics.Color.argb(aInt, r, g, b)
    }

    val labelPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 14f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        color = android.graphics.Color.argb((aInt * 0.82f).toInt(), 203, 213, 225)
    }

    val bgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
        color = android.graphics.Color.argb((aInt * 0.92f).toInt(), 15, 23, 42) // Frosted Slate 900
    }

    val borderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 1.8f
        color = android.graphics.Color.argb((aInt * 0.78f).toInt(), r, g, b)
    }

    val scoreWidth = scorePaint.measureText(scoreLabel)
    val labelWidth = labelPaint.measureText(subLabel)
    val contentWidth = maxOf(scoreWidth, labelWidth)
    val badgeWidth = contentWidth + 36f
    val badgeHeight = 48f

    // Position next to the top-right of the bounding box
    var badgeLeft = rect.right + 10f
    var badgeTop = rect.top

    // Fallback if overflowing screen boundary on the right
    if (badgeLeft + badgeWidth > size.width - 8f) {
        badgeLeft = (rect.left - badgeWidth - 10f)
    }
    if (badgeLeft < 8f) {
        badgeLeft = (rect.right - badgeWidth).coerceAtLeast(8f)
        badgeTop = (rect.top + 8f).coerceAtMost(size.height - badgeHeight - 8f)
    }

    val badgeRect = android.graphics.RectF(badgeLeft, badgeTop, badgeLeft + badgeWidth, badgeTop + badgeHeight)

    // Draw Frosted Pill Background & Border
    nativeCanvas.drawRoundRect(badgeRect, 12f, 12f, bgPaint)
    nativeCanvas.drawRoundRect(badgeRect, 12f, 12f, borderPaint)

    // Dynamic Dot Indicator
    val dotPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
        color = android.graphics.Color.argb(aInt, r, g, b)
    }
    nativeCanvas.drawCircle(badgeLeft + 14f, badgeTop + 18f, 4.5f, dotPaint)

    // Text Lines
    nativeCanvas.drawText(scoreLabel, badgeLeft + 24f, badgeTop + 24f, scorePaint)
    nativeCanvas.drawText(subLabel, badgeLeft + 24f, badgeTop + 41f, labelPaint)
}

/** Draws dense MediaPipe 468-point 3D facial mesh tessellation wireframe & HRNet fiducials with geometric precision. */
private fun DrawScope.drawMediaPipeMeshTessellation(
    mesh: Array<FloatArray>,
    bounds: androidx.compose.ui.geometry.Rect,
    themeColor: Color,
    alpha: Float = 1.0f,
    isFrontCamera: Boolean = true
) {
    if (alpha <= 0.01f) return

    // Accurately map normalized [0, 1] mesh points from the 1.25x square face crop to preview canvas coordinates
    val maxDim = maxOf(bounds.width, bounds.height) * 1.25f
    val cropLeft = bounds.center.x - maxDim / 2f
    val cropTop = bounds.center.y - maxDim / 2f
    val cropRight = bounds.center.x + maxDim / 2f

    fun meshPointToOffset(idx: Int): Offset? {
        if (idx !in mesh.indices) return null
        val p = mesh[idx]
        val x = if (isFrontCamera) cropRight - p[0] * maxDim else cropLeft + p[0] * maxDim
        val y = cropTop + p[1] * maxDim
        return Offset(x, y)
    }

    val wireColor = Color.White.copy(alpha = 0.45f * alpha)
    val haloColor = themeColor.copy(alpha = 0.70f * alpha)

    // ── Qualcomm AI Hub MediaPipe 3D Mesh Canonical Wireframe Edges ──
    val meshConnections = arrayOf(
        // Lips Outer & Inner
        61 to 146, 146 to 91, 91 to 181, 181 to 84, 84 to 17, 17 to 314, 314 to 405, 405 to 321, 321 to 375, 375 to 291,
        61 to 185, 185 to 40, 40 to 39, 39 to 37, 37 to 0, 0 to 267, 267 to 269, 269 to 270, 270 to 409, 409 to 291,
        78 to 95, 95 to 88, 88 to 178, 178 to 87, 87 to 14, 14 to 317, 317 to 402, 402 to 318, 318 to 324, 324 to 308,
        78 to 191, 191 to 80, 80 to 81, 81 to 82, 82 to 13, 13 to 312, 312 to 311, 311 to 310, 310 to 415, 415 to 308,
        // Left Eye
        263 to 249, 249 to 390, 390 to 373, 373 to 374, 374 to 380, 380 to 381, 381 to 382, 382 to 362,
        263 to 466, 466 to 388, 388 to 387, 387 to 386, 386 to 385, 385 to 384, 384 to 398, 398 to 362,
        // Left Eyebrow
        276 to 283, 283 to 282, 282 to 295, 295 to 285, 300 to 293, 293 to 334, 334 to 296, 296 to 336,
        // Right Eye
        33 to 7, 7 to 163, 163 to 144, 144 to 145, 145 to 153, 153 to 154, 154 to 155, 155 to 133,
        33 to 246, 246 to 161, 161 to 160, 160 to 159, 159 to 158, 158 to 157, 157 to 173, 173 to 133,
        // Right Eyebrow
        46 to 53, 53 to 52, 52 to 65, 65 to 55, 70 to 63, 63 to 105, 105 to 66, 66 to 107,
        // Face Oval (Forehead to Chin)
        10 to 338, 338 to 297, 297 to 332, 332 to 284, 284 to 251, 251 to 389, 389 to 356, 356 to 454,
        454 to 323, 323 to 361, 361 to 288, 288 to 397, 397 to 365, 365 to 379, 379 to 378, 378 to 400,
        400 to 377, 377 to 152, 152 to 148, 148 to 176, 176 to 149, 149 to 150, 150 to 136, 136 to 172,
        172 to 58, 58 to 132, 132 to 93, 93 to 234, 234 to 127, 127 to 162, 162 to 21, 21 to 54,
        54 to 103, 103 to 67, 67 to 109, 109 to 10,
        // Nose Bridge & Base
        168 to 6, 6 to 197, 197 to 195, 195 to 5, 5 to 4, 4 to 1, 1 to 19, 19 to 94, 94 to 2,
        98 to 97, 97 to 2, 2 to 326, 326 to 327
    )

    // 1. Draw all Qualcomm MediaPipe Wireframe Connections
    for (conn in meshConnections) {
        val p1 = meshPointToOffset(conn.first) ?: continue
        val p2 = meshPointToOffset(conn.second) ?: continue
        drawLine(
            color = wireColor,
            start = p1,
            end = p2,
            strokeWidth = 1.3f,
            cap = StrokeCap.Round
        )
    }

    // 2. Draw all 468 Vertex Fiducials (Subtle white points)
    for (idx in mesh.indices) {
        val pt = meshPointToOffset(idx) ?: continue
        drawCircle(Color.White.copy(alpha = 0.35f * alpha), radius = 1.2f, center = pt)
    }

    // 3. HRNet-Style 29 Keypoint Glowing Constellation
    val fiducialKeypoints = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152,
        70, 105, 46, 336, 334, 276,
        33, 133, 362, 263, 1, 4, 2,
        61, 291, 0, 17
    )

    for (idx in fiducialKeypoints) {
        val pt = meshPointToOffset(idx) ?: continue
        drawCircle(haloColor, radius = 5.0f, center = pt)
        drawCircle(Color.White.copy(alpha = alpha), radius = 2.2f, center = pt)
    }
}

/**
 * Draws real-time dense facial contour wireframe & landmark tessellation (133+ contour vertices).
 * Connected along jaw, eyebrows, eyes, nose bridge, and lips with cybernetic triangulation.
 */
private fun DrawScope.drawDenseContourMesh(
    contours: Map<Int, List<Offset>>,
    bounds: androidx.compose.ui.geometry.Rect,
    themeColor: Color,
    alpha: Float = 1.0f
) {
    if (alpha <= 0.01f || contours.isEmpty()) return

    val wireColor = Color.White.copy(alpha = 0.55f * alpha)
    val meshGlowColor = themeColor.copy(alpha = 0.70f * alpha)
    val triColor = themeColor.copy(alpha = 0.30f * alpha)

    // 1. Draw connected contour loops (Face oval, Left Eye, Right Eye, Upper Lip, Lower Lip, Nose Bridge, Eyebrows)
    for ((type, pts) in contours) {
        if (pts.size < 2) continue
        for (i in 0 until pts.size - 1) {
            drawLine(
                color = wireColor,
                start = pts[i],
                end = pts[i + 1],
                strokeWidth = 1.5f,
                cap = StrokeCap.Round
            )
        }
        // Close eyes and lip loops
        if ((type == 6 || type == 7 || type == 8 || type == 9 || type == 10 || type == 11) && pts.size > 2) {
            drawLine(
                color = wireColor,
                start = pts.last(),
                end = pts.first(),
                strokeWidth = 1.5f,
                cap = StrokeCap.Round
            )
        }
        // Draw fiducial points
        for (pt in pts) {
            drawCircle(meshGlowColor, radius = 2.5f, center = pt)
            drawCircle(Color.White.copy(alpha = alpha), radius = 1.2f, center = pt)
        }
    }

    // 2. Cybernetic Cross-Triangulation Wireframe
    val leftEye = contours[6]
    val rightEye = contours[7]
    val noseBridge = contours[12]
    val noseBottom = contours[13]
    val upperLip = contours[8]
    val leftEyebrow = contours[2]
    val rightEyebrow = contours[4]

    // Eyebrows to Eyes Triangulation
    if (leftEyebrow != null && leftEye != null && leftEyebrow.isNotEmpty() && leftEye.isNotEmpty()) {
        val midEb = leftEyebrow[leftEyebrow.size / 2]
        val midEye = leftEye[leftEye.size / 2]
        drawLine(triColor, midEb, midEye, strokeWidth = 1.0f, cap = StrokeCap.Round)
    }
    if (rightEyebrow != null && rightEye != null && rightEyebrow.isNotEmpty() && rightEye.isNotEmpty()) {
        val midEb = rightEyebrow[rightEyebrow.size / 2]
        val midEye = rightEye[rightEye.size / 2]
        drawLine(triColor, midEb, midEye, strokeWidth = 1.0f, cap = StrokeCap.Round)
    }

    // Eyes to Nose Bridge Triangulation
    if (noseBridge != null && noseBridge.isNotEmpty()) {
        val noseTop = noseBridge.first()
        if (leftEye != null && leftEye.isNotEmpty()) {
            drawLine(triColor, leftEye.first(), noseTop, strokeWidth = 1.0f, cap = StrokeCap.Round)
        }
        if (rightEye != null && rightEye.isNotEmpty()) {
            drawLine(triColor, rightEye.last(), noseTop, strokeWidth = 1.0f, cap = StrokeCap.Round)
        }
    }

    // Nose Bottom to Upper Lip Triangulation
    if (noseBottom != null && upperLip != null && noseBottom.isNotEmpty() && upperLip.isNotEmpty()) {
        val noseMid = noseBottom[noseBottom.size / 2]
        val lipMid = upperLip[upperLip.size / 2]
        drawLine(triColor, noseMid, lipMid, strokeWidth = 1.1f, cap = StrokeCap.Round)
    }
}

/** Draws real-time 5-point canonical facial landmarks with instant subpixel locking. */
private fun DrawScope.drawLandmarkFiducials(
    landmarks: Array<android.graphics.PointF>,
    bounds: androidx.compose.ui.geometry.Rect,
    themeColor: Color,
    alpha: Float = 1.0f,
    isFrontCamera: Boolean = true
) {
    if (alpha <= 0.01f || landmarks.isEmpty()) return

    val ptColor = themeColor.copy(alpha = 0.85f * alpha)
    val ringColor = Color.White.copy(alpha = 0.70f * alpha)

    // Landmarks are already projected into preview canvas space
    val offsets = landmarks.map { pt -> Offset(pt.x, pt.y) }

    // Connect eye-to-eye and nose-to-mouth with subtle cybernetic constellation lines
    if (offsets.size >= 5) {
        val linePaint = Color.White.copy(alpha = 0.25f * alpha)
        // Left Eye to Right Eye
        drawLine(linePaint, offsets[0], offsets[1], strokeWidth = 1.2f, cap = StrokeCap.Round)
        // Eyes to Nose Base
        drawLine(linePaint, offsets[0], offsets[2], strokeWidth = 1.0f, cap = StrokeCap.Round)
        drawLine(linePaint, offsets[1], offsets[2], strokeWidth = 1.0f, cap = StrokeCap.Round)
        // Nose to Mouth Corners
        drawLine(linePaint, offsets[2], offsets[3], strokeWidth = 1.0f, cap = StrokeCap.Round)
        drawLine(linePaint, offsets[2], offsets[4], strokeWidth = 1.0f, cap = StrokeCap.Round)
        // Mouth Left to Mouth Right
        drawLine(linePaint, offsets[3], offsets[4], strokeWidth = 1.2f, cap = StrokeCap.Round)
    }

    for (pt in offsets) {
        drawCircle(color = ptColor, radius = 4.5f, center = pt)
        drawCircle(color = ringColor, radius = 2.0f, center = pt)
    }
}

/** Draws Qualcomm AI Hub Cybernetic Attribute Floating HUD Tags with smooth alpha fade. */
private fun DrawScope.drawQualcommAttributeTags(
    bounds: androidx.compose.ui.geometry.Rect,
    face: FaceGeometryVisualData,
    alpha: Float = 1.0f
) {
    if (alpha <= 0.01f) return
    val nativeCanvas = drawContext.canvas.nativeCanvas
    val aInt = (alpha * 255).toInt().coerceIn(0, 255)

    val tagTextPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 24f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }

    val tagBgPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
        color = android.graphics.Color.argb((aInt * 0.86f).toInt(), 11, 15, 25) // Frosted Obsidian Slate
    }

    val tagBorderPaint = android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.0f
        color = android.graphics.Color.argb((aInt * 0.63f).toInt(), 255, 255, 255) // Crisp white hairline
    }

    val attr = face.attributes
    val tags = mutableListOf<Pair<String, Int>>()

    // Tag 1: Identity Embedding
    tags.add("Identity Embedding" to android.graphics.Color.argb(aInt, 255, 255, 255))

    // Tag 2: Eye Openness
    val eyeOpen = (attr?.rawProbabilities?.getOrNull(3) ?: 0.95f) > 0.4f
    val eyeColor = if (eyeOpen) android.graphics.Color.argb(aInt, 52, 199, 89) else android.graphics.Color.argb(aInt, 255, 59, 48)
    tags.add("Eye Openness ${if (eyeOpen) "True" else "False"}" to eyeColor)

    // Tag 3: Liveness
    val liveColor = if (face.isLive) android.graphics.Color.argb(aInt, 52, 199, 89) else android.graphics.Color.argb(aInt, 255, 59, 48)
    tags.add("Liveness ${if (face.isLive) "True" else "False"}" to liveColor)

    // Tag 4: Mask
    val mask = (attr?.rawProbabilities?.getOrNull(2) ?: 0.05f) > 0.5f
    val maskColor = if (mask) android.graphics.Color.argb(aInt, 255, 149, 0) else android.graphics.Color.argb((aInt * 0.7f).toInt(), 180, 180, 180)
    tags.add("Mask ${if (mask) "True" else "False"}" to maskColor)

    // Tag 5: Glasses
    val glasses = (attr?.rawProbabilities?.getOrNull(1) ?: 0.1f) > 0.5f
    val glassesColor = if (glasses) android.graphics.Color.argb(aInt, 255, 149, 0) else android.graphics.Color.argb((aInt * 0.7f).toInt(), 180, 180, 180)
    tags.add("Glasses ${if (glasses) "True" else "False"}" to glassesColor)

    val startX = (bounds.left - 240f).coerceAtLeast(16f)
    var currentY = bounds.top + 8f

    for ((tagText, valColor) in tags) {
        tagTextPaint.color = valColor
        val textWidth = tagTextPaint.measureText(tagText)
        val pillWidth = textWidth + 28f
        val pillHeight = 36f
        val rectF = android.graphics.RectF(startX, currentY, startX + pillWidth, currentY + pillHeight)

        // Draw Frosted Card Background & Specular Border
        nativeCanvas.drawRoundRect(rectF, 10f, 10f, tagBgPaint)
        nativeCanvas.drawRoundRect(rectF, 10f, 10f, tagBorderPaint)
        // Draw Text
        nativeCanvas.drawText(tagText, startX + 14f, currentY + 25f, tagTextPaint)

        currentY += pillHeight + 8f
    }
}

/** Draws FaceMap 3DMM depth topography contour lines. */
private fun DrawScope.drawFaceMap3DMMContours(
    bounds: androidx.compose.ui.geometry.Rect,
    depthVariance: Float,
    color: Color,
    alpha: Float = 1.0f
) {
    if (alpha <= 0.01f) return
    val rings = (depthVariance * 10f).toInt().coerceIn(2, 4)
    for (i in 1..rings) {
        val scale = 0.40f + (i * 0.18f)
        val w = bounds.width * scale
        val h = bounds.height * scale
        val left = bounds.center.x - w / 2f
        val top = bounds.center.y - h / 2f
        val contourAlpha = (0.22f * (1f - (i * 0.2f)) * (depthVariance * 2.5f).coerceIn(0.6f, 1.4f)).coerceIn(0.05f, 0.40f) * alpha
        drawOval(
            color = color.copy(alpha = contourAlpha),
            topLeft = Offset(left, top),
            size = Size(w, h),
            style = Stroke(width = 1.2f)
        )
    }
}

/** Draws 3D Head Pose Coordinate Frame Axes (Pitch, Yaw, Roll) anchored at face center. */
private fun DrawScope.drawHeadPoseAxes(
    cx: Float, cy: Float, radius: Float,
    yaw: Float, pitch: Float, roll: Float,
    alpha: Float = 1.0f,
    isFrontCamera: Boolean = true
) {
    if (alpha <= 0.01f) return
    // Visual yaw is already normalized to preview coordinate space (negative = screen left, positive = screen right)
    val effectiveYaw = yaw
    val effectiveRoll = if (isFrontCamera) -roll else roll
    val yawRad = Math.toRadians(effectiveYaw.toDouble()).toFloat()
    val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()
    val rollRad = Math.toRadians(effectiveRoll.toDouble()).toFloat()
    val axisLen = radius * 0.70f

    // X-Axis (Yaw / Lateral Direction) - RED
    val xEnd = Offset(
        x = cx + axisLen * cos(yawRad) * cos(rollRad),
        y = cy + axisLen * sin(rollRad)
    )
    drawLine(Color(0xFFFF3B30).copy(alpha = alpha), Offset(cx, cy), xEnd, strokeWidth = 3.5f, cap = StrokeCap.Round)

    // Y-Axis (Pitch / Vertical Direction) - GREEN
    val yEnd = Offset(
        x = cx - axisLen * sin(rollRad),
        y = cy - axisLen * cos(pitchRad) * cos(rollRad)
    )
    drawLine(Color(0xFF34C759).copy(alpha = alpha), Offset(cx, cy), yEnd, strokeWidth = 3.5f, cap = StrokeCap.Round)

    // Z-Axis (Optical Normal / Depth Vector) - CYAN
    val zEnd = Offset(
        x = cx + axisLen * 0.55f * sin(yawRad),
        y = cy - axisLen * 0.55f * sin(pitchRad)
    )
    drawLine(Color(0xFF00E5FF).copy(alpha = alpha), Offset(cx, cy), zEnd, strokeWidth = 4.0f, cap = StrokeCap.Round)
}

/** Draws optical eye gaze vector rays radiating from pupils to focal target. */
private fun DrawScope.drawEyeGazeRays(
    cx: Float, cy: Float, radius: Float,
    gaze: EyeGazeResult,
    alpha: Float = 1.0f,
    isFrontCamera: Boolean = true,
    landmarks5Pts: Array<PointF>? = null
) {
    if (alpha <= 0.01f) return
    val gazeLen = radius * 0.65f
    val effectiveGazeYaw = if (isFrontCamera) -gaze.yaw else gaze.yaw
    val gazeYawRad = Math.toRadians(effectiveGazeYaw.toDouble()).toFloat()
    val gazePitchRad = Math.toRadians(gaze.pitch.toDouble()).toFloat()

    val leftEyeOrigin = if (landmarks5Pts != null && landmarks5Pts.isNotEmpty()) {
        Offset(landmarks5Pts[0].x, landmarks5Pts[0].y)
    } else {
        Offset(cx - radius * 0.32f, cy - radius * 0.20f)
    }
    val rightEyeOrigin = if (landmarks5Pts != null && landmarks5Pts.size > 1) {
        Offset(landmarks5Pts[1].x, landmarks5Pts[1].y)
    } else {
        Offset(cx + radius * 0.32f, cy - radius * 0.20f)
    }

    val rayColor = if (gaze.isGazeAttentive) Color(0xFF0A84FF).copy(alpha = alpha) else Color(0xFFFF9500).copy(alpha = alpha)

    for (origin in listOf(leftEyeOrigin, rightEyeOrigin)) {
        val target = Offset(
            x = origin.x + gazeLen * sin(gazeYawRad),
            y = origin.y - gazeLen * sin(gazePitchRad)
        )
        drawLine(rayColor, origin, target, strokeWidth = 2.8f, cap = StrokeCap.Round)
        drawCircle(rayColor, radius = 4.5f, center = target)
        drawCircle(Color.White.copy(alpha = alpha), radius = 2.0f, center = target)
    }
}

