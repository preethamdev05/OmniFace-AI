package com.omniface.ai.inference

import android.content.res.AssetFileDescriptor
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class CpuBackend : InferenceBackend {
    override val backendType: BackendType = BackendType.CPU_XNNPACK
    override var interpreter: Interpreter? = null
        private set
    private val modelName: String

    override val isReady: Boolean
        get() = interpreter != null

    constructor(modelFile: File) {
        this.modelName = modelFile.name
        try {
            val inputStream = FileInputStream(modelFile)
            val channel = inputStream.channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                useXNNPACK = true
            }
            interpreter = Interpreter(buffer, options)
        } catch (t: Throwable) {
            Log.w("CpuBackend", "CPU XNNPACK init failed for $modelName: ${t.message}")
        }
    }

    constructor(assetFd: AssetFileDescriptor, modelName: String) {
        this.modelName = modelName
        try {
            val inputStream = FileInputStream(assetFd.fileDescriptor)
            val channel = inputStream.channel
            val buffer = channel.map(FileChannel.MapMode.READ_ONLY, assetFd.startOffset, assetFd.declaredLength)
            val options = Interpreter.Options().apply {
                setNumThreads(4)
                useXNNPACK = true
            }
            interpreter = Interpreter(buffer, options)
        } catch (t: Throwable) {
            Log.w("CpuBackend", "CPU XNNPACK asset init failed for $modelName: ${t.message}")
        }
    }

    override fun run(input: ByteBuffer, output: Any): BackendExecutionReport {
        val interp = interpreter ?: throw IllegalStateException("CpuBackend interpreter is null")
        val t0 = System.nanoTime()
        interp.run(input, output)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0f
        return BackendExecutionReport(backendType, modelName, elapsedMs, isHardwareAccelerated = false)
    }

    override fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        interpreter = null
    }
}
