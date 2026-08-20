package com.omniface.ai.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import com.omniface.ai.ml.FaceRecognitionEngine
import com.omniface.ai.qualcomm.QualcommEngine
import com.omniface.ai.qualcomm.SnapdragonDetector
import com.omniface.ai.qualcomm.UnifiedQualcommEngine

class FaceEmbeddingEngine(val context: Context) {

    val unifiedQualcommEngine: UnifiedQualcommEngine? = if (SnapdragonDetector.isQualcommSnapdragon) {
        try {
            val eng = UnifiedQualcommEngine(context)
            if (eng.isReady) eng else null
        } catch (_: Throwable) { null }
    } else null

    val qualcommEngine: QualcommEngine? = if (SnapdragonDetector.isQualcommSnapdragon) {
        try { QualcommEngine(context) } catch (_: Throwable) { null }
    } else null

    val standardEngine: FaceRecognitionEngine by lazy { FaceRecognitionEngine(context) }

    val activeBackboneName: String
        get() = when {
            unifiedQualcommEngine?.isReady == true -> "Qualcomm Unified NPU Engine (${unifiedQualcommEngine.hardwareLabel})"
            qualcommEngine?.isCavafaceReady == true -> "Qualcomm CavaFace IR-SE-100 HD (65.5M)"
            else -> "MobileFaceNet 512-D (NPU/GPU Delegate)"
        }

    val isQualcommCavaFaceActive: Boolean
        get() = (unifiedQualcommEngine?.isReady == true) || (qualcommEngine?.isCavafaceReady == true)

    fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        // 1. If on Qualcomm Snapdragon and Unified NPU Engine is ready -> use Unified Engine
        if (unifiedQualcommEngine?.isReady == true) {
            val res = unifiedQualcommEngine.executeUnifiedInference(faceBitmap)
            if (res != null) return res.embedding
        }

        // 2. If CavaFace is ready -> use CavaFace
        if (qualcommEngine?.isCavafaceReady == true) {
            val emb = qualcommEngine.extractCavaFaceEmbedding(faceBitmap)
            if (emb != null) return emb
        }

        // 3. Standard multi-tier engine
        return standardEngine.extractEmbedding(faceBitmap)
    }

    fun extractEmbeddingWithFlipAugmentation(faceBitmap: Bitmap): FloatArray {
        if (unifiedQualcommEngine?.isReady == true) {
            val emb1 = unifiedQualcommEngine.executeUnifiedInference(faceBitmap)?.embedding
            val matrix = Matrix().apply { preScale(-1f, 1f) }
            val flipped = Bitmap.createBitmap(faceBitmap, 0, 0, faceBitmap.width, faceBitmap.height, matrix, false)
            val emb2 = unifiedQualcommEngine.executeUnifiedInference(flipped)?.embedding
            flipped.recycle()

            if (emb1 != null && emb2 != null) {
                val fused = FloatArray(512)
                for (i in 0 until 512) fused[i] = (emb1[i] + emb2[i]) * 0.5f
                var sumSq = 0.0
                for (v in fused) sumSq += (v * v)
                val norm = kotlin.math.sqrt(sumSq.coerceAtLeast(1e-12)).toFloat()
                for (i in 0 until 512) fused[i] /= norm
                return fused
            } else if (emb1 != null) {
                return emb1
            }
        }

        if (qualcommEngine?.isCavafaceReady == true) {
            val emb1 = qualcommEngine.extractCavaFaceEmbedding(faceBitmap)
            val matrix = Matrix().apply { preScale(-1f, 1f) }
            val flipped = Bitmap.createBitmap(faceBitmap, 0, 0, faceBitmap.width, faceBitmap.height, matrix, false)
            val emb2 = qualcommEngine.extractCavaFaceEmbedding(flipped)
            flipped.recycle()

            if (emb1 != null && emb2 != null) {
                val fused = FloatArray(512)
                for (i in 0 until 512) fused[i] = (emb1[i] + emb2[i]) * 0.5f
                // L2 Normalize
                var sumSq = 0.0
                for (v in fused) sumSq += (v * v)
                val norm = kotlin.math.sqrt(sumSq.coerceAtLeast(1e-12)).toFloat()
                for (i in 0 until 512) fused[i] /= norm
                return fused
            } else if (emb1 != null) {
                return emb1
            }
        }
        return standardEngine.extractEmbeddingWithFlipAugmentation(faceBitmap)
    }
}
