package com.omniface.ai.face

import android.content.Context
import android.graphics.Bitmap
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.SecurityTier

class FaceEngine(val context: Context) {

    val embeddingEngine = FaceEmbeddingEngine(context)
    val tracker = FaceTracker()

    fun extractEmbedding(faceBitmap: Bitmap): FloatArray {
        return embeddingEngine.extractEmbedding(faceBitmap)
    }

    fun extractEmbeddingWithFlipAugmentation(faceBitmap: Bitmap): FloatArray {
        return embeddingEngine.extractEmbeddingWithFlipAugmentation(faceBitmap)
    }

    suspend fun matchFace(
        queryEmbedding: FloatArray,
        knownTemplates: List<FaceTemplateEntity>,
        studentMap: Map<String, String>,
        securityTier: SecurityTier
    ): MatchResult {
        return embeddingEngine.standardEngine.matchFace(queryEmbedding, knownTemplates, studentMap, securityTier)
    }
}
