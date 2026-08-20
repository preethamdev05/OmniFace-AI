package com.omniface.ai.inference

import android.content.Context
import android.util.Log
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.hardware.NpuHardwareInfo
import java.io.File

/**
 * Sovereign Inference Backend Manager & Hardware Arbiter.
 * Orchestrates runtime selection and capability discovery across:
 * 1. QualcommBackend (Qualcomm Snapdragon Adreno GPU / Hexagon HTP NPU)
 * 2. ONNXBackend (ONNX Neural Execution Graph)
 * 3. CpuBackend (Multi-Core CPU XNNPACK Threadpool)
 * 4. FallbackBackend (NNAPI / CPU Reference Fallback)
 */
class InferenceBackendManager(private val context: Context) : AutoCloseable {

    companion object {
        private const val TAG = "InferenceBackendMgr"
    }

    val npuHardwareInfo: NpuHardwareInfo = NpuHardwareDetector.detectNpuHardware()
    var activeBackend: InferenceBackend? = null
        private set

    val isQualcommCapable: Boolean = NpuHardwareDetector.isQualcommAiHubDevice() ||
            (npuHardwareInfo.socModel.contains("Snapdragon", ignoreCase = true) &&
             (npuHardwareInfo.socModel.contains("8", ignoreCase = true) || npuHardwareInfo.socModel.contains("SM8", ignoreCase = true)))

    init {
        initializeOptimalBackend()
    }

    /**
     * Initializes the highest-performing available inference backend with automatic fallback.
     */
    fun initializeOptimalBackend() {
        close()

        // 1. Priority 1: Qualcomm Acceleration Backend (if Snapdragon silicon + model exists)
        if (isQualcommCapable) {
            val cavafaceCandidate = File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_cavaface/cavaface-tflite-float/cavaface.tflite")
            val suiteCandidate = File("/storage/emulated/0/AI-HUB/FR/models/qualcomm_suite/cavaface/cavaface-tflite-float/cavaface.tflite")
            val targetFile = if (cavafaceCandidate.exists() && cavafaceCandidate.canRead()) cavafaceCandidate
                             else if (suiteCandidate.exists() && suiteCandidate.canRead()) suiteCandidate
                             else null

            if (targetFile != null) {
                try {
                    val qBackend = QualcommBackend(targetFile, "Qualcomm CavaFace IR-SE-100")
                    if (qBackend.isReady) {
                        activeBackend = qBackend
                        Log.i(TAG, "⚡ Active Backend: QualcommBackend (Qualcomm Adreno GPU / HTP NPU)")
                        return
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "QualcommBackend initialization skipped: ${t.message}")
                }
            }
        }

        // 2. Priority 2: ONNX Runtime Backend (if ONNX graph exists)
        val onnxCandidate = File("/storage/emulated/0/AI-HUB/FR/models/latest_pretrained/w600k_mbf.onnx")
        if (onnxCandidate.exists() && onnxCandidate.canRead()) {
            try {
                val onnxBackend = ONNXBackend(onnxCandidate, "w600k_mbf.onnx")
                if (onnxBackend.isReady) {
                    activeBackend = onnxBackend
                    Log.i(TAG, "⚡ Active Backend: ONNXBackend (w600k_mbf.onnx)")
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "ONNXBackend initialization skipped: ${t.message}")
            }
        }

        // 3. Priority 3: Multi-Core CPU XNNPACK Backend
        val fp32Model = File("/storage/emulated/0/AI-HUB/FR/models/mobilefacenet_512d_fp32.tflite")
        if (fp32Model.exists() && fp32Model.canRead()) {
            try {
                val cpuBackend = CpuBackend(fp32Model)
                if (cpuBackend.isReady) {
                    activeBackend = cpuBackend
                    Log.i(TAG, "⚡ Active Backend: CpuBackend (4-Thread Multi-Core XNNPACK)")
                    return
                }
            } catch (t: Throwable) {
                Log.w(TAG, "CpuBackend initialization skipped: ${t.message}")
            }
        }

        // 4. Priority 4: FallbackBackend (Asset-bundled NNAPI / CPU INT8)
        try {
            val fallback = FallbackBackend(context, "mobilefacenet_512d_int8.tflite")
            activeBackend = fallback
            Log.i(TAG, "⚡ Active Backend: FallbackBackend (Bundled Asset NNAPI/CPU)")
        } catch (t: Throwable) {
            Log.e(TAG, "❌ Critical: All inference backends failed to initialize: ${t.message}")
        }
    }

    override fun close() {
        try { activeBackend?.close() } catch (_: Throwable) {}
        activeBackend = null
    }
}
