package com.omniface.ai.tier2

import com.omniface.ai.ml.recognition.FaissVectorIndex
import com.omniface.ai.ml.recognition.HnswVectorIndex
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 2: Boundary & Corner Cases - Feature 5: Vector Index Capacity & Boundaries
 */
class Tier2VectorIndexBoundaryTest {

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 1e-7f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    private fun makeVec(dim: Int = 64, seed: Float = 1.0f): FloatArray {
        return l2Normalize(FloatArray(dim) { i -> seed + i * 0.01f })
    }

    @Test
    fun testSearchEmptyIndexReturnsEmpty() {
        val faiss = FaissVectorIndex(dimension = 64)
        val result = faiss.search(makeVec(64), k = 10)
        assertTrue("Searching empty index must return empty candidates", result.candidates.isEmpty())
        assertEquals(0, result.totalIndexed)
    }

    @Test
    fun testSearchKGreaterThanTotalIndexed() {
        val faiss = FaissVectorIndex(dimension = 64)
        faiss.add("id1", "R1", "FRONTAL", makeVec(64, 1.0f))
        faiss.add("id2", "R2", "FRONTAL", makeVec(64, 2.0f))

        val result = faiss.search(makeVec(64, 1.0f), k = 50)
        assertEquals("k > totalIndexed should return exactly totalIndexed items", 2, result.candidates.size)
    }

    @Test
    fun testDuplicateVectorInsertion() {
        val faiss = FaissVectorIndex(dimension = 64)
        val vec = makeVec(64, 1.0f)

        faiss.add("dup1", "R1", "FRONTAL", vec)
        faiss.add("dup2", "R1", "FRONTAL", vec)

        val result = faiss.search(vec, k = 2)
        assertEquals(2, result.candidates.size)
        assertEquals(1.0f, result.candidates[0].similarity, 1e-4f)
        assertEquals(1.0f, result.candidates[1].similarity, 1e-4f)
    }

    @Test
    fun testExtremeKValueCoercedSafely() {
        val faiss = FaissVectorIndex(dimension = 64)
        faiss.add("id1", "R1", "FRONTAL", makeVec(64, 1.0f))

        val resultK0 = faiss.search(makeVec(64, 1.0f), k = 0)
        assertEquals(1, resultK0.candidates.size)

        val resultKMax = faiss.search(makeVec(64, 1.0f), k = 10000)
        assertEquals(1, resultKMax.candidates.size)
    }

    @Test
    fun testReconstructNonExistentIdReturnsNull() {
        val faiss = FaissVectorIndex(dimension = 64)
        val vec = faiss.reconstruct("non_existent_id")
        assertNull("Reconstructing missing id should return null", vec)
    }
}
