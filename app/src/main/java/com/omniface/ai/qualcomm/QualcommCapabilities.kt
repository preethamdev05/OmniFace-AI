package com.omniface.ai.qualcomm

import android.content.Context
import android.util.Log
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File

data class ModelCapabilityStatus(
    val spec: QualcommModelSpec,
    val isChipsetSupported: Boolean,
    val isArtifactAvailable: Boolean,
    val artifactFile: File?,
    val isBackendAvailable: Boolean,
    val isReadyForInference: Boolean
)

object QualcommCapabilities {
    private const val TAG = "QualcommCapabilities"

    private var isGpuBackendVerified: Boolean? = null

    fun checkGpuBackendAvailability(): Boolean {
        isGpuBackendVerified?.let { return it }
        return try {
            val options = GpuDelegate.Options().apply {
                setPrecisionLossAllowed(true)
            }
            val delegate = GpuDelegate(options)
            delegate.close()
            isGpuBackendVerified = true
            true
        } catch (t: Throwable) {
            Log.w(TAG, "GpuDelegate unavailable: ${t.message}")
            isGpuBackendVerified = false
            false
        }
    }

    fun evaluateModelCapability(context: Context, spec: QualcommModelSpec): ModelCapabilityStatus {
        val currentChipset = SnapdragonDetector.currentChipset
        val isChipsetSupported = spec.supportedChipsets.contains(currentChipset)
        val file = ModelRegistry.resolveArtifactFile(context, spec)
        val isArtifactAvailable = file != null && file.exists() && file.canRead() && file.length() > 1024
        val isBackendAvailable = checkGpuBackendAvailability()

        val isReady = isChipsetSupported && isArtifactAvailable && isBackendAvailable

        return ModelCapabilityStatus(
            spec = spec,
            isChipsetSupported = isChipsetSupported,
            isArtifactAvailable = isArtifactAvailable,
            artifactFile = file,
            isBackendAvailable = isBackendAvailable,
            isReadyForInference = isReady
        )
    }

    fun evaluateSuiteCapabilities(context: Context): Map<String, ModelCapabilityStatus> {
        return ModelRegistry.ALL_MODELS.associate { spec ->
            spec.modelId to evaluateModelCapability(context, spec)
        }
    }
}
