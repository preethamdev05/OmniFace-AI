package com.omniface.ai.testutil

import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object BiometricTestFixtures {
    fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0.0) { acc, x -> acc + x * x }).toFloat()
        return if (norm > 0f) FloatArray(v.size) { v[it] / norm } else v.copyOf()
    }

    fun makeEmbedding(seed: Float, dim: Int = 512): FloatArray {
        val raw = FloatArray(dim) { i -> sin((i + 1) * seed) }
        return l2Normalize(raw)
    }

    fun makeLinearEmbedding(seed: Float, dim: Int = 512): FloatArray {
        val v = FloatArray(dim) { i -> seed + i * 0.001f }
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 1e-7f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    fun generateSyntheticEmbedding(seed: Float, dim: Int = 512): FloatArray {
        val emb = FloatArray(dim) { i ->
            (sin(seed + i * 0.015f) * 0.5f + cos(seed * 0.5f + i * 0.007f) * 0.5f)
        }
        return l2Normalize(emb)
    }

    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var nA = 0f
        var nB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            nA += a[i] * a[i]
            nB += b[i] * b[i]
        }
        val denom = sqrt(nA) * sqrt(nB)
        return if (denom > 1e-12f) dot / denom else 0f
    }

    fun toCsv(v: FloatArray): String =
        v.joinToString(",") { "%.6f".format(Locale.US, it) }
}
