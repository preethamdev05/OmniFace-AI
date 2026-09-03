package com.omniface.ai.ml

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.recognition.BiometricDeduplicationEngine
import com.omniface.ai.ml.recognition.DuplicateCheckResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class BiometricDeduplicationEngineTest {

    private fun createUnitVector(dim: Int = 512, seed: Float = 1.0f): FloatArray {
        val arr = FloatArray(dim) { (it + 1) * seed * 0.001f }
        var sumSq = 0f
        for (v in arr) sumSq += v * v
        val norm = sqrt(sumSq)
        for (i in arr.indices) arr[i] /= norm
        return arr
    }

    @Test
    fun testCosineSimilarity_identicalVectors_returnsOne() {
        val v1 = createUnitVector(512, 1.0f)
        val v2 = v1.clone()
        val sim = BiometricDeduplicationEngine.computeCosineSimilarity(v1, v2)
        assertEquals(1.0f, sim, 1e-4f)
    }

    @Test
    fun testCosineSimilarity_orthogonalVectors_returnsZero() {
        val v1 = FloatArray(512) { if (it < 256) 1.0f else 0.0f }
        val v2 = FloatArray(512) { if (it >= 256) 1.0f else 0.0f }
        var n1 = 0f; var n2 = 0f
        for (x in v1) n1 += x * x
        for (x in v2) n2 += x * x
        for (i in v1.indices) v1[i] /= sqrt(n1)
        for (i in v2.indices) v2[i] /= sqrt(n2)

        val sim = BiometricDeduplicationEngine.computeCosineSimilarity(v1, v2)
        assertEquals(0.0f, sim, 1e-4f)
    }

    @Test
    fun testCheckEnrollmentDuplicate_identicalFace_flagsDuplicate() = runBlocking {
        val baseVector = createUnitVector(512, 2.0f)
        val csv = baseVector.joinToString(",")

        val existingTemplate = FaceTemplateEntity(
            id = "tpl-1",
            studentRoll = "CS101",
            angleType = "FRONTAL",
            embeddingEncryptedCsv = csv,
            isEncrypted = false,
            qualityScore = 95f,
            sharpnessScore = 95f,
            lightingScore = 95f,
            consistencyScore = 95f
        )

        val studentMap = mapOf("CS101" to "John Doe")

        // Candidate with near identical embedding (sim > 0.95)
        val candidateVector = baseVector.clone()
        candidateVector[0] += 0.001f
        var s = 0f
        for (x in candidateVector) s += x * x
        for (i in candidateVector.indices) candidateVector[i] /= sqrt(s)

        val result = BiometricDeduplicationEngine.checkEnrollmentDuplicate(
            candidateEmbedding = candidateVector,
            existingTemplates = listOf(existingTemplate),
            studentMap = studentMap
        )

        assertTrue("Expected DuplicateFound result", result is DuplicateCheckResult.DuplicateFound)
        val dup = result as DuplicateCheckResult.DuplicateFound
        assertEquals("CS101", dup.matchedRoll)
        assertEquals("John Doe", dup.matchedName)
        assertTrue(dup.similarityScore >= 0.84f)
    }

    @Test
    fun testCheckEnrollmentDuplicate_differentPerson_returnsClean() = runBlocking {
        val v1 = createUnitVector(512, 1.0f)
        val v2 = createUnitVector(512, -3.0f)
        val csv1 = v1.joinToString(",")

        val existingTemplate = FaceTemplateEntity(
            id = "tpl-1",
            studentRoll = "CS101",
            angleType = "FRONTAL",
            embeddingEncryptedCsv = csv1,
            isEncrypted = false,
            qualityScore = 95f,
            sharpnessScore = 95f,
            lightingScore = 95f,
            consistencyScore = 95f
        )

        val studentMap = mapOf("CS101" to "John Doe")

        val result = BiometricDeduplicationEngine.checkEnrollmentDuplicate(
            candidateEmbedding = v2,
            existingTemplates = listOf(existingTemplate),
            studentMap = studentMap
        )

        assertTrue("Expected Clean result for different identities", result is DuplicateCheckResult.Clean)
    }

    @Test
    fun testScanDatabaseForDuplicates_findsDuplicateClusters() = runBlocking {
        val personA_Vector = createUnitVector(512, 1.0f)
        val personA_Clone = personA_Vector.clone() // Same person registered under different roll CS102
        val personB_Vector = createUnitVector(512, -5.0f) // Completely different person CS103

        val templates = listOf(
            FaceTemplateEntity(
                id = "tpl-1",
                studentRoll = "CS101",
                angleType = "FRONTAL",
                embeddingEncryptedCsv = personA_Vector.joinToString(","),
                isEncrypted = false,
                qualityScore = 90f,
                sharpnessScore = 90f,
                lightingScore = 90f,
                consistencyScore = 90f
            ),
            FaceTemplateEntity(
                id = "tpl-2",
                studentRoll = "CS102",
                angleType = "FRONTAL",
                embeddingEncryptedCsv = personA_Clone.joinToString(","),
                isEncrypted = false,
                qualityScore = 90f,
                sharpnessScore = 90f,
                lightingScore = 90f,
                consistencyScore = 90f
            ),
            FaceTemplateEntity(
                id = "tpl-3",
                studentRoll = "CS103",
                angleType = "FRONTAL",
                embeddingEncryptedCsv = personB_Vector.joinToString(","),
                isEncrypted = false,
                qualityScore = 90f,
                sharpnessScore = 90f,
                lightingScore = 90f,
                consistencyScore = 90f
            )
        )

        val studentMap = mapOf(
            "CS101" to "Alice Smith",
            "CS102" to "Alice Duplicate",
            "CS103" to "Bob Jones"
        )

        val clusters = BiometricDeduplicationEngine.scanDatabaseForDuplicates(
            templates = templates,
            studentMap = studentMap,
            threshold = 0.80f
        )

        assertEquals("Expected 1 collision cluster", 1, clusters.size)
        val cluster = clusters[0]
        assertEquals("CS101", cluster.primaryRoll)
        assertEquals(1, cluster.duplicateCandidates.size)
        assertEquals("CS102", cluster.duplicateCandidates[0].rollNumber)
        assertTrue(cluster.duplicateCandidates[0].similarityScore >= 0.99f)
    }
}
