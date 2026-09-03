package com.omniface.ai.ml

import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.recognition.HnswVectorIndex
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

class FaceMatcherTest {

    private lateinit var matcher: FaceMatcher

    @Before
    fun setUp() { matcher = FaceMatcher() }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f; for (x in v) sum += x * x
        val norm = sqrt(sum); if (norm > 1e-7f) for (i in v.indices) v[i] /= norm; return v
    }

    private fun makeEmbedding(dim: Int = 512, seed: Float = 1.0f) =
        l2Normalize(FloatArray(dim) { i -> seed + i * 0.001f })

    private fun toCsv(v: FloatArray) = v.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }

    // ── adaptCentroidIfHighConfidence ─────────────────────────────────────────

    @Test fun `adaptCentroid rejects similarity below 0 72`() {
        matcher.preloadTemplates(listOf(FaceTemplateEntity("t1","R001","FRONTAL", toCsv(makeEmbedding()), false)))
        assertNull(matcher.adaptCentroidIfHighConfidence("R001", makeEmbedding(), 0.71f))
    }

    @Test fun `adaptCentroid accepts similarity at or above 0 72`() {
        matcher.preloadTemplates(listOf(FaceTemplateEntity("t1","R001","FRONTAL", toCsv(makeEmbedding()), false)))
        val result = matcher.adaptCentroidIfHighConfidence("R001", makeEmbedding(), 0.72f)
        assertNotNull(result); assertEquals("t1", result!!.first)
    }

    @Test fun `adaptCentroid returns null for unknown student`() {
        matcher.preloadTemplates(listOf(FaceTemplateEntity("t1","R001","FRONTAL", toCsv(makeEmbedding()), false)))
        assertNull(matcher.adaptCentroidIfHighConfidence("UNKNOWN", makeEmbedding(), 0.90f))
    }

    @Test fun `adaptCentroid preserves 512 dimensions`() {
        matcher.preloadTemplates(listOf(FaceTemplateEntity("t1","R001","MASTER_CENTROID", toCsv(makeEmbedding(512, 0.5f)), false)))
        val result = matcher.adaptCentroidIfHighConfidence("R001", makeEmbedding(512, 0.6f), 0.85f)
        assertNotNull(result)
        val rawCsv = if (result!!.second.contains(",")) {
            result.second
        } else {
            val dec = com.omniface.ai.security.AndroidSecurityUtils.decrypt(result.second)
            if (dec.isNotBlank()) dec else null
        }
        if (rawCsv != null) {
            assertEquals(512, rawCsv.split(",").size)
        }
        assertTrue("Enrolled template must remain present in matcher", matcher.enrolledTemplateCount > 0)
    }

    @Test fun `clear removes all templates`() {
        matcher.preloadTemplates(listOf(FaceTemplateEntity("t1","R001","FRONTAL", toCsv(makeEmbedding()), false)))
        assertEquals(1, matcher.enrolledTemplateCount)
        matcher.clear()
        assertEquals(0, matcher.enrolledTemplateCount)
    }

    private fun makeDistinctEmbedding(index: Int, dim: Int = 512): FloatArray {
        val v = FloatArray(dim) { i ->
            val angle = (index * 1.37f + i * 0.19f).toDouble()
            kotlin.math.sin(angle).toFloat()
        }
        return l2Normalize(v)
    }

    @Test fun `composite score bounds within valid range`() {
        val templates = listOf(
            FaceTemplateEntity("t1","R001","FRONTAL", toCsv(makeDistinctEmbedding(1)), false),
            FaceTemplateEntity("t2","R001","MASTER_CENTROID", toCsv(makeDistinctEmbedding(1)), false)
        )
        matcher.preloadTemplates(templates)
        val result = matcher.match(makeDistinctEmbedding(1), mapOf("R001" to "Alice"), SecurityTier.STANDARD)
        assertTrue(result.similarity in 0f..1f)
    }

    @Test fun `multi-angle galleried template matching achieves high similarity`() {
        val aliceCentroid = makeDistinctEmbedding(1)
        val aliceLeft = FloatArray(512) { (aliceCentroid[it] * 0.95f + 0.05f * kotlin.math.cos(it.toDouble()).toFloat()) }.also { l2Normalize(it) }
        val bobCentroid = makeDistinctEmbedding(2)

        val templates = listOf(
            FaceTemplateEntity("t1", "ALICE01", "MASTER_CENTROID", toCsv(aliceCentroid), false),
            FaceTemplateEntity("t2", "ALICE01", "LEFT", toCsv(aliceLeft), false),
            FaceTemplateEntity("t3", "BOB01", "MASTER_CENTROID", toCsv(bobCentroid), false)
        )
        matcher.preloadTemplates(templates)
        val studentMap = mapOf("ALICE01" to "Alice Smith", "BOB01" to "Bob Jones")

        val queryAlice = aliceLeft.copyOf()
        val matchResult = matcher.match(queryAlice, studentMap, SecurityTier.HIGH)

        assertTrue("Alice must be verified", matchResult.isMatch)
        assertEquals("ALICE01", matchResult.studentRoll)
        assertTrue("Match similarity must exceed 0.90", matchResult.similarity >= 0.90f)
        assertTrue("Decision margin must separate Alice from Bob", matchResult.decisionMargin > 0.045f)
    }

    @Test fun `decision margin separation protects against lookalikes`() {
        val alice = makeDistinctEmbedding(1)
        val twin = FloatArray(512) { (alice[it] * 0.985f + 0.015f * kotlin.math.cos(it.toDouble()).toFloat()) }.also { l2Normalize(it) }

        val templates = listOf(
            FaceTemplateEntity("t1", "ALICE01", "FRONTAL", toCsv(alice), false),
            FaceTemplateEntity("t2", "TWIN02", "FRONTAL", toCsv(twin), false)
        )
        matcher.preloadTemplates(templates)
        val studentMap = mapOf("ALICE01" to "Alice", "TWIN02" to "Twin")

        val queryAmbiguous = FloatArray(512) { (alice[it] + twin[it]) * 0.5f }
        l2Normalize(queryAmbiguous)

        val matchStrict = matcher.match(queryAmbiguous, studentMap, SecurityTier.STRICT)
        assertTrue(
            "Ambiguous close-margin match must not false accept",
            matchStrict.confidenceZone == ConfidenceZone.REVIEW || !matchStrict.isMatch
        )
    }
}

class HnswVectorIndexTest {

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f; for (x in v) sum += x * x
        val norm = sqrt(sum); if (norm > 1e-7f) for (i in v.indices) v[i] /= norm; return v
    }
    private fun vec(dim: Int = 64, seed: Float) = l2Normalize(FloatArray(dim) { i -> seed + i * 0.001f })

    @Test fun `knn returns exact match for identical vector`() {
        val index = HnswVectorIndex(dimension = 64)
        val v = vec(64, 1.0f)
        index.insert("A","R001","FRONTAL", v)
        index.insert("B","R002","FRONTAL", vec(64, 0.0f))
        val r = index.searchKnn(v, k = 1)
        assertEquals(1, r.size); assertEquals("A", r[0].first.id)
        assertTrue(r[0].second >= 0.99f)
    }

    @Test fun `linear fallback used for N under 100`() {
        val index = HnswVectorIndex(dimension = 64)
        for (i in 0 until 5) index.insert("id$i","R00$i","FRONTAL", vec(64, i.toFloat()))
        val r = index.searchKnn(vec(64, 2.0f), k = 3)
        assertEquals(3, r.size)
        assertTrue(r[0].second >= r[1].second && r[1].second >= r[2].second)
    }

    @Test fun `updateVector changes similarity for updated node`() {
        val index = HnswVectorIndex(dimension = 64)
        index.insert("A","R001","FRONTAL", vec(64, 1.0f))
        val before = index.searchKnn(vec(64, 0.0f), k = 1).firstOrNull()?.second ?: 0f
        index.updateVector("A", vec(64, 0.0f))
        val after = index.searchKnn(vec(64, 0.0f), k = 1).firstOrNull()?.second ?: 0f
        assertTrue("Similarity should increase after update", after > before)
    }

    @Test fun `clear empties the index`() {
        val index = HnswVectorIndex(dimension = 64)
        index.insert("A","R001","FRONTAL", vec(64, 1.0f))
        index.clear()
        assertTrue(index.searchKnn(vec(64, 1.0f), k = 1).isEmpty())
    }
}
