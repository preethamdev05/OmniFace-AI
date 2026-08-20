package com.omniface.ai.inference

import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer

enum class BackendType {
    QUALCOMM_NPU_GPU,
    ONNX_RUNTIME,
    CPU_XNNPACK,
    NNAPI_FALLBACK
}

data class BackendExecutionReport(
    val backendType: BackendType,
    val modelName: String,
    val executionLatencyMs: Float,
    val isHardwareAccelerated: Boolean
)

interface InferenceBackend : Closeable {
    val backendType: BackendType
    val isReady: Boolean
    val interpreter: Interpreter?
    fun run(input: ByteBuffer, output: Any): BackendExecutionReport
}
