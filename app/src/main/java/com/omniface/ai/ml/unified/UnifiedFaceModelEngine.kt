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
import com.omniface.ai.ml.CachedBiometric
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.security.AndroidSecurityUtils
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
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
    val modelVersion: String = UnifiedFaceModelEngine.MODEL_VERSION
) {
    val isLive: Boolean get() = padProbabilities.size >= 3 && padProbabilities[0] > 0.50f
    val overallQuality: Float get() = qualityScores.getOrNull(0) ?: 1.0f
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

/**
 * 🏛️ Master Unified Face Intelligence Neural Engine (UnifiedFaceModel V1).
 *
 * Implements the single-graph multi-task architecture:
 * 1. Shared MobileNetV4-Conv-Small backbone.
 * 2. Seven specialized multi-task heads (Identity, PAD, Quality, Mesh, 3DMM, Gaze, Attributes).
 * 3. Multi-tier hardware fallback: NPU/NNAPI (Tier 1) -> GPU (Tier 2) -> CPU XNNPACK (Tier 3).
 * 4. Strict runtime Model Contract verification with fail-closed integrity gating.
 */
class UnifiedFaceModelEngine(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "UnifiedFaceEngine"
        const val MODEL_ID = "OmniFaceUnifiedModel"
        const val MODEL_VERSION = "UnifiedFaceModel_v2.0"
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

        const val PRIMARY_MODEL_FILE = "unified_face_v2_fp16.tflite"
        const val INT8_MODEL_FILE = "unified_face_v2_int8.tflite"
        const val V1_PRIMARY_MODEL_FILE = "unified_face_v1_fp16.tflite"
        const val V1_INT8_MODEL_FILE = "unified_face_v1_int8.tflite"

        @Volatile
        private var INSTANCE: UnifiedFaceModelEngine? = null

        fun getInstance(context: Context): UnifiedFaceModelEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UnifiedFaceModelEngine(context.applicationContext).also { INSTANCE = it }
            }
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnapiDelegate: NnApiDelegate? = null

    private var identityOutputIdx = 0
    private var padOutputIdx = 1
    private var qualityOutputIdx = 2
    private var meshOutputIdx = 3
    private var geomOutputIdx = 4
    private var gazeOutputIdx = 5
    private var attribOutputIdx = 6

    var activeHardwareTier: HardwareTier = HardwareTier.CPU_XNNPACK
        private set

    val modelVersion: String get() = MODEL_VERSION

    val cachedStudentMap = ConcurrentHashMap<String, String>()
    val cachedTemplates = CopyOnWriteArrayList<CachedBiometric>()

    val npuHardwareInfo: NpuHardwareInfo by lazy {
        NpuHardwareDetector.detectNpuHardware()
    }

    val isReady: Boolean get() = interpreter != null
    val isEngineReady: Boolean get() = isReady

    init {
        initializeEngine()
    }

    private fun validateModelContract(interp: Interpreter): Boolean {
        if (interp.inputTensorCount != 1) {
            Log.e(TAG, "Contract Violation: Expected 1 input tensor, got ${interp.inputTensorCount}")
            return false
        }
        val inTensor = interp.getInputTensor(0)
        val inShape = inTensor.shape()
        if (inShape.size != 4 || inShape[1] != INPUT_WIDTH || inShape[2] != INPUT_HEIGHT || inShape[3] != INPUT_CHANNELS) {
            Log.e(TAG, "Contract Violation: Expected input shape [1, $INPUT_WIDTH, $INPUT_HEIGHT, $INPUT_CHANNELS], got ${inShape.contentToString()}")
            return false
        }
        if (interp.outputTensorCount != 7) {
            Log.e(TAG, "Contract Violation: Expected 7 output tensors, got ${interp.outputTensorCount}")
            return false
        }
        return true
    }

    private fun resolveOutputIndices(interp: Interpreter): Boolean {
        var foundIdentity = false
        var foundPad = false
        var foundQuality = false
        var foundMesh = false
        var foundGeom = false
        var foundGaze = false
        var foundAttrib = false

        val count = interp.outputTensorCount
        for (i in 0 until count) {
            val tensor = interp.getOutputTensor(i)
            val totalElements = tensor.shape().fold(1) { acc, dim -> acc * dim }
            when (totalElements) {
                EMBEDDING_DIM -> { identityOutputIdx = i; foundIdentity = true }
                PAD_CLASSES -> { padOutputIdx = i; foundPad = true }
                QUALITY_DIMS -> { qualityOutputIdx = i; foundQuality = true }
                MESH_POINTS * 3 -> { meshOutputIdx = i; foundMesh = true }
                GEOM_DIM -> { geomOutputIdx = i; foundGeom = true }
                GAZE_DIMS -> { gazeOutputIdx = i; foundGaze = true }
                ATTRIB_DIMS -> { attribOutputIdx = i; foundAttrib = true }
            }
        }
        val allFound = foundIdentity && foundPad && foundQuality && foundMesh && foundGeom && foundGaze && foundAttrib
        if (!allFound) {
            Log.e(TAG, "Failed to resolve all 7 output heads: id=$foundIdentity, pad=$foundPad, qual=$foundQuality, mesh=$foundMesh, geom=$foundGeom, gaze=$foundGaze, attr=$foundAttrib")
        } else {
            Log.i(TAG, "Resolved output tensor indices: id=$identityOutputIdx, pad=$padOutputIdx, quality=$qualityOutputIdx, mesh=$meshOutputIdx, geom=$geomOutputIdx, gaze=$gazeOutputIdx, attr=$attribOutputIdx")
        }
        return allFound
    }

    private fun resolveModelFile(preferInt8: Boolean = false): String {
        val candidates = if (preferInt8) {
            listOf(INT8_MODEL_FILE, PRIMARY_MODEL_FILE, V1_INT8_MODEL_FILE, V1_PRIMARY_MODEL_FILE)
        } else {
            listOf(PRIMARY_MODEL_FILE, INT8_MODEL_FILE, V1_PRIMARY_MODEL_FILE, V1_INT8_MODEL_FILE)
        }
        for (cand in candidates) {
            try {
                context.assets.openFd(cand).close()
                return cand
            } catch (_: Throwable) {
                val f = java.io.File(java.io.File(context.filesDir, "models"), cand)
                if (f.exists()) return cand
            }
        }
        return PRIMARY_MODEL_FILE
    }

    @Synchronized
    fun initializeEngine(): Boolean {
        close()

        // Priority 1: NPU / NNAPI
        val npuModel = resolveModelFile(preferInt8 = true)
        try {
            val nnapi = NnApiDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(nnapi)
                setNumThreads(4)
            }
            val interp = Interpreter(loadModelBuffer(npuModel), options)
            if (validateModelContract(interp) && resolveOutputIndices(interp)) {
                interpreter = interp
                nnapiDelegate = nnapi
                activeHardwareTier = HardwareTier.NPU_NNAPI
                Log.i(TAG, "Initialized UnifiedFaceModel V2 on NPU/NNAPI ($npuModel)")
                return true
            } else {
                interp.close()
                nnapi.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NNAPI init failed: ${t.message}. Falling back to GPU.")
        }

        // Priority 2: Mobile GPU
        val gpuModel = resolveModelFile(preferInt8 = false)
        try {
            val gpu = GpuDelegate()
            val options = Interpreter.Options().apply {
                addDelegate(gpu)
                setNumThreads(4)
            }
            val interp = Interpreter(loadModelBuffer(gpuModel), options)
            if (validateModelContract(interp) && resolveOutputIndices(interp)) {
                interpreter = interp
                gpuDelegate = gpu
                activeHardwareTier = HardwareTier.GPU_DELEGATE
                Log.i(TAG, "Initialized UnifiedFaceModel V2 on GPU ($gpuModel)")
                return true
            } else {
                interp.close()
                gpu.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "GPU init failed: ${t.message}. Falling back to CPU.")
        }

        // Priority 3: Multi-Core CPU XNNPACK
        val cpuModel = resolveModelFile(preferInt8 = false)
        try {
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                setUseXNNPACK(true)
            }
            val interp = Interpreter(loadModelBuffer(cpuModel), options)
            if (validateModelContract(interp) && resolveOutputIndices(interp)) {
                interpreter = interp
                activeHardwareTier = HardwareTier.CPU_XNNPACK
                Log.i(TAG, "Initialized UnifiedFaceModel V2 on CPU XNNPACK ($cpuModel)")
                return true
            } else {
                interp.close()
                Log.e(TAG, "Contract validation failed for CPU interpreter.")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to initialize UnifiedFaceModel V2 on CPU: ${t.message}")
        }

        Log.e(TAG, "FATAL: Failed to initialize UnifiedFaceModel on any hardware tier. Engine disabled.")
        return false
    }

    fun reloadEngine() {
        initializeEngine()
    }

    private fun loadModelBuffer(modelName: String): ByteBuffer {
        return try {
            val fileDescriptor = context.assets.openFd(modelName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (_: Throwable) {
            val f = java.io.File(java.io.File(context.filesDir, "models"), modelName)
            val inputStream = FileInputStream(f)
            val fileChannel = inputStream.channel
            fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
        }
    }

    @Synchronized
    fun processFace(alignedBitmap: Bitmap): UnifiedInferenceResult? {
        val activeInterpreter = interpreter ?: return null
        val t0 = SystemClock.elapsedRealtime()

        val inputBuffer = preprocessBitmap(alignedBitmap, activeInterpreter)

        val identityOutput = Array(1) { FloatArray(EMBEDDING_DIM) }
        val padOutput = Array(1) { FloatArray(PAD_CLASSES) }
        val meshOutput = Array(1) { FloatArray(MESH_POINTS * 3) }
        val geomOutput = Array(1) { FloatArray(GEOM_DIM) }
        val qualityOutput = Array(1) { FloatArray(QUALITY_DIMS) }
        val gazeOutput = Array(1) { FloatArray(GAZE_DIMS) }
        val attribOutput = Array(1) { FloatArray(ATTRIB_DIMS) }

        val outputs = mutableMapOf<Int, Any>(
            identityOutputIdx to identityOutput,
            padOutputIdx to padOutput,
            qualityOutputIdx to qualityOutput,
            meshOutputIdx to meshOutput,
            geomOutputIdx to geomOutput,
            gazeOutputIdx to gazeOutput,
            attribOutputIdx to attribOutput
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

    fun extractEmbedding(alignedBitmap: Bitmap): FloatArray {
        return processFace(alignedBitmap)?.identityEmbedding ?: FloatArray(0)
    }

    fun extractEmbeddingWithFlipAugmentation(alignedBitmap: Bitmap): FloatArray {
        val emb1 = extractEmbedding(alignedBitmap)
        if (emb1.isEmpty()) return FloatArray(0)
        val matrix = android.graphics.Matrix().apply { preScale(-1f, 1f) }
        val flipped = Bitmap.createBitmap(alignedBitmap, 0, 0, alignedBitmap.width, alignedBitmap.height, matrix, true)
        val emb2 = extractEmbedding(flipped)
        if (!flipped.isRecycled) flipped.recycle()
        if (emb2.isEmpty()) return emb1

        val fused = FloatArray(EMBEDDING_DIM)
        for (i in fused.indices) fused[i] = emb1[i] + emb2[i]
        l2Normalize(fused)
        return fused
    }

    fun evaluateLiveness(alignedBitmap: Bitmap): Boolean {
        val res = processFace(alignedBitmap) ?: return false
        val padLive = res.padProbabilities.getOrNull(0) ?: 0f
        var depthVar = 0f
        if (res.geometry3DMM.isNotEmpty()) {
            val mean = res.geometry3DMM.average().toFloat()
            var sumDiffSq = 0f
            for (x in res.geometry3DMM) sumDiffSq += (x - mean) * (x - mean)
            depthVar = sumDiffSq / res.geometry3DMM.size
        }
        return padLive >= 0.70f && (res.geometry3DMM.isEmpty() || depthVar > 0.0015f)
    }

    fun preloadTemplates(templates: List<FaceTemplateEntity>) {
        cachedTemplates.clear()
        for (tpl in templates) {
            val decryptedCsv = try {
                if (tpl.isEncrypted) AndroidSecurityUtils.decrypt(tpl.embeddingEncryptedCsv)
                else tpl.embeddingEncryptedCsv
            } catch (t: Throwable) {
                Log.e(TAG, "Skipping corrupt template ${tpl.id}: ${t.message}")
                continue
            }
            if (decryptedCsv.isBlank()) continue
            val emb = parseEmbeddingCsv(decryptedCsv)
            if (emb.size == EMBEDDING_DIM) {
                l2Normalize(emb)
                cachedTemplates.add(
                    CachedBiometric(
                        templateId = tpl.id,
                        studentRoll = tpl.studentRoll,
                        angleType = tpl.angleType,
                        embedding = emb,
                        modelVersion = tpl.modelVersion
                    )
                )
            }
        }
    }

    fun preloadCachedBiometrics(cachedList: List<CachedBiometric>) {
        cachedTemplates.clear()
        cachedTemplates.addAll(cachedList)
    }

    private fun parseEmbeddingCsv(csv: String): FloatArray {
        return try {
            csv.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (_: Exception) {
            FloatArray(0)
        }
    }

    private fun preprocessBitmap(bitmap: Bitmap, interpreter: Interpreter): ByteBuffer {
        val scaled = if (bitmap.width != INPUT_WIDTH || bitmap.height != INPUT_HEIGHT) {
            Bitmap.createScaledBitmap(bitmap, INPUT_WIDTH, INPUT_HEIGHT, true)
        } else bitmap

        val inputTensor = interpreter.getInputTensor(0)
        val isQuantized = inputTensor.dataType() == org.tensorflow.lite.DataType.INT8 ||
                inputTensor.dataType() == org.tensorflow.lite.DataType.UINT8
        val quantParams = inputTensor.quantizationParams()
        val scale = if (quantParams.scale > 0f) quantParams.scale else 0.0078125f
        val zeroPoint = quantParams.zeroPoint

        val bytesPerChannel = if (isQuantized) 1 else 4
        val byteBuffer = ByteBuffer.allocateDirect(1 * INPUT_WIDTH * INPUT_HEIGHT * INPUT_CHANNELS * bytesPerChannel)
        byteBuffer.order(ByteOrder.nativeOrder())
        val intValues = IntArray(INPUT_WIDTH * INPUT_HEIGHT)
        scaled.getPixels(intValues, 0, INPUT_WIDTH, 0, 0, INPUT_WIDTH, INPUT_HEIGHT)

        var pixel = 0
        for (i in 0 until INPUT_WIDTH) {
            for (j in 0 until INPUT_HEIGHT) {
                val v = intValues[pixel++]
                val rNorm = (((v shr 16) and 0xFF) - 127.5f) / 128.0f
                val gNorm = (((v shr 8) and 0xFF) - 127.5f) / 128.0f
                val bNorm = ((v and 0xFF) - 127.5f) / 128.0f

                if (isQuantized) {
                    val rQ = kotlin.math.round(((rNorm / scale) + zeroPoint)).toInt().coerceIn(-128, 127).toByte()
                    val gQ = kotlin.math.round(((gNorm / scale) + zeroPoint)).toInt().coerceIn(-128, 127).toByte()
                    val bQ = kotlin.math.round(((bNorm / scale) + zeroPoint)).toInt().coerceIn(-128, 127).toByte()
                    byteBuffer.put(rQ)
                    byteBuffer.put(gQ)
                    byteBuffer.put(bQ)
                } else {
                    byteBuffer.putFloat(rNorm)
                    byteBuffer.putFloat(gNorm)
                    byteBuffer.putFloat(bNorm)
                }
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
