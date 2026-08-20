package com.omniface.ai.inference

import android.content.Context
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.nio.ByteBuffer
import java.io.FileInputStream
import java.nio.channels.FileChannel

class FallbackBackend(
    val context: Context,
    val modelAssetName: String = "mobilefacenet_512d_int8.tflite"
) : InferenceBackend {

    override val backendType: BackendType = BackendType.NNAPI_FALLBACK
    private var nnApiDelegate: NnApiDelegate? = null
    override var interpreter: Interpreter? = null
        private set

    override val isReady: Boolean
        get() = interpreter != null

    init {
        initialize()
    }

    private fun initialize() {
        try {
            val fd = context.assets.openFd(modelAssetName)
            val stream = FileInputStream(fd.fileDescriptor)
            val buffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)

            val nnApiOptions = NnApiDelegate.Options().apply {
                setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
                setAllowFp16(true)
            }
            val nnapi = NnApiDelegate(nnApiOptions)
            nnApiDelegate = nnapi

            val options = Interpreter.Options().apply {
                addDelegate(nnapi)
                setNumThreads(4)
            }
            interpreter = Interpreter(buffer, options)
            Log.i("FallbackBackend", "✅ Loaded $modelAssetName on NNAPI fallback")
        } catch (t: Throwable) {
            Log.w("FallbackBackend", "NNAPI init fallback to CPU: ${t.message}")
            try {
                val fd = context.assets.openFd(modelAssetName)
                val stream = FileInputStream(fd.fileDescriptor)
                val buffer = stream.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
                val options = Interpreter.Options().apply { setNumThreads(4); useXNNPACK = true }
                interpreter = Interpreter(buffer, options)
            } catch (_: Throwable) {}
        }
    }

    override fun run(input: ByteBuffer, output: Any): BackendExecutionReport {
        val interp = interpreter ?: throw IllegalStateException("FallbackBackend interpreter is null")
        val t0 = System.nanoTime()
        interp.run(input, output)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0f
        return BackendExecutionReport(backendType, modelAssetName, elapsedMs, isHardwareAccelerated = nnApiDelegate != null)
    }

    override fun close() {
        try { interpreter?.close() } catch (_: Throwable) {}
        try { nnApiDelegate?.close() } catch (_: Throwable) {}
        interpreter = null
        nnApiDelegate = null
    }
}
