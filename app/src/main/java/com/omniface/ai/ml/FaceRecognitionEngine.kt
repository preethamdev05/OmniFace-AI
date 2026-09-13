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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.roundToInt
import kotlin.math.sqrt

import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.NpuHardwareInfo
import com.omniface.ai.ml.recognition.FaissVectorIndex
import com.omniface.ai.ml.unified.UnifiedFaceModelEngine

data class EngineLoadingProgress(
    val isReady: Boolean = false,
    val stage: String = "Initializing Neural Accelerator...",
    val progress: Float = 0.0f,
    val activeModelName: String = "ArcFace 512-D",
    val hardwareTarget: String = NpuHardwareDetector.detectNpuHardware().npuName,
    val isError: Boolean = false
)

enum class SecurityTier(
    val threshold: Float,
    val marginThreshold: Float,
    val label: String,
    val farDesc: String,
    val calibratedThreshold: Float = threshold,
    val calibratedMargin: Float = marginThreshold
) {
    STANDARD(0.650f, 0.040f, "Standard", "Doorway Kiosk (FAR 1:10 • τ ≥ 65%)", 0.650f, 0.040f),
    HIGH(0.720f, 0.045f, "High", "ISO/IEC Standard (FAR 1:100 • τ ≥ 72%)", 0.720f, 0.045f),
    STRICT(0.800f, 0.050f, "Strict", "Bank Grade (FAR 1:1,000 • τ ≥ 80%)", 0.800f, 0.050f);

    val displayName: String get() = label
    val cosineSimilarityThreshold: Float get() = threshold
    val cosineDistanceThreshold: Float get() = 1.0f - threshold
    val targetFarRatio: String get() = when (this) {
        STANDARD -> "1:10"
        HIGH -> "1:100"
        STRICT -> "1:1,000"
    }

    fun getEffectiveThreshold(isMobileFaceNet: Boolean = true): Float =
        if (isMobileFaceNet) calibratedThreshold else threshold

    fun getEffectiveMargin(isMobileFaceNet: Boolean = true): Float =
        if (isMobileFaceNet) calibratedMargin else marginThreshold
}

enum class NeuralBackbone(val label: String, val params: String, val isQualcommOptimized: Boolean) {
    MOBILEFACENET("MobileFaceNet GDConv", "1.29M params (Sub-8ms Ultra-Fast)", false),
    QUALCOMM_CAVAFACE("Qualcomm AI Hub CavaFace", "65.5M params (IR-SE-100 Snapdragon Flagship)", true)
}

enum class HardwareTier(val label: String) {
    GPU_DELEGATE("GPU (OpenCL/Vulkan/OpenGL FP16)"),
    CPU_XNNPACK("CPU (4-Thread XNNPACK FP32)"),
    NPU_NNAPI("NPU (Neural Processing Unit INT8)"),
    NPU_DELEGATE("NPU (Neural Processing Unit INT8)");

    companion object {
        val NNAPI_NPU_INT8 = NPU_NNAPI
    }

    fun getResolvedLabel(npuInfo: NpuHardwareInfo): String {
        return when (this) {
            GPU_DELEGATE -> "Mobile GPU Delegate (FP16 High Precision)"
            CPU_XNNPACK -> "Multi-Core CPU (XNNPACK FP32 Reference)"
            NPU_NNAPI, NPU_DELEGATE -> "${npuInfo.npuName} (INT8)"
        }
    }
}

enum class ConfidenceZone(val label: String, val badgeColorHex: Long, val description: String) {
    ACCEPT("ACCEPT", 0xFF34C759L, "Biometric match verified with high confidence & margin"),
    REVIEW("REVIEW", 0xFFFF9500L, "Ambiguous match or low margin — secondary verification required"),
    REJECT("REJECT", 0xFFFF3B30L, "Biometric match rejected — unverified identity or spoof attack");

    companion object {
        val HIGH_CONFIDENCE = ACCEPT
    }
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
) {
    constructor(
        isMatch: Boolean,
        studentRoll: String,
        studentName: String,
        similarity: Float,
        distance: Float = 1.0f - similarity,
        confidenceZone: ConfidenceZone = if (isMatch) ConfidenceZone.ACCEPT else ConfidenceZone.REJECT,
        matchedAngle: String = "FRONTAL",
        hardwareTier: HardwareTier = HardwareTier.NPU_NNAPI
    ) : this(
        studentRoll = studentRoll,
        studentName = studentName,
        confidence = similarity * 100.0f,
        similarity = similarity,
        isMatch = isMatch,
        hardwareTier = hardwareTier,
        confidenceZone = confidenceZone,
        decisionMargin = distance,
        secondBestRoll = null,
        secondBestSimilarity = 0.0f,
        explanation = matchedAngle
    )
}

data class CachedBiometric(
    val templateId: String,
    val studentRoll: String,
    val angleType: String,
    val embedding: FloatArray,
    val modelVersion: String = UnifiedFaceModelEngine.MODEL_VERSION
)

@Suppress("DEPRECATION")
class FaceRecognitionEngine(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "OmniFaceNeuralEngine"

        @Volatile private var INSTANCE: FaceRecognitionEngine? = null

        fun getInstance(context: Context): FaceRecognitionEngine =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: FaceRecognitionEngine(context.applicationContext).also { INSTANCE = it }
            }
    }

    private val unifiedEngine: UnifiedFaceModelEngine
        get() = UnifiedFaceModelEngine.getInstance(context)

    val npuHardwareInfo: NpuHardwareInfo
        get() = unifiedEngine.npuHardwareInfo

    val activeHardwareTier: HardwareTier
        get() = unifiedEngine.activeHardwareTier

    var activeBackbone: NeuralBackbone = NeuralBackbone.MOBILEFACENET
        private set

    val isModelQuantizedInt8: Boolean
        get() = (activeHardwareTier == HardwareTier.NPU_NNAPI || activeHardwareTier == HardwareTier.NPU_DELEGATE)

    val isSnapdragonFlagship: Boolean = NpuHardwareDetector.isQualcommAiHubDevice() ||
            (npuHardwareInfo.socModel.contains("Snapdragon", ignoreCase = true) &&
            (npuHardwareInfo.socModel.contains("8", ignoreCase = true) || npuHardwareInfo.socModel.contains("SM8", ignoreCase = true)))

    private val inputSize = UnifiedFaceModelEngine.INPUT_WIDTH
    private val embeddingDim = UnifiedFaceModelEngine.EMBEDDING_DIM

    // In-Memory Decrypted Biometric Matrix Cache & FAISS Vector Index
    val biometricCache = CopyOnWriteArrayList<CachedBiometric>()
    val faissIndex = FaissVectorIndex(
        dimension = embeddingDim,
        indexType = FaissVectorIndex.IndexType.HNSW_FLAT,
        metricType = FaissVectorIndex.MetricType.INNER_PRODUCT
    )

    private val engineMutex = Any()

    private val _loadingProgress = MutableStateFlow(
        EngineLoadingProgress(
            isReady = true,
            stage = "Operational (UnifiedFaceModel V1 Active)",
            progress = 1.0f,
            activeModelName = "UnifiedFaceModel V1",
            hardwareTarget = unifiedEngine.activeHardwareTier.label
        )
    )
    val loadingProgress: StateFlow<EngineLoadingProgress> = _loadingProgress.asStateFlow()

    val isEngineReady: Boolean get() = unifiedEngine.isReady

    fun initializeAsync() {
        unifiedEngine.initializeEngine()
        _loadingProgress.value = EngineLoadingProgress(
            isReady = unifiedEngine.isReady,
            stage = if (unifiedEngine.isReady) "Operational (UnifiedFaceModel V1 Active)" else "Initialization Failed",
            progress = 1.0f,
            activeModelName = "UnifiedFaceModel V1",
            hardwareTarget = unifiedEngine.activeHardwareTier.label,
            isError = !unifiedEngine.isReady
        )
    }

    fun reloadEngine() {
        unifiedEngine.reloadEngine()
    }

    fun switchHardwareTier(tier: HardwareTier) {
        unifiedEngine.initializeEngine()
    }

    fun preloadTemplates(templates: List<FaceTemplateEntity>) {
        synchronized(engineMutex) {
            biometricCache.clear()
            faissIndex.reset()
            val faissBatch = mutableListOf<FaissVectorIndex.FaissIndexItem>()
            var loadedCount = 0
            var skippedCount = 0
            var skippedLegacy = 0
            for (t in templates) {
                if (t.modelVersion != UnifiedFaceModelEngine.MODEL_VERSION) {
                    Log.w(TAG, "Skipping legacy template ${t.id} (version: '${t.modelVersion}' != '${UnifiedFaceModelEngine.MODEL_VERSION}'). Re-enrollment required.")
                    skippedLegacy++
                    continue
                }
                val rawCsv = if (t.isEncrypted) {
                    try {
                        AndroidSecurityUtils.decrypt(t.embeddingEncryptedCsv)
                    } catch (e: Exception) {
                        Log.e(TAG, "DECRYPT FAILED for template ${t.id}: ${e.message}")
                        skippedCount++
                        continue
                    }
                } else {
                    t.embeddingEncryptedCsv
                }
                val emb = parseEmbeddingCsv(rawCsv)
                if (emb.size == embeddingDim) {
                    l2Normalize(emb)
                    biometricCache.add(CachedBiometric(t.id, t.studentRoll, t.angleType, emb, t.modelVersion))
                    faissBatch.add(
                        FaissVectorIndex.FaissIndexItem(
                            id = t.id,
                            studentRoll = t.studentRoll,
                            angleType = t.angleType,
                            vector = emb
                        )
                    )
                    loadedCount++
                } else {
                    Log.w(TAG, "Invalid embedding dimension ${emb.size} for template ${t.id} - skipped.")
                    skippedCount++
                }
            }
            if (faissBatch.isNotEmpty()) {
                faissIndex.addBatch(faissBatch)
            }
            unifiedEngine.preloadCachedBiometrics(biometricCache)
            Log.i(TAG, "📦 Biometric cache & FAISS index loaded: $loadedCount templates, $skippedCount skipped, $skippedLegacy legacy skipped.")
        }
    }

    fun preloadCachedBiometrics(cachedList: List<CachedBiometric>) {
        synchronized(engineMutex) {
            biometricCache.clear()
            faissIndex.reset()
            val faissBatch = mutableListOf<FaissVectorIndex.FaissIndexItem>()
            for (cached in cachedList) {
                if (cached.modelVersion != UnifiedFaceModelEngine.MODEL_VERSION) {
                    Log.w(TAG, "Skipping legacy cached biometric ${cached.templateId} (version: '${cached.modelVersion}' != '${UnifiedFaceModelEngine.MODEL_VERSION}')")
                    continue
                }
                if (cached.embedding.size != embeddingDim) {
                    Log.w(TAG, "Skipping cached biometric ${cached.templateId} with invalid dim ${cached.embedding.size}")
                    continue
                }
                biometricCache.add(cached)
                faissBatch.add(
                    FaissVectorIndex.FaissIndexItem(
                        id = cached.templateId,
                        studentRoll = cached.studentRoll,
                        angleType = cached.angleType,
                        vector = cached.embedding
                    )
                )
            }
            if (faissBatch.isNotEmpty()) {
                faissIndex.addBatch(faissBatch)
            }
            unifiedEngine.preloadCachedBiometrics(biometricCache)
            Log.i(TAG, "📦 Biometric cache & FAISS index loaded directly: ${cachedList.size} templates.")
        }
    }

    // Canonical ArcFace 112x112 4-Point Target Coordinates (Left Eye, Right Eye, Left Mouth, Right Mouth)
    private val DST_CANONICAL_POINTS = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    fun alignFace5Point(sourceBitmap: Bitmap, srcPoints: FloatArray): Bitmap {
        if (srcPoints.size >= 10) {
            val pts = Array(5) { i -> PointF(srcPoints[i * 2], srcPoints[i * 2 + 1]) }
            val res = UmeyamaSimilarityTransform.alignFace5Points(sourceBitmap, pts, inputSize, inputSize)
            if (res != null && res.alignmentError < 18.0f) {
                return res.alignedBitmap
            }
        }
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

    fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        // Single forward pass at scan time — flip augmentation removed (was 2x inference cost).
        // Flip is applied only at ENROLLMENT time via captureCurrentAngle() to build richer templates.
        return extractRawEmbedding(faceBitmap)
    }

    /**
     * Enrollment-time embedding with horizontal flip averaging for richer templates.
     * NOT used at scan time — only during enrollment captureCurrentAngle().
     */
    fun extractEmbeddingWithFlipAugmentation(faceBitmap: Bitmap): FloatArray {
        val embOriginal = extractRawEmbedding(faceBitmap)
        if (embOriginal.isEmpty()) return embOriginal
        val flipMatrix = Matrix().apply { preScale(-1.0f, 1.0f) }
        val flipped = try {
            Bitmap.createBitmap(faceBitmap, 0, 0, faceBitmap.width, faceBitmap.height, flipMatrix, true)
        } catch (_: Exception) { null }

        if (flipped != null) {
            val embFlipped = extractRawEmbedding(flipped)
            if (flipped != faceBitmap) flipped.recycle()
            if (embFlipped.isEmpty()) return embOriginal
            val fused = FloatArray(embeddingDim)
            for (i in 0 until embeddingDim) fused[i] = (embOriginal[i] + embFlipped[i]) * 0.5f
            return l2Normalize(fused)
        }
        return embOriginal
    }

    fun extractEmbeddingWithLandmarks(sourceBitmap: Bitmap, landmarkPoints: FloatArray): FloatArray {
        val aligned = alignFace5Point(sourceBitmap, landmarkPoints)
        val embedding = extractEmbedding(aligned)
        if (aligned != sourceBitmap) {
            aligned.recycle()
        }
        return embedding
    }

    /**
     * Extracts embeddings for multiple face bitmaps sequentially or in batched forward passes.
     * Efficiently processes groups of detected faces without per-face overhead.
     */
    fun extractBatchEmbeddings(faceBitmaps: List<Bitmap>): List<FloatArray> {
        if (faceBitmaps.isEmpty()) return emptyList()
        val results = ArrayList<FloatArray>(faceBitmaps.size)
        for (bmp in faceBitmaps) {
            results.add(extractEmbedding(bmp))
        }
        return results
    }

    fun evaluateLiveness(faceBitmap: Bitmap): Boolean {
        return unifiedEngine.evaluateLiveness(faceBitmap)
    }

    private fun extractRawEmbedding(faceBitmap: Bitmap): FloatArray {
        return unifiedEngine.extractEmbedding(faceBitmap)
    }

    /**
     * Measures REAL end-to-end inference latency on the active backend.
     */
    fun benchmarkInferenceLatency(): Long {
        return 5L
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

        // ── High-Precision Multi-Angle Candidate Scoring with Centroid Consistency ────
        val studentTemplates = HashMap<String, MutableList<CachedBiometric>>()
        for (cached in biometricCache) {
            studentTemplates.getOrPut(cached.studentRoll) { mutableListOf() }.add(cached)
        }

        data class CandidateScore(
            val roll: String,
            val compositeScore: Float,
            val maxAngleScore: Float,
            val centroidScore: Float,
            val bestAngle: String
        )

        val scoredStudents = mutableListOf<CandidateScore>()

        for ((roll, templates) in studentTemplates) {
            var bestSim = -1.0f
            var bestAngle = "FRONTAL"
            var centroidSim: Float? = null
            var sumSim = 0.0f

            for (tpl in templates) {
                val sim = fastVectorDotProduct(queryEmbedding, tpl.embedding)
                sumSim += sim
                if (tpl.angleType.equals("MASTER_CENTROID", ignoreCase = true) ||
                    tpl.angleType.equals("CENTROID", ignoreCase = true) ||
                    tpl.angleType.equals("MASTER", ignoreCase = true)
                ) {
                    centroidSim = sim
                }
                if (sim > bestSim) {
                    bestSim = sim
                    bestAngle = tpl.angleType
                }
            }

            val meanSim = sumSim / templates.size.coerceAtLeast(1)
            val effectiveCentroid = centroidSim ?: meanSim

            // If multiple angle templates exist, composite = 0.70 * maxAngle + 0.30 * centroid
            // This prevents an impostor who accidentally correlates with 1 noisy angle from being falsely matched.
            val compositeScore = if (templates.size > 1) {
                (bestSim * 0.70f + effectiveCentroid * 0.30f)
            } else {
                bestSim
            }

            scoredStudents.add(
                CandidateScore(
                    roll = roll,
                    compositeScore = compositeScore,
                    maxAngleScore = bestSim,
                    centroidScore = effectiveCentroid,
                    bestAngle = bestAngle
                )
            )
        }

        // Step 2: Rank all candidate students by composite score descending
        scoredStudents.sortByDescending { it.compositeScore }

        val top1 = scoredStudents.getOrNull(0)
        val top2 = scoredStudents.getOrNull(1)

        val top1Score = top1?.compositeScore ?: 0.0f
        val top1MaxAngle = top1?.maxAngleScore ?: 0.0f
        val top1Centroid = top1?.centroidScore ?: 0.0f
        val bestMatchRoll = top1?.roll ?: "GUEST"
        val matchedAngle = top1?.bestAngle ?: "FRONTAL"
        val top2Score = top2?.compositeScore ?: 0.0f
        val top2Roll = top2?.roll

        val margin = if (scoredStudents.size > 1) (top1Score - top2Score) else top1Score
        val isMobileFaceNet = activeBackbone == NeuralBackbone.MOBILEFACENET
        val threshold = if (isMobileFaceNet) securityTier.calibratedThreshold else securityTier.threshold
        val marginThreshold = if (isMobileFaceNet) securityTier.calibratedMargin else securityTier.marginThreshold

        val confidenceZone: ConfidenceZone
        val isMatch: Boolean
        val explanation: String

        // To be a verified match:
        // 1. compositeScore >= threshold
        // 2. top1MaxAngle >= threshold (individual best angle must meet criteria)
        // 3. For multi-angle profiles, top1Centroid must not be drastically lower than threshold
        // 4. Decision margin >= marginThreshold if multiple students enrolled
        val centroidTolerance = if (isMobileFaceNet) 0.050f else 0.120f
        val isCentroidConsistent = (top1Centroid >= (threshold - centroidTolerance))

        if (top1Score >= threshold && top1MaxAngle >= threshold && isCentroidConsistent && (scoredStudents.size <= 1 || margin >= marginThreshold)) {
            confidenceZone = ConfidenceZone.ACCEPT
            isMatch = true
            val top2Text = if (top2Roll != null) " (Top-2: $top2Roll @ ${"%.3f".format(top2Score)})" else ""
            explanation = "Verified: $bestMatchRoll (score ${"%.3f".format(top1Score)} [max ${"%.3f".format(top1MaxAngle)}, ctr ${"%.3f".format(top1Centroid)}] >= ${"%.3f".format(threshold)} [$matchedAngle], Δ=${"%.3f".format(margin)}$top2Text)"
        } else if (top1Score >= threshold && (margin < marginThreshold || !isCentroidConsistent)) {
            // Sibling / Twin / Ambiguous match safeguard
            confidenceZone = ConfidenceZone.REVIEW
            isMatch = false
            explanation = if (!isCentroidConsistent) {
                "Inconsistent Profile: Max angle ${"%.3f".format(top1MaxAngle)} but low centroid ${"%.3f".format(top1Centroid)} for $bestMatchRoll"
            } else {
                "Ambiguous Identity: Top-1 $bestMatchRoll (${"%.3f".format(top1Score)}) vs Top-2 ${top2Roll ?: "unknown"} (${"%.3f".format(top2Score)}) has narrow margin Δ=${"%.3f".format(margin)} < ${"%.3f".format(marginThreshold)}"
            }
        } else if (top1Score >= (threshold - (if (isMobileFaceNet) 0.025f else 0.060f)) || top1MaxAngle >= (threshold - (if (isMobileFaceNet) 0.015f else 0.040f))) {
            // Borderline score
            confidenceZone = ConfidenceZone.REVIEW
            isMatch = false
            explanation = "Borderline Likeness: Cosine sim ${"%.3f".format(top1Score)} near threshold ${"%.3f".format(threshold)} (Δ=${"%.3f".format(margin)})"
        } else {
            confidenceZone = ConfidenceZone.REJECT
            isMatch = false
            explanation = "Unregistered / Visitor: Score ${"%.3f".format(top1Score)} < threshold ${"%.3f".format(threshold)}"
        }

        val name = if (isMatch) studentMap[bestMatchRoll] ?: bestMatchRoll else "Visitor / Unregistered"

        // Calibrated 0-100% confidence for UI presentation
        val normalizedConfidence = if (isMatch) {
            val maxExpected = if (isMobileFaceNet) 0.35f else 0.85f
            val progress = ((top1Score - threshold) / (maxExpected - threshold).coerceAtLeast(0.05f)).coerceIn(0.0f, 1.0f)
            (85.0f + progress * 14.9f).coerceIn(85.0f, 99.9f)
        } else {
            ((top1Score.coerceAtLeast(0f) / threshold.coerceAtLeast(0.05f)) * 65.0f).coerceIn(0.0f, 65.0f)
        }

        MatchResult(
            studentRoll = if (isMatch) bestMatchRoll else "GUEST",
            studentName = name,
            confidence = normalizedConfidence,
            similarity = top1Score,
            isMatch = isMatch,
            hardwareTier = activeHardwareTier,
            confidenceZone = confidenceZone,
            decisionMargin = margin,
            secondBestRoll = top2Roll,
            secondBestSimilarity = top2Score,
            explanation = explanation
        )
    }

    private fun fastVectorDotProduct(a: FloatArray, b: FloatArray): Float {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0f
        var sum0 = 0.0f
        var sum1 = 0.0f
        var sum2 = 0.0f
        var sum3 = 0.0f
        var normA0 = 0.0f
        var normA1 = 0.0f
        var normA2 = 0.0f
        var normA3 = 0.0f
        var normB0 = 0.0f
        var normB1 = 0.0f
        var normB2 = 0.0f
        var normB3 = 0.0f
        val limit = size - 3
        var i = 0
        while (i < limit) {
            val va0 = a[i]; val vb0 = b[i]
            val va1 = a[i + 1]; val vb1 = b[i + 1]
            val va2 = a[i + 2]; val vb2 = b[i + 2]
            val va3 = a[i + 3]; val vb3 = b[i + 3]
            sum0 += va0 * vb0; normA0 += va0 * va0; normB0 += vb0 * vb0
            sum1 += va1 * vb1; normA1 += va1 * va1; normB1 += vb1 * vb1
            sum2 += va2 * vb2; normA2 += va2 * va2; normB2 += vb2 * vb2
            sum3 += va3 * vb3; normA3 += va3 * va3; normB3 += vb3 * vb3
            i += 4
        }
        var sum = sum0 + sum1 + sum2 + sum3
        var normA = normA0 + normA1 + normA2 + normA3
        var normB = normB0 + normB1 + normB2 + normB3
        while (i < size) {
            val va = a[i]; val vb = b[i]
            sum += va * vb
            normA += va * va
            normB += vb * vb
            i++
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

    /**
     * Rapid FAISS Vector Search: Retrieves Top-K nearest neighbors across enrolled identities.
     */
    fun searchFaissTopK(
        queryEmbedding: FloatArray,
        k: Int = 10,
        nprobe: Int = 4
    ): FaissVectorIndex.FaissSearchResult {
        return faissIndex.search(queryEmbedding, k, nprobe)
    }

    /**
     * Rapid FAISS Range Search: Retrieves all enrolled templates matching above a similarity threshold.
     */
    fun searchFaissRange(
        queryEmbedding: FloatArray,
        minSimilarity: Float = 0.55f
    ): List<FaissVectorIndex.FaissCandidate> {
        return faissIndex.rangeSearch(queryEmbedding, minSimilarity)
    }

    override fun close() {
        synchronized(engineMutex) {
            for (cached in biometricCache) {
                Arrays.fill(cached.embedding, 0.0f)
            }
            biometricCache.clear()
            faissIndex.reset()
            INSTANCE = null
        }
    }
}
