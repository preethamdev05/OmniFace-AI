package com.omniface.ai.ml.recognition

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Result of checking an enrollment face for duplicate registration conflicts.
 */
sealed class DuplicateCheckResult {
    object Clean : DuplicateCheckResult()
    data class DuplicateFound(
        val matchedRoll: String,
        val matchedName: String,
        val similarityScore: Float,
        val matchedAngle: String
    ) : DuplicateCheckResult()
}

/**
 * A group of detected duplicate or near-duplicate enrolled identities in the database.
 */
data class DuplicateCluster(
    val primaryRoll: String,
    val primaryName: String,
    val duplicateCandidates: List<DuplicateCandidate>
)

data class DuplicateCandidate(
    val rollNumber: String,
    val fullName: String,
    val similarityScore: Float,
    val matchedAngle: String
)

/**
 * High-performance Biometric Deduplication Engine for OmniFace AI.
 *
 * Prevents multiple registrations of the same individual under different roll numbers/names,
 * and scans the existing database to discover identity collisions with full AES-256 hardware decryption.
 */
object BiometricDeduplicationEngine {

    const val ENROLLMENT_DUPLICATE_THRESHOLD = 0.84f // Cosine similarity >= 84% is flagged as duplicate
    const val DATABASE_SCAN_THRESHOLD = 0.80f        // Scan threshold for finding potential duplicates

    /**
     * Calculates cosine similarity between two FloatArray embeddings.
     */
    fun computeCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        val size = minOf(v1.size, v2.size)
        if (size == 0) return 0f
        var sum0 = 0f
        var sum1 = 0f
        var sum2 = 0f
        var sum3 = 0f
        var normA = 0f
        var normB = 0f
        val limit = size - 3
        var i = 0
        while (i < limit) {
            val a0 = v1[i]; val b0 = v2[i]
            val a1 = v1[i + 1]; val b1 = v2[i + 1]
            val a2 = v1[i + 2]; val b2 = v2[i + 2]
            val a3 = v1[i + 3]; val b3 = v2[i + 3]
            sum0 += a0 * b0; normA += a0 * a0; normB += b0 * b0
            sum1 += a1 * b1; normA += a1 * a1; normB += b1 * b1
            sum2 += a2 * b2; normA += a2 * a2; normB += b2 * b2
            sum3 += a3 * b3; normA += a3 * a3; normB += b3 * b3
            i += 4
        }
        var dot = sum0 + sum1 + sum2 + sum3
        while (i < size) {
            val a = v1[i]; val b = v2[i]
            dot += a * b
            normA += a * a
            normB += b * b
            i++
        }
        val denom = sqrt(normA * normB)
        return if (denom > 1e-6f) (dot / denom).coerceIn(-1f, 1f) else 0f
    }

    private fun parseCsv(csv: String): FloatArray {
        return try {
            csv.split(",").map { it.trim().toFloat() }.toFloatArray()
        } catch (_: Exception) {
            FloatArray(0)
        }
    }

    /**
     * Validates a candidate face embedding before enrollment against all existing student templates.
     */
    suspend fun checkEnrollmentDuplicate(
        candidateEmbedding: FloatArray,
        existingTemplates: List<FaceTemplateEntity>,
        studentMap: Map<String, String>,
        threshold: Float = ENROLLMENT_DUPLICATE_THRESHOLD
    ): DuplicateCheckResult = withContext(Dispatchers.Default) {
        var highestMatchRoll: String? = null
        var highestMatchAngle = "FRONTAL"
        var maxSim = 0f

        for (template in existingTemplates) {
            val rawCsv = if (template.isEncrypted) {
                try {
                    AndroidSecurityUtils.decrypt(template.embeddingEncryptedCsv)
                } catch (_: Exception) {
                    ""
                }
            } else {
                template.embeddingEncryptedCsv
            }

            if (rawCsv.isNotBlank()) {
                val vec = parseCsv(rawCsv)
                if (vec.isNotEmpty()) {
                    val sim = computeCosineSimilarity(candidateEmbedding, vec)
                    if (sim > maxSim) {
                        maxSim = sim
                        highestMatchRoll = template.studentRoll
                        highestMatchAngle = template.angleType
                    }
                }
            }
        }

        if (highestMatchRoll != null && maxSim >= threshold) {
            val name = studentMap[highestMatchRoll] ?: highestMatchRoll
            DuplicateCheckResult.DuplicateFound(
                matchedRoll = highestMatchRoll,
                matchedName = name,
                similarityScore = maxSim,
                matchedAngle = highestMatchAngle
            )
        } else {
            DuplicateCheckResult.Clean
        }
    }

    /**
     * Scans the entire database by performing an O(N^2) pairwise similarity matrix search
     * across all decrypted templates, grouping duplicate identities into reviewable clusters.
     */
    suspend fun scanDatabaseForDuplicates(
        templates: List<FaceTemplateEntity>,
        studentMap: Map<String, String>,
        threshold: Float = DATABASE_SCAN_THRESHOLD
    ): List<DuplicateCluster> = withContext(Dispatchers.Default) {
        if (templates.size < 2) return@withContext emptyList()

        // 1. Group templates by student roll and compute/extract representative template
        data class StudentBiometric(
            val roll: String,
            val name: String,
            val angleType: String,
            val embedding: FloatArray
        )

        val decryptedList = mutableListOf<StudentBiometric>()
        for (tpl in templates) {
            val rawCsv = if (tpl.isEncrypted) {
                try {
                    AndroidSecurityUtils.decrypt(tpl.embeddingEncryptedCsv)
                } catch (_: Exception) {
                    ""
                }
            } else {
                tpl.embeddingEncryptedCsv
            }
            if (rawCsv.isNotBlank()) {
                val vec = parseCsv(rawCsv)
                if (vec.isNotEmpty()) {
                    decryptedList.add(
                        StudentBiometric(
                            roll = tpl.studentRoll,
                            name = studentMap[tpl.studentRoll] ?: tpl.studentRoll,
                            angleType = tpl.angleType,
                            embedding = vec
                        )
                    )
                }
            }
        }

        // 2. Perform Pairwise Comparison across distinct student rolls
        val clusters = mutableListOf<DuplicateCluster>()
        val processedRolls = mutableSetOf<String>()

        val groupedByRoll = decryptedList.groupBy { it.roll }
        val distinctRolls = groupedByRoll.keys.toList()

        for (i in distinctRolls.indices) {
            val rollA = distinctRolls[i]
            if (rollA in processedRolls) continue

            val templatesA = groupedByRoll[rollA] ?: continue
            val nameA = templatesA.firstOrNull()?.name ?: rollA
            val duplicatesForA = mutableListOf<DuplicateCandidate>()

            for (j in i + 1 until distinctRolls.size) {
                val rollB = distinctRolls[j]
                if (rollB in processedRolls) continue

                val templatesB = groupedByRoll[rollB] ?: continue
                val nameB = templatesB.firstOrNull()?.name ?: rollB

                // Find max similarity between any angle of A and any angle of B
                var maxPairSim = 0f
                var matchedAngle = "FRONTAL"
                for (tA in templatesA) {
                    for (tB in templatesB) {
                        val sim = computeCosineSimilarity(tA.embedding, tB.embedding)
                        if (sim > maxPairSim) {
                            maxPairSim = sim
                            matchedAngle = "${tA.angleType} ↔ ${tB.angleType}"
                        }
                    }
                }

                if (maxPairSim >= threshold) {
                    duplicatesForA.add(
                        DuplicateCandidate(
                            rollNumber = rollB,
                            fullName = nameB,
                            similarityScore = maxPairSim,
                            matchedAngle = matchedAngle
                        )
                    )
                    processedRolls.add(rollB)
                }
            }

            if (duplicatesForA.isNotEmpty()) {
                processedRolls.add(rollA)
                clusters.add(
                    DuplicateCluster(
                        primaryRoll = rollA,
                        primaryName = nameA,
                        duplicateCandidates = duplicatesForA
                    )
                )
            }
        }

        clusters
    }
}
