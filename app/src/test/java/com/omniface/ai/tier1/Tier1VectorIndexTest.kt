package com.omniface.ai.tier1

import com.omniface.ai.ml.recognition.FaissVectorIndex
import com.omniface.ai.ml.recognition.HnswVectorIndex
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 1: Feature 5 - FAISS & HNSW Vector Indexing & Search
 */
class Tier1VectorIndexTest {

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 1e-7f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    private fun makeVector(seed: Float, dim: Int = 512): FloatArray {
        return l2Normalize(FloatArray(dim) { i -> seed + i * 0.001f })
    }

    @Test
    fun testFaissFlatIpExactSearch() {
        val faiss = FaissVectorIndex(
            dimension = 512,
            indexType = FaissVectorIndex.IndexType.FLAT_IP,
            metricType = FaissVectorIndex.MetricType.INNER_PRODUCT
        )

        val target = makeVector(1.0f)
        val other1 = makeVector(2.0f)
        val other2 = makeVector(3.0f)

        faiss.add("id1", "R001", "FRONTAL", target)
        faiss.add("id2", "R002", "FRONTAL", other1)
        faiss.add("id3", "R003", "FRONTAL", other2)

        val result = faiss.search(target, k = 1)
        assertEquals(1, result.candidates.size)
        assertEquals("id1", result.candidates[0].id)
        assertEquals("R001", result.candidates[0].studentRoll)
        assertEquals("Exact vector must have cosine similarity ~ 1.0", 1.0f, result.candidates[0].similarity, 1e-4f)
    }

    @Test
    fun testFaissHnswFlatKnnSearch() {
        val faiss = FaissVectorIndex(
            dimension = 64,
            indexType = FaissVectorIndex.IndexType.HNSW_FLAT
        )

        for (i in 1..10) {
            faiss.add("node_$i", "ROLL_$i", "FRONTAL", makeVector(i.toFloat(), 64))
        }

        val query = makeVector(5.0f, 64)
        val result = faiss.search(query, k = 3)
        assertEquals(3, result.candidates.size)
        assertEquals("node_5", result.candidates[0].id)
        assertTrue("Top candidate similarity must be >= 2nd candidate", result.candidates[0].similarity >= result.candidates[1].similarity)
    }

    @Test
    fun testFaissRangeSearchThresholdFiltering() {
        val faiss = FaissVectorIndex(dimension = 64, indexType = FaissVectorIndex.IndexType.FLAT_IP)
        val base = makeVector(1.0f, 64)
        val near = makeVector(1.01f, 64)
        val far = makeVector(100.0f, 64)

        faiss.add("base_id", "R1", "FRONTAL", base)
        faiss.add("near_id", "R2", "FRONTAL", near)
        faiss.add("far_id", "R3", "FRONTAL", far)

        val matches = faiss.rangeSearch(base, minSimilarityThreshold = 0.90f)
        assertTrue("Range search should return high-similarity items", matches.any { it.id == "base_id" })
        assertTrue("Range search should include near item", matches.any { it.id == "near_id" })
    }

    @Test
    fun testFaissBatchInsertion() {
        val faiss = FaissVectorIndex(dimension = 64, indexType = FaissVectorIndex.IndexType.FLAT_IP)
        val batch = (1..20).map {
            FaissVectorIndex.FaissIndexItem(
                id = "item_$it",
                studentRoll = "S$it",
                angleType = "FRONTAL",
                vector = makeVector(it.toFloat(), 64)
            )
        }

        faiss.addBatch(batch)
        assertEquals("Batch insert must update totalIndexed count", 20, faiss.totalIndexed)
    }

    @Test
    fun testHnswVectorIndexUpdate() {
        val hnsw = HnswVectorIndex(dimension = 64)
        val vA = makeVector(1.0f, 64)
        val vB = makeVector(10.0f, 64)

        hnsw.insert("nodeA", "R1", "FRONTAL", vA)
        val beforeSim = hnsw.searchKnn(vB, k = 1).firstOrNull()?.second ?: 0f

        // Update nodeA with vB
        hnsw.updateVector("nodeA", vB)
        val afterSim = hnsw.searchKnn(vB, k = 1).firstOrNull()?.second ?: 0f

        assertTrue("Similarity after vector update must increase", afterSim > beforeSim)
        assertEquals(1.0f, afterSim, 1e-4f)
    }
}
