package com.omniface.ai.attendance

import android.graphics.Bitmap
import kotlin.math.abs

data class QualityGateResult(
    val isPassed: Boolean,
    val blurScore: Float,
    val brightnessScore: Float,
    val headEulerRoll: Float,
    val rejectionReason: String? = null
)

class QualityGate {

    fun evaluateFaceQuality(faceCrop: Bitmap, rollAngle: Float = 0f): QualityGateResult {
        val width = faceCrop.width
        val height = faceCrop.height
        if (width < 40 || height < 40) {
            return QualityGateResult(false, 0f, 0f, rollAngle, "Face resolution too low ($width x $height)")
        }

        val pixels = IntArray(width * height)
        faceCrop.getPixels(pixels, 0, width, 0, 0, width, height)

        // 1. Average Brightness Calculation
        var totalLuma = 0L
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (r * 299 + g * 587 + b * 114) / 1000
            totalLuma += luma
        }
        val meanBrightness = totalLuma.toFloat() / (width * height)

        if (meanBrightness < 35.0f) {
            return QualityGateResult(false, 0f, meanBrightness, rollAngle, "Lighting too dark (Luma: ${meanBrightness.toInt()})")
        }
        if (meanBrightness > 235.0f) {
            return QualityGateResult(false, 0f, meanBrightness, rollAngle, "Overexposed glare (Luma: ${meanBrightness.toInt()})")
        }

        // 2. Fast Discrete Laplacian Variance on Luma
        var laplacianSum = 0L
        val innerW = width - 2
        val innerH = height - 2
        val count = innerW * innerH

        fun getLuma(p: Int): Int {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return (r * 299 + g * 587 + b * 114) / 1000
        }

        for (y in 1 until height - 1) {
            val row = y * width
            val prev = (y - 1) * width
            val next = (y + 1) * width
            for (x in 1 until width - 1) {
                val c = getLuma(pixels[row + x])
                val up = getLuma(pixels[prev + x])
                val down = getLuma(pixels[next + x])
                val left = getLuma(pixels[row + x - 1])
                val right = getLuma(pixels[row + x + 1])
                val lap = abs(4 * c - up - down - left - right)
                laplacianSum += lap
            }
        }
        val blurScore = if (count > 0) (laplacianSum.toFloat() / count) else 0f

        if (blurScore < 4.5f) {
            return QualityGateResult(false, blurScore, meanBrightness, rollAngle, "Motion blur detected ($blurScore < 4.5)")
        }

        if (abs(rollAngle) > 25.0f) {
            return QualityGateResult(false, blurScore, meanBrightness, rollAngle, "Excessive head tilt ($rollAngle°)")
        }

        return QualityGateResult(true, blurScore, meanBrightness, rollAngle, null)
    }
}
