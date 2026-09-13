package com.omniface.ai.ml.unified

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.NpuHardwareInfo
import com.omniface.ai.ml.HardwareTier
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.sqrt

data class UnifiedInferenceResult(
    val identityEmbedding: FloatArray,
    val padProbabilities: FloatArray,
    val meshLandmarks: FloatArray,
    val geometry3DMM: FloatArray,
    val qualityScores: FloatArray,
    val gazeAngles: FloatArray,
    val attributeProbabilities: FloatArray,
    val latencyMs: Long,
    val hardwareTier: HardwareTier,
    val modelVersion: String = "UnifiedFaceModel_v1"
) {
    val isLive: Boolean get() = padProbabilities.size >= 3 && padProbabilities[0] > 0.50f
    val overallQuality: Float get() = qualityScores.getOrNull(3) ?: 1.0f
    val isFrontalGaze: Boolean get() = gazeAngles.size >= 2 && kotlin.math.abs(gazeAngles[0]) < 20f && kotlin.math.abs(gazeAngles[1]) < 25f

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnifiedInferenceResult) return false
        return identityEmbedding.contentEquals(other.identityEmbedding) &&
                padProbabilities.contentEquals(other.padProbabilities) &&
                modelVersion == other.modelVersion
    }

    override fun hashCode(): Int {
        var result = identityEmbedding.contentHashCode()
        result = 31 * result + padProbabilities.contentHashCode()
        result = 31 * result + modelVersion.hashCode()
        return result
    }
}

class UnifiedFaceModelEngine(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "UnifiedFaceEngine"
        const val MODEL_VERSION = "UnifiedFaceModel_v1.0"
        const val INPUT_WIDTH = 112
        const val INPUT_HEIGHT = 112
        const val INPUT_CHANNELS = 3
        const val EMBEDDING_DIM = 512
        const val MESH_POINTS = 468
        const val GEOM_DIM = 265
        const val QUALITY_DIMS = 4
        const val GAZE_DIMS = 2
        const val ATTRIB_DIMS = 5
        const val PAD_CLASSES = 3

        const val PRIMARY_MODEL_FILE = "unified_face_v1_fp16.tflite"
        const val INT8_MODEL_FILE = "unified_face_v1_int8.tflite"
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null

    var activeHardwareTier: HardwareTier = HardwareTier.CPU_XNNPACK
        private set

    val npuHardwareInfo: NpuHardwareInfo by lazy {
        NpuHardwareDetector.detectNpuHardware()
    }

    val isReady: Boolean get() = interpreter != null

    init {
        initializeEngine()
    }

    @Synchronized
    fun initializeEngine(): Boolean {
        close()
        val assetManager = context.assets

        val modelAsset = try {
            val list = assetManager.list("") ?: emptyArray()
            when {
                list.contains(INT8_MODEL_FILE) -> INT8_MODEL_FILE
                list.contains(PRIMARY_MODEL_FILE) -> PRIMARY_MODEL_FILE
                else -> null
            }
        } catch (_: Throwable) {
            null
        }

        if (modelAsset == null) {
            Log.w(TAG, "Unified model not yet packaged in assets; engine in standby mode.")
            return false
        }

        try {
            val nnapi = NnApiDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(nnapi)
                setNumThreads(4)
            }
            interpreter = Interpreter(loadModelBuffer(modelAsset), options)
            nnapiDelegate = nnapi
            activeHardwareTier = HardwareTier.NPU_NNAPI
            Log.i(TAG, "Initialized UnifiedFaceModel on NPU/NNAPI ($modelAsset)")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "NNAPI init failed: ${t.message}. Falling back to GPU.")
        }

        try {
            val gpu = GpuDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(gpu)
                setNumThreads(4)
            }
            interpreter = Interpreter(loadModelBuffer(modelAsset), options)
            gpuDelegate = gpu
            activeHardwareTier = HardwareTier.GPU_DELEGATE
            Log.i(TAG, "Initialized UnifiedFaceModel on GPU ($modelAsset)")
            return true
        } catch (t: Throwable) {
            Log.w(TAG, "GPU init failed: ${t.message}. Falling back to CPU.")
        }

        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            interpreter = Interpreter(loadModelBuffer(modelAsset), options)
            activeHardwareTier = HardwareTier.CPU_XNNPACK
            Log.i(TAG, "Initialized UnifiedFaceModel on CPU XNNPACK ($modelAsset)")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize UnifiedFaceModel on any tier: ${t.message}")
            return false
        }
    }

    private fun loadModelBuffer(modelName: String): ByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    @Synchronized
    fun processFace(alignedBitmap: Bitmap): UnifiedInferenceResult? {
        val activeInterpreter = interpreter ?: return null
        val t0 = SystemClock.elapsedRealtime()

        val inputBuffer = preprocessBitmap(alignedBitmap)

        val identityOutput = Array(1) { FloatArray(EMBEDDING_DIM) }
        val padOutput = Array(1) { FloatArray(PAD_CLASSES) }
        val meshOutput = Array(1) { FloatArray(MESH_POINTS * 3) }
        val geomOutput = Array(1) { FloatArray(GEOM_DIM) }
        val qualityOutput = Array(1) { FloatArray(QUALITY_DIMS) }
        val gazeOutput = Array(1) { FloatArray(GAZE_DIMS) }
        val attribOutput = Array(1) { FloatArray(ATTRIB_DIMS) }

        val outputs = mutableMapOf<Int, Any>(
            0 to identityOutput,
            1 to padOutput,
            2 to qualityOutput,
            3 to meshOutput,
            4 to geomOutput,
            5 to gazeOutput,
            6 to attribOutput
        )

        try {
            activeInterpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        } catch (t: Throwable) {
            Log.e(TAG, "Unified model inference failed: ${t.message}")
            return null
        }

        val latency = SystemClock.elapsedRealtime() - t0

        val rawIdentity = identityOutput[0]
        l2Normalize(rawIdentity)

        val rawPad = padOutput[0]
        val padProbs = if (isProbabilities(rawPad)) rawPad else applySoftmax(rawPad)

        return UnifiedInferenceResult(
            identityEmbedding = rawIdentity,
            padProbabilities = padProbs,
            meshLandmarks = meshOutput[0],
            geometry3DMM = geomOutput[0],
            qualityScores = qualityOutput[0],
            gazeAngles = gazeOutput[0],
            attributeProbabilities = attribOutput[0],
            latencyMs = latency,
            hardwareTier = activeHardwareTier,
            modelVersion = MODEL_VERSION
        )
    }

    private fun preprocessBitmap(bitmap: Bitmap): ByteBuffer {
        val scaled = if (bitmap.width != INPUT_WIDTH || bitmap.height != INPUT_HEIGHT) {
            Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
        } else bitmap

        val byteBuffer = ByteBuffer.allocateDirect(1 * INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS * 4)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        scaled.getPixels(intValues, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        var pixel = 0
        for (i in 0 until INPUT_WIDTH) {
            for (j in 0 until INPUT_HEIGHT) {
                val v = intValues[pixel++]
                byteBuffer.putFloat((((v shr 16) and 0xFF) - 127.5f) / 128.0f)
                byteBuffer.putFloat((((v shr 8) and 0xFF) - 127.5f) / 128.0f)
                byteBuffer.putFloat(((v and 0xFF) - 127.5f) / 128.0f)
            }
        }
        if (scaled !== bitmap && !scaled.isRecycled) {
            scaled.recycle()
        }
        return byteBuffer
    }

    private fun l2Normalize(v: FloatArray) {
        var sumSquares = 0f
        for (x in v) sumSquares += x * x
        val norm = sqrt(sumSquares)
        if (norm > 1e-6f) {
            val inv = 1.0f / norm
            for (i in v.indices) v[i] *= inv
        }
    }

    private fun isProbabilities(arr: FloatArray): Boolean {
        var sum = 0f
        for (v in arr) {
            if (v < -0.01f || v > 1.01f) return false
            sum += v
        }
        return kotlin.math.abs(sum - 1.0f) < 0.05f
    }

    private fun applySoftmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0f
        val exp = FloatArray(logits.size)
        for (i in logits.indices) {
            exp[i] = kotlin.math.exp(logits[i] - max)
            sum += exp[i]
        }
        if (sum > 0f) {
            for (i in exp.indices) exp[i] /= sum
        }
        return exp
    }

    override fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        interpreter = null
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        gpuDelegate = null
        try { nnapiDelegate?.close() } catch (_: Throwable) {}
        nnapiDelegate = null
    }
}
