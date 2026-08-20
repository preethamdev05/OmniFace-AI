package com.omniface.ai.inference

import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class QualcommBackend(
    val modelFile: File,
    val modelName: String = modelFile.name
) : InferenceBackend {

    override val backendType: BackendType = BackendType.QUALCOMM_NPU_GPU
    private var gpuDelegate: GpuDelegate? = null
    override var interpreter: Interpreter? = null
        private set

    override val isReady: Boolean
        get() = interpreter != null

    init {
        initialize()
    }

    private fun initialize() {
        try {
            val inputStream = FileInputStream(modelFile)
            val channel = inputStream.channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())

            val gpuOptions = GpuDelegate.Options().apply {
                setPrecisionLossAllowed(true)
                setInferencePreference(GpuDelegate.Options.INFERENCE_PREFERENCE_SUSTAINED_SPEED)
            }
            val gpu = GpuDelegate(gpuOptions)
            gpuDelegate = gpu

            val options = Interpreter.Options().apply {
                addDelegate(gpu)
                setNumThreads(4)
            }
            interpreter = Interpreter(buffer, options)
            Log.i("QualcommBackend", "✅ Loaded $modelName on Qualcomm Adreno GPU Delegate (${modelFile.length() / 1024 / 1024} MB)")
        } catch (t: Throwable) {
            Log.w("QualcommBackend", "Failed to init Qualcomm GPU Delegate for $modelName: ${t.message}")
            close()
        }
    }

    override fun run(input: ByteBuffer, output: Any): BackendExecutionReport {
        val interp = interpreter ?: throw IllegalStateException("QualcommBackend interpreter is null")
        val t0 = System.nanoTime()
        interp.run(input, output)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0f
        return BackendExecutionReport(backendType, modelName, elapsedMs, isHardwareAccelerated = true)
    }

    override fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        try { gpuDelegate?.close() } catch (_: Throwable) {}
        interpreter = null
        gpuDelegate = null
    }
}
