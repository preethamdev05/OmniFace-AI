@file:Suppress("DEPRECATION")

package com.omniface.ai.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.omniface.ai.ml.antispoof.PassivePadResult
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UnifiedFaceInferenceResult(
    val embedding512: FloatArray,
    val cavafaceEmbedding512: FloatArray = FloatArray(512),
    val passivePad: PassivePadResult,
    val map3d: FaceMap3DMMResult,
    val attributes: FaceAttributesResult,
    val gaze: EyeGazeResult,
    val mesh: MediaPipeMeshResult? = null,
    val hrnet: HRNetFaceResult? = null,
    val totalInferenceMs: Long
)

data class UnifiedRegistrationResult(
    val embedding512: FloatArray,
    val passivePad: PassivePadResult,
    val map3d: FaceMap3DMMResult,
    val attributes: FaceAttributesResult,
    val gaze: EyeGazeResult,
    val totalInferenceMs: Long
)

/**
 * Sovereign Unified LiteRT / TFLite Inference Engine for OmniFace-AI.
 *
 * Consolidates all 8 biometric neural networks into ONE single unified LiteRT model:
 * 1. MiniFASNetV2 LiteRT (Passive RGB PAD / Anti-Spoofing)
 * 2. Qualcomm AI Hub CavaFace (Flagship 65.5M Param ArcFace-512 Identity Embedding)
 * 3. FaceNet-512 (512-D L2-Normalized Identity Biometrics)
 * 4. Qualcomm FaceMap 3DMM (265-D Surface Geometry & Depth Variance)
 * 5. Qualcomm FaceAttribNet (Expression, Smile, Eyeglasses, Head Pose)
 * 6. Qualcomm EyeGaze (Pupil Pitch/Yaw, 34 Eye Keypoints, Attention Cone)
 * 7. Qualcomm MediaPipe Mesh (468 Dense 3D Topological Mesh Points)
 * 8. Qualcomm HRNetFace (29 High-Resolution Heatmap Landmarks)
 *
 * Packaged as single on-device model: `assets/unified_omniface.tflite`.
 */
class UnifiedFaceIntelligenceEngine private constructor(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "UnifiedFaceEngine"
        const val MODEL_ASSET = "unified_omniface.tflite"

        @Volatile
        private var INSTANCE: UnifiedFaceIntelligenceEngine? = null

        fun getInstance(context: Context): UnifiedFaceIntelligenceEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UnifiedFaceIntelligenceEngine(context.applicationContext).also { INSTANCE = it }
            }
    }

    private var interpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null
    private var nnApiDelegate: NnApiDelegate? = null
    var isModelLoaded: Boolean = false
        private set
    var activeBackend: String = "CPU (XNNPACK)"
        private set

    private val _isModelLoadedState = MutableStateFlow(false)
    val isModelLoadedState: StateFlow<Boolean> = _isModelLoadedState.asStateFlow()

    // Preallocated direct ByteBuffers for inputs (thread-safe synchronization on inference)
    private val bufferLock = Any()
    private val inputAntiSpoof = ByteBuffer.allocateDirect(1 * 3 * 80 * 80 * 4).order(ByteOrder.nativeOrder())
    private val inputCavaface = ByteBuffer.allocateDirect(1 * 112 * 112 * 3 * 4).order(ByteOrder.nativeOrder())
    private val input3DMM = ByteBuffer.allocateDirect(1 * 128 * 128 * 3 * 4).order(ByteOrder.nativeOrder())
    private val inputAttrib = ByteBuffer.allocateDirect(1 * 128 * 128 * 3 * 4).order(ByteOrder.nativeOrder())
    private val inputEyeGaze = ByteBuffer.allocateDirect(1 * 96 * 160 * 4).order(ByteOrder.nativeOrder())
    private val inputMesh = ByteBuffer.allocateDirect(1 * 192 * 192 * 3 * 4).order(ByteOrder.nativeOrder())
    private val inputHRNet = ByteBuffer.allocateDirect(1 * 256 * 256 * 3 * 4).order(ByteOrder.nativeOrder())

    // Preallocated output data structures
    private val outAntiSpoof = Array(1) { FloatArray(3) }
    private val outCavaface = Array(1) { FloatArray(512) }
    private val out3DMM = Array(1) { FloatArray(265) }
    private val outAttrib = Array(1) { FloatArray(5) }
    private val outEyeHeatmaps = ByteBuffer.allocateDirect(1 * 3 * 34 * 48 * 80 * 4).order(ByteOrder.nativeOrder())
    private val outEyeLandmarks = Array(1) { Array(34) { FloatArray(2) } }
    private val outEyePitchYaw = Array(1) { FloatArray(2) }
    private val outMeshScores = FloatArray(1)
    private val outMeshLandmarks = Array(1) { Array(468) { FloatArray(3) } }
    private val outHRNetHeatmaps = ByteBuffer.allocateDirect(1 * 29 * 64 * 64 * 4).order(ByteOrder.nativeOrder())

    init {
        // Standby mode by default: Model is loaded on-demand via loadUnifiedModelExplicit()
    }

    fun getLocalUnifiedModelFile(): File {
        return File(File(context.filesDir, "models"), MODEL_ASSET)
    }

    @Synchronized
    fun reloadModel(): Boolean {
        return loadUnifiedModelExplicit()
    }

    @Synchronized
    fun loadUnifiedModelExplicit(context: Context = this.context): Boolean {
        if (isModelLoaded && interpreter != null) return true
        loadUnifiedModel()
        return isModelLoaded
    }

    @Synchronized
    fun unloadUnifiedModel() {
        synchronized(bufferLock) {
            try {
                interpreter?.close()
                interpreter = null
                nnApiDelegate?.close()
                nnApiDelegate = null
                gpuDelegate?.close()
                gpuDelegate = null
                isModelLoaded = false
                _isModelLoadedState.value = false
                Log.i(TAG, "Unified Face Intelligence Engine unloaded. RAM and NPU memory released.")
                System.gc()
            } catch (e: Throwable) {
                Log.e(TAG, "Error while unloading model: ${e.message}", e)
            }
        }
    }

    private fun loadUnifiedModel() {
        try {
            val localFile = getLocalUnifiedModelFile()
            val externalDevFile = File("/storage/emulated/0/AI-HUB/FR/models/$MODEL_ASSET")

            val modelBuffer: ByteBuffer = when {
                localFile.exists() && localFile.length() > 10_000_000L -> {
                    Log.i(TAG, "⚡ Loading unified model from app private storage: ${localFile.absolutePath} (${localFile.length() / 1024 / 1024} MB)")
                    FileInputStream(localFile).channel.use { channel ->
                        channel.map(FileChannel.MapMode.READ_ONLY, 0, localFile.length())
                    }
                }
                runCatching { externalDevFile.exists() && externalDevFile.canRead() && externalDevFile.length() > 10_000_000L }.getOrDefault(false) -> {
                    val buf = runCatching {
                        Log.i(TAG, "⚡ Loading unified model from external test storage: ${externalDevFile.absolutePath}")
                        FileInputStream(externalDevFile).channel.use { channel ->
                            channel.map(FileChannel.MapMode.READ_ONLY, 0, externalDevFile.length())
                        }
                    }.getOrNull()
                    buf ?: run {
                        isModelLoaded = false
                        _isModelLoadedState.value = false
                        return
                    }
                }
                else -> {
                    Log.i(TAG, "Unified model not present in local storage. Model must be downloaded on-demand from Cloudflare R2.")
                    isModelLoaded = false
                    _isModelLoadedState.value = false
                    return
                }
            }

            // Fast-Path Multi-Threaded XNNPACK SIMD (Sub-300ms Instant Startup)
            // Memory-mapped execution avoids 1-2 minute driver compilation/partitioning stalls
            // across complex 380MB multi-head graph architectures on mobile devices.
            var loadedInterpreter: Interpreter? = null
            try {
                val cpuOptions = Interpreter.Options().apply {
                    numThreads = 4
                    useXNNPACK = true
                }
                loadedInterpreter = Interpreter(modelBuffer, cpuOptions)
                activeBackend = "CPU (4-Core XNNPACK SIMD)"
                Log.i(TAG, "⚡ Unified model loaded instantly on optimized CPU: $activeBackend")
            } catch (cpuEx: Throwable) {
                Log.w(TAG, "Standard CPU fallback: ${cpuEx.message}")
                val fallbackOptions = Interpreter.Options().apply {
                    numThreads = 4
                }
                loadedInterpreter = Interpreter(modelBuffer, fallbackOptions)
                activeBackend = "CPU (Standard Multi-Threaded)"
            }

            interpreter = loadedInterpreter

            isModelLoaded = true
            _isModelLoadedState.value = true
            Log.i(TAG, "Unified LiteRT model initialized. File size: ${modelBuffer.capacity()} bytes")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize unified LiteRT model: ${e.message}", e)
            isModelLoaded = false
            _isModelLoadedState.value = false
        }
    }

    /**
     * Executes single-pass unified inference across all 7 biometric heads.
     * When [alignedFace] is provided (e.g. from Umeyama 5-point landmark alignment),
     * it is fed directly into Qualcomm CavaFace ArcFace-512 for bit-accurate identity matching,
     * while the surrounding [faceCrop] is fed into the auxiliary anti-spoof/3D/mesh heads.
     */
    fun processFace(
        faceCrop: Bitmap,
        headYaw: Float = 0f,
        headPitch: Float = 0f,
        leftEyeOpenProb: Float? = null,
        rightEyeOpenProb: Float? = null,
        alignedFace: Bitmap? = null
    ): UnifiedFaceInferenceResult? {
        val interp = interpreter ?: return null
        if (!isModelLoaded || faceCrop.isRecycled) return null

        val t0 = SystemClock.elapsedRealtime()

        synchronized(bufferLock) {
            // 1. Populate Input 0: Anti-Spoof (MiniFASNetV2) [1, 3, 80, 80] Float32 NCHW
            populateAntiSpoofBuffer(faceCrop)

            // 2. Populate Input 1: Qualcomm CavaFace [1, 112, 112, 3] Float32 NHWC [0.0, 1.0]
            val cavaFaceBitmap = if (alignedFace != null && !alignedFace.isRecycled) alignedFace else faceCrop
            populateRgbNormalizedBuffer(cavaFaceBitmap, inputCavaface, 112, 112)

            // 3. Populate Input 2: FaceMap 3DMM [1, 128, 128, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, input3DMM, 128, 128)

            // 4. Populate Input 3: FaceAttribNet [1, 128, 128, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, inputAttrib, 128, 128)

            // 5. Populate Input 4: EyeGaze [1, 96, 160] Float32 Grayscale
            populateEyeGazeBuffer(faceCrop)

            // 6. Populate Input 5: MediaPipe Mesh [1, 192, 192, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, inputMesh, 192, 192)

            // 7. Populate Input 6: HRNetFace [1, 256, 256, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, inputHRNet, 256, 256)

            val inputs = arrayOf<Any>(
                inputAntiSpoof,
                inputCavaface,
                input3DMM,
                inputAttrib,
                inputEyeGaze,
                inputMesh,
                inputHRNet
            )

            outEyeHeatmaps.rewind()
            outHRNetHeatmaps.rewind()

            val outputs = mutableMapOf<Int, Any>(
                0 to outAntiSpoof,
                1 to outCavaface,
                2 to out3DMM,
                3 to outAttrib,
                4 to outEyeHeatmaps,
                5 to outEyeLandmarks,
                6 to outEyePitchYaw,
                7 to outMeshScores,
                8 to outMeshLandmarks,
                9 to outHRNetHeatmaps
            )

            // Single unified native invocation
            rewindAllInputs()
            interp.runForMultipleInputsOutputs(inputs, outputs)

            val elapsedMs = SystemClock.elapsedRealtime() - t0

            // ── Parse Output 2: FaceMap 3DMM ──
            val params265 = out3DMM[0].clone()
            var sumVariance = 0f
            val varCount = minOf(params265.size, 40)
            for (i in 0 until varCount) {
                sumVariance += params265[i] * params265[i]
            }
            val depthVariance = sumVariance / varCount
            val is3D = depthVariance > 0.0015f // Planar screen/photo exhibits < 0.0015 depth variance
            val map3dResult = FaceMap3DMMResult(
                parameters265 = params265,
                depthVariance = depthVariance,
                isTrue3DSurface = is3D,
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 0: Anti-Spoof (MiniFASNetV2) ──
            // silentface.tflite outputs 3 probabilities: [Photo Spoof, Screen Spoof, Real Live Face]
            val rawPad = outAntiSpoof[0]
            val p0 = rawPad.getOrElse(0) { 0f }
            val p1 = rawPad.getOrElse(1) { 0f }
            val p2 = rawPad.getOrElse(2) { 0f }
            val sumP = p0 + p1 + p2
            val (spoofPhoto, spoofScreen, liveProb) = if (sumP in 0.90f..1.10f) {
                Triple(p0.coerceIn(0f, 1f), p1.coerceIn(0f, 1f), p2.coerceIn(0f, 1f))
            } else {
                val maxL = maxOf(p0, maxOf(p1, p2))
                val e0 = exp((p0 - maxL).toDouble()).toFloat()
                val e1 = exp((p1 - maxL).toDouble()).toFloat()
                val e2 = exp((p2 - maxL).toDouble()).toFloat()
                val s = (e0 + e1 + e2).coerceAtLeast(1e-9f)
                Triple(e0 / s, e1 / s, e2 / s)
            }
            val spoofScore = (spoofPhoto + spoofScreen).coerceIn(0f, 1f)
            val liveScore = liveProb.coerceIn(0f, 1f)

            // Fused multimodal anti-spoofing consensus
            val isConfirmedSpoof = (spoofScore >= 0.70f) || (!is3D && spoofScore >= 0.45f)
            val isLive = !isConfirmedSpoof
            val attackDesc = when {
                isLive -> "Live Real Human"
                spoofPhoto > spoofScreen -> "2D Photo Print Attack Detected"
                else -> "Electronic Screen Replay Attack Detected"
            }
            val padResult = PassivePadResult(
                isLive = isLive,
                livenessScore = liveScore,
                spoofProbability = spoofScore,
                attackTypeDescription = attackDesc,
                latencyMs = elapsedMs
            )

            // ── Parse Output 1: 512-D Qualcomm CavaFace Identity Embedding ──
            val rawCava = outCavaface[0]
            var cavaNormSum = 0f
            for (v in rawCava) cavaNormSum += v * v
            val cavaNorm = sqrt(cavaNormSum).coerceAtLeast(1e-12f)
            val normalizedCavaEmb = FloatArray(512) { i -> rawCava[i] / cavaNorm }

            // ── Parse Output 3: Qualcomm FaceAttribNet ──
            val rawAttr = outAttrib[0]
            val leftEyeOpen = rawAttr.getOrElse(0) { 1f }.coerceIn(0f, 1f)
            val rightEyeOpen = rawAttr.getOrElse(1) { 1f }.coerceIn(0f, 1f)
            val eyeglasses = rawAttr.getOrElse(2) { 0f }.coerceIn(0f, 1f)
            val mask = rawAttr.getOrElse(3) { 0f }.coerceIn(0f, 1f)
            val sunglasses = rawAttr.getOrElse(4) { 0f }.coerceIn(0f, 1f)
            val attrResult = FaceAttributesResult(
                leftEyeOpenScore = leftEyeOpen,
                rightEyeOpenScore = rightEyeOpen,
                eyeglassesScore = eyeglasses,
                maskScore = mask,
                sunglassesScore = sunglasses,
                rawProbabilities = rawAttr.clone(),
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 4, 5, 6: EyeGaze ──
            val pitch = outEyePitchYaw[0][0]
            val yaw = outEyePitchYaw[0][1]
            val gazeNorm = sqrt(pitch * pitch + yaw * yaw)
            val totalYaw = yaw + (headYaw * 0.0174533f)
            val isAttentive = gazeNorm < 0.45f && Math.abs(totalYaw) < 0.50f
            val gazeLandmarks = Array(34) { i -> outEyeLandmarks[0][i].clone() }
            val gazeResult = EyeGazeResult(
                pitch = pitch,
                yaw = yaw,
                gazeVectorNorm = gazeNorm,
                eyeLandmarks34x2 = gazeLandmarks,
                isGazeAttentive = isAttentive,
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 7, 8: MediaPipe Mesh ──
            val faceScore = outMeshScores[0]
            val meshLandmarks = Array(468) { i -> outMeshLandmarks[0][i].clone() }
            var zVarSum = 0f
            for (pt in meshLandmarks) {
                zVarSum += pt[2] * pt[2]
            }
            val meshZVariance = zVarSum / 468f
            val meshResult = MediaPipeMeshResult(
                landmarks468x3 = meshLandmarks,
                faceScore = faceScore,
                meshDepthVariance = meshZVariance,
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 9: HRNetFace Landmarks ──
            val hrnetLandmarks = Array(29) { FloatArray(2) }
            val hrnetConfidences = FloatArray(29)
            parseHrnetHeatmaps(outHRNetHeatmaps, hrnetLandmarks, hrnetConfidences)
            val hrnetResult = HRNetFaceResult(
                landmarks29x2 = hrnetLandmarks,
                landmarkConfidences = hrnetConfidences,
                executionTimeMs = elapsedMs.toFloat()
            )

            return UnifiedFaceInferenceResult(
                embedding512 = normalizedCavaEmb,
                cavafaceEmbedding512 = normalizedCavaEmb,
                passivePad = padResult,
                map3d = map3dResult,
                attributes = attrResult,
                gaze = gazeResult,
                mesh = meshResult,
                hrnet = hrnetResult,
                totalInferenceMs = elapsedMs
            )
        }
    }

    /**
     * Highly optimized registration inference path:
     * Evaluates ONLY the heads required for biometric enrollment:
     * - Output 0: MiniFASNetV2 Anti-Spoofing (Passive PAD)
     * - Output 1: Qualcomm CavaFace ArcFace-512 Identity Embedding
     * - Output 2: FaceMap 3DMM Depth Variance & Geometry
     * - Output 3: FaceAttribNet Sunglasses / Mask / Expression Gating
     * - Output 6: EyeGaze Pitch / Yaw Gaze Gating
     *
     * Skips heavy HRNet (Output 9) and MediaPipe dense mesh (Outputs 7, 8),
     * drastically reducing registration latency and memory pressure while keeping
     * all computation strictly inside the single unified model.
     */
    fun processRegistrationFace(
        faceCrop: Bitmap,
        alignedFace: Bitmap? = null
    ): UnifiedRegistrationResult? {
        val interp = interpreter ?: return null
        if (!isModelLoaded || faceCrop.isRecycled) return null

        val t0 = SystemClock.elapsedRealtime()

        synchronized(bufferLock) {
            // 1. Populate Input 0: Anti-Spoof (MiniFASNetV2) [1, 3, 80, 80] Float32 NCHW
            populateAntiSpoofBuffer(faceCrop)

            // 2. Populate Input 1: Qualcomm CavaFace [1, 112, 112, 3] Float32 NHWC [0.0, 1.0]
            val cavaFaceBitmap = if (alignedFace != null && !alignedFace.isRecycled) alignedFace else faceCrop
            populateRgbNormalizedBuffer(cavaFaceBitmap, inputCavaface, 112, 112)

            // 3. Populate Input 2: FaceMap 3DMM [1, 128, 128, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, input3DMM, 128, 128)

            // 4. Populate Input 3: FaceAttribNet [1, 128, 128, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, inputAttrib, 128, 128)

            // 5. Populate Input 4: EyeGaze [1, 96, 160] Float32 Grayscale
            populateEyeGazeBuffer(faceCrop)

            val inputs = arrayOf<Any>(
                inputAntiSpoof,
                inputCavaface,
                input3DMM,
                inputAttrib,
                inputEyeGaze,
                inputMesh,
                inputHRNet
            )

            // Selective output mapping — only execute registration-required heads!
            val outputs = mutableMapOf<Int, Any>(
                0 to outAntiSpoof,
                1 to outCavaface,
                2 to out3DMM,
                3 to outAttrib,
                6 to outEyePitchYaw
            )

            rewindAllInputs()
            interp.runForMultipleInputsOutputs(inputs, outputs)

            val elapsedMs = SystemClock.elapsedRealtime() - t0

            // ── Parse Output 2: FaceMap 3DMM ──
            val params265 = out3DMM[0].clone()
            var sumVariance = 0f
            val varCount = minOf(params265.size, 40)
            for (i in 0 until varCount) {
                sumVariance += params265[i] * params265[i]
            }
            val depthVariance = sumVariance / varCount
            val is3D = depthVariance > 0.0015f
            val map3dResult = FaceMap3DMMResult(
                parameters265 = params265,
                depthVariance = depthVariance,
                isTrue3DSurface = is3D,
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 0: Anti-Spoof (MiniFASNetV2) ──
            // silentface.tflite outputs 3 probabilities: [Photo Spoof, Screen Spoof, Real Live Face]
            val rawPad = outAntiSpoof[0]
            val p0 = rawPad.getOrElse(0) { 0f }
            val p1 = rawPad.getOrElse(1) { 0f }
            val p2 = rawPad.getOrElse(2) { 0f }
            val sumP = p0 + p1 + p2
            val (spoofPhoto, spoofScreen, liveProb) = if (sumP in 0.90f..1.10f) {
                Triple(p0.coerceIn(0f, 1f), p1.coerceIn(0f, 1f), p2.coerceIn(0f, 1f))
            } else {
                val maxL = maxOf(p0, maxOf(p1, p2))
                val e0 = exp((p0 - maxL).toDouble()).toFloat()
                val e1 = exp((p1 - maxL).toDouble()).toFloat()
                val e2 = exp((p2 - maxL).toDouble()).toFloat()
                val s = (e0 + e1 + e2).coerceAtLeast(1e-9f)
                Triple(e0 / s, e1 / s, e2 / s)
            }
            val spoofScore = (spoofPhoto + spoofScreen).coerceIn(0f, 1f)
            val liveScore = liveProb.coerceIn(0f, 1f)

            // Fused anti-spoofing decision:
            // Presentation attack is confirmed if:
            // 1. Definite neural spoof detection (spoofScore >= 0.70f)
            // 2. OR 2D flat planar surface (depthVariance < 0.0015f AND spoofScore >= 0.45f)
            val isConfirmedSpoof = (spoofScore >= 0.70f) || (!is3D && spoofScore >= 0.45f)
            val isLive = !isConfirmedSpoof

            Log.i(TAG, "🔍 Registration PAD: liveScore=${"%.3f".format(liveScore)}, spoofScore=${"%.3f".format(spoofScore)}, 3DMM_variance=${"%.4f".format(depthVariance)}, isLive=$isLive, raw=[${rawPad.joinToString()}]")

            val padResult = PassivePadResult(
                isLive = isLive,
                livenessScore = liveScore,
                spoofProbability = spoofScore,
                attackTypeDescription = if (isLive) "Real Person" else if (!is3D) "2D Flat Photo Attack" else "Screen Replay Spoof Detected",
                latencyMs = elapsedMs
            )

            // ── Parse Output 1: CavaFace ArcFace-512 Embedding ──
            val rawCava = outCavaface[0]
            var cavaNormSum = 0f
            for (v in rawCava) cavaNormSum += v * v
            val cavaNorm = sqrt(cavaNormSum).coerceAtLeast(1e-12f)
            val normalizedCavaEmb = FloatArray(512) { i -> rawCava[i] / cavaNorm }

            // ── Parse Output 3: Qualcomm FaceAttribNet ──
            val rawAttr = outAttrib[0]
            val leftEyeOpen = rawAttr.getOrElse(0) { 1f }.coerceIn(0f, 1f)
            val rightEyeOpen = rawAttr.getOrElse(1) { 1f }.coerceIn(0f, 1f)
            val eyeglasses = rawAttr.getOrElse(2) { 0f }.coerceIn(0f, 1f)
            val mask = rawAttr.getOrElse(3) { 0f }.coerceIn(0f, 1f)
            val sunglasses = rawAttr.getOrElse(4) { 0f }.coerceIn(0f, 1f)
            Log.i(TAG, "🔍 Registration Attrib: leftEye=${"%.2f".format(leftEyeOpen)}, rightEye=${"%.2f".format(rightEyeOpen)}, glasses=${"%.2f".format(eyeglasses)}, mask=${"%.2f".format(mask)}, sunglasses=${"%.2f".format(sunglasses)}")
            val attrResult = FaceAttributesResult(
                leftEyeOpenScore = leftEyeOpen,
                rightEyeOpenScore = rightEyeOpen,
                eyeglassesScore = eyeglasses,
                maskScore = mask,
                sunglassesScore = sunglasses,
                rawProbabilities = rawAttr.clone(),
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 6: EyeGaze Pitch / Yaw ──
            val pitch = outEyePitchYaw[0][0]
            val yaw = outEyePitchYaw[0][1]
            val gazeNorm = sqrt(pitch * pitch + yaw * yaw)
            val isAttentive = gazeNorm < 0.45f
            val gazeResult = EyeGazeResult(
                pitch = pitch,
                yaw = yaw,
                gazeVectorNorm = gazeNorm,
                eyeLandmarks34x2 = Array(34) { FloatArray(2) },
                isGazeAttentive = isAttentive,
                executionTimeMs = elapsedMs.toFloat()
            )

            return UnifiedRegistrationResult(
                embedding512 = normalizedCavaEmb,
                passivePad = padResult,
                map3d = map3dResult,
                attributes = attrResult,
                gaze = gazeResult,
                totalInferenceMs = elapsedMs
            )
        }
    }

    /**
     * Executes optimized selective inference for real-time attendance scanning.
     * Binds only scanner-needed heads (PAD, CavaFace, 3DMM, Attrib, EyeGaze, MediaPipe Mesh),
     * completely omitting heavy HRNet heatmaps (475 KB) and Eye heatmaps (130 KB)
     * to eliminate 605 KB of JNI memory transfers and 118,784 heatmap argmax iterations per frame.
     */
    fun processScannerFace(
        faceCrop: Bitmap,
        headYaw: Float = 0f,
        headPitch: Float = 0f,
        leftEyeOpenProb: Float? = null,
        rightEyeOpenProb: Float? = null,
        alignedFace: Bitmap? = null
    ): UnifiedFaceInferenceResult? {
        val interp = interpreter ?: return null
        if (!isModelLoaded || faceCrop.isRecycled) return null

        val t0 = SystemClock.elapsedRealtime()

        synchronized(bufferLock) {
            // 1. Populate Input 0: Anti-Spoof (MiniFASNetV2) [1, 3, 80, 80] Float32 NCHW (BGR)
            populateAntiSpoofBuffer(faceCrop)

            // 2. Populate Input 1: Qualcomm CavaFace [1, 112, 112, 3] Float32 NHWC [0.0, 1.0]
            val cavaFaceBitmap = if (alignedFace != null && !alignedFace.isRecycled) alignedFace else faceCrop
            populateRgbNormalizedBuffer(cavaFaceBitmap, inputCavaface, 112, 112)

            // 3. Populate Input 2: FaceMap 3DMM [1, 128, 128, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, input3DMM, 128, 128)

            // 4. Populate Input 3: FaceAttribNet [1, 128, 128, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, inputAttrib, 128, 128)

            // 5. Populate Input 4: EyeGaze [1, 96, 160] Float32 Grayscale
            populateEyeGazeBuffer(faceCrop)

            // 6. Populate Input 5: MediaPipe Mesh [1, 192, 192, 3] Float32 NHWC
            populateRgbNormalizedBuffer(faceCrop, inputMesh, 192, 192)

            val inputs = arrayOf<Any>(
                inputAntiSpoof,
                inputCavaface,
                input3DMM,
                inputAttrib,
                inputEyeGaze,
                inputMesh,
                inputHRNet
            )

            // Selective output mapping for Scanner — omits outputs 4 and 9!
            val outputs = mutableMapOf<Int, Any>(
                0 to outAntiSpoof,
                1 to outCavaface,
                2 to out3DMM,
                3 to outAttrib,
                6 to outEyePitchYaw,
                7 to outMeshScores,
                8 to outMeshLandmarks
            )

            rewindAllInputs()
            interp.runForMultipleInputsOutputs(inputs, outputs)

            val elapsedMs = SystemClock.elapsedRealtime() - t0

            // ── Parse Output 2: FaceMap 3DMM ──
            val params265 = out3DMM[0].clone()
            var sumVariance = 0f
            val varCount = minOf(params265.size, 40)
            for (i in 0 until varCount) {
                sumVariance += params265[i] * params265[i]
            }
            val depthVariance = sumVariance / varCount
            val is3D = depthVariance > 0.0015f
            val map3dResult = FaceMap3DMMResult(
                parameters265 = params265,
                depthVariance = depthVariance,
                isTrue3DSurface = is3D,
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 0: Anti-Spoof (MiniFASNetV2) ──
            val rawPad = outAntiSpoof[0]
            val p0 = rawPad.getOrElse(0) { 0f }
            val p1 = rawPad.getOrElse(1) { 0f }
            val p2 = rawPad.getOrElse(2) { 0f }
            val sumP = p0 + p1 + p2
            val (spoofPhoto, spoofScreen, liveProb) = if (sumP in 0.90f..1.10f) {
                Triple(p0.coerceIn(0f, 1f), p1.coerceIn(0f, 1f), p2.coerceIn(0f, 1f))
            } else {
                val maxL = maxOf(p0, maxOf(p1, p2))
                val e0 = exp((p0 - maxL).toDouble()).toFloat()
                val e1 = exp((p1 - maxL).toDouble()).toFloat()
                val e2 = exp((p2 - maxL).toDouble()).toFloat()
                val s = (e0 + e1 + e2).coerceAtLeast(1e-9f)
                Triple(e0 / s, e1 / s, e2 / s)
            }
            val spoofScore = (spoofPhoto + spoofScreen).coerceIn(0f, 1f)
            val liveScore = liveProb.coerceIn(0f, 1f)

            val isConfirmedSpoof = (spoofScore >= 0.70f) || (!is3D && spoofScore >= 0.45f)
            val isLive = !isConfirmedSpoof

            val padResult = PassivePadResult(
                isLive = isLive,
                livenessScore = liveScore,
                spoofProbability = spoofScore,
                attackTypeDescription = if (isLive) "Real Person" else if (!is3D) "2D Flat Photo Attack" else "Screen Replay Spoof Detected",
                latencyMs = elapsedMs
            )

            // ── Parse Output 1: CavaFace ArcFace-512 Embedding ──
            val rawCava = outCavaface[0]
            var cavaNormSum = 0f
            for (v in rawCava) cavaNormSum += v * v
            val cavaNorm = sqrt(cavaNormSum).coerceAtLeast(1e-12f)
            val normalizedCavaEmb = FloatArray(512) { i -> rawCava[i] / cavaNorm }

            // ── Parse Output 3: Qualcomm FaceAttribNet ──
            val rawAttr = outAttrib[0]
            val leftEyeOpen = rawAttr.getOrElse(0) { 1f }.coerceIn(0f, 1f)
            val rightEyeOpen = rawAttr.getOrElse(1) { 1f }.coerceIn(0f, 1f)
            val eyeglasses = rawAttr.getOrElse(2) { 0f }.coerceIn(0f, 1f)
            val mask = rawAttr.getOrElse(3) { 0f }.coerceIn(0f, 1f)
            val sunglasses = rawAttr.getOrElse(4) { 0f }.coerceIn(0f, 1f)
            val attrResult = FaceAttributesResult(
                leftEyeOpenScore = leftEyeOpen,
                rightEyeOpenScore = rightEyeOpen,
                eyeglassesScore = eyeglasses,
                maskScore = mask,
                sunglassesScore = sunglasses,
                rawProbabilities = rawAttr.clone(),
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 6: EyeGaze Pitch / Yaw ──
            val pitch = outEyePitchYaw[0][0]
            val yaw = outEyePitchYaw[0][1]
            val gazeNorm = sqrt(pitch * pitch + yaw * yaw)
            val totalYaw = yaw + (headYaw * 0.0174533f)
            val isAttentive = gazeNorm < 0.45f && Math.abs(totalYaw) < 0.50f
            val gazeResult = EyeGazeResult(
                pitch = pitch,
                yaw = yaw,
                gazeVectorNorm = gazeNorm,
                eyeLandmarks34x2 = Array(34) { FloatArray(2) },
                isGazeAttentive = isAttentive,
                executionTimeMs = elapsedMs.toFloat()
            )

            // ── Parse Output 7 & 8: MediaPipe Mesh ──
            val meshScore = outMeshScores[0]
            val meshLandmarks = Array(468) { i ->
                floatArrayOf(
                    outMeshLandmarks[0][i][0],
                    outMeshLandmarks[0][i][1],
                    outMeshLandmarks[0][i][2]
                )
            }
            var sumZ = 0f
            for (i in 0 until 468) sumZ += meshLandmarks[i][2]
            val meanZ = sumZ / 468f
            var varZ = 0f
            for (i in 0 until 468) {
                val dz = meshLandmarks[i][2] - meanZ
                varZ += dz * dz
            }
            val meshZVariance = varZ / 468f

            val meshResult = MediaPipeMeshResult(
                landmarks468x3 = meshLandmarks,
                faceScore = meshScore,
                meshDepthVariance = meshZVariance,
                executionTimeMs = elapsedMs.toFloat()
            )

            return UnifiedFaceInferenceResult(
                embedding512 = normalizedCavaEmb,
                cavafaceEmbedding512 = normalizedCavaEmb,
                passivePad = padResult,
                map3d = map3dResult,
                attributes = attrResult,
                gaze = gazeResult,
                mesh = meshResult,
                hrnet = null,
                totalInferenceMs = elapsedMs
            )
        }
    }

    /**
     * Executes single-pass CavaFace ArcFace-512 embedding extraction ONLY.
     * Used for horizontal flip-augmentation during registration Step 1 (Frontal),
     * avoiding re-evaluating any auxiliary anti-spoof or geometry heads twice.
     */
    fun extractCavafaceEmbeddingOnly(alignedFace: Bitmap): FloatArray {
        val interp = interpreter ?: return FloatArray(512)
        if (!isModelLoaded || alignedFace.isRecycled) return FloatArray(512)

        synchronized(bufferLock) {
            populateRgbNormalizedBuffer(alignedFace, inputCavaface, 112, 112)
            val inputs = arrayOf<Any>(
                inputAntiSpoof,
                inputCavaface,
                input3DMM,
                inputAttrib,
                inputEyeGaze,
                inputMesh,
                inputHRNet
            )
            val outputs = mutableMapOf<Int, Any>(
                1 to outCavaface
            )
            rewindAllInputs()
            interp.runForMultipleInputsOutputs(inputs, outputs)

            val rawCava = outCavaface[0]
            var cavaNormSum = 0f
            for (v in rawCava) cavaNormSum += v * v
            val cavaNorm = sqrt(cavaNormSum).coerceAtLeast(1e-12f)
            return FloatArray(512) { i -> rawCava[i] / cavaNorm }
        }
    }

    fun l2Normalize(v: FloatArray): FloatArray {
        var sumSq = 0f
        for (x in v) sumSq += x * x
        val norm = sqrt(sumSq).coerceAtLeast(1e-12f)
        for (i in v.indices) v[i] /= norm
        return v
    }

    // ── Input Population Helpers ──

    private fun rewindAllInputs() {
        inputAntiSpoof.rewind()
        inputCavaface.rewind()
        input3DMM.rewind()
        inputAttrib.rewind()
        inputEyeGaze.rewind()
        inputMesh.rewind()
        inputHRNet.rewind()
    }

    private fun populateAntiSpoofBuffer(bitmap: Bitmap) {
        inputAntiSpoof.rewind()
        val scaled = Bitmap.createScaledBitmap(bitmap, 80, 80, true)
        val pixels = IntArray(80 * 80)
        scaled.getPixels(pixels, 0, 80, 0, 0, 80, 80)
        if (scaled != bitmap && !scaled.isRecycled) scaled.recycle()

        // MiniFASNet expects BGR format in NCHW layout: channel 0 (B), channel 1 (G), channel 2 (R)
        for (c in 0 until 3) {
            for (i in 0 until 6400) {
                val pixel = pixels[i]
                val channelVal = when (c) {
                    0 -> (pixel and 0xFF) / 255.0f              // BLUE
                    1 -> ((pixel shr 8) and 0xFF) / 255.0f      // GREEN
                    else -> ((pixel shr 16) and 0xFF) / 255.0f   // RED
                }
                inputAntiSpoof.putFloat(channelVal)
            }
        }
    }

    private fun populateRgbNormalizedBuffer(bitmap: Bitmap, buf: ByteBuffer, targetW: Int, targetH: Int) {
        buf.rewind()
        val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        val totalPixels = targetW * targetH
        val pixels = IntArray(totalPixels)
        scaled.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)
        if (scaled != bitmap && !scaled.isRecycled) scaled.recycle()

        // Float32 NHWC format [0.0, 1.0]
        for (i in 0 until totalPixels) {
            val p = pixels[i]
            buf.putFloat(((p shr 16) and 0xFF) / 255.0f)
            buf.putFloat(((p shr 8) and 0xFF) / 255.0f)
            buf.putFloat((p and 0xFF) / 255.0f)
        }
    }

    private fun populateEyeGazeBuffer(bitmap: Bitmap) {
        inputEyeGaze.rewind()
        val scaled = Bitmap.createScaledBitmap(bitmap, 160, 96, true)
        val pixels = IntArray(96 * 160)
        scaled.getPixels(pixels, 0, 160, 0, 0, 160, 96)
        if (scaled != bitmap && !scaled.isRecycled) scaled.recycle()

        // Grayscale [0.0, 1.0]
        for (i in 0 until (96 * 160)) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val gray = (0.299f * r + 0.587f * g + 0.114f * b) / 255.0f
            inputEyeGaze.putFloat(gray)
        }
    }

    private fun parseHrnetHeatmaps(
        buf: ByteBuffer,
        outLandmarks: Array<FloatArray>,
        outConfidences: FloatArray
    ) {
        buf.rewind()
        val fb = buf.asFloatBuffer()
        val numKeypoints = 29
        val hmSize = 64 * 64

        for (k in 0 until numKeypoints) {
            var maxVal = -Float.MAX_VALUE
            var maxIdx = 0
            for (idx in 0 until hmSize) {
                val v = fb.get()
                if (v > maxVal) {
                    maxVal = v
                    maxIdx = idx
                }
            }
            val y = (maxIdx / 64) / 64.0f
            val x = (maxIdx % 64) / 64.0f
            outLandmarks[k][0] = x
            outLandmarks[k][1] = y
            outConfidences[k] = maxVal
        }
    }

    // ── Modular Delegating API for Existing Callers ──

    fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        val res = processFace(faceBitmap)
        return if (res != null && res.cavafaceEmbedding512.isNotEmpty() && (res.cavafaceEmbedding512[0] != 0f || res.cavafaceEmbedding512[1] != 0f)) {
            res.cavafaceEmbedding512
        } else {
            res?.embedding512 ?: FloatArray(512)
        }
    }

    fun extractCavafaceEmbedding(faceBitmap: Bitmap): FloatArray {
        val res = processFace(faceBitmap)
        return res?.cavafaceEmbedding512 ?: FloatArray(512)
    }

    fun runPassivePad(faceBitmap: Bitmap): PassivePadResult {
        val res = processFace(faceBitmap)
        return res?.passivePad ?: PassivePadResult(false, 0f, "Inference Failed", 0L)
    }

    fun estimate3dFaceMap(faceBitmap: Bitmap): FaceMap3DMMResult {
        val res = processFace(faceBitmap)
        return res?.map3d ?: FaceMap3DMMResult(FloatArray(265), 0f, false, 0f)
    }

    fun detectFaceAttributes(faceBitmap: Bitmap): FaceAttributesResult {
        val res = processFace(faceBitmap)
        return res?.attributes ?: FaceAttributesResult(0f, 0f, 0f, FloatArray(5), 0f)
    }

    fun estimateEyeGaze(
        eyeCropBitmap: Bitmap,
        headYaw: Float = 0f,
        headPitch: Float = 0f,
        leftEyeOpenProb: Float? = null,
        rightEyeOpenProb: Float? = null
    ): EyeGazeResult {
        val res = processFace(eyeCropBitmap, headYaw, headPitch, leftEyeOpenProb, rightEyeOpenProb)
        return res?.gaze ?: EyeGazeResult(0f, 0f, 0f, Array(34) { FloatArray(2) }, false, 0f)
    }

    fun estimateMediaPipeFaceMesh(faceBitmap: Bitmap): MediaPipeMeshResult {
        val res = processFace(faceBitmap)
        return res?.mesh ?: MediaPipeMeshResult(Array(468) { FloatArray(3) }, 0f, 0f, 0f)
    }

    fun estimateHrnetLandmarks(faceBitmap: Bitmap): HRNetFaceResult {
        val res = processFace(faceBitmap)
        return res?.hrnet ?: HRNetFaceResult(Array(29) { FloatArray(2) }, FloatArray(29), 0f)
    }

    fun benchmarkInferenceLatency(): Long {
        if (!isModelLoaded || interpreter == null) return 0L
        return try {
            val dummyBitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
            // Warm-up pass to trigger delegate kernel/shader compilation
            processFace(dummyBitmap)
            val startTime = System.nanoTime()
            processFace(dummyBitmap)
            dummyBitmap.recycle()
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000L
            elapsedMs.coerceAtLeast(1L)
        } catch (e: Throwable) {
            Log.w(TAG, "Unified benchmark error: ${e.message}")
            0L
        }
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
        gpuDelegate?.close()
        gpuDelegate = null
        nnApiDelegate?.close()
        nnApiDelegate = null
        isModelLoaded = false
    }
}
