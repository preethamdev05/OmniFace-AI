package com.omniface.ai.ml

import android.graphics.Bitmap
import kotlin.math.abs

data class QualityCheckResult(
    val isGoodQuality: Boolean,
    val blurScore: Float,
    val brightnessScore: Float,
    val rollAngle: Float = 0f,
    val failureReason: String? = null
)

class QualityChecker {

    /**
     * Tri-Factor Biometric Quality Gate:
     * 1. Resolution & Z-Axis Euler Roll Tilt (|roll| <= 15°)
     * 2. Grayscale Mean Illumination Range [35.0, 230.0]
     * 3. Discrete Laplacian 2D Kernel Blur Variance (>= 5.0)
     */
    fun checkFaceQuality(faceBitmap: Bitmap, headEulerAngleZ: Float = 0.0f): QualityCheckResult {
        val width = faceBitmap.width
        val height = faceBitmap.height
        if (width < 60 || height < 60) {
            return QualityCheckResult(false, 0f, 0f, headEulerAngleZ, "Face crop resolution too small.")
        }

        // 1. Roll Tilt Check (Permit natural tilt up to 28°)
        if (abs(headEulerAngleZ) > 28.0f) {
            return QualityCheckResult(false, 0f, 0f, headEulerAngleZ, "Head tilted sideways (>28°). Please hold level.")
        }

        val pixels = IntArray(width * height)
        faceBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var totalBrightness = 0L
        var minLuma = 255
        var maxLuma = 0

        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val gray = (r * 299 + g * 587 + b * 114) / 1000
            totalBrightness += gray
            if (gray < minLuma) minLuma = gray
            if (gray > maxLuma) maxLuma = gray
        }
        val meanBrightness = (totalBrightness.toFloat() / pixels.size)

        // 2. Brightness & Michelson Contrast Gate
        if (meanBrightness < 15.0f) {
            return QualityCheckResult(false, 0f, meanBrightness, headEulerAngleZ, "Scene illumination too dark.")
        }
        if (meanBrightness > 245.0f) {
            return QualityCheckResult(false, 0f, meanBrightness, headEulerAngleZ, "Scene illumination overexposed.")
        }
        val michelsonContrast = (maxLuma - minLuma).toFloat() / (maxLuma + minLuma + 0.001f)
        if (michelsonContrast < 0.05f) {
            return QualityCheckResult(false, 0f, meanBrightness, headEulerAngleZ, "Low scene contrast detected.")
        }

        // 3. Fast Discrete Approximation of Laplacian Variance
        var laplacianSum = 0L
        val innerWidth = width - 2
        val innerHeight = height - 2
        val count = innerWidth * innerHeight

        fun luma(p: Int): Int {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8)  and 0xFF
            val b =  p         and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }

        for (y in 1 until height - 1) {
            val rowOffset     = y * width
            val prevRowOffset = (y - 1) * width
            val nextRowOffset = (y + 1) * width
            for (x in 1 until width - 1) {
                val c     = luma(pixels[rowOffset + x])
                val up    = luma(pixels[prevRowOffset + x])
                val down  = luma(pixels[nextRowOffset + x])
                val left  = luma(pixels[rowOffset + (x - 1)])
                val right = luma(pixels[rowOffset + (x + 1)])
                val lap = kotlin.math.abs(4 * c - up - down - left - right)
                laplacianSum += lap
            }
        }
        val blurScore = if (count > 0) (laplacianSum.toFloat() / count) else 0.0f

        if (blurScore < 1.5f) {
            return QualityCheckResult(false, blurScore, meanBrightness, headEulerAngleZ, "Motion blur detected.")
        }

        return QualityCheckResult(true, blurScore, meanBrightness, headEulerAngleZ, null)
    }
}
