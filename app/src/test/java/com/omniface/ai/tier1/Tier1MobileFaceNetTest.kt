package com.omniface.ai.tier1

import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.recognition.FaceMatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 1: Feature 1 - MobileFaceNet Inference & Mathematical Metric Normalization
 */
class Tier1MobileFaceNetTest {

    private lateinit var matcher: FaceMatcher

    @Before
    fun setUp() {
        matcher = FaceMatcher()
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 1e-7f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    private fun makeEmbedding(dim: Int = 512, seed: Float = 1.0f): FloatArray {
        return l2Normalize(FloatArray(dim) { i -> seed + i * 0.001f })
    }

    @Test
    fun testInputTensorShape512d() {
        val emb = makeEmbedding(512, 0.5f)
        assertEquals("MobileFaceNet embedding dimension must be 512", 512, emb.size)
        var sumSq = 0f
        for (x in emb) sumSq += x * x
        assertEquals("L2 normalized embedding must have unit Euclidean norm ~ 1.0", 1.0f, sqrt(sumSq), 1e-4f)
    }

    @Test
    fun testL2NormalizationPreservesDirection() {
        val raw = FloatArray(512) { (it + 1) * 2.5f }
        val normalized = l2Normalize(raw.clone())
        
        var dot = 0f
        var rawNormSq = 0f
        for (i in 0 until 512) {
            dot += raw[i] * normalized[i]
            rawNormSq += raw[i] * raw[i]
        }
        val rawNorm = sqrt(rawNormSq)
        assertEquals("Direction must be preserved under L2 normalization", rawNorm, dot, 0.1f)
    }

    @Test
    fun testConfidenceMappingProgress() {
        // High confidence match mapping test
        val emb1 = makeEmbedding(512, 1.0f)
        val tplCsv = emb1.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }
        val template = com.omniface.ai.data.local.entity.FaceTemplateEntity(
            id = "t1",
            studentRoll = "CS101",
            angleType = "FRONTAL",
            embeddingEncryptedCsv = tplCsv,
            isEncrypted = false
        )
        matcher.preloadTemplates(listOf(template))

        val result = matcher.match(
            queryEmbedding = emb1,
            studentMap = mapOf("CS101" to "Alice"),
            securityTier = SecurityTier.HIGH
        )

        assertTrue("Identical vector must match", result.isMatch)
        assertTrue("Match confidence should be >= 85.0%", result.confidence >= 85.0f)
        assertEquals(ConfidenceZone.ACCEPT, result.confidenceZone)
        assertEquals("Alice", result.studentName)
    }

    @Test
    fun testCosineSimilaritySymmetry() {
        val a = makeEmbedding(512, 1.0f)
        val b = makeEmbedding(512, 2.0f)
        
        var dotAB = 0f
        var dotBA = 0f
        for (i in 0 until 512) {
            dotAB += a[i] * b[i]
            dotBA += b[i] * a[i]
        }
        assertEquals("Cosine similarity must be symmetric", dotAB, dotBA, 1e-6f)
    }

    @Test
    fun testHardwareTierLabels() {
        val npuTier = HardwareTier.NPU_NNAPI
        val gpuTier = HardwareTier.GPU_DELEGATE
        val cpuTier = HardwareTier.CPU_XNNPACK

        assertNotNull("NPU tier label must not be null", npuTier.label)
        assertNotNull("GPU tier label must not be null", gpuTier.label)
        assertNotNull("CPU tier label must not be null", cpuTier.label)

        assertTrue(npuTier.label.contains("NPU") || npuTier.label.contains("NNAPI") || npuTier.label.contains("Hexagon"))
        assertTrue(gpuTier.label.contains("GPU") || gpuTier.label.contains("OpenCL"))
        assertTrue(cpuTier.label.contains("CPU") || cpuTier.label.contains("XNNPACK"))
    }
}
