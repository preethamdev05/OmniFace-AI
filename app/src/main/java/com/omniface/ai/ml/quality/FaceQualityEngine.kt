package com.omniface.ai.ml.quality

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import kotlin.math.abs

data class QualityGateResult(
    val isPassed: Boolean,
    val overallQualityScore: Float, // 0.0 to 100.0
    val sharpnessScore: Float,      // 0.0 to 100.0
    val exposureScore: Float,       // 0.0 to 100.0
    val poseScore: Float,           // 0.0 to 100.0
    val sizeScore: Float,           // 0.0 to 100.0
    val rejectionReason: String
)

/**
 * Multi-Factor Biometric Quality Gate Engine (Gate 1).
 *
 * Pre-screens camera frames before expensive embedding extraction:
 * - Rejects motion-blurred or out-of-focus crops
 * - Rejects overexposed glare or extreme underexposed shadows
 * - Rejects extreme head yaw / pitch profiles
 * - Rejects distant faces that lack sufficient pixel density
 */
object FaceQualityEngine {

    private const val MIN_FACE_WIDTH_RATIO = 0.05f // Face must occupy >= 5% of camera frame width (natural kiosk distance)
    private const val MAX_ALLOWED_YAW = 38.0f
    private const val MAX_ALLOWED_PITCH = 32.0f
    private const val MAX_ALLOWED_ROLL = 28.0f
    private const val MIN_SHARPNESS_VARIANCE = 8.0f // Realistic Laplacian threshold for mobile cameras

    fun evaluateFaceQuality(
        face: Face,
        fullFrameWidth: Int,
        fullFrameHeight: Int,
        faceCrop: Bitmap?
    ): QualityGateResult {
        val box = face.boundingBox

        // 1. Face Size Fraction
        val widthFraction = box.width().toFloat() / fullFrameWidth.toFloat().coerceAtLeast(1f)
        val sizeScore = ((widthFraction / 0.35f).coerceIn(0f, 1f) * 100f)
        val isSizeOk = widthFraction >= MIN_FACE_WIDTH_RATIO

        // Height sanity check for portrait crop validation
        val heightFraction = box.height().toFloat() / fullFrameHeight.toFloat().coerceAtLeast(1f)
        val isAspectOk = heightFraction >= MIN_FACE_WIDTH_RATIO * 0.8f

        // 2. Pose Angle Evaluation
        val yaw = abs(face.headEulerAngleY)
        val pitch = abs(face.headEulerAngleX)
        val roll = abs(face.headEulerAngleZ)
        val isPoseOk = yaw <= MAX_ALLOWED_YAW && pitch <= MAX_ALLOWED_PITCH && roll <= MAX_ALLOWED_ROLL
        val poseScore = (100f - (yaw * 2f + pitch * 2f + roll * 1.5f)).coerceIn(0f, 100f)

        // 3. Image Sharpness & Illumination Analysis (if crop is available)
        var sharpnessScore = 85.0f
        var exposureScore = 90.0f
        var isSharpOk = true
        var isExposureOk = true

        if (faceCrop != null && !faceCrop.isRecycled) {
            val (sharpVal, meanLum) = computeImageMetrics(faceCrop)
            sharpnessScore = (sharpVal * 4.0f).coerceIn(0f, 100f)
            isSharpOk = sharpVal >= MIN_SHARPNESS_VARIANCE

            val lumDiff = abs(meanLum - 128.0f)
            exposureScore = (100.0f - lumDiff * 0.70f).coerceIn(0f, 100f)
            isExposureOk = meanLum in 15.0f..245.0f
        }

        // 4. Overall Weighted Score Synthesis
        val overallScore = (sharpnessScore * 0.35f + exposureScore * 0.25f + poseScore * 0.25f + sizeScore * 0.15f)

        val isPassed = isSizeOk && isAspectOk && isPoseOk && isSharpOk && isExposureOk

        val reason = when {
            !isSizeOk -> "Face too far — move closer to camera"
            !isPoseOk -> "Level face — look directly at camera"
            !isSharpOk -> "Image blurry — hold still"
            !isExposureOk -> "Poor lighting — avoid harsh glare or deep shadow"
            else -> "High Biometric Quality"
        }

        return QualityGateResult(
            isPassed = isPassed,
            overallQualityScore = overallScore,
            sharpnessScore = sharpnessScore,
            exposureScore = exposureScore,
            poseScore = poseScore,
            sizeScore = sizeScore,
            rejectionReason = reason
        )
    }

    private fun computeImageMetrics(bitmap: Bitmap): Pair<Float, Float> {
        val w = bitmap.width
        val h = bitmap.height
        val step = maxOf(1, minOf(w, h) / 32)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        var totalLum = 0.0
        var count = 0
        var varianceSum = 0.0

        for (y in 1 until h - 1 step step) {
            for (x in 1 until w - 1 step step) {
                val idx = y * w + x
                val center = getLuminance(pixels[idx])
                val top = getLuminance(pixels[(y - 1) * w + x])
                val bottom = getLuminance(pixels[(y + 1) * w + x])
                val left = getLuminance(pixels[y * w + (x - 1)])
                val right = getLuminance(pixels[y * w + (x + 1)])

                // Laplacian 2nd-order discrete derivative
                val laplacian = 4.0 * center - top - bottom - left - right
                varianceSum += (laplacian * laplacian)
                totalLum += center
                count++
            }
        }

        val safeCount = count.coerceAtLeast(1)
        val meanLuminance = (totalLum / safeCount).toFloat()
        val sharpnessVariance = (varianceSum / safeCount).toFloat()

        return Pair(sharpnessVariance, meanLuminance)
    }

    private fun getLuminance(pixel: Int): Double {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
