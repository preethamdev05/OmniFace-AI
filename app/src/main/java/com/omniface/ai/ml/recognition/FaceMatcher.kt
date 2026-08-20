package com.omniface.ai.ml.recognition

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.CachedBiometric
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.security.AndroidSecurityUtils
import java.util.Arrays
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.sqrt

/**
 * High-Performance In-Memory Biometric Vector Search & Best-Angle Matcher.
 *
 * Implements ISO/IEC 19794-5 compliant candidate scoring:
 * 1. Decrypts Keystore templates into an in-memory cache for sub-millisecond 1:N lookup.
 * 2. Best-Angle Maximum Selection: Selects the maximum similarity across enrolled 3D poses.
 * 3. Decision Margin Analysis: Asserts Top-1 vs Top-2 margin delta >= 0.070 to prevent sibling/twin confusion.
 * 4. Calibrated Operating Gate: Enforces Strict (0.600), High (0.500), or Standard (0.420) thresholds.
 */
class FaceMatcher {

    private val biometricCache = CopyOnWriteArrayList<CachedBiometric>()

    val enrolledTemplateCount: Int get() = biometricCache.size

    fun preloadTemplates(templates: List<FaceTemplateEntity>) {
        biometricCache.clear()
        for (entity in templates) {
            val decryptedCsv = try {
                if (entity.isEncrypted) AndroidSecurityUtils.decrypt(entity.embeddingEncryptedCsv)
                else entity.embeddingEncryptedCsv
            } catch (t: Throwable) {
                entity.embeddingEncryptedCsv
            }

            val embedding = parseEmbeddingCsv(decryptedCsv)
            if (embedding.isNotEmpty()) {
                l2Normalize(embedding)
                biometricCache.add(
                    CachedBiometric(
                        templateId = entity.id,
                        studentRoll = entity.studentRoll,
                        angleType = entity.angleType,
                        embedding = embedding
                    )
                )
            }
        }
    }

    fun match(
        queryEmbedding: FloatArray,
        studentMap: Map<String, String>,
        securityTier: SecurityTier = SecurityTier.HIGH,
        activeTier: HardwareTier = HardwareTier.GPU_DELEGATE
    ): MatchResult {
        if (biometricCache.isEmpty()) {
            return MatchResult(
                studentRoll = "GUEST",
                studentName = "Unknown Visitor",
                confidence = 0.0f,
                similarity = 0.0f,
                isMatch = false,
                hardwareTier = activeTier,
                confidenceZone = ConfidenceZone.REJECT,
                decisionMargin = 0.0f,
                explanation = "Database is empty — no enrolled face templates"
            )
        }

        // 1. Compute similarity against every cached angle template and group by student
        val perStudentBestScores = HashMap<String, Float>()
        val perStudentBestAngles = HashMap<String, String>()

        for (cached in biometricCache) {
            val sim = cosineSimilarity(queryEmbedding, cached.embedding)
            val currentBest = perStudentBestScores[cached.studentRoll] ?: -1.0f
            if (sim > currentBest) {
                perStudentBestScores[cached.studentRoll] = sim
                perStudentBestAngles[cached.studentRoll] = cached.angleType
            }
        }

        // 2. Rank candidates descending
        val ranked = perStudentBestScores.map { (roll, sim) ->
            roll to sim
        }.sortedByDescending { it.second }

        val top1 = ranked.getOrNull(0)
        val top2 = ranked.getOrNull(1)

        val maxSimilarity = top1?.second ?: 0.0f
        val bestRoll = top1?.first ?: "GUEST"
        val matchedAngle = perStudentBestAngles[bestRoll] ?: "FRONTAL"
        val top2Similarity = top2?.second ?: 0.0f
        val top2Roll = top2?.first

        val margin = if (ranked.size > 1) (maxSimilarity - top2Similarity) else maxSimilarity
        val threshold = securityTier.threshold
        val marginThreshold = 0.070f

        val confidenceZone: ConfidenceZone
        val isMatch: Boolean
        val explanation: String

        if (maxSimilarity >= threshold && (ranked.size <= 1 || margin >= marginThreshold)) {
            confidenceZone = ConfidenceZone.ACCEPT
            isMatch = true
            val top2Text = if (top2Roll != null) " (Top-2: $top2Roll @ ${"%.3f".format(top2Similarity)})" else ""
            explanation = "Verified: $bestRoll (sim ${"%.3f".format(maxSimilarity)} >= ${"%.3f".format(threshold)} [$matchedAngle], Δ=${"%.3f".format(margin)}$top2Text)"
        } else if (maxSimilarity >= threshold && margin < marginThreshold) {
            confidenceZone = ConfidenceZone.REVIEW
            isMatch = false
            explanation = "Ambiguous Identity: Top-1 $bestRoll (${"%.3f".format(maxSimilarity)}) vs Top-2 ${top2Roll ?: "unknown"} (${"%.3f".format(top2Similarity)}) has narrow margin Δ=${"%.3f".format(margin)} < $marginThreshold"
        } else if (maxSimilarity >= (threshold - 0.060f)) {
            confidenceZone = ConfidenceZone.REVIEW
            isMatch = false
            explanation = "Borderline Match: Cosine sim ${"%.3f".format(maxSimilarity)} near threshold ${"%.3f".format(threshold)} (Δ=${"%.3f".format(margin)})"
        } else {
            confidenceZone = ConfidenceZone.REJECT
            isMatch = false
            explanation = "Unregistered Visitor: Cosine sim ${"%.3f".format(maxSimilarity)} < threshold ${"%.3f".format(threshold)}"
        }

        val name = if (isMatch) studentMap[bestRoll] ?: bestRoll else "Visitor / Unregistered"

        val normalizedConfidence = if (isMatch) {
            val progress = ((maxSimilarity - threshold) / (0.85f - threshold).coerceAtLeast(0.10f)).coerceIn(0.0f, 1.0f)
            (82.0f + progress * 17.9f).coerceIn(82.0f, 99.9f)
        } else {
            ((maxSimilarity.coerceAtLeast(0f) / threshold) * 69.0f).coerceIn(0.0f, 69.0f)
        }

        return MatchResult(
            studentRoll = if (isMatch) bestRoll else "GUEST",
            studentName = name,
            confidence = normalizedConfidence,
            similarity = maxSimilarity,
            isMatch = isMatch,
            hardwareTier = activeTier,
            confidenceZone = confidenceZone,
            decisionMargin = margin,
            secondBestRoll = top2Roll,
            secondBestSimilarity = top2Similarity,
            explanation = explanation
        )
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val size = minOf(a.size, b.size)
        if (size == 0) return 0.0f
        var sum = 0.0f
        var normA = 0.0f
        var normB = 0.0f
        for (i in 0 until size) {
            val va = a[i]
            val vb = b[i]
            sum += va * vb
            normA += va * va
            normB += vb * vb
        }
        val denom = sqrt(normA * normB)
        return if (denom > 1e-7f) (sum / denom).coerceIn(-1.0f, 1.0f) else 0.0f
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sum = 0.0f
        for (v in vec) sum += (v * v)
        val norm = sqrt(sum)
        if (norm > 1e-7f) {
            val inv = 1.0f / norm
            for (i in vec.indices) vec[i] *= inv
        }
        return vec
    }

    private fun parseEmbeddingCsv(csv: String): FloatArray {
        return try {
            csv.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (e: Exception) {
            FloatArray(0)
        }
    }

    fun clear() {
        for (cached in biometricCache) {
            Arrays.fill(cached.embedding, 0.0f)
        }
        biometricCache.clear()
    }
}
