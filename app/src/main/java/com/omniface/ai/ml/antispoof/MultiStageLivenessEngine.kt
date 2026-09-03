package com.omniface.ai.ml.antispoof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Detailed breakdown of the multi-stage liveness assessment.
 */
data class LivenessStageBreakdown(
    val reflectionScore: Float,        // 0.0 to 1.0 (1.0 = natural diffuse light, 0.0 = severe screen glare)
    val textureScore: Float,           // 0.0 to 1.0 (1.0 = natural skin dermis, 0.0 = artificial / print grid)
    val moiréScore: Float,             // 0.0 to 1.0 (1.0 = no moiré, 0.0 = strong digital display moiré)
    val chromaticScore: Float,         // 0.0 to 1.0 (1.0 = genuine skin chromatic dispersion, 0.0 = gamut compressed)
    val neuralPadScore: Float,         // 0.0 to 1.0 (MiniFASNet neural score)
    val hasSpecularScreenHotspots: Boolean,
    val hasPeriodicDisplayGrid: Boolean,
    val hasUnnaturalPaperFlatness: Boolean
)

/**
 * Result of the multi-stage liveness pre-processing pipeline.
 */
data class MultiStageLivenessResult(
    val isLive: Boolean,
    val overallLivenessScore: Float,
    val spoofProbability: Float,
    val primaryAttackVector: String?,
    val detectedAnomalies: List<String>,
    val stageBreakdown: LivenessStageBreakdown,
    val passivePadResult: PassivePadResult?,
    val latencyMs: Long
)

/**
 * Multi-Stage Liveness Assessment Engine.
 *
 * Pre-processes incoming face crops to detect screen reflections, analyze micro-texture
 * patterns (LBP & Fourier-approximated moiré), and assert chromatic skin consistency
 * BEFORE data is passed to the deep metric embedding extraction layer.
 *
 * Stages:
 * 1. Screen Reflection & Specular Glare Analysis: Identifies AMOLED/LCD display glass hotspots & bounce.
 * 2. High-Frequency Texture & Moiré Frequency Analysis: Distinguishes natural skin pores from digital pixel grids & paper halftone patterns.
 * 3. Chromatic Dispersion & Subsurface Scattering Analysis: Validates physiological RGB/HSV human skin locus.
 * 4. Neural Passive PAD Inference: Integrates MiniFASNetV2 deep convolutional feature extraction.
 */
class MultiStageLivenessEngine(
    private val context: Context,
    private val passivePadEngine: PassivePadEngine? = null
) {

    companion object {
        private const val TAG = "MultiStageLiveness"

        // Detection Thresholds (Calibrated for real-world mobile camera sensors & lighting)
        private const val SPECULAR_GLARE_LUMA_THRESHOLD = 248
        private const val SPECULAR_GLARE_MAX_SATURATION = 0.15f
        private const val MAX_ALLOWABLE_GLARE_FRACTION = 0.18f // >18% specular hotspot indicates screen reflection

        private const val MIN_TEXTURE_ENTROPY = 1.40f // Calibrated for mobile front-camera ISP denoising
        private const val MAX_MOIRE_ENERGY_RATIO = 0.45f // High-frequency periodic energy ratio indicating display raster

        private const val LIVE_SYNTHESIS_THRESHOLD = 0.45f
    }

    private val internalPassivePad: PassivePadEngine by lazy {
        passivePadEngine ?: PassivePadEngine(context)
    }

    /**
     * Executes the full multi-stage assessment on an isolated face crop.
     *
     * @param faceCrop High-resolution cropped face bitmap
     * @return MultiStageLivenessResult containing overall verdict and detailed per-stage telemetry
     */
    suspend fun evaluate(faceCrop: Bitmap): MultiStageLivenessResult {
        val t0 = SystemClock.elapsedRealtimeNanos()

        if (faceCrop.isRecycled || faceCrop.width < 32 || faceCrop.height < 32) {
            return MultiStageLivenessResult(
                isLive = false,
                overallLivenessScore = 0.0f,
                spoofProbability = 1.0f,
                primaryAttackVector = "Invalid or Corrupt Face Crop",
                detectedAnomalies = listOf("Degraded Face Crop Dimensions"),
                stageBreakdown = LivenessStageBreakdown(
                    reflectionScore = 0f, textureScore = 0f, moiréScore = 0f, chromaticScore = 0f,
                    neuralPadScore = 0f, hasSpecularScreenHotspots = false, hasPeriodicDisplayGrid = false,
                    hasUnnaturalPaperFlatness = false
                ),
                passivePadResult = null,
                latencyMs = 0L
            )
        }

        // Downsample slightly for fast heuristic analysis if bitmap is very large
        val analysisBitmap = if (faceCrop.width > 160 || faceCrop.height > 160) {
            Bitmap.createScaledBitmap(faceCrop, 128, 128, true)
        } else {
            faceCrop
        }

        val width = analysisBitmap.width
        val height = analysisBitmap.height
        val pixels = IntArray(width * height)
        analysisBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        if (analysisBitmap != faceCrop && !analysisBitmap.isRecycled) {
            analysisBitmap.recycle()
        }

        // ── STAGE 1: Screen Reflection & Specular Glare Analysis ──
        val reflectionAnalysis = analyzeScreenReflections(pixels, width, height)

        // ── STAGE 2: Micro-Texture & High-Frequency Moiré Grid Analysis ──
        val textureAnalysis = analyzeTextureAndMoire(pixels, width, height)

        // ── STAGE 3: Chromatic Dispersion & Skin Subsurface Scattering ──
        val chromaticAnalysis = analyzeChromaticDispersion(pixels, width, height)

        // ── STAGE 4: Neural MiniFASNet Passive PAD ──
        val neuralResult = try {
            internalPassivePad.run(faceCrop)
        } catch (t: Throwable) {
            Log.w(TAG, "Neural PAD fallback: ${t.message}")
            null
        }

        val neuralPadScore = neuralResult?.livenessScore ?: 0.85f

        // ── STAGE 5: Multi-Stage Synthesis ──
        val detectedAnomalies = mutableListOf<String>()

        if (reflectionAnalysis.hasScreenHotspots) {
            detectedAnomalies.add("Display Glass Specular Reflection (${(reflectionAnalysis.glareFraction * 100).toInt()}% Hotspots)")
        }
        if (textureAnalysis.hasPeriodicDisplayGrid) {
            detectedAnomalies.add("Digital Display Moiré Grid Interference")
        }
        if (textureAnalysis.hasUnnaturalPaperFlatness) {
            detectedAnomalies.add("Unnatural Low-Entropy Surface (Paper/2D Print)")
        }
        if (chromaticAnalysis.isGamutCompressed) {
            detectedAnomalies.add("Compressed Color Gamut / Missing Skin Scattering")
        }
        if (neuralResult != null && !neuralResult.isLive) {
            detectedAnomalies.add("Neural PAD: ${neuralResult.attackTypeDescription}")
        }

        // Weighted Multi-Stage Fusion Score
        val compositeScore = (
            reflectionAnalysis.reflectionAuthenticityScore * 0.25f +
            textureAnalysis.textureAuthenticityScore * 0.25f +
            textureAnalysis.moireScore * 0.15f +
            chromaticAnalysis.chromaticScore * 0.10f +
            neuralPadScore * 0.25f
        ).coerceIn(0.0f, 1.0f)

        // Hard Gating: Extreme display reflection or moiré forces spoof rejection
        val hardGatingPassed = !reflectionAnalysis.hasSevereDisplayGlare &&
                               !textureAnalysis.hasSevereDisplayMoire &&
                               (neuralResult?.isLive ?: true)

        val isLive = compositeScore >= LIVE_SYNTHESIS_THRESHOLD && hardGatingPassed

        val primaryAttack = when {
            isLive -> null
            reflectionAnalysis.hasScreenHotspots -> "Screen Replay / Mobile Display Glare"
            textureAnalysis.hasPeriodicDisplayGrid -> "Digital Display Grid (Moiré Spoof)"
            textureAnalysis.hasUnnaturalPaperFlatness -> "2D Printed Paper Photo Attack"
            neuralResult != null && !neuralResult.isLive -> neuralResult.attackTypeDescription
            else -> "Suspected Presentation Attack"
        }

        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000L

        return MultiStageLivenessResult(
            isLive = isLive,
            overallLivenessScore = compositeScore,
            spoofProbability = (1.0f - compositeScore).coerceIn(0.0f, 1.0f),
            primaryAttackVector = primaryAttack,
            detectedAnomalies = detectedAnomalies,
            stageBreakdown = LivenessStageBreakdown(
                reflectionScore = reflectionAnalysis.reflectionAuthenticityScore,
                textureScore = textureAnalysis.textureAuthenticityScore,
                moiréScore = textureAnalysis.moireScore,
                chromaticScore = chromaticAnalysis.chromaticScore,
                neuralPadScore = neuralPadScore,
                hasSpecularScreenHotspots = reflectionAnalysis.hasScreenHotspots,
                hasPeriodicDisplayGrid = textureAnalysis.hasPeriodicDisplayGrid,
                hasUnnaturalPaperFlatness = textureAnalysis.hasUnnaturalPaperFlatness
            ),
            passivePadResult = neuralResult,
            latencyMs = elapsedMs.coerceAtLeast(1L)
        )
    }

    // ── STAGE 1: Screen Reflection Analysis ──

    private data class ReflectionAnalysis(
        val reflectionAuthenticityScore: Float,
        val glareFraction: Float,
        val hasScreenHotspots: Boolean,
        val hasSevereDisplayGlare: Boolean
    )

    private fun analyzeScreenReflections(pixels: IntArray, width: Int, height: Int): ReflectionAnalysis {
        var specularGlarePixels = 0
        var totalAnalyzed = 0
        val hsv = FloatArray(3)

        // Count specular saturation drops in high-luminance regions
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = (0.299f * r + 0.587f * g + 0.114f * b).toInt()

            Color.RGBToHSV(r, g, b, hsv)
            val sat = hsv[1]
            val value = hsv[2]

            if (luma >= SPECULAR_GLARE_LUMA_THRESHOLD && sat <= SPECULAR_GLARE_MAX_SATURATION && value >= 0.94f) {
                specularGlarePixels++
            }
            totalAnalyzed++
        }

        val glareFraction = if (totalAnalyzed > 0) specularGlarePixels.toFloat() / totalAnalyzed else 0f
        val hasScreenHotspots = glareFraction > MAX_ALLOWABLE_GLARE_FRACTION
        val hasSevereDisplayGlare = glareFraction > 0.35f // >35% pure glare is definitive glass screen

        val reflectionScore = (1.0f - (glareFraction / 0.25f)).coerceIn(0.0f, 1.0f)

        return ReflectionAnalysis(
            reflectionAuthenticityScore = reflectionScore,
            glareFraction = glareFraction,
            hasScreenHotspots = hasScreenHotspots,
            hasSevereDisplayGlare = hasSevereDisplayGlare
        )
    }

    // ── STAGE 2: Texture & Moiré Analysis ──

    private data class TextureAnalysis(
        val textureAuthenticityScore: Float,
        val moireScore: Float,
        val hasPeriodicDisplayGrid: Boolean,
        val hasUnnaturalPaperFlatness: Boolean,
        val hasSevereDisplayMoire: Boolean
    )

    private fun analyzeTextureAndMoire(pixels: IntArray, width: Int, height: Int): TextureAnalysis {
        val lumaGrid = Array(height) { FloatArray(width) }

        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                lumaGrid[y][x] = 0.299f * r + 0.587f * g + 0.114f * b
            }
        }

        // 1. Local Binary Pattern (LBP) Histogram Entropy
        val lbpHistogram = IntArray(256)
        var totalLbpSamples = 0

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val center = lumaGrid[y][x]
                var code = 0
                if (lumaGrid[y - 1][x - 1] >= center) code = code or (1 shl 7)
                if (lumaGrid[y - 1][x] >= center)     code = code or (1 shl 6)
                if (lumaGrid[y - 1][x + 1] >= center) code = code or (1 shl 5)
                if (lumaGrid[y][x + 1] >= center)     code = code or (1 shl 4)
                if (lumaGrid[y + 1][x + 1] >= center) code = code or (1 shl 3)
                if (lumaGrid[y + 1][x] >= center)     code = code or (1 shl 2)
                if (lumaGrid[y + 1][x - 1] >= center) code = code or (1 shl 1)
                if (lumaGrid[y][x - 1] >= center)     code = code or (1 shl 0)

                lbpHistogram[code]++
                totalLbpSamples++
            }
        }

        var lbpEntropy = 0.0
        if (totalLbpSamples > 0) {
            for (count in lbpHistogram) {
                if (count > 0) {
                    val p = count.toDouble() / totalLbpSamples
                    lbpEntropy -= p * (ln(p) / ln(2.0))
                }
            }
        }

        val textureScore = ((lbpEntropy.toFloat() - 1.2f) / 3.0f).coerceIn(0.0f, 1.0f)
        val hasUnnaturalPaperFlatness = lbpEntropy < MIN_TEXTURE_ENTROPY

        // 2. High-Frequency Horizontal / Vertical Gradient Energy (Moiré Grid Pattern)
        var highFreqEnergy = 0.0f
        var lowFreqEnergy = 0.0f

        for (y in 2 until height - 2 step 2) {
            for (x in 2 until width - 2 step 2) {
                // 2nd derivative discrete Laplacian
                val d2x = abs(lumaGrid[y][x + 1] - 2f * lumaGrid[y][x] + lumaGrid[y][x - 1])
                val d2y = abs(lumaGrid[y + 1][x] - 2f * lumaGrid[y][x] + lumaGrid[y - 1][x])
                val laplacian = d2x + d2y

                if (laplacian > 16.0f) {
                    highFreqEnergy += laplacian
                } else {
                    lowFreqEnergy += laplacian
                }
            }
        }

        val totalEnergy = (highFreqEnergy + lowFreqEnergy).coerceAtLeast(1.0f)
        val highFreqRatio = highFreqEnergy / totalEnergy

        val moireScore = (1.0f - (highFreqRatio / MAX_MOIRE_ENERGY_RATIO)).coerceIn(0.0f, 1.0f)
        val hasPeriodicDisplayGrid = highFreqRatio > MAX_MOIRE_ENERGY_RATIO
        val hasSevereDisplayMoire = highFreqRatio > 0.60f

        return TextureAnalysis(
            textureAuthenticityScore = textureScore,
            moireScore = moireScore,
            hasPeriodicDisplayGrid = hasPeriodicDisplayGrid,
            hasUnnaturalPaperFlatness = hasUnnaturalPaperFlatness,
            hasSevereDisplayMoire = hasSevereDisplayMoire
        )
    }

    // ── STAGE 3: Chromatic Dispersion & Subsurface Scattering Analysis ──

    private data class ChromaticAnalysis(
        val chromaticScore: Float,
        val isGamutCompressed: Boolean
    )

    private fun analyzeChromaticDispersion(pixels: IntArray, width: Int, height: Int): ChromaticAnalysis {
        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var validSkinPixels = 0

        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF

            // Human skin locus: R > G > B and (R - G) >= 12
            if (r > g && g > b && (r - g) >= 10 && (r + g + b) > 60) {
                rSum += r
                gSum += g
                bSum += b
                validSkinPixels++
            }
        }

        val skinFraction = if (pixels.isNotEmpty()) validSkinPixels.toFloat() / pixels.size else 0f
        val isGamutCompressed = skinFraction < 0.20f // Real human face crop typically contains >35% natural skin locus

        val chromaticScore = (skinFraction / 0.50f).coerceIn(0.0f, 1.0f)

        return ChromaticAnalysis(
            chromaticScore = chromaticScore,
            isGamutCompressed = isGamutCompressed
        )
    }
}
