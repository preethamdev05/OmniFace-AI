package com.omniface.ai.ml.unified

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.omniface.ai.ml.HardwareTier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.sqrt

/**
 * Real-time shadow comparison telemetry between primary production model and UnifiedFaceModel V1.
 */
data class ShadowComparisonTelemetry(
    val isShadowActive: Boolean = false,
    val primaryModelName: String = "MobileFaceNet ArcFace",
    val shadowModelName: String = "UnifiedFaceModel V1 (Shadow)",
    val lastIdentityCosineAgreement: Float = 0.0f,
    val isPadDecisionAgreement: Boolean = true,
    val primaryLatencyMs: Long = 0L,
    val shadowLatencyMs: Long = 0L,
    val shadowHardwareTier: HardwareTier = HardwareTier.CPU_XNNPACK,
    val totalShadowFramesEvaluated: Long = 0L,
    val rollingMeanAgreement: Float = 0.0f
)

/**
 * Executes UnifiedFaceModel V1 in non-interfering shadow mode.
 * Evaluates behavioral parity and telemetry without altering production attendance decisions.
 */
class UnifiedShadowEvaluator(context: Context) {

    companion object {
        private const val TAG = "UnifiedShadowEvaluator"

        @Volatile
        private var INSTANCE: UnifiedShadowEvaluator? = null

        fun getInstance(context: Context): UnifiedShadowEvaluator =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: UnifiedShadowEvaluator(context.applicationContext).also { INSTANCE = it }
            }
    }

    val unifiedEngine = UnifiedFaceModelEngine(context)

    private val _telemetry = MutableStateFlow(
        ShadowComparisonTelemetry(
            isShadowActive = unifiedEngine.isReady,
            shadowHardwareTier = unifiedEngine.activeHardwareTier
        )
    )
    val telemetry: StateFlow<ShadowComparisonTelemetry> = _telemetry.asStateFlow()

    private var cumulativeAgreementSum = 0.0
    private var frameCount = 0L

    /**
     * Evaluates a frame in shadow mode.
     * @param alignedBitmap The aligned face crop evaluated by production pipeline.
     * @param primaryEmbedding The 512-D embedding extracted by the primary model.
     * @param primaryIsLive True if primary PAD evaluated the face as live.
     * @param primaryLatencyMs Execution time of primary pipeline.
     */
    fun evaluateShadow(
        alignedBitmap: Bitmap,
        primaryEmbedding: FloatArray,
        primaryIsLive: Boolean,
        primaryLatencyMs: Long
    ): UnifiedInferenceResult? {
        if (!unifiedEngine.isReady) return null

        val shadowResult = try {
            unifiedEngine.processFace(alignedBitmap)
        } catch (t: Throwable) {
            Log.w(TAG, "Shadow evaluation error: ${t.message}")
            null
        } ?: return null

        // Compute cosine agreement between primary and shadow identity embeddings
        val agreement = if (primaryEmbedding.isNotEmpty() && shadowResult.identityEmbedding.isNotEmpty()) {
            cosineSimilarity(primaryEmbedding, shadowResult.identityEmbedding)
        } else 0.0f

        val padAgree = (primaryIsLive == shadowResult.isLive)

        frameCount++
        cumulativeAgreementSum += agreement
        val rollingMean = (cumulativeAgreementSum / frameCount).toFloat()

        _telemetry.update {
            it.copy(
                isShadowActive = true,
                lastIdentityCosineAgreement = agreement,
                isPadDecisionAgreement = padAgree,
                primaryLatencyMs = primaryLatencyMs,
                shadowLatencyMs = shadowResult.latencyMs,
                shadowHardwareTier = shadowResult.hardwareTier,
                totalShadowFramesEvaluated = frameCount,
                rollingMeanAgreement = rollingMean
            )
        }

        return shadowResult
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var nA = 0f
        var nB = 0f
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            dot += a[i] * b[i]
            nA += a[i] * a[i]
            nB += b[i] * b[i]
        }
        val denom = sqrt(nA) * sqrt(nB)
        return if (denom > 1e-9f) (dot / denom).coerceIn(-1.0f, 1.0f) else 0.0f
    }
}
