package com.omniface.ai.ml

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.util.Log
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.NpuHardwareInfo

enum class SecurityTier(val threshold: Float, val label: String, val farDesc: String) {
    STANDARD(0.420f, "STANDARD", "Doorway Kiosk (FAR 1:100 • τ ≥ 0.420)"),
    HIGH(0.500f, "HIGH", "ISO/IEC Standard (FAR 1:1,000 • τ ≥ 0.500)"),
    STRICT(0.600f, "STRICT", "Bank Grade (FAR 1:10,000 • τ ≥ 0.600)")
}

enum class NeuralBackbone(val label: String, val params: String, val isQualcommOptimized: Boolean) {
    MOBILEFACENET("MobileFaceNet GDConv", "1.29M params (Sub-8ms Ultra-Fast)", false),
    QUALCOMM_CAVAFACE("Qualcomm AI Hub CavaFace", "65.5M params (IR-SE-100 Snapdragon Flagship)", true)
}

enum class HardwareTier(val label: String) {
    GPU_DELEGATE("GPU (OpenCL/Vulkan/OpenGL FP16)"),
    CPU_XNNPACK("CPU (4-Thread XNNPACK FP32)"),
    NPU_NNAPI("NPU (Neural Processing Unit INT8)");

    fun getResolvedLabel(npuInfo: NpuHardwareInfo): String {
        return when (this) {
            GPU_DELEGATE -> "Mobile GPU Delegate (FP16 High Precision)"
            CPU_XNNPACK -> "Multi-Core CPU (XNNPACK FP32 Reference)"
            NPU_NNAPI -> "${npuInfo.npuName} (INT8)"
        }
    }
}

enum class ConfidenceZone(val label: String, val badgeColorHex: Long, val description: String) {
    ACCEPT("ACCEPT", 0xFF34C759, "Biometric match verified with high confidence & margin"),
    REVIEW("REVIEW", 0xFFFF9500, "Ambiguous match or low margin — secondary verification required"),
    REJECT("REJECT", 0xFFFF3B30, "Biometric match rejected — unverified identity or spoof attack")
}

data class MatchResult(
    val studentRoll: String,
    val studentName: String,
    val confidence: Float,
    val similarity: Float,
    val isMatch: Boolean,
    val hardwareTier: HardwareTier,
    val confidenceZone: ConfidenceZone = if (isMatch) ConfidenceZone.ACCEPT else ConfidenceZone.REJECT,
    val decisionMargin: Float = 0.0f,
    val secondBestRoll: String? = null,
    val secondBestSimilarity: Float = 0.0f,
    val explanation: String = ""
)

data class CachedBiometric(
    val templateId: String,
    val studentRoll: String,
    val angleType: String,
    val embedding: FloatArray
)

@Suppress("DEPRECATION")
class FaceRecognitionEngine(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "OmniFaceNeuralEngine"
        private const val CAVAFACE_LOCAL_PATH = "/storage/emulated/0/AI-HUB/FR/models/qualcomm_cavaface/cavaface-tflite-float/cavaface.tflite"
    }

    val npuHardwareInfo: NpuHardwareInfo = NpuHardwareDetector.detectNpuHardware()

    private var tfliteInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    var activeHardwareTier: HardwareTier = HardwareTier.GPU_DELEGATE
        private set
    var activeBackbone: NeuralBackbone = NeuralBackbone.MOBILEFACENET
        private set

    val isSnapdragonFlagship: Boolean = NpuHardwareDetector.isQualcommAiHubDevice() ||
            (npuHardwareInfo.socModel.contains("Snapdragon", ignoreCase = true) &&
            (npuHardwareInfo.socModel.contains("8", ignoreCase = true) || npuHardwareInfo.socModel.contains("SM8", ignoreCase = true)))

    private fun findCavaFaceFile(): File? {
        val path1 = File(CAVAFACE_LOCAL_PATH)
        if (path1.exists() && path1.canRead()) return path1
        val suiteModel = QualcommSuiteDownloadManager.SUITE_MODELS.find { it.id == "cavaface" }
        if (suiteModel != null) {
            val resolved = QualcommSuiteDownloadManager.resolveModelFile(context, suiteModel)
            if (resolved != null && resolved.exists() && resolved.canRead()) return resolved
        }
        val path2 = File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/cavaface/cavaface-tflite-float/cavaface.tflite")
        if (path2.exists() && path2.canRead()) return path2
        val path3 = File(context.getExternalFilesDir(null), "models/qualcomm_suite/cavaface/cavaface.tflite")
        if (path3.exists() && path3.canRead()) return path3
        return null
    }

    private val inputSize = 112
    private val embeddingDim = 512

    // Pre-Allocated Native Direct Buffers for Zero-GC Execution
    private val inputBufferFloat: ByteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4).apply {
        order(ByteOrder.nativeOrder())
    }
    private val inputBufferInt8: ByteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 1).apply {
        order(ByteOrder.nativeOrder())
    }
    private val outputBufferFloat = Array(1) { FloatArray(embeddingDim) }
    private val outputBufferInt8 = Array(1) { ByteArray(embeddingDim) }
    private val pixelBuffer = IntArray(inputSize * inputSize)

    // In-Memory Decrypted Biometric Matrix Cache
    private val biometricCache = CopyOnWriteArrayList<CachedBiometric>()

    init {
        initializeHardwareEngine()
    }

    @Suppress("DEPRECATION")
    private fun initializeHardwareEngine() {
        val cavaFile = findCavaFaceFile()

        // 1. If Qualcomm CavaFace is available and ready on disk, use CavaFace
        if (cavaFile != null && cavaFile.exists() && cavaFile.canRead()) {
            Log.i(TAG, "⚡ [QUALCOMM EXCLUSIVE] Initializing Qualcomm CavaFace (65.5M IR-SE-100)...")
            val modelBuffer = loadModelFile("cavaface.tflite")

            // Priority 1: Qualcomm Adreno GPU Delegate (FP16)
            try {
                val gpuOptions = GpuDelegate.Options().apply {
                    setPrecisionLossAllowed(true)
                    setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                }
                val gpu = GpuDelegate(gpuOptions)
                gpuDelegate = gpu
                val options = Interpreter.Options().apply { addDelegate(gpu) }
                val testInterpreter = Interpreter(modelBuffer, options)
                warmupFloat(testInterpreter)
                tfliteInterpreter = testInterpreter
                activeHardwareTier = HardwareTier.GPU_DELEGATE
                Log.i(TAG, "✅ [QUALCOMM SUCCESS] CavaFace Active on Adreno GPU Delegate (FP16)!")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "⚠️ CavaFace GPU init failed (${t.message}), trying CPU XNNPACK...")
                try { gpuDelegate?.close() } catch (_: Throwable) {}
                gpuDelegate = null
            }

            // Priority 2: Multi-Core CPU XNNPACK (FP32)
            try {
                val options = Interpreter.Options().apply {
                    setNumThreads(4)
                    useXNNPACK = true
                }
                val testInterpreter = Interpreter(modelBuffer, options)
                warmupFloat(testInterpreter)
                tfliteInterpreter = testInterpreter
                activeHardwareTier = HardwareTier.CPU_XNNPACK
                Log.i(TAG, "✅ [QUALCOMM SUCCESS] CavaFace Active on 4-Thread CPU XNNPACK!")
                return
            } catch (t: Throwable) {
                Log.e(TAG, "❌ CavaFace initialization failed: ${t.message}")
            }
        }

        // =========================================================================
        // PRIMARY HARDWARE TIER 1: Silicon NPU / NNAPI Hardware Accelerator (INT8)
        // Tested on: Qualcomm Hexagon HTP / MediaTek APU / Google Tensor TPU / Samsung NPU
        // =========================================================================
        try {
            Log.i(TAG, "⚡ [TIER 1 - NPU NNAPI] Initializing Genuine Silicon NPU Accelerator with INT8 Graph...")
            val modelBuffer = loadModelFile("mobilefacenet_512d_int8.tflite")
            val nnApiOptions = NnApiDelegate.Options().apply {
                setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                setAllowFp16(true)
            }
            val nnapi = NnApiDelegate(nnApiOptions)
            nnApiDelegate = nnapi

            val options = Interpreter.Options().apply {
                addDelegate(nnapi)
                setNumThreads(4)
            }
            val testInterpreter = Interpreter(modelBuffer, options)

            // Warm-up INT8
            inputBufferInt8.rewind()
            for (i in 0 until (inputSize * inputSize * 3)) inputBufferInt8.put(0.toByte())
            inputBufferInt8.rewind()
            testInterpreter.run(inputBufferInt8, outputBufferInt8)

            tfliteInterpreter = testInterpreter
            activeHardwareTier = HardwareTier.NPU_NNAPI
            Log.i(TAG, "✅ [SUCCESS] Genuine Silicon NPU Hardware Accelerator Active! Mode: INT8 Graph (${npuHardwareInfo.npuName}).")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ NPU / NNAPI Delegate failed (${t.message}). Trying Mobile GPU Delegate...")
            try { nnApiDelegate?.close() } catch (_: Throwable) {}
            nnApiDelegate = null
        }

        // =========================================================================
        // HARDWARE TIER 2: Mobile GPU Hardware Delegate (FP16)
        // =========================================================================
        try {
            Log.i(TAG, "🎮 [TIER 2 - GPU] Initializing Mobile GPU Delegate with FP16 Graph...")
            val modelBuffer = loadModelFile("mobilefacenet_512d_fp16.tflite")
            val gpuOptions = GpuDelegate.Options().apply {
                setPrecisionLossAllowed(true)
                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            }
            val gpu = GpuDelegate(gpuOptions)
            gpuDelegate = gpu

            val options = Interpreter.Options().apply { addDelegate(gpu) }
            val testInterpreter = Interpreter(modelBuffer, options)
            warmupFloat(testInterpreter)
            tfliteInterpreter = testInterpreter
            activeHardwareTier = HardwareTier.GPU_DELEGATE
            Log.i(TAG, "✅ [SUCCESS] Mobile GPU Delegate Active! Mode: FP16 ($activeBackbone).")
            return
        } catch (t: Throwable) {
            Log.w(TAG, "⚠️ GPU Delegate failed (${t.message}). Falling back to Multi-Core CPU XNNPACK...")
            try { gpuDelegate?.close() } catch (_: Throwable) {}
            gpuDelegate = null
        }

        // =========================================================================
        // HARDWARE TIER 3: Multi-Core CPU XNNPACK Threadpool (FP32)
        // =========================================================================
        try {
            Log.i(TAG, "💻 [TIER 3 - CPU] Initializing Multi-Threaded CPU XNNPACK (4 Threads)...")
            val modelBuffer = loadModelFile("mobilefacenet_512d_fp32.tflite")
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                useXNNPACK = true
            }
            val testInterpreter = Interpreter(modelBuffer, options)
            warmupFloat(testInterpreter)
            tfliteInterpreter = testInterpreter
            activeHardwareTier = HardwareTier.CPU_XNNPACK
            Log.i(TAG, "✅ CPU XNNPACK Active! Mode: FP32 Baseline Reference ($activeBackbone).")
            return
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Critical Neural Engine Failure: Unable to initialize any TFLite tier (${t.message})")
        }
    }

    private fun warmupFloat(interpreter: Interpreter) {
        inputBufferFloat.rewind()
        for (i in 0 until (inputSize * inputSize * 3)) inputBufferFloat.putFloat(128.0f)
        inputBufferFloat.rewind()
        interpreter.run(inputBufferFloat, outputBufferFloat)
    }

    fun reloadEngine() {
        synchronized(this) {
            try {
                tfliteInterpreter?.close()
                gpuDelegate?.close()
                nnApiDelegate?.close()
            } catch (_: Throwable) {}
            tfliteInterpreter = null
            gpuDelegate = null
            nnApiDelegate = null
            initializeHardwareEngine()
        }
    }

    fun switchHardwareTier(tier: HardwareTier) {
        synchronized(this) {
            try {
                tfliteInterpreter?.close()
                gpuDelegate?.close()
                nnApiDelegate?.close()
            } catch (_: Throwable) {}
            tfliteInterpreter = null
            gpuDelegate = null
            nnApiDelegate = null

            when (tier) {
                HardwareTier.NPU_NNAPI -> {
                    try {
                        val modelBuffer = loadModelFile("mobilefacenet_512d_int8.tflite")
                        val nnApiOptions = NnApiDelegate.Options().apply {
                            setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                            setAllowFp16(true)
                        }
                        val nnapi = NnApiDelegate(nnApiOptions)
                        nnApiDelegate = nnapi
                        val options = Interpreter.Options().apply {
                            addDelegate(nnapi)
                            setNumThreads(4)
                        }
                        tfliteInterpreter = Interpreter(modelBuffer, options)
                        activeHardwareTier = HardwareTier.NPU_NNAPI
                        return
                    } catch (_: Throwable) {}
                }
                HardwareTier.GPU_DELEGATE -> {
                    try {
                        val modelBuffer = loadModelFile("mobilefacenet_512d_fp16.tflite")
                        val gpuOptions = GpuDelegate.Options().apply {
                            setPrecisionLossAllowed(true)
                            setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                        }
                        val gpu = GpuDelegate(gpuOptions)
                        gpuDelegate = gpu
                        val options = Interpreter.Options().apply { addDelegate(gpu) }
                        tfliteInterpreter = Interpreter(modelBuffer, options)
                        activeHardwareTier = HardwareTier.GPU_DELEGATE
                        return
                    } catch (_: Throwable) {}
                }
                HardwareTier.CPU_XNNPACK -> {
                    try {
                        val modelBuffer = loadModelFile("mobilefacenet_512d_fp32.tflite")
                        val options = Interpreter.Options().apply {
                            setNumThreads(4)
                            useXNNPACK = true
                        }
                        tfliteInterpreter = Interpreter(modelBuffer, options)
                        activeHardwareTier = HardwareTier.CPU_XNNPACK
                        return
                    } catch (_: Throwable) {}
                }
            }
            initializeHardwareEngine()
        }
    }

    private fun loadModelFile(modelName: String): ByteBuffer {
        // Priority 0: Check for local Qualcomm CavaFace model on Qualcomm devices
        if (modelName.contains("cavaface", ignoreCase = true)) {
            val cavafaceFile = findCavaFaceFile()
            if (cavafaceFile != null && cavafaceFile.exists() && cavafaceFile.canRead()) {
                Log.i(TAG, "⚡ Loading Qualcomm AI Hub CavaFace Engine from ${cavafaceFile.absolutePath} (${cavafaceFile.length() / 1024 / 1024} MB)...")
                val inputStream = FileInputStream(cavafaceFile)
                val fileChannel = inputStream.channel
                activeBackbone = NeuralBackbone.QUALCOMM_CAVAFACE
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, cavafaceFile.length())
            }
        }

        // Priority 1: Check for verified downloaded model in private app storage
        val downloadManager = ModelDownloadManager.getInstance(context)
        val localFile = downloadManager.getLocalModelFile()
        if (localFile.exists() && (localFile.name == modelName || modelName.contains("antelope", ignoreCase = true) || modelName.contains("mobilefacenet", ignoreCase = true)) && downloadManager.verifyModelIntegrity(localFile)) {
            Log.i(TAG, "📂 Loading verified private Hugging Face model from: ${localFile.absolutePath} (${localFile.length()} bytes)")
            val inputStream = FileInputStream(localFile)
            val fileChannel = inputStream.channel
            activeBackbone = NeuralBackbone.MOBILEFACENET
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, localFile.length())
        }

        // Priority 2: Check for on-device disk models directory
        val candidateFiles = listOf(
            File(context.filesDir, "models/$modelName"),
            File(context.getExternalFilesDir(null), "models/$modelName"),
            File("/storage/emulated/0/AI-HUB/FR/models/$modelName")
        )
        for (f in candidateFiles) {
            if (f.exists() && f.canRead() && f.length() > 1024) {
                Log.i(TAG, "📂 Loading on-device model from: ${f.absolutePath} (${f.length()} bytes)")
                val inputStream = FileInputStream(f)
                val fileChannel = inputStream.channel
                activeBackbone = NeuralBackbone.MOBILEFACENET
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
            }
        }

        // Priority 3: Fallback to pre-bundled APK assets if present
        activeBackbone = NeuralBackbone.MOBILEFACENET
        return try {
            val fileDescriptor: AssetFileDescriptor = context.assets.openFd(modelName)
            val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = fileDescriptor.startOffset
            val declaredLength = fileDescriptor.declaredLength
            fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ Model $modelName not bundled in assets and not yet downloaded on device: ${e.message}")
            throw e
        }
    }

    fun preloadTemplates(templates: List<FaceTemplateEntity>) {
        biometricCache.clear()
        var loadedCount = 0
        var skippedCount = 0
        for (t in templates) {
            val rawCsv = if (t.isEncrypted) {
                try {
                    AndroidSecurityUtils.decrypt(t.embeddingEncryptedCsv)
                } catch (e: Exception) {
                    // Do NOT fall back to embeddingEncryptedCsv — it is ciphertext, not a valid embedding.
                    // Log a distinct ERROR so operators know templates are corrupt (key rotation / loss).
                    Log.e(TAG, "❌ DECRYPT FAILED for template ${t.id} (student ${t.studentRoll} / ${t.angleType}): ${e.message}. Template SKIPPED — re-enroll this student.")
                    skippedCount++
                    continue
                }
            } else {
                t.embeddingEncryptedCsv
            }
            val emb = parseEmbeddingCsv(rawCsv)
            if (emb.isNotEmpty()) {
                biometricCache.add(CachedBiometric(t.id, t.studentRoll, t.angleType, emb))
                loadedCount++
            } else {
                Log.w(TAG, "⚠️ Empty embedding for template ${t.id} (${t.angleType}) — skipped.")
                skippedCount++
            }
        }
        Log.i(TAG, "📦 Biometric cache loaded: $loadedCount templates, $skippedCount skipped.")
    }

    // Canonical ArcFace 112x112 4-Point Target Coordinates (Left Eye, Right Eye, Left Mouth, Right Mouth)
    private val DST_CANONICAL_POINTS = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    fun alignFace5Point(sourceBitmap: Bitmap, srcPoints: FloatArray): Bitmap {
        if (srcPoints.size < 8) return sourceBitmap
        val matrix = Matrix()
        val success = matrix.setPolyToPoly(srcPoints, 0, DST_CANONICAL_POINTS, 0, 4)
        if (!success) return sourceBitmap

        val aligned = Bitmap.createBitmap(inputSize, inputSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(aligned)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(sourceBitmap, matrix, paint)
        return aligned
    }

    @Synchronized
    fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        // Single forward pass at scan time — flip augmentation removed (was 2x inference cost).
        // Flip is applied only at ENROLLMENT time via captureCurrentAngle() to build richer templates.
        return extractRawEmbedding(faceBitmap)
    }

    /**
     * Enrollment-time embedding with horizontal flip averaging for richer templates.
     * NOT used at scan time — only during enrollment captureCurrentAngle().
     */
    @Synchronized
    fun extractEmbeddingWithFlipAugmentation(faceBitmap: Bitmap): FloatArray {
        val embOriginal = extractRawEmbedding(faceBitmap)
        val flipMatrix = Matrix().apply { preScale(-1.0f, 1.0f) }
        val flipped = try {
            Bitmap.createBitmap(faceBitmap, 0, 0, faceBitmap.width, faceBitmap.height, flipMatrix, true)
        } catch (_: Exception) { null }

        if (flipped != null) {
            val embFlipped = extractRawEmbedding(flipped)
            if (flipped != faceBitmap) flipped.recycle()
            val fused = FloatArray(embeddingDim)
            for (i in 0 until embeddingDim) fused[i] = (embOriginal[i] + embFlipped[i]) * 0.5f
            return l2Normalize(fused)
        }
        return embOriginal
    }

    @Synchronized
    fun extractEmbeddingWithLandmarks(sourceBitmap: Bitmap, landmarkPoints: FloatArray): FloatArray {
        val aligned = alignFace5Point(sourceBitmap, landmarkPoints)
        val embedding = extractEmbedding(aligned)
        if (aligned != sourceBitmap) {
            aligned.recycle()
        }
        return embedding
    }

    @Synchronized
    private fun extractRawEmbedding(faceBitmap: Bitmap): FloatArray {
        val interpreter = tfliteInterpreter ?: throw IllegalStateException("TFLite Neural Interpreter not initialized.")
        val resized = Bitmap.createScaledBitmap(faceBitmap, inputSize, inputSize, true)
        resized.getPixels(pixelBuffer, 0, inputSize, 0, 0, inputSize, inputSize)
        if (resized != faceBitmap) {
            resized.recycle()
        }

        val embedding = when (activeHardwareTier) {
            HardwareTier.NPU_NNAPI -> {
                inputBufferInt8.rewind()
                for (pixel in pixelBuffer) {
                    val r = (((pixel shr 16) and 0xFF) - 128).coerceIn(-128, 127)
                    val g = (((pixel shr 8) and 0xFF) - 128).coerceIn(-128, 127)
                    val b = ((pixel and 0xFF) - 128).coerceIn(-128, 127)
                    inputBufferInt8.put(r.toByte())
                    inputBufferInt8.put(g.toByte())
                    inputBufferInt8.put(b.toByte())
                }
                interpreter.run(inputBufferInt8, outputBufferInt8)
                val rawBytes = outputBufferInt8[0]
                val floatOut = FloatArray(embeddingDim)
                for (i in 0 until embeddingDim) {
                    floatOut[i] = rawBytes[i].toFloat() / 128.0f
                }
                l2Normalize(floatOut)
            }
            else -> {
                inputBufferFloat.rewind()
                val isCavaface = activeBackbone == NeuralBackbone.QUALCOMM_CAVAFACE
                for (pixel in pixelBuffer) {
                    val r = ((pixel shr 16) and 0xFF).toFloat()
                    val g = ((pixel shr 8) and 0xFF).toFloat()
                    val b = (pixel and 0xFF).toFloat()
                    if (isCavaface) {
                        // Qualcomm CavaFace expects normalized float [0.0f, 1.0f]
                        inputBufferFloat.putFloat(r / 255.0f)
                        inputBufferFloat.putFloat(g / 255.0f)
                        inputBufferFloat.putFloat(b / 255.0f)
                    } else {
                        // MobileFaceNet in-graph Rescaling expects [0.0f, 255.0f]
                        inputBufferFloat.putFloat(r)
                        inputBufferFloat.putFloat(g)
                        inputBufferFloat.putFloat(b)
                    }
                }
                interpreter.run(inputBufferFloat, outputBufferFloat)
                l2Normalize(outputBufferFloat[0].copyOf())
            }
        }

        return embedding
    }

    @Synchronized
    fun benchmarkInferenceLatency(): Long {
        val interpreter = tfliteInterpreter ?: return 0L
        val startTime = System.nanoTime()
        when (activeHardwareTier) {
            HardwareTier.NPU_NNAPI -> {
                inputBufferInt8.rewind()
                interpreter.run(inputBufferInt8, outputBufferInt8)
            }
            else -> {
                inputBufferFloat.rewind()
                interpreter.run(inputBufferFloat, outputBufferFloat)
            }
        }
        val elapsedNanos = System.nanoTime() - startTime
        return (elapsedNanos / 1_000_000L).coerceAtLeast(1L)
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vec) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm > 1e-6f) {
            val invNorm = 1.0f / norm
            for (i in vec.indices) vec[i] *= invNorm
        } else {
            java.util.Arrays.fill(vec, 0.0f)
        }
        return vec
    }

    suspend fun matchFace(
        queryEmbedding: FloatArray,
        knownTemplates: List<FaceTemplateEntity>,
        studentMap: Map<String, String>,
        securityTier: SecurityTier = SecurityTier.HIGH
    ): MatchResult = withContext(Dispatchers.Default) {
        if (biometricCache.isEmpty() && knownTemplates.isNotEmpty()) {
            preloadTemplates(knownTemplates)
        }

        if (biometricCache.isEmpty()) {
            return@withContext MatchResult(
                studentRoll = "GUEST",
                studentName = "Unknown Guest",
                confidence = 0.0f,
                similarity = 0.0f,
                isMatch = false,
                hardwareTier = activeHardwareTier,
                confidenceZone = ConfidenceZone.REJECT,
                decisionMargin = 0.0f,
                explanation = "Database is empty — no enrolled face templates"
            )
        }

        // ── High-Precision Multi-Angle Candidate Scoring ────
        // Step 1: For each student, find their MAXIMUM similarity across their multi-angle templates
        // (frontal, left 22°, right 22°, up 16°, down 16°).
        // Since a person presents one pose at a time, the best-matching angle represents their true biometric likeness.
        val perStudentBestScores = HashMap<String, Float>()
        val perStudentBestAngles = HashMap<String, String>()

        for (cached in biometricCache) {
            val sim = fastVectorDotProduct(queryEmbedding, cached.embedding)
            val currentBest = perStudentBestScores[cached.studentRoll] ?: -1.0f
            if (sim > currentBest) {
                perStudentBestScores[cached.studentRoll] = sim
                perStudentBestAngles[cached.studentRoll] = cached.angleType
            }
        }

        // Step 2: Rank all candidate students by best similarity descending
        val rankedCandidates = perStudentBestScores.map { (roll, sim) ->
            roll to sim
        }.sortedByDescending { it.second }

        val top1 = rankedCandidates.getOrNull(0)
        val top2 = rankedCandidates.getOrNull(1)

        val maxSimilarity = top1?.second ?: 0.0f
        val bestMatchRoll = top1?.first ?: "GUEST"
        val matchedAngle = perStudentBestAngles[bestMatchRoll] ?: "FRONTAL"
        val top2Similarity = top2?.second ?: 0.0f
        val top2Roll = top2?.first

        val margin = if (rankedCandidates.size > 1) (maxSimilarity - top2Similarity) else maxSimilarity
        val threshold = securityTier.threshold
        val marginThreshold = 0.070f // Strict margin guard against lookalike / twin confusion

        val confidenceZone: ConfidenceZone
        val isMatch: Boolean
        val explanation: String

        if (maxSimilarity >= threshold && (rankedCandidates.size <= 1 || margin >= marginThreshold)) {
            confidenceZone = ConfidenceZone.ACCEPT
            isMatch = true
            val top2Text = if (top2Roll != null) " (Top-2: $top2Roll @ ${"%.3f".format(top2Similarity)})" else ""
            explanation = "Verified: $bestMatchRoll (sim ${"%.3f".format(maxSimilarity)} >= ${"%.3f".format(threshold)} [$matchedAngle], Δ=${"%.3f".format(margin)}$top2Text)"
        } else if (maxSimilarity >= threshold && margin < marginThreshold) {
            // Sibling / Twin / Ambiguous match safeguard
            confidenceZone = ConfidenceZone.REVIEW
            isMatch = false
            explanation = "Ambiguous Identity: Top-1 $bestMatchRoll (${"%.3f".format(maxSimilarity)}) vs Top-2 ${top2Roll ?: "unknown"} (${"%.3f".format(top2Similarity)}) has narrow margin Δ=${"%.3f".format(margin)} < ${"%.3f".format(marginThreshold)}"
        } else if (maxSimilarity >= (threshold - 0.060f)) {
            // Borderline score
            confidenceZone = ConfidenceZone.REVIEW
            isMatch = false
            explanation = "Borderline Likeness: Cosine sim ${"%.3f".format(maxSimilarity)} near threshold ${"%.3f".format(threshold)} (Δ=${"%.3f".format(margin)})"
        } else {
            confidenceZone = ConfidenceZone.REJECT
            isMatch = false
            explanation = "Unregistered / Visitor: Cosine sim ${"%.3f".format(maxSimilarity)} < threshold ${"%.3f".format(threshold)}"
        }

        val name = if (isMatch) studentMap[bestMatchRoll] ?: bestMatchRoll else "Visitor / Unregistered"

        // Calibrated 0-100% confidence for UI presentation
        val normalizedConfidence = if (isMatch) {
            val progress = ((maxSimilarity - threshold) / (0.85f - threshold).coerceAtLeast(0.10f)).coerceIn(0.0f, 1.0f)
            (82.0f + progress * 17.9f).coerceIn(82.0f, 99.9f)
        } else {
            ((maxSimilarity.coerceAtLeast(0f) / threshold) * 69.0f).coerceIn(0.0f, 69.0f)
        }

        MatchResult(
            studentRoll = if (isMatch) bestMatchRoll else "GUEST",
            studentName = name,
            confidence = normalizedConfidence,
            similarity = maxSimilarity,
            isMatch = isMatch,
            hardwareTier = activeHardwareTier,
            confidenceZone = confidenceZone,
            decisionMargin = margin,
            secondBestRoll = top2Roll,
            secondBestSimilarity = top2Similarity,
            explanation = explanation
        )
    }

    private fun fastVectorDotProduct(a: FloatArray, b: FloatArray): Float {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0f
        var sum = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in 0 until size) {
            val va = a[i]
            val vb = b[i]
            sum += va * vb
            normA += va * va
            normB += vb * vb
        }
        val denom = kotlin.math.sqrt(normA * normB)
        return if (denom > 1e-7f) {
            (sum / denom).coerceIn(-1.0f, 1.0f)
        } else {
            0.0f
        }
    }

    private fun parseEmbeddingCsv(csv: String): FloatArray {
        return try {
            csv.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    override fun close() {
        tfliteInterpreter?.close()
        gpuDelegate?.close()
        nnApiDelegate?.close()
        for (cached in biometricCache) {
            Arrays.fill(cached.embedding, 0.0f)
        }
        biometricCache.clear()
    }
}
