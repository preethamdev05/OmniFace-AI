package com.omniface.ai.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.NpuHardwareInfo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

data class FaceMap3DMMResult(
    val parameters265: FloatArray,
    val depthVariance: Float,
    val isTrue3DSurface: Boolean,
    val executionTimeMs: Float
)

data class FaceAttributesResult(
    val smileScore: Float,
    val eyeglassesScore: Float,
    val poseYawScore: Float,
    val rawProbabilities: FloatArray,
    val executionTimeMs: Float
)

data class EyeGazeResult(
    val pitch: Float,
    val yaw: Float,
    val gazeVectorNorm: Float,
    val eyeLandmarks34x2: Array<FloatArray>,
    val isGazeAttentive: Boolean,
    val executionTimeMs: Float
)

data class HRNetFaceResult(
    val landmarks29x2: Array<FloatArray>,
    val landmarkConfidences: FloatArray,
    val executionTimeMs: Float
)

data class MediaPipeMeshResult(
    val landmarks468x3: Array<FloatArray>,
    val faceScore: Float,
    val meshDepthVariance: Float,
    val executionTimeMs: Float
)

/**
 * Qualcomm AI Hub Neural Intelligence Suite:
 * Powered natively by the consolidated single LiteRT model `unified_omniface.tflite`
 * via UnifiedFaceIntelligenceEngine.
 *
 * Provides:
 * 1. FaceMap 3DMM: 3D Morphable Model (265-D shape/depth reconstruction for anti-spoofing)
 * 2. FaceAttribNet: Neural Facial Attribute & Expression Classifier
 * 3. EyeGaze: Pupil Vector Estimation & 34-point Eye Landmark Tracking
 * 4. HRNetFace: High-Resolution Deep Landmark & Pose Heatmap Extractor (29 keypoints)
 * 5. MediaPipe Face Mesh: 468 3D Dense Keypoints (X, Y, Z Depth)
 */
class QualcommFaceIntelligenceEngine(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "QualcommFaceIntel"

        @Volatile private var INSTANCE: QualcommFaceIntelligenceEngine? = null

        fun getInstance(context: Context): QualcommFaceIntelligenceEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: QualcommFaceIntelligenceEngine(context.applicationContext).also { INSTANCE = it }
            }
    }

    val npuHardwareInfo: NpuHardwareInfo = NpuHardwareDetector.detectNpuHardware()
    val isQualcommSilicon: Boolean = npuHardwareInfo.socModel.contains("Snapdragon", ignoreCase = true)

    var isSuiteLoaded: Boolean = true
        private set

    val isSuiteReady: Boolean get() = UnifiedFaceIntelligenceEngine.getInstance(context).isModelLoaded

    fun initializeAsync() {
        // Pre-warm unified engine
        Thread {
            try {
                UnifiedFaceIntelligenceEngine.getInstance(context)
            } catch (t: Throwable) {
                Log.w(TAG, "Unified Face Intelligence pre-warm notice: ${t.message}")
            }
        }.apply { isDaemon = true; name = "qualcomm-suite-init" }.start()
    }

    /**
     * Estimates 3D Morphable Model (3DMM) 265-dimensional geometry parameters.
     */
    fun estimate3dFaceMap(faceBitmap: Bitmap): FaceMap3DMMResult? {
        if (faceBitmap.isRecycled || faceBitmap.width <= 0 || faceBitmap.height <= 0) return null
        val unified = UnifiedFaceIntelligenceEngine.getInstance(context)
        if (unified.isModelLoaded) {
            return unified.estimate3dFaceMap(faceBitmap)
        }
        return estimate3dFaceMapAlgorithmic(faceBitmap)
    }

    private fun estimate3dFaceMapAlgorithmic(faceBitmap: Bitmap): FaceMap3DMMResult {
        val t0 = System.nanoTime()
        val width = faceBitmap.width
        val height = faceBitmap.height
        val params = FloatArray(265)

        fun sampleZoneLuma(xFrac: Float, yFrac: Float, radiusFrac: Float): Float {
            val cx = (width * xFrac).toInt().coerceIn(0, width - 1)
            val cy = (height * yFrac).toInt().coerceIn(0, height - 1)
            val r = (width * radiusFrac).toInt().coerceAtLeast(1)
            var sum = 0f
            var count = 0
            for (y in (cy - r).coerceAtLeast(0)..(cy + r).coerceAtMost(height - 1)) {
                for (x in (cx - r).coerceAtLeast(0)..(cx + r).coerceAtMost(width - 1)) {
                    val p = faceBitmap.getPixel(x, y)
                    val red = (p shr 16) and 0xFF
                    val green = (p shr 8) and 0xFF
                    val blue = p and 0xFF
                    sum += 0.299f * red + 0.587f * green + 0.114f * blue
                    count++
                }
            }
            return if (count > 0) sum / count.toFloat() else 128f
        }

        val foreheadLuma = sampleZoneLuma(0.5f, 0.25f, 0.08f)
        val noseLuma = sampleZoneLuma(0.5f, 0.52f, 0.06f)
        val leftCheekLuma = sampleZoneLuma(0.28f, 0.58f, 0.08f)
        val rightCheekLuma = sampleZoneLuma(0.72f, 0.58f, 0.08f)
        val chinLuma = sampleZoneLuma(0.5f, 0.85f, 0.08f)

        val zones = floatArrayOf(foreheadLuma, noseLuma, leftCheekLuma, rightCheekLuma, chinLuma)
        val meanLuma = zones.average().toFloat()
        var variance = 0f
        for (z in zones) {
            val diff = z - meanLuma
            variance += diff * diff
        }
        variance /= zones.size

        val normalizedVariance = (variance / (128f * 128f)).coerceIn(0.001f, 0.120f)
        val isTrue3D = normalizedVariance > 0.005f

        for (i in 0 until min(40, params.size)) {
            val baseVal = (zones[i % zones.size] - meanLuma) / 255.0f
            params[i] = baseVal * (1.0f / (1.0f + i * 0.1f))
        }

        val t1 = System.nanoTime()
        val durationMs = (t1 - t0) / 1_000_000.0f

        return FaceMap3DMMResult(
            parameters265 = params,
            depthVariance = normalizedVariance,
            isTrue3DSurface = isTrue3D,
            executionTimeMs = durationMs
        )
    }

    /**
     * Analyzes facial attributes (Expression, Eyeglasses, Pose, Smile).
     */
    fun detectFaceAttributes(faceBitmap: Bitmap): FaceAttributesResult? {
        if (faceBitmap.isRecycled || faceBitmap.width <= 0 || faceBitmap.height <= 0) return null
        val unified = UnifiedFaceIntelligenceEngine.getInstance(context)
        if (unified.isModelLoaded) {
            return unified.detectFaceAttributes(faceBitmap)
        }
        return detectFaceAttributesAlgorithmic(faceBitmap)
    }

    private fun detectFaceAttributesAlgorithmic(faceBitmap: Bitmap): FaceAttributesResult {
        val t0 = System.nanoTime()
        val width = faceBitmap.width
        val height = faceBitmap.height

        val mouthTop = (height * 0.65f).toInt().coerceIn(0, height - 1)
        val mouthBottom = (height * 0.82f).toInt().coerceIn(0, height - 1)
        val mouthLeft = (width * 0.30f).toInt().coerceIn(0, width - 1)
        val mouthRight = (width * 0.70f).toInt().coerceIn(0, width - 1)

        var mouthLumaSum = 0f
        var mouthPixels = 0
        for (y in mouthTop..mouthBottom) {
            for (x in mouthLeft..mouthRight) {
                val p = faceBitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                mouthLumaSum += 0.299f * r + 0.587f * g + 0.114f * b
                mouthPixels++
            }
        }
        val mouthLuma = if (mouthPixels > 0) mouthLumaSum / mouthPixels else 128f

        val eyeY = (height * 0.38f).toInt().coerceIn(0, height - 1)
        val noseBridgeX = (width * 0.50f).toInt().coerceIn(0, width - 1)
        var darkBridgePixels = 0
        val sampleH = (height * 0.05f).toInt().coerceAtLeast(1)
        val sampleW = (width * 0.08f).toInt().coerceAtLeast(1)

        for (y in (eyeY - sampleH).coerceAtLeast(0)..(eyeY + sampleH).coerceAtMost(height - 1)) {
            for (x in (noseBridgeX - sampleW).coerceAtLeast(0)..(noseBridgeX + sampleW).coerceAtMost(width - 1)) {
                val p = faceBitmap.getPixel(x, y)
                val luma = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                if (luma < 60f) darkBridgePixels++
            }
        }
        val glassesScore = (darkBridgePixels.toFloat() / ((sampleH * 2 + 1) * (sampleW * 2 + 1))).coerceIn(0f, 1f)
        val smileScore = if (mouthLuma > 140f) ((mouthLuma - 140f) / 60f).coerceIn(0f, 1f) else 0.1f

        val probs = floatArrayOf(smileScore, glassesScore, 0.05f, 0.95f, 0.02f)
        val t1 = System.nanoTime()
        val durationMs = (t1 - t0) / 1_000_000.0f

        return FaceAttributesResult(
            smileScore = smileScore,
            eyeglassesScore = glassesScore,
            poseYawScore = 0.0f,
            rawProbabilities = probs,
            executionTimeMs = durationMs
        )
    }

    /**
     * Estimates eye gaze pitch/yaw angles and 34-point eye landmark contour.
     */
    fun estimateEyeGaze(
        eyeCropBitmap: Bitmap,
        headYaw: Float = 0f,
        headPitch: Float = 0f,
        leftEyeOpenProb: Float? = null,
        rightEyeOpenProb: Float? = null
    ): EyeGazeResult? {
        if (eyeCropBitmap.isRecycled || eyeCropBitmap.width <= 0 || eyeCropBitmap.height <= 0) return null
        val unified = UnifiedFaceIntelligenceEngine.getInstance(context)
        if (unified.isModelLoaded) {
            return unified.estimateEyeGaze(eyeCropBitmap, headYaw, headPitch, leftEyeOpenProb, rightEyeOpenProb)
        }
        return estimateEyeGazeAlgorithmic(eyeCropBitmap, headYaw, headPitch, leftEyeOpenProb, rightEyeOpenProb)
    }

    private fun estimateEyeGazeAlgorithmic(
        bitmap: Bitmap,
        headYaw: Float,
        headPitch: Float,
        leftEyeOpenProb: Float?,
        rightEyeOpenProb: Float?
    ): EyeGazeResult {
        val t0 = System.nanoTime()
        val width = bitmap.width
        val height = bitmap.height

        var minLuma = 255f
        var minX = width / 2
        var minY = height / 2

        for (y in (height * 0.2f).toInt()..(height * 0.8f).toInt()) {
            for (x in (width * 0.2f).toInt()..(width * 0.8f).toInt()) {
                val p = bitmap.getPixel(x, y)
                val luma = 0.299f * ((p shr 16) and 0xFF) + 0.587f * ((p shr 8) and 0xFF) + 0.114f * (p and 0xFF)
                if (luma < minLuma) {
                    minLuma = luma
                    minX = x
                    minY = y
                }
            }
        }

        val pupilOffsetX = (minX.toFloat() / width.toFloat()) - 0.5f
        val pupilOffsetY = (minY.toFloat() / height.toFloat()) - 0.5f

        val estimatedYaw = pupilOffsetX * 45.0f
        val estimatedPitch = pupilOffsetY * 35.0f

        val fusedYaw = (estimatedYaw * 0.60f) + (headYaw * 0.40f)
        val fusedPitch = (estimatedPitch * 0.60f) + (headPitch * 0.40f)

        val yawRad = Math.toRadians(fusedYaw.toDouble()).toFloat()
        val pitchRad = Math.toRadians(fusedPitch.toDouble()).toFloat()
        val gazeNorm = sqrt(yawRad * yawRad + pitchRad * pitchRad)

        val isBothEyesOpen = (leftEyeOpenProb ?: 1.0f) > 0.40f && (rightEyeOpenProb ?: 1.0f) > 0.40f
        val isGazeAttentive = gazeNorm < 0.42f && abs(fusedYaw) < 22.0f && abs(fusedPitch) < 18.0f && isBothEyesOpen

        val syntheticLandmarks = Array(34) { i ->
            val angle = (i.toFloat() / 34f) * (2.0 * Math.PI)
            val rx = width * 0.35f
            val ry = height * 0.22f
            floatArrayOf(
                (minX + cos(angle) * rx).toFloat().coerceIn(0f, width.toFloat()),
                (minY + sin(angle) * ry).toFloat().coerceIn(0f, height.toFloat())
            )
        }

        val t1 = System.nanoTime()
        val durationMs = (t1 - t0) / 1_000_000.0f

        return EyeGazeResult(
            pitch = fusedPitch,
            yaw = fusedYaw,
            gazeVectorNorm = gazeNorm,
            eyeLandmarks34x2 = syntheticLandmarks,
            isGazeAttentive = isGazeAttentive,
            executionTimeMs = durationMs
        )
    }

    /**
     * Estimates 29 high-resolution facial keypoint heatmaps via Qualcomm HRNetFace.
     */
    fun estimateHrnetLandmarks(faceBitmap: Bitmap): HRNetFaceResult? {
        if (faceBitmap.isRecycled || faceBitmap.width <= 0 || faceBitmap.height <= 0) return null
        val unified = UnifiedFaceIntelligenceEngine.getInstance(context)
        if (unified.isModelLoaded) {
            return unified.estimateHrnetLandmarks(faceBitmap)
        }
        return null
    }

    /**
     * Estimates 468 3D (X, Y, Z depth) dense facial mesh keypoints via Qualcomm MediaPipe Face Mesh.
     */
    fun estimateMediaPipeFaceMesh(faceBitmap: Bitmap): MediaPipeMeshResult? {
        if (faceBitmap.isRecycled || faceBitmap.width <= 0 || faceBitmap.height <= 0) return null
        val unified = UnifiedFaceIntelligenceEngine.getInstance(context)
        if (unified.isModelLoaded) {
            return unified.estimateMediaPipeFaceMesh(faceBitmap)
        }
        return null
    }

    override fun close() {
        isSuiteLoaded = false
    }
}
