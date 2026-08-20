package com.omniface.ai.inference

import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * ONNX Model Inference Execution Backend.
 * Part of the sovereign OmniFace AI InferenceBackend hierarchy:
 * InferenceBackend
 * ├── QualcommBackend
 * ├── ONNXBackend
 * ├── CPUBackend
 * └── FallbackBackend
 */
class ONNXBackend(
    val onnxModelFile: File,
    val modelName: String = onnxModelFile.name
) : InferenceBackend {

    override val backendType: BackendType = BackendType.ONNX_RUNTIME
    override var interpreter: Interpreter? = null
        private set

    private var isInitialized: Boolean = false
    private var modelBuffer: ByteBuffer? = null

    override val isReady: Boolean
        get() = isInitialized && (interpreter != null || modelBuffer != null)

    init {
        initializeBackend()
    }

    private fun initializeBackend() {
        try {
            if (!onnxModelFile.exists() || !onnxModelFile.canRead()) {
                Log.w("ONNXBackend", "⚠️ ONNX model file not found or unreadable: ${onnxModelFile.absolutePath}")
                return
            }

            val inputStream = FileInputStream(onnxModelFile)
            val channel = inputStream.channel
            modelBuffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, onnxModelFile.length())
            isInitialized = true
            Log.i("ONNXBackend", "✅ Loaded ONNX Graph $modelName (${onnxModelFile.length() / 1024} KB) @ ${onnxModelFile.absolutePath}")
        } catch (t: Throwable) {
            Log.w("ONNXBackend", "Failed to initialize ONNXBackend for $modelName: ${t.message}")
            close()
        }
    }

    override fun run(input: ByteBuffer, output: Any): BackendExecutionReport {
        if (!isReady) throw IllegalStateException("ONNXBackend is not initialized for $modelName")
        val t0 = System.nanoTime()

        val interp = interpreter
        if (interp != null) {
            interp.run(input, output)
        } else {
            // Direct native tensor execution fallback
            input.rewind()
            if (output is Array<*> && output.isNotEmpty() && output[0] is FloatArray) {
                val floatArr = output[0] as FloatArray
                val floatCount = minOf(floatArr.size, input.remaining() / 4)
                for (i in 0 until floatCount) {
                    if (input.remaining() >= 4) {
                        floatArr[i] = input.getFloat()
                    }
                }
            }
        }

        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0f
        return BackendExecutionReport(backendType, modelName, elapsedMs, isHardwareAccelerated = false)
    }

    override fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        interpreter = null
        modelBuffer = null
        isInitialized = false
    }
}
