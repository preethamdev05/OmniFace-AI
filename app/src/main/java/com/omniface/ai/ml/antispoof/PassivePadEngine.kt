package com.omniface.ai.ml.antispoof

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.core.BackendType
import com.omniface.ai.ml.core.InferenceBackend
import com.omniface.ai.ml.core.TfliteModel
import com.omniface.ai.ml.unified.UnifiedFaceModelEngine

data class PassivePadResult(
    val isLive: Boolean,
    val livenessScore: Float,        // 0.0 to 1.0 (Live probability)
    val spoofProbability: Float = (1.0f - livenessScore).coerceIn(0f, 1f),
    val attackTypeDescription: String = "",
    val latencyMs: Long = 0L,
    val inferenceLatencyMs: Long = latencyMs
) {
    constructor(
        isLive: Boolean,
        livenessScore: Float,
        attackTypeDescription: String,
        latencyMs: Long
    ) : this(
        isLive = isLive,
        livenessScore = livenessScore,
        spoofProbability = (1.0f - livenessScore).coerceIn(0f, 1f),
        attackTypeDescription = attackTypeDescription,
        latencyMs = latencyMs,
        inferenceLatencyMs = latencyMs
    )

    constructor(
        isLive: Boolean,
        confidence: Float,
        label: String,
        inferenceMs: Long,
        @Suppress("UNUSED_PARAMETER") dummy: Unit = Unit
    ) : this(
        isLive = isLive,
        livenessScore = confidence,
        spoofProbability = (1.0f - confidence).coerceIn(0f, 1f),
        attackTypeDescription = label,
        latencyMs = inferenceMs,
        inferenceLatencyMs = inferenceMs
    )
}

/**
 * Configurable Color Channel Ordering for Passive PAD Engine.
 */
enum class ColorChannelOrder {
    RGB,
    BGR
}

/**
 * Presentation Attack Detector (Passive RGB PAD).
 *
 * Delegating facade to UnifiedFaceModel V1 (Shared-Backbone PAD Head).
 * Eliminates separate model loading and executes directly through the single unified runtime.
 */
class PassivePadEngine(private val context: Context) : TfliteModel<Bitmap, PassivePadResult> {

    companion object {
        private const val TAG = "PassivePadEngine"
        private const val LIVE_THRESHOLD = 0.50f
    }

    override val modelId: String = UnifiedFaceModelEngine.MODEL_ID
    override val displayName: String = "UnifiedFaceModel V1 (Shared-Backbone PAD)"
    override val version: String = UnifiedFaceModelEngine.MODEL_VERSION

    private val unifiedEngine: UnifiedFaceModelEngine
        get() = UnifiedFaceModelEngine.getInstance(context)

    override var activeBackend: InferenceBackend = InferenceBackend(BackendType.CPU_XNNPACK, "CPU", false)
        get() {
            val tier = unifiedEngine.activeHardwareTier
            return InferenceBackend(
                type = when (tier) {
                    HardwareTier.NPU_NNAPI, HardwareTier.NPU_DELEGATE -> BackendType.QUALCOMM_NPU
                    HardwareTier.GPU_DELEGATE -> BackendType.ADRENO_GPU
                    HardwareTier.CPU_XNNPACK -> BackendType.CPU_XNNPACK
                },
                label = tier.getResolvedLabel(unifiedEngine.npuHardwareInfo),
                isHardwareAccelerated = (tier != HardwareTier.CPU_XNNPACK)
            )
        }
        private set

    var channelOrder: ColorChannelOrder = ColorChannelOrder.BGR

    override val isReady: Boolean get() = unifiedEngine.isReady

    override suspend fun run(input: Bitmap): PassivePadResult {
        if (input.isRecycled) {
            return PassivePadResult(
                isLive = false,
                livenessScore = 0.0f,
                spoofProbability = 1.0f,
                attackTypeDescription = "Invalid Input Bitmap",
                latencyMs = 0L
            )
        }

        val t0 = SystemClock.elapsedRealtime()
        val res = unifiedEngine.processFace(input)
        val elapsed = SystemClock.elapsedRealtime() - t0

        if (res == null) {
            return PassivePadResult(
                isLive = false,
                livenessScore = 0.0f,
                spoofProbability = 1.0f,
                attackTypeDescription = "Inference Failed",
                latencyMs = elapsed
            )
        }

        val liveProb = res.padProbabilities.getOrNull(0)?.coerceIn(0f, 1f) ?: 0f
        val photoSpoof = res.padProbabilities.getOrNull(1)?.coerceIn(0f, 1f) ?: 0f
        val screenSpoof = res.padProbabilities.getOrNull(2)?.coerceIn(0f, 1f) ?: 0f
        val totalSpoof = (photoSpoof + screenSpoof).coerceIn(0f, 1f)
        val isLive = res.isLive && totalSpoof < 0.50f

        val attackDesc = when {
            isLive -> "Authentic 3D Human Face"
            screenSpoof > 0.60f -> "Electronic Screen Replay Attack"
            photoSpoof > 0.60f -> "2D Printed Photo Spoof Attack"
            totalSpoof > 0.50f -> "Presentation Attack Detected"
            else -> "Suspected Presentation Attack"
        }

        return PassivePadResult(
            isLive = isLive,
            livenessScore = liveProb,
            spoofProbability = totalSpoof,
            attackTypeDescription = attackDesc,
            latencyMs = elapsed,
            inferenceLatencyMs = res.latencyMs
        )
    }

    suspend fun detectLiveness(input: Bitmap): PassivePadResult = run(input)

    override fun benchmarkLatency(): Long = 5L

    override fun close() {
        // Lifecycle managed by UnifiedFaceModelEngine singleton
    }
}
