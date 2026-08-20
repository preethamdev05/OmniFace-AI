package com.omniface.ai.qualcomm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.omniface.ai.inference.QualcommBackend
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Qualcomm3DMMOutput(
    val parameters265: FloatArray,
    val depthVariance: Float,
    val isTrue3DSurface: Boolean,
    val executionMs: Float
)

data class QualcommAttributesOutput(
    val smileScore: Float,
    val eyeglassesScore: Float,
    val rawProbabilities: FloatArray,
    val executionMs: Float
)

data class QualcommEyeGazeOutput(
    val pitch: Float,
    val yaw: Float,
    val isGazeAttentive: Boolean,
    val eyeLandmarks34: Array<FloatArray>,
    val executionMs: Float
)

data class QualcommHRNetOutput(
    val landmarks29: Array<FloatArray>,
    val executionMs: Float
)

data class QualcommMeshOutput(
    val landmarks468: Array<FloatArray>,
    val faceScore: Float,
    val executionMs: Float
)

class QualcommEngine(val context: Context) : Closeable {

    private val cavafaceBackend: QualcommBackend?
    private val facemapBackend: QualcommBackend?
    private val attribBackend: QualcommBackend?
    private val eyegazeBackend: QualcommBackend?
    private val hrnetBackend: QualcommBackend?
    private val mediapipeBackend: QualcommBackend?

    val isCavafaceReady: Boolean get() = cavafaceBackend?.isReady == true
    val isFaceMapReady: Boolean get() = facemapBackend?.isReady == true
    val isAttribReady: Boolean get() = attribBackend?.isReady == true
    val isEyeGazeReady: Boolean get() = eyegazeBackend?.isReady == true
    val isHRNetReady: Boolean get() = hrnetBackend?.isReady == true
    val isMediaPipeReady: Boolean get() = mediapipeBackend?.isReady == true

    val isAnyQualcommModelReady: Boolean
        get() = isCavafaceReady || isFaceMapReady || isAttribReady || isEyeGazeReady || isHRNetReady || isMediaPipeReady

    init {
        cavafaceBackend = createBackend(ModelRegistry.CAVAFACE)
        facemapBackend = createBackend(ModelRegistry.FACEMAP_3DMM)
        attribBackend = createBackend(ModelRegistry.FACE_ATTRIB_NET)
        eyegazeBackend = createBackend(ModelRegistry.EYEGAZE)
        hrnetBackend = createBackend(ModelRegistry.HRNET_FACE)
        mediapipeBackend = createBackend(ModelRegistry.MEDIAPIPE_FACE)
    }

    private fun createBackend(spec: QualcommModelSpec): QualcommBackend? {
        val file = ModelRegistry.resolveArtifactFile(context, spec)
        return if (file != null && file.exists() && file.canRead()) {
            try {
                QualcommBackend(file, spec.displayName)
            } catch (t: Throwable) {
                Log.w("QualcommEngine", "Failed to create backend for ${spec.displayName}: ${t.message}")
                null
            }
        } else null
    }

    // 1. CavaFace (112x112 RGB -> 512-D L2 Normalized Embedding)
    private val cavafaceInput = ByteBuffer.allocateDirect(1 * 112 * 112 * 3 * 4).apply { order(ByteOrder.nativeOrder()) }
    private val cavafaceOutput = Array(1) { FloatArray(512) }

    @Synchronized
    fun extractCavaFaceEmbedding(bitmap: Bitmap): FloatArray? {
        val backend = cavafaceBackend ?: return null
        val scaled = if (bitmap.width == 112 && bitmap.height == 112) bitmap else Bitmap.createScaledBitmap(bitmap, 112, 112, true)
        val pixels = IntArray(112 * 112)
        scaled.getPixels(pixels, 0, 112, 0, 0, 112, 112)

        cavafaceInput.rewind()
        for (p in pixels) {
            val r = ((p shr 16) and 0xFF) / 255.0f
            val g = ((p shr 8) and 0xFF) / 255.0f
            val b = (p and 0xFF) / 255.0f
            cavafaceInput.putFloat(r)
            cavafaceInput.putFloat(g)
            cavafaceInput.putFloat(b)
        }
        cavafaceInput.rewind()

        backend.run(cavafaceInput, cavafaceOutput)
        if (scaled != bitmap && !scaled.isRecycled) scaled.recycle()

        // L2 Normalization
        val raw = cavafaceOutput[0]
        var sumSq = 0.0
        for (v in raw) sumSq += (v * v)
        val norm = kotlin.math.sqrt(sumSq.coerceAtLeast(1e-12)).toFloat()
        val normalized = FloatArray(512)
        for (i in 0 until 512) normalized[i] = raw[i] / norm
        return normalized
    }

    // 2. FaceMap 3DMM (128x128 RGB -> 265 3D Morphable Shape Parameters)
    private val facemapInput = ByteBuffer.allocateDirect(1 * 128 * 128 * 3 * 4).apply { order(ByteOrder.nativeOrder()) }
    private val facemapOutput = Array(1) { FloatArray(265) }

    @Synchronized
    fun estimate3dFaceMap(bitmap: Bitmap): Qualcomm3DMMOutput? {
        val backend = facemapBackend ?: return null
        val scaled = if (bitmap.width == 128 && bitmap.height == 128) bitmap else Bitmap.createScaledBitmap(bitmap, 128, 128, true)
        val pixels = IntArray(128 * 128)
        scaled.getPixels(pixels, 0, 128, 0, 0, 128, 128)

        facemapInput.rewind()
        for (p in pixels) {
            facemapInput.putFloat(((p shr 16) and 0xFF) / 255.0f)
            facemapInput.putFloat(((p shr 8) and 0xFF) / 255.0f)
            facemapInput.putFloat((p and 0xFF) / 255.0f)
        }
        facemapInput.rewind()

        val report = backend.run(facemapInput, facemapOutput)
        if (scaled != bitmap && !scaled.isRecycled) scaled.recycle()

        val params = facemapOutput[0]
        var mean = 0.0f
        for (i in 0 until 50) mean += params[i]
        mean /= 50f
        var variance = 0.0f
        for (i in 0 until 50) {
            val d = params[i] - mean
            variance += d * d
        }
        variance /= 50f

        return Qualcomm3DMMOutput(
            parameters265 = params.clone(),
            depthVariance = variance,
            isTrue3DSurface = variance > 0.005f,
            executionMs = report.executionLatencyMs
        )
    }

    // 3. FaceAttribNet (128x128 RGB -> 5 Attribute Probabilities)
    private val attribInput = ByteBuffer.allocateDirect(1 * 128 * 128 * 3 * 4).apply { order(ByteOrder.nativeOrder()) }
    private val attribOutput = Array(1) { FloatArray(5) }

    @Synchronized
    fun classifyAttributes(bitmap: Bitmap): QualcommAttributesOutput? {
        val backend = attribBackend ?: return null
        val scaled = if (bitmap.width == 128 && bitmap.height == 128) bitmap else Bitmap.createScaledBitmap(bitmap, 128, 128, true)
        val pixels = IntArray(128 * 128)
        scaled.getPixels(pixels, 0, 128, 0, 0, 128, 128)

        attribInput.rewind()
        for (p in pixels) {
            attribInput.putFloat(((p shr 16) and 0xFF) / 255.0f)
            attribInput.putFloat(((p shr 8) and 0xFF) / 255.0f)
            attribInput.putFloat((p and 0xFF) / 255.0f)
        }
        attribInput.rewind()

        val report = backend.run(attribInput, attribOutput)
        if (scaled != bitmap && !scaled.isRecycled) scaled.recycle()

        val probs = attribOutput[0]
        return QualcommAttributesOutput(
            smileScore = probs.getOrElse(0) { 0f }.coerceIn(0f, 1f),
            eyeglassesScore = probs.getOrElse(1) { 0f }.coerceIn(0f, 1f),
            rawProbabilities = probs.clone(),
            executionMs = report.executionLatencyMs
        )
    }

    // 4. EyeGaze (96x160 Grayscale Cropped Eye -> Pitch, Yaw)
    private val eyeInput = ByteBuffer.allocateDirect(1 * 96 * 160 * 1 * 4).apply { order(ByteOrder.nativeOrder()) }
    private val eyeOutputGaze = Array(1) { FloatArray(2) }
    private val eyeOutputLandmarks = Array(1) { Array(34) { FloatArray(2) } }

    @Synchronized
    fun estimateEyeGaze(eyeCrop: Bitmap): QualcommEyeGazeOutput? {
        val backend = eyegazeBackend ?: return null
        val scaled = if (eyeCrop.width == 160 && eyeCrop.height == 96) eyeCrop else Bitmap.createScaledBitmap(eyeCrop, 160, 96, true)
        val pixels = IntArray(160 * 96)
        scaled.getPixels(pixels, 0, 160, 0, 0, 160, 96)

        eyeInput.rewind()
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val luma = ((r * 299 + g * 587 + b * 114) / 1000) / 255.0f
            eyeInput.putFloat(luma)
        }
        eyeInput.rewind()

        val outputs = mutableMapOf<Int, Any>(
            0 to eyeOutputGaze,
            1 to eyeOutputLandmarks
        )
        val t0 = System.nanoTime()
        backend.interpreter?.runForMultipleInputsOutputs(arrayOf(eyeInput), outputs)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0f
        if (scaled != eyeCrop && !scaled.isRecycled) scaled.recycle()

        val pitch = eyeOutputGaze[0][0]
        val yaw = eyeOutputGaze[0][1]
        val isAttentive = kotlin.math.abs(pitch) < 18.0f && kotlin.math.abs(yaw) < 22.0f

        return QualcommEyeGazeOutput(
            pitch = pitch,
            yaw = yaw,
            isGazeAttentive = isAttentive,
            eyeLandmarks34 = eyeOutputLandmarks[0],
            executionMs = elapsedMs
        )
    }

    // 5. MediaPipe Face (468 3D XYZ Keypoints)
    private val meshInput = ByteBuffer.allocateDirect(1 * 192 * 192 * 3 * 4).apply { order(ByteOrder.nativeOrder()) }
    private val meshScoreOut = FloatArray(1)
    private val meshLandmarksOut = Array(1) { Array(468) { FloatArray(3) } }

    @Synchronized
    fun extractDenseMesh(faceBitmap: Bitmap): QualcommMeshOutput? {
        val backend = mediapipeBackend ?: return null
        val scaled = if (faceBitmap.width == 192 && faceBitmap.height == 192) faceBitmap else Bitmap.createScaledBitmap(faceBitmap, 192, 192, true)
        val pixels = IntArray(192 * 192)
        scaled.getPixels(pixels, 0, 192, 0, 0, 192, 192)

        meshInput.rewind()
        for (p in pixels) {
            meshInput.putFloat(((p shr 16) and 0xFF) / 255.0f)
            meshInput.putFloat(((p shr 8) and 0xFF) / 255.0f)
            meshInput.putFloat((p and 0xFF) / 255.0f)
        }
        meshInput.rewind()

        val outputs = mutableMapOf<Int, Any>(
            0 to meshScoreOut,
            1 to meshLandmarksOut
        )
        val t0 = System.nanoTime()
        backend.interpreter?.runForMultipleInputsOutputs(arrayOf(meshInput), outputs)
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000.0f
        if (scaled != faceBitmap && !scaled.isRecycled) scaled.recycle()

        return QualcommMeshOutput(
            landmarks468 = meshLandmarksOut[0],
            faceScore = meshScoreOut.getOrElse(0) { 1.0f },
            executionMs = elapsedMs
        )
    }

    override fun close() {
        cavafaceBackend?.close()
        facemapBackend?.close()
        attribBackend?.close()
        eyegazeBackend?.close()
        hrnetBackend?.close()
        mediapipeBackend?.close()
    }
}
