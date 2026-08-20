package com.omniface.ai.ml

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.NpuHardwareInfo
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

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
 * 1. FaceMap 3DMM: 3D Morphable Model (265-D shape/depth reconstruction for anti-spoofing)
 * 2. FaceAttribNet: Neural Facial Attribute & Expression Classifier
 * 3. EyeGaze: Pupil Vector Estimation & 34-point Eye Landmark Tracking
 * 4. HRNetFace: High-Resolution Deep Landmark & Pose Heatmap Extractor (29 keypoints)
 * 5. MediaPipe Face Mesh: 468 3D Dense Keypoints (X, Y, Z Depth)
 *
 * Exclusively optimized for Snapdragon® 8 Elite, 8 Gen 3, 8 Gen 2, 8 Gen 1, and 888.
 */
class QualcommFaceIntelligenceEngine(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "QualcommFaceIntel"
    }

    val npuHardwareInfo: NpuHardwareInfo = NpuHardwareDetector.detectNpuHardware()
    val isQualcommSilicon: Boolean = npuHardwareInfo.socModel.contains("Snapdragon", ignoreCase = true)

    // Interpreters & GPU Delegates
    private var faceMapInterpreter: Interpreter? = null
    private var attribInterpreter: Interpreter? = null
    private var eyeGazeInterpreter: Interpreter? = null
    private var hrnetInterpreter: Interpreter? = null
    private var mediapipeMeshInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    // Native Direct Buffers (Zero-GC)
    private val buffer128x128: ByteBuffer = ByteBuffer.allocateDirect(1 * 128 * 128 * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val buffer192x192: ByteBuffer = ByteBuffer.allocateDirect(1 * 192 * 192 * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val buffer256x256: ByteBuffer = ByteBuffer.allocateDirect(1 * 256 * 256 * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val bufferEye96x160: ByteBuffer = ByteBuffer.allocateDirect(1 * 96 * 160 * 4).apply {
        order(ByteOrder.nativeOrder())
    }

    // Output Buffers
    private val out3DMM = Array(1) { FloatArray(265) }
    private val outAttrib = Array(1) { FloatArray(5) }
    private val outGazePitchYaw = Array(1) { FloatArray(2) }
    private val outGazeLandmarks = Array(1) { Array(34) { FloatArray(2) } }
    private val outHrnetHeatmaps = Array(1) { Array(29) { Array(64) { FloatArray(64) } } }
    private val outMeshScores = FloatArray(1)
    private val outMeshLandmarks = Array(1) { Array(468) { FloatArray(3) } }

    var isSuiteLoaded: Boolean = false
        private set

    init {
        initializeQualcommSuite()
    }

    /** Resolves a model file checking app external dir first, then legacy pre-placed path. */
    private fun resolveModel(modelId: String): File? =
        QualcommSuiteDownloadManager.SUITE_MODELS
            .find { it.id == modelId }
            ?.let { QualcommSuiteDownloadManager.resolveModelFile(context, it) }

    private fun initializeQualcommSuite() {
        try {
            val gpuOptions = GpuDelegate.Options().apply {
                setPrecisionLossAllowed(true)
                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            }
            val gpu = try { GpuDelegate(gpuOptions) } catch (_: Throwable) { null }
            gpuDelegate = gpu

            val options = Interpreter.Options().apply {
                if (gpu != null) addDelegate(gpu)
                setNumThreads(4)
            }

            // 1. Load FaceMap 3DMM
            resolveModel("facemap_3dmm")?.let { f ->
                faceMapInterpreter = Interpreter(mapFile(f), options)
                Log.i(TAG, "✅ [QUALCOMM AI HUB] FaceMap 3DMM (${f.length() / 1024 / 1024} MB) @ ${f.absolutePath}")
            }

            // 2. Load FaceAttribNet
            resolveModel("face_attrib_net")?.let { f ->
                attribInterpreter = Interpreter(mapFile(f), options)
                Log.i(TAG, "✅ [QUALCOMM AI HUB] FaceAttribNet (${f.length() / 1024 / 1024} MB)")
            }

            // 3. Load EyeGaze
            resolveModel("eyegaze")?.let { f ->
                eyeGazeInterpreter = Interpreter(mapFile(f), options)
                Log.i(TAG, "✅ [QUALCOMM AI HUB] EyeGaze (${f.length() / 1024 / 1024} MB)")
            }

            // 4. Load HRNetFace
            resolveModel("hrnet_face")?.let { f ->
                hrnetInterpreter = Interpreter(mapFile(f), options)
                Log.i(TAG, "✅ [QUALCOMM AI HUB] HRNetFace (${f.length() / 1024 / 1024} MB)")
            }

            // 5. Load MediaPipe Face Mesh (468 3D Keypoints)
            resolveModel("mediapipe_face")?.let { f ->
                mediapipeMeshInterpreter = Interpreter(mapFile(f), options)
                Log.i(TAG, "✅ [QUALCOMM AI HUB] MediaPipe Face Mesh (${f.length() / 1024 / 1024} MB)")
            }

            isSuiteLoaded = faceMapInterpreter != null || attribInterpreter != null ||
                eyeGazeInterpreter != null || hrnetInterpreter != null || mediapipeMeshInterpreter != null
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ Qualcomm Face Intelligence Suite partial init: ${t.message}")
        }
    }

    private fun mapFile(file: File): ByteBuffer {
        val inputStream = FileInputStream(file)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
    }

    /**
     * Estimates 3D Morphable Model (3DMM) 265-dimensional geometry parameters.
     */
    @Synchronized
    fun estimate3dFaceMap(faceBitmap: Bitmap): FaceMap3DMMResult? {
        val interpreter = faceMapInterpreter ?: return null
        val t0 = System.nanoTime()

        val resized = Bitmap.createScaledBitmap(faceBitmap, 128, 128, true)
        val pixels = IntArray(128 * 128)
        resized.getPixels(pixels, 0, 128, 0, 0, 128, 128)
        if (resized != faceBitmap) resized.recycle()

        buffer128x128.rewind()
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            buffer128x128.putFloat(r)
            buffer128x128.putFloat(g)
            buffer128x128.putFloat(b)
        }

        interpreter.run(buffer128x128, out3DMM)
        val t1 = System.nanoTime()
        val durationMs = (t1 - t0) / 1_000_000.0f

        val params = out3DMM[0].copyOf()
        // Calculate 3D geometric variance from shape eigenvalues
        var sum = 0.0f
        for (i in 0 until minOf(params.size, 40)) {
            sum += params[i] * params[i]
        }
        val depthVariance = sum / 40.0f
        val isTrue3D = depthVariance > 0.005f

        return FaceMap3DMMResult(
            parameters265 = params,
            depthVariance = depthVariance,
            isTrue3DSurface = isTrue3D,
            executionTimeMs = durationMs
        )
    }

    /**
     * Analyzes facial attributes (Expression, Eyeglasses, Pose, Smile).
     */
    @Synchronized
    fun detectFaceAttributes(faceBitmap: Bitmap): FaceAttributesResult? {
        val interpreter = attribInterpreter ?: return null
        val t0 = System.nanoTime()

        val resized = Bitmap.createScaledBitmap(faceBitmap, 128, 128, true)
        val pixels = IntArray(128 * 128)
        resized.getPixels(pixels, 0, 128, 0, 0, 128, 128)
        if (resized != faceBitmap) resized.recycle()

        buffer128x128.rewind()
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            buffer128x128.putFloat(r)
            buffer128x128.putFloat(g)
            buffer128x128.putFloat(b)
        }

        interpreter.run(buffer128x128, outAttrib)
        val t1 = System.nanoTime()
        val durationMs = (t1 - t0) / 1_000_000.0f

        val probs = outAttrib[0].copyOf()
        val smile = if (probs.isNotEmpty()) probs[0].coerceIn(0.0f, 1.0f) else 0.0f
        val glasses = if (probs.size > 1) probs[1].coerceIn(0.0f, 1.0f) else 0.0f
        val yaw = if (probs.size > 2) probs[2] else 0.0f

        return FaceAttributesResult(
            smileScore = smile,
            eyeglassesScore = glasses,
            poseYawScore = yaw,
            rawProbabilities = probs,
            executionTimeMs = durationMs
        )
    }

    /**
     * Estimates eye gaze pitch/yaw angles and 34-point eye landmark contour.
     */
    @Synchronized
    fun estimateEyeGaze(eyeCropBitmap: Bitmap): EyeGazeResult? {
        val interpreter = eyeGazeInterpreter ?: return null
        val t0 = System.nanoTime()

        val resized = Bitmap.createScaledBitmap(eyeCropBitmap, 160, 96, true)
        val pixels = IntArray(160 * 96)
        resized.getPixels(pixels, 0, 160, 0, 0, 160, 96)
        if (resized != eyeCropBitmap) resized.recycle()

        bufferEye96x160.rewind()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Grayscale conversion [0.0f, 1.0f]
            val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            bufferEye96x160.putFloat(gray)
        }

        val outputs = HashMap<Int, Any>()
        outputs[0] = outGazePitchYaw
        outputs[1] = outGazeLandmarks
        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(bufferEye96x160), outputs)
        } catch (_: Throwable) {
            // Fallback for single-output variant
            interpreter.run(bufferEye96x160, outGazePitchYaw)
        }

        val t1 = System.nanoTime()
        val durationMs = (t1 - t0) / 1_000_000.0f

        val pitch = outGazePitchYaw[0][0]
        val yaw = if (outGazePitchYaw[0].size > 1) outGazePitchYaw[0][1] else 0.0f
        val norm = kotlin.math.sqrt(pitch * pitch + yaw * yaw)
        val isAttentive = norm < 0.45f // Looking directly towards camera lens

        return EyeGazeResult(
            pitch = pitch,
            yaw = yaw,
            gazeVectorNorm = norm,
            eyeLandmarks34x2 = outGazeLandmarks[0].copyOf(),
            isGazeAttentive = isAttentive,
            executionTimeMs = durationMs
        )
    }

    /**
     * Estimates 29 high-resolution facial keypoint heatmaps via Qualcomm HRNetFace.
     */
    @Synchronized
    fun estimateHrnetLandmarks(faceBitmap: Bitmap): HRNetFaceResult? {
        val interpreter = hrnetInterpreter ?: return null
        val t0 = System.nanoTime()

        val resized = Bitmap.createScaledBitmap(faceBitmap, 256, 256, true)
        val pixels = IntArray(256 * 256)
        resized.getPixels(pixels, 0, 256, 0, 0, 256, 256)
        if (resized != faceBitmap) resized.recycle()

        buffer256x256.rewind()
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            buffer256x256.putFloat(r)
            buffer256x256.putFloat(g)
            buffer256x256.putFloat(b)
        }

        interpreter.run(buffer256x256, outHrnetHeatmaps)
        val t1 = System.nanoTime()
        val durationMs = (t1 - t0) / 1_000_000.0f

        val landmarks = Array(29) { FloatArray(2) }
        val confidences = FloatArray(29)

        val heatmaps = outHrnetHeatmaps[0]
        for (k in 0 until 29) {
            val map = heatmaps[k]
            var maxVal = -Float.MAX_VALUE
            var maxRow = 0
            var maxCol = 0

            for (r in 0 until 64) {
                val rowArr = map[r]
                for (c in 0 until 64) {
                    val v = rowArr[c]
                    if (v > maxVal) {
                        maxVal = v
                        maxRow = r
                        maxCol = c
                    }
                }
            }

            landmarks[k][0] = maxCol.toFloat() / 64.0f
            landmarks[k][1] = maxRow.toFloat() / 64.0f
            confidences[k] = maxVal
        }

        return HRNetFaceResult(
            landmarks29x2 = landmarks,
            landmarkConfidences = confidences,
            executionTimeMs = durationMs
        )
    }

    /**
     * Estimates 468 3D (X, Y, Z depth) dense facial mesh keypoints via Qualcomm MediaPipe Face Mesh.
     */
    @Synchronized
    fun estimateMediaPipeFaceMesh(faceBitmap: Bitmap): MediaPipeMeshResult? {
        val interpreter = mediapipeMeshInterpreter ?: return null
        val t0 = System.nanoTime()

        val resized = Bitmap.createScaledBitmap(faceBitmap, 192, 192, true)
        val pixels = IntArray(192 * 192)
        resized.getPixels(pixels, 0, 192, 0, 0, 192, 192)
        if (resized != faceBitmap) resized.recycle()

        buffer192x192.rewind()
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            buffer192x192.putFloat(r)
            buffer192x192.putFloat(g)
            buffer192x192.putFloat(b)
        }

        val outputs = HashMap<Int, Any>()
        outputs[0] = outMeshScores
        outputs[1] = outMeshLandmarks
        try {
            interpreter.runForMultipleInputsOutputs(arrayOf(buffer192x192), outputs)
        } catch (_: Throwable) {
            // Fallback for single landmark output
            interpreter.run(buffer192x192, outMeshLandmarks)
        }

        val t1 = System.nanoTime()
        val durationMs = (t1 - t0) / 1_000_000.0f

        val mesh = Array(468) { i -> outMeshLandmarks[0][i].copyOf() }
        val score = if (outMeshScores.isNotEmpty()) outMeshScores[0] else 1.0f

        // Compute Z-Depth variance for 3D biometrics & anti-spoofing
        var zSum = 0.0f
        for (pt in mesh) {
            val z = pt[2]
            zSum += z * z
        }
        val depthVar = zSum / 468.0f

        return MediaPipeMeshResult(
            landmarks468x3 = mesh,
            faceScore = score,
            meshDepthVariance = depthVar,
            executionTimeMs = durationMs
        )
    }

    override fun close() {
        try { faceMapInterpreter?.close() } catch (_: Throwable) {}
        try { attribInterpreter?.close() } catch (_: Throwable) {}
        try { eyeGazeInterpreter?.close() } catch (_: Throwable) {}
        try { hrnetInterpreter?.close() } catch (_: Throwable) {}
        try { mediapipeMeshInterpreter?.close() } catch (_: Throwable) {}
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        faceMapInterpreter = null
        attribInterpreter = null
        eyeGazeInterpreter = null
        hrnetInterpreter = null
        mediapipeMeshInterpreter = null
        gpuDelegate = null
        isSuiteLoaded = false
    }
}
