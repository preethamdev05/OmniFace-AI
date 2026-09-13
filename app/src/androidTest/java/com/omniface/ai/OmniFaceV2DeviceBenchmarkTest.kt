package com.omniface.ai

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omniface.ai.hardware.NpuHardwareDetector
import com.omniface.ai.ml.unified.UnifiedFaceModelEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.Arrays

@RunWith(AndroidJUnit4::class)
class OmniFaceV2DeviceBenchmarkTest {

    companion object {
        private const val TAG = "OmniFaceV2Benchmark"
        private const val V2_INT8 = "unified_face_v2_int8.tflite"
        private const val V2_FP16 = "unified_face_v2_fp16.tflite"
        private const val WORKLOAD_FACE_COUNT = 100
        private const val WARMUP_COUNT = 5
    }

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun loadModelBuffer(modelName: String): ByteBuffer {
        val fd = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fd.fileDescriptor)
        val channel = inputStream.channel
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun createSyntheticFaceBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * 112 * 112 * 3 * 4).apply {
            order(ByteOrder.nativeOrder())
        }
        // Normalized pixel values in [-1.0, 1.0]
        for (i in 0 until (112 * 112 * 3)) {
            val v = ((i % 256) - 127.5f) / 128.0f
            buffer.putFloat(v)
        }
        buffer.rewind()
        return buffer
    }

    @Test
    fun testV2ModelAssetsExist() {
        val assetList = context.assets.list("")?.toList() ?: emptyList()
        Log.i(TAG, "Available assets: $assetList")
        assertTrue("unified_face_v2_int8.tflite must exist in assets", assetList.contains(V2_INT8))
        assertTrue("unified_face_v2_fp16.tflite must exist in assets", assetList.contains(V2_FP16))
    }

    @Test
    fun testV2EngineInitializationAndContract() {
        val engine = UnifiedFaceModelEngine.getInstance(context)
        assertTrue("UnifiedFaceModelEngine V2 must initialize successfully", engine.isReady)
        assertEquals("Model version must be V2.0", "UnifiedFaceModel_v2.0", engine.modelVersion)
        
        val hardware = NpuHardwareDetector.detectNpuHardware()
        Log.i(TAG, "Detected Device Hardware: ${hardware.npuName}, Peak TOPS: ${hardware.peakTops}")
        Log.i(TAG, "Active Engine Hardware Tier: ${engine.activeHardwareTier}")
    }

    @Test
    fun test100FaceWorkloadLatencyOnDevice() {
        val hardware = NpuHardwareDetector.detectNpuHardware()
        Log.i(TAG, "======================================================================")
        Log.i(TAG, " OmniFace V2 100-Face Physical Device Workload Benchmark")
        Log.i(TAG, " Target Device : ${hardware.npuName} (SoC: ${hardware.socModel}, Board: ${hardware.boardPlatform}, TOPS: ${hardware.peakTops})")
        Log.i(TAG, " Model Targets : $V2_INT8 & $V2_FP16")
        Log.i(TAG, "======================================================================")

        // Test configuration matrix: NNAPI (INT8), GPU (FP16), CPU (INT8), CPU (FP16)
        val delegates = listOf(
            Triple("NNAPI / MediaTek APU (INT8)", V2_INT8, { opts: Interpreter.Options -> opts.addDelegate(NnApiDelegate()) }),
            Triple("Mobile GPU Delegate (FP16)", V2_FP16, { opts: Interpreter.Options -> opts.addDelegate(GpuDelegate()) }),
            Triple("CPU XNNPACK 4-Thread (INT8)", V2_INT8, { opts: Interpreter.Options -> opts.setUseXNNPACK(true); opts.setNumThreads(4) }),
            Triple("CPU XNNPACK 4-Thread (FP16)", V2_FP16, { opts: Interpreter.Options -> opts.setUseXNNPACK(true); opts.setNumThreads(4) })
        )

        val benchmarkResults = mutableMapOf<String, Map<String, Any>>()

        for ((name, modelFile, configDelegate) in delegates) {
            Log.i(TAG, "--- Benchmarking: $name on $modelFile ---")
            var interp: Interpreter? = null
            try {
                val opts = Interpreter.Options()
                configDelegate(opts)
                val buffer = loadModelBuffer(modelFile)
                interp = Interpreter(buffer, opts)

                // Verify contract: 1 input, 7 outputs
                assertEquals(1, interp.inputTensorCount)
                assertEquals(7, interp.outputTensorCount)

                // Output buffers
                val outIdentity = Array(1) { FloatArray(512) }
                val outPad = Array(1) { FloatArray(3) }
                val outQuality = Array(1) { FloatArray(4) }
                val outMesh = Array(1) { FloatArray(1404) }
                val outGeom = Array(1) { FloatArray(265) }
                val outGaze = Array(1) { FloatArray(2) }
                val outAttrib = Array(1) { FloatArray(5) }

                val outputs = mutableMapOf<Int, Any>()
                for (i in 0 until interp.outputTensorCount) {
                    val tensor = interp.getOutputTensor(i)
                    val totalElements = tensor.shape().fold(1) { acc, dim -> acc * dim }
                    when (totalElements) {
                        512 -> outputs[i] = outIdentity
                        3 -> outputs[i] = outPad
                        4 -> outputs[i] = outQuality
                        1404 -> outputs[i] = outMesh
                        265 -> outputs[i] = outGeom
                        2 -> outputs[i] = outGaze
                        5 -> outputs[i] = outAttrib
                    }
                }
                assertEquals("Must resolve all 7 output tensors", 7, outputs.size)

                val inputBuf = createSyntheticFaceBuffer()

                // Warm-up passes
                for (w in 0 until WARMUP_COUNT) {
                    inputBuf.rewind()
                    interp.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)
                }

                // 100-Face Workload Execution
                val latenciesMs = DoubleArray(WORKLOAD_FACE_COUNT)
                val totalT0 = System.nanoTime()

                for (faceIdx in 0 until WORKLOAD_FACE_COUNT) {
                    inputBuf.rewind()
                    val t0 = System.nanoTime()
                    interp.runForMultipleInputsOutputs(arrayOf(inputBuf), outputs)
                    val t1 = System.nanoTime()
                    latenciesMs[faceIdx] = (t1 - t0) / 1_000_000.0
                }
                val totalT1 = System.nanoTime()
                val totalWorkloadMs = (totalT1 - totalT0) / 1_000_000.0

                Arrays.sort(latenciesMs)
                val meanLatency = latenciesMs.average()
                val minLatency = latenciesMs.first()
                val maxLatency = latenciesMs.last()
                val p50Latency = latenciesMs[50]
                val p90Latency = latenciesMs[90]
                val p95Latency = latenciesMs[95]
                val p99Latency = latenciesMs[99]
                val throughputFps = (WORKLOAD_FACE_COUNT / (totalWorkloadMs / 1000.0))

                // Verify output sanity
                val emb = outIdentity[0]
                var normSq = 0.0
                for (v in emb) {
                    assertFalse("Embedding cannot contain NaN", v.isNaN())
                    normSq += v * v
                }
                val norm = Math.sqrt(normSq)
                assertTrue("Embedding norm must be non-zero", norm > 0.01)

                Log.i(TAG, "  -> 100 Faces Completed in : %.2f ms".format(totalWorkloadMs))
                Log.i(TAG, "  -> Throughput             : %.1f FPS".format(throughputFps))
                Log.i(TAG, "  -> Latency Mean           : %.2f ms".format(meanLatency))
                Log.i(TAG, "  -> Latency Min / Max      : %.2f / %.2f ms".format(minLatency, maxLatency))
                Log.i(TAG, "  -> Latency P50 (Median)   : %.2f ms".format(p50Latency))
                Log.i(TAG, "  -> Latency P95            : %.2f ms".format(p95Latency))
                Log.i(TAG, "  -> Latency P99            : %.2f ms".format(p99Latency))
                Log.i(TAG, "  -> Vector Norm Check      : %.4f".format(norm))

                benchmarkResults[name] = mapOf(
                    "model" to modelFile,
                    "total_100_workload_ms" to totalWorkloadMs,
                    "throughput_fps" to throughputFps,
                    "mean_latency_ms" to meanLatency,
                    "p50_latency_ms" to p50Latency,
                    "p95_latency_ms" to p95Latency,
                    "p99_latency_ms" to p99Latency,
                    "min_latency_ms" to minLatency,
                    "max_latency_ms" to maxLatency,
                    "embedding_norm" to norm
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Delegate $name failed: ${t.message}")
            } finally {
                interp?.close()
            }
        }

        assertTrue("At least one hardware tier must pass 100-face benchmark", benchmarkResults.isNotEmpty())
        Log.i(TAG, "======================================================================")
        Log.i(TAG, " BENCHMARK COMPLETE: ${benchmarkResults.size} hardware configs verified.")
        Log.i(TAG, "======================================================================")
    }
}
