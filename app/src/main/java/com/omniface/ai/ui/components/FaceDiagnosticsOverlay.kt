package com.omniface.ai.ui.components

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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
    val gazeResult: EyeGazeResult? = null,
    val faceMap3DMM: FaceMap3DMMResult? = null,
    val attributes: FaceAttributesResult? = null,
    val meshResult: MediaPipeMeshResult? = null,
    val qualityScore: RegistrationQualityScore? = null,
    val confidenceZone: ConfidenceZone = ConfidenceZone.REJECT,
    val decisionMargin: Float = 0.0f,
    val similarityScore: Float = 0.0f,
    val studentName: String = "",
    val isLive: Boolean = true,
    val activeHardwareNpu: String = "Qualcomm Hexagon NPU"
)

/**
 * Sovereign 60 FPS Real-Time Biometric Analysis & Explainable AI Visualizer.
 *
 * Renders:
 * 1. 468-Point MediaPipe Dense 3D Facial Mesh Wireframe & Topological Contours
 * 2. 3D Head Pose Orientation Coordinate Frame (RGB Euler Vector Rays: Pitch, Yaw, Roll)
 * 3. Optical Eye Gaze Subpixel Vectors & Fixation Rays radiating from pupils
 * 4. Qualcomm FaceMap 3DMM Neural Depth Topography Contours & Surface Variance
 * 5. Dynamic Confidence Zone Corner Reticles & Holographic Glassmorphic Glow
 * 6. Real-Time Hardware NPU & Biometric Attribute Diagnostics HUD
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
    Canvas(modifier = modifier.fillMaxSize()) {
        for (face in visualData) {
            val rect = face.bounds
            val cx = rect.center.x
            val cy = rect.center.y
            val faceRadius = rect.width / 2f

            // 1. Reactive Reticle Halo & Specular Glass Glow
            val haloColor = when {
                !face.isLive -> Color(0xFFFF3B30) // Red (Spoof)
                face.confidenceZone == ConfidenceZone.ACCEPT -> Color(0xFF34C759) // Emerald Green (Accept)
                face.confidenceZone == ConfidenceZone.REVIEW -> Color(0xFFFF9500) // Amber (Review)
                else -> Color(0xFF007AFF) // Electric Blue (Scanning)
            }

            drawReticleBrackets(rect, haloColor)

            // 2. 468-Point MediaPipe Dense 3D Face Mesh Wireframe & HRNet Constellation
            val mesh = face.meshResult?.landmarks468x3
            if (showMeshWireframe && mesh != null && mesh.isNotEmpty()) {
                drawMediaPipeMeshTessellation(mesh, rect, haloColor)
            } else if (face.landmarks5Pts != null) {
                // Fallback: Canonical Alignment Landmarks
                for (p in face.landmarks5Pts) {
                    drawCircle(Color.White, radius = 4f, center = Offset(p.x, p.y))
                    drawCircle(Color(0xFF00E5FF), radius = 2f, center = Offset(p.x, p.y))
                }
            }

            // 3. Qualcomm FaceMap 3DMM Neural Depth Topography Contours
            val map3d = face.faceMap3DMM
            if (show3DMMTopography && map3d != null && map3d.isTrue3DSurface) {
                drawFaceMap3DMMContours(rect, map3d.depthVariance, haloColor)
            }

            // 4. 3D Head Pose Coordinate Frame Axes (Pitch, Yaw, Roll)
            if (showPoseAxes || isDeveloperMode) {
                drawHeadPoseAxes(cx, cy, faceRadius, face.yaw, face.pitch, face.roll)
            }

            // 5. Optical Eye Gaze Ray Vectors & Pupil Fixation
            val gaze = face.gazeResult
            if (showGazeRays && gaze != null) {
                drawEyeGazeRays(cx, cy, faceRadius, gaze)
            }

            // 6. Qualcomm AI Hub Cybernetic Attribute HUD Tags (Screenshot 3 & 4 style)
            if (isDeveloperMode || face.attributes != null) {
                drawQualcommAttributeTags(rect, face)
            }
        }
    }
}

/** Draws rounded reticle brackets with liquid specular glow. */
private fun DrawScope.drawReticleBrackets(rect: androidx.compose.ui.geometry.Rect, color: Color) {
    val cornerLen = (rect.width * 0.18f).coerceIn(18f, 42f)
    val strokeW = 3.5f

    // Soft surrounding bounding box
    val glowColor = color.copy(alpha = 0.25f)
    drawRect(glowColor, Offset(rect.left, rect.top), Size(rect.width, rect.height), style = Stroke(width = 1.2f))

    // Top-Left
    drawLine(color, Offset(rect.left, rect.top + cornerLen), Offset(rect.left, rect.top), strokeW, StrokeCap.Round)
    drawLine(color, Offset(rect.left, rect.top), Offset(rect.left + cornerLen, rect.top), strokeW, StrokeCap.Round)

    // Top-Right
    drawLine(color, Offset(rect.right - cornerLen, rect.top), Offset(rect.right, rect.top), strokeW, StrokeCap.Round)
    drawLine(color, Offset(rect.right, rect.top), Offset(rect.right, rect.top + cornerLen), strokeW, StrokeCap.Round)

    // Bottom-Left
    drawLine(color, Offset(rect.left, rect.bottom - cornerLen), Offset(rect.left, rect.bottom), strokeW, StrokeCap.Round)
    drawLine(color, Offset(rect.left, rect.bottom), Offset(rect.left + cornerLen, rect.bottom), strokeW, StrokeCap.Round)

    // Bottom-Right
    drawLine(color, Offset(rect.right - cornerLen, rect.bottom), Offset(rect.right, rect.bottom), strokeW, StrokeCap.Round)
    drawLine(color, Offset(rect.right, rect.bottom), Offset(rect.right, rect.bottom - cornerLen), strokeW, StrokeCap.Round)
}

/** Draws dense MediaPipe 468-point 3D facial mesh tessellation wireframe & HRNet fiducials. */
private fun DrawScope.drawMediaPipeMeshTessellation(
    mesh: Array<FloatArray>,
    bounds: androidx.compose.ui.geometry.Rect,
    themeColor: Color
) {
    fun meshPointToOffset(idx: Int): Offset? {
        if (idx !in mesh.indices) return null
        val p = mesh[idx]
        val x = bounds.left + p[0] * bounds.width
        val y = bounds.top + p[1] * bounds.height
        return Offset(x, y)
    }

    val wireColor = Color.White.copy(alpha = 0.45f)
    val haloColor = themeColor.copy(alpha = 0.70f)

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
        drawCircle(Color.White.copy(alpha = 0.35f), radius = 1.2f, center = pt)
    }

    // 3. HRNet-Style 29 Keypoint Glowing Constellation (Screenshot 4)
    val fiducialKeypoints = intArrayOf(
        10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152,
        70, 105, 46, 336, 334, 276,
        33, 133, 362, 263, 1, 4, 2,
        61, 291, 0, 17
    )

    for (idx in fiducialKeypoints) {
        val pt = meshPointToOffset(idx) ?: continue
        // Outer Specular Halo (Theme Glowing Ring)
        drawCircle(haloColor, radius = 5.0f, center = pt)
        // Crisp Solid White Core
        drawCircle(Color.White, radius = 2.2f, center = pt)
    }
}

private val sharedTagTextPaint by lazy {
    android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = 24f
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
    }
}

private val sharedTagBgPaint by lazy {
    android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.FILL
        color = android.graphics.Color.argb(220, 11, 15, 25) // Frosted Obsidian Slate
    }
}

private val sharedTagBorderPaint by lazy {
    android.graphics.Paint().apply {
        isAntiAlias = true
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 2.0f
        color = android.graphics.Color.argb(160, 255, 255, 255) // Crisp white hairline
    }
}

/** Draws Qualcomm AI Hub Cybernetic Attribute Floating HUD Tags (Screenshot 3 style). */
private fun DrawScope.drawQualcommAttributeTags(
    bounds: androidx.compose.ui.geometry.Rect,
    face: FaceGeometryVisualData
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas

    val tagTextPaint = sharedTagTextPaint
    val tagBgPaint = sharedTagBgPaint
    val tagBorderPaint = sharedTagBorderPaint

    val attr = face.attributes
    val tags = mutableListOf<Pair<String, Int>>()

    // Tag 1: Identity Embedding
    tags.add("Identity Embedding" to android.graphics.Color.WHITE)

    // Tag 2: Eye Openness
    val eyeOpen = (attr?.rawProbabilities?.getOrNull(3) ?: 0.95f) > 0.4f
    val eyeColor = if (eyeOpen) android.graphics.Color.rgb(52, 199, 89) else android.graphics.Color.rgb(255, 59, 48)
    tags.add("Eye Openness ${if (eyeOpen) "True" else "False"}" to eyeColor)

    // Tag 3: Liveness
    val liveColor = if (face.isLive) android.graphics.Color.rgb(52, 199, 89) else android.graphics.Color.rgb(255, 59, 48)
    tags.add("Liveness ${if (face.isLive) "True" else "False"}" to liveColor)

    // Tag 4: Mask
    val mask = (attr?.rawProbabilities?.getOrNull(2) ?: 0.05f) > 0.5f
    val maskColor = if (mask) android.graphics.Color.rgb(255, 149, 0) else android.graphics.Color.rgb(180, 180, 180)
    tags.add("Mask ${if (mask) "True" else "False"}" to maskColor)

    // Tag 5: Glasses
    val glasses = (attr?.rawProbabilities?.getOrNull(1) ?: 0.1f) > 0.5f
    val glassesColor = if (glasses) android.graphics.Color.rgb(255, 149, 0) else android.graphics.Color.rgb(180, 180, 180)
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
    color: Color
) {
    val rings = (depthVariance * 10f).toInt().coerceIn(2, 4)
    for (i in 1..rings) {
        val scale = 0.40f + (i * 0.18f)
        val w = bounds.width * scale
        val h = bounds.height * scale
        val left = bounds.center.x - w / 2f
        val top = bounds.center.y - h / 2f
        val alpha = (0.22f * (1f - (i * 0.2f)) * (depthVariance * 2.5f).coerceIn(0.6f, 1.4f)).coerceIn(0.05f, 0.40f)
        drawOval(
            color = color.copy(alpha = alpha),
            topLeft = Offset(left, top),
            size = Size(w, h),
            style = Stroke(width = 1.2f)
        )
    }
}

/** Draws 3D Head Pose Coordinate Frame Axes (Pitch, Yaw, Roll) anchored at face center. */
private fun DrawScope.drawHeadPoseAxes(
    cx: Float, cy: Float, radius: Float,
    yaw: Float, pitch: Float, roll: Float
) {
    val yawRad = Math.toRadians(yaw.toDouble()).toFloat()
    val pitchRad = Math.toRadians(pitch.toDouble()).toFloat()
    val rollRad = Math.toRadians(roll.toDouble()).toFloat()
    val axisLen = radius * 0.70f

    // X-Axis (Yaw / Lateral Direction) - RED
    val xEnd = Offset(
        x = cx + axisLen * cos(yawRad) * cos(rollRad),
        y = cy + axisLen * sin(rollRad)
    )
    drawLine(Color(0xFFFF3B30), Offset(cx, cy), xEnd, strokeWidth = 3.5f, cap = StrokeCap.Round)

    // Y-Axis (Pitch / Vertical Direction) - GREEN
    val yEnd = Offset(
        x = cx - axisLen * sin(rollRad),
        y = cy - axisLen * cos(pitchRad) * cos(rollRad)
    )
    drawLine(Color(0xFF34C759), Offset(cx, cy), yEnd, strokeWidth = 3.5f, cap = StrokeCap.Round)

    // Z-Axis (Optical Normal / Depth Vector) - CYAN
    val zEnd = Offset(
        x = cx - axisLen * 0.55f * sin(yawRad),
        y = cy - axisLen * 0.55f * sin(pitchRad)
    )
    drawLine(Color(0xFF00E5FF), Offset(cx, cy), zEnd, strokeWidth = 4.0f, cap = StrokeCap.Round)
}

/** Draws optical eye gaze vector rays radiating from pupils to focal target. */
private fun DrawScope.drawEyeGazeRays(
    cx: Float, cy: Float, radius: Float,
    gaze: EyeGazeResult
) {
    val gazeLen = radius * 0.65f
    val gazeYawRad = Math.toRadians(gaze.yaw.toDouble()).toFloat()
    val gazePitchRad = Math.toRadians(gaze.pitch.toDouble()).toFloat()

    val leftEyeOrigin = Offset(cx - radius * 0.32f, cy - radius * 0.20f)
    val rightEyeOrigin = Offset(cx + radius * 0.32f, cy - radius * 0.20f)

    val rayColor = if (gaze.isGazeAttentive) Color(0xFF0A84FF) else Color(0xFFFF9500)

    for (origin in listOf(leftEyeOrigin, rightEyeOrigin)) {
        val target = Offset(
            x = origin.x + gazeLen * sin(gazeYawRad),
            y = origin.y - gazeLen * sin(gazePitchRad)
        )
        drawLine(rayColor, origin, target, strokeWidth = 2.8f, cap = StrokeCap.Round)
        drawCircle(rayColor, radius = 4.5f, center = target)
        drawCircle(Color.White, radius = 2.0f, center = target)
    }
}

