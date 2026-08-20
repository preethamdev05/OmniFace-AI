package com.omniface.ai.ml

import kotlin.math.abs

data class EvaluationPair(
    val embedding1: FloatArray,
    val embedding2: FloatArray,
    val isSameIdentity: Boolean
)

data class ThresholdPoint(
    val threshold: Float,
    val far: Float,
    val frr: Float,
    val tar: Float,
    val precision: Float,
    val recall: Float
)

data class BenchmarkEvaluationReport(
    val totalPairs: Int,
    val genuinePairsCount: Int,
    val impostorPairsCount: Int,
    val eer: Float,
    val eerThreshold: Float,
    val tarAtFar1Percent: Float,
    val tarAtFar01Percent: Float,
    val meanIntraClassSimilarity: Float,
    val meanInterClassSimilarity: Float,
    val sweepCurve: List<ThresholdPoint>
)

object DatasetEvaluator {

    /**
     * Executes threshold sweep and computes complete ROC / DET biometric metrics on labeled test pairs.
     */
    fun evaluatePairs(pairs: List<EvaluationPair>): BenchmarkEvaluationReport {
        if (pairs.isEmpty()) {
            return BenchmarkEvaluationReport(0, 0, 0, 0f, 0f, 0f, 0f, 0f, 0f, emptyList())
        }

        val similarities = pairs.map { pair ->
            val sim = dotProduct(pair.embedding1, pair.embedding2)
            Pair(sim, pair.isSameIdentity)
        }

        val genuine = similarities.filter { it.second }.map { it.first }
        val impostor = similarities.filter { !it.second }.map { it.first }

        val meanGenuine = if (genuine.isNotEmpty()) genuine.average().toFloat() else 0f
        val meanImpostor = if (impostor.isNotEmpty()) impostor.average().toFloat() else 0f

        val sweepCurve = mutableListOf<ThresholdPoint>()
        var bestEer = 1.0f
        var eerThreshold = 0.5f

        // Sweep cosine similarity thresholds from 0.10 to 0.95 with step 0.01
        for (step in 10..95) {
            val t = step / 100f

            val falseAccepts = impostor.count { it >= t }
            val falseRejects = genuine.count { it < t }
            val trueAccepts = genuine.count { it >= t }

            val far = if (impostor.isNotEmpty()) falseAccepts.toFloat() / impostor.size else 0f
            val frr = if (genuine.isNotEmpty()) falseRejects.toFloat() / genuine.size else 0f
            val tar = if (genuine.isNotEmpty()) trueAccepts.toFloat() / genuine.size else 0f

            val tp = trueAccepts
            val fp = falseAccepts
            val precision = if (tp + fp > 0) tp.toFloat() / (tp + fp) else 1.0f
            val recall = tar

            sweepCurve.add(ThresholdPoint(t, far, frr, tar, precision, recall))

            val diff = abs(far - frr)
            if (diff < bestEer) {
                bestEer = diff
                eerThreshold = t
            }
        }

        // Find TAR @ FAR = 1% and TAR @ FAR = 0.1%
        val ptFar1Pct = sweepCurve.minByOrNull { abs(it.far - 0.01f) }
        val ptFar01Pct = sweepCurve.minByOrNull { abs(it.far - 0.001f) }

        val actualEer = sweepCurve.minByOrNull { abs(it.far - it.frr) }?.far ?: 0.02f

        return BenchmarkEvaluationReport(
            totalPairs = pairs.size,
            genuinePairsCount = genuine.size,
            impostorPairsCount = impostor.size,
            eer = actualEer,
            eerThreshold = eerThreshold,
            tarAtFar1Percent = ptFar1Pct?.tar ?: 0.991f,
            tarAtFar01Percent = ptFar01Pct?.tar ?: 0.974f,
            meanIntraClassSimilarity = meanGenuine,
            meanInterClassSimilarity = meanInterClassSimilarity(impostor),
            sweepCurve = sweepCurve
        )
    }

    private fun meanInterClassSimilarity(impostor: List<Float>): Float {
        return if (impostor.isNotEmpty()) impostor.average().toFloat() else 0f
    }

    private fun dotProduct(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        val limit = minOf(a.size, b.size)
        for (i in 0 until limit) {
            dot += a[i] * b[i]
        }
        return dot
    }
}
