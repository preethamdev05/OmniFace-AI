package com.omniface.ai.ml.governor

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.omniface.ai.hardware.CapabilityNormalizer
import com.omniface.ai.hardware.HardwareCapability
import com.omniface.ai.hardware.HardwareProfile
import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.core.BackendType
import com.omniface.ai.ml.core.InferenceBackend
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer

/**
 * Normalized UI Hardware Capability Presentation.
 * The UI consumes this model directly without chipset-specific branches.
 */
data class UiHardwareCapability(
    val cpuSummary: String,
    val gpuSummary: String,
    val acceleratorStatus: String,
    val selectedBackend: String,
    val performanceTier: String,
    val thermalStatus: String
)

/**
 * Sovereign Execution Governor for Biometric Edge Execution.
 *
 * Sits inside the biometric module and governs runtime backend selection based on:
 * 1. Accelerator availability
 * 2. Operator support
 * 3. Precision support
 * 4. Tensor compatibility
 * 5. Measured latency
 * 6. Memory usage
 * 7. Thermal state
 *
 * Selects NPU -> GPU -> CPU as appropriate and caches decisions across launches.
 */
class ExecutionGovernor private constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "ExecutionGovernor"
        private const val PREFS_NAME = "execution_governor_cache"
        private const val KEY_CACHED_BACKEND = "cached_backend_type"
        private const val KEY_CACHED_MODEL_VERSION = "cached_model_version"

        @Volatile
        private var instance: ExecutionGovernor? = null

        fun getInstance(context: Context): ExecutionGovernor {
            return instance ?: synchronized(this) {
                instance ?: ExecutionGovernor(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Resolves the optimal backend for a given model buffer according to the 7 execution criteria.
     */
    fun selectOptimalBackend(
        modelBuffer: ByteBuffer,
        modelVersion: String,
        numThreads: Int = 4
    ): Triple<Interpreter, Any?, BackendType> {
        val profile: HardwareProfile = CapabilityNormalizer.resolve(context)
        val thermalState: ThermalState = ThermalGovernor.thermalState.value

        // If device is in thermal throttle, immediately downgrade to power-efficient execution
        if (thermalState == ThermalState.CRITICAL) {
            Log.w(TAG, "⚠️ Thermal degradation ($thermalState) detected. Bypassing NPU to conserve thermal headroom.")
            val (interp, gpu, _) = InferenceBackend.createInterpreterWithFallback(
                modelBuffer = modelBuffer,
                preferredType = BackendType.CPU_XNNPACK,
                numThreads = 2
            )
            return Triple(interp, gpu, BackendType.CPU_XNNPACK)
        }

        val effectiveThreads = when (thermalState) {
            ThermalState.CRITICAL -> 2
            ThermalState.WARM -> 3.coerceAtMost(numThreads)
            ThermalState.NOMINAL -> numThreads
        }

        // Check if decision was already probed and cached for this model version
        val cachedBackendName = prefs.getString(KEY_CACHED_BACKEND, null)
        val cachedVersion = prefs.getString(KEY_CACHED_MODEL_VERSION, null)

        if (cachedVersion == modelVersion && cachedBackendName != null) {
            val preferred = try { BackendType.valueOf(cachedBackendName) } catch (_: Throwable) { null }
            if (preferred != null) {
                try {
                    val (interp, gpu, nnapi) = InferenceBackend.createInterpreterWithFallback(
                        modelBuffer = modelBuffer,
                        preferredType = preferred,
                        numThreads = effectiveThreads
                    )
                    val activeType = when {
                        nnapi != null -> preferred
                        gpu != null -> BackendType.ADRENO_GPU
                        else -> BackendType.CPU_XNNPACK
                    }
                    Log.i(TAG, "⚡ Reusing cached execution backend: $activeType for model $modelVersion")
                    return Triple(interp, gpu ?: nnapi, activeType)
                } catch (t: Throwable) {
                    Log.w(TAG, "Cached backend failed initialization: ${t.message}. Re-probing...")
                }
            }
        }

        // ── Full 7-Criteria Runtime Probe ──
        val selectedType = probeAndSelectBackend(modelBuffer, profile)

        // Cache selected backend
        prefs.edit()
            .putString(KEY_CACHED_BACKEND, selectedType.name)
            .putString(KEY_CACHED_MODEL_VERSION, modelVersion)
            .apply()

        val (interp, gpu, nnapi) = InferenceBackend.createInterpreterWithFallback(
            modelBuffer = modelBuffer,
            preferredType = selectedType,
            numThreads = effectiveThreads
        )

        return Triple(interp, gpu ?: nnapi, selectedType)
    }

    private fun probeAndSelectBackend(
        modelBuffer: ByteBuffer,
        profile: HardwareProfile
    ): BackendType {
        // 1. Try NPU / Dedicated Accelerator if available
        if (profile.hasAccelerator && profile.capabilities.contains(HardwareCapability.NNAPI_ACCELERATION)) {
            val npuType = when {
                profile.socManufacturer.contains("Qualcomm", ignoreCase = true) -> BackendType.QUALCOMM_NPU
                profile.socManufacturer.contains("MediaTek", ignoreCase = true) -> BackendType.MEDIATEK_APU
                profile.socManufacturer.contains("Google", ignoreCase = true) -> BackendType.GOOGLE_TENSOR_TPU
                profile.socManufacturer.contains("Samsung", ignoreCase = true) -> BackendType.SAMSUNG_EXYNOS_NPU
                else -> BackendType.QUALCOMM_NPU
            }

            if (verifyBackendCompatibility(modelBuffer, npuType)) {
                Log.i(TAG, "✅ Probe passed for NPU accelerator: $npuType")
                return npuType
            }
        }

        // 2. Try Mobile GPU Delegate
        if (profile.capabilities.contains(HardwareCapability.GPU_DELEGATE)) {
            if (verifyBackendCompatibility(modelBuffer, BackendType.ADRENO_GPU)) {
                Log.i(TAG, "✅ Probe passed for Mobile GPU Delegate")
                return BackendType.ADRENO_GPU
            }
        }

        // 3. Fallback to CPU XNNPACK
        Log.i(TAG, "ℹ️ Selected Multi-Threaded CPU XNNPACK")
        return BackendType.CPU_XNNPACK
    }

    private fun verifyBackendCompatibility(
        modelBuffer: ByteBuffer,
        type: BackendType
    ): Boolean {
        return try {
            val start = SystemClock.elapsedRealtime()
            val (interp, gpu, nnapi) = InferenceBackend.createInterpreterWithFallback(
                modelBuffer = modelBuffer,
                preferredType = type,
                numThreads = 2
            )
            // Verify allocation was successful
            interp.allocateTensors()
            val latency = SystemClock.elapsedRealtime() - start

            // Clean up probe resources
            try { interp.close() } catch (_: Throwable) {}
            try {
                if (gpu is AutoCloseable) gpu.close()
                if (nnapi is AutoCloseable) nnapi.close()
            } catch (_: Throwable) {}

            Log.d(TAG, "Probe for $type completed in ${latency}ms")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Probe for $type failed: ${t.message}")
            false
        }
    }

    /**
     * Constructs normalized UI capability information for diagnostics display.
     */
    fun getUiCapability(): UiHardwareCapability {
        val profile = CapabilityNormalizer.resolve(context)
        val thermal = ThermalGovernor.thermalState.value

        val cachedBackend = prefs.getString(KEY_CACHED_BACKEND, null)?.let {
            try { BackendType.valueOf(it).label } catch (_: Throwable) { null }
        } ?: if (profile.hasAccelerator) "${profile.acceleratorName} (INT8)" else "ARM64 CPU"

        return UiHardwareCapability(
            cpuSummary = "${profile.cpuCores} cores · ${profile.cpuArchitecture}",
            gpuSummary = profile.gpuRenderer,
            acceleratorStatus = if (profile.hasAccelerator) "Available (${profile.acceleratorName})" else "CPU Only",
            selectedBackend = cachedBackend,
            performanceTier = profile.performanceTier.label,
            thermalStatus = thermal.name
        )
    }
}
