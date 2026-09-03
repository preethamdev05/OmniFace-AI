package com.omniface.ai.ml.core

import android.content.Context
import android.util.Log
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.NpuHardwareInfo
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer

enum class BackendType(val label: String) {
    QUALCOMM_NPU("Hexagon HTP NPU (INT8 MLIR)"),
    ADRENO_GPU("Adreno GPU Delegate (FP16)"),
    CPU_XNNPACK("ARM64 Multi-Core CPU (XNNPACK FP32)")
}

data class InferenceBackend(
    val type: BackendType,
    val label: String,
    val isHardwareAccelerated: Boolean
) {
    companion object {
        private const val TAG = "InferenceBackend"

        fun createInterpreterWithFallback(
            modelBuffer: ByteBuffer,
            preferredType: BackendType? = null,
            numThreads: Int = 4
        ): Triple<Interpreter, GpuDelegate?, NnApiDelegate?> {
            val npuInfo: NpuHardwareInfo = NpuHardwareDetector.detectNpuHardware()
            val isQualcomm = NpuHardwareDetector.isQualcommAiHubDevice()

            // 1. Try NNAPI / NPU Delegate (if preferred or on Qualcomm/Tensor)
            if (preferredType == BackendType.QUALCOMM_NPU || (preferredType == null && isQualcomm)) {
                try {
                    val nnapiOptions = NnApiDelegate.Options().apply {
                        setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                        setAllowFp16(true)
                    }
                    val nnapi = NnApiDelegate(nnapiOptions)
                    val options = Interpreter.Options().apply {
                        addDelegate(nnapi)
                        setNumThreads(numThreads)
                    }
                    val interpreter = Interpreter(modelBuffer, options)
                    Log.i(TAG, "⚡ NPU/NNAPI Delegate active (${npuInfo.npuName})")
                    return Triple(interpreter, null, nnapi)
                } catch (t: Throwable) {
                    Log.w(TAG, "⚠️ NPU/NNAPI delegate skipped: ${t.message}")
                }
            }

            // 2. Try Mobile GPU Delegate (Adreno/Mali)
            // Note: GpuDelegate.Options() is deprecated in TFLite 2.14 but has no replacement
            // in this version. The try-catch guarantees safe fallback if the delegate fails.
            if (preferredType == BackendType.ADRENO_GPU || preferredType == null) {
                try {
                    @Suppress("DEPRECATION")
                    val gpuOptions = GpuDelegate.Options().apply {
                        setPrecisionLossAllowed(true)
                        setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
                    }
                    val gpu = GpuDelegate(gpuOptions)
                    val options = Interpreter.Options().apply {
                        addDelegate(gpu)
                        setNumThreads(numThreads)
                    }
                    val interpreter = Interpreter(modelBuffer, options)
                    Log.i(TAG, "⚡ Mobile GPU Delegate active")
                    return Triple(interpreter, gpu, null)
                } catch (t: Throwable) {
                    Log.w(TAG, "⚠️ GPU delegate skipped: ${t.message}")
                }
            }

            // 3. Fallback to Multi-Threaded CPU XNNPACK
            val options = Interpreter.Options().apply {
                setNumThreads(numThreads)
                useXNNPACK = true
            }
            val interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "💻 Multi-Threaded CPU XNNPACK active ($numThreads threads)")
            return Triple(interpreter, null, null)
        }

        fun resolveBackendLabel(type: BackendType, npuInfo: NpuHardwareInfo): String {
            return when (type) {
                BackendType.QUALCOMM_NPU -> "${npuInfo.npuName} (INT8)"
                BackendType.ADRENO_GPU -> "Mobile GPU Delegate (FP16)"
                BackendType.CPU_XNNPACK -> "Multi-Core CPU (XNNPACK)"
            }
        }
    }
}
