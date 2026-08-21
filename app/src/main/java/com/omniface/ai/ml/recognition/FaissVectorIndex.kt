package com.omniface.ai.ml.recognition

import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * High-Speed FAISS (Facebook AI Similarity Search) Biometric Vector Index Wrapper.
 *
 * Provides optimized K-Nearest Neighbor (k-NN) and Approximate Nearest Neighbor (ANN)
 * cosine similarity search for 512-D L2-normalized ArcFace & MobileFaceNet face embeddings.
 *
 * Implements:
 * 1. IndexFlatIP (Exact Maximum Inner Product Search / Cosine Similarity with SIMD unrolling)
 * 2. IndexIVFFlat (Inverted File Index with Voronoi Cell Quantization & Inverted Posting Lists)
 * 3. IndexHNSWFlat (Hierarchical Navigable Small World Graph for O(log N) scale retrieval)
 */
class FaissVectorIndex(
    val dimension: Int = 512,
    val indexType: IndexType = IndexType.HNSW_FLAT,
    val metricType: MetricType = MetricType.INNER_PRODUCT,
    val ivfNList: Int = 16,
    val hnswMaxNeighbors: Int = 16,
    val hnswEfConstruction: Int = 64,
    val hnswEfSearch: Int = 32
) {

    enum class IndexType {
        FLAT_IP,     // Exact Inner Product / Cosine Similarity
        IVF_FLAT,    // Inverted File with Quantization Cells
        HNSW_FLAT    // Hierarchical Navigable Small World Graph
    }

    enum class MetricType {
        INNER_PRODUCT, // Cosine Similarity for L2-normalized vectors
        L2_DISTANCE    // Euclidean Distance
    }

    data class FaissIndexItem(
        val id: String,
        val studentRoll: String,
        val angleType: String,
        val vector: FloatArray
    )

    data class FaissCandidate(
        val id: String,
        val studentRoll: String,
        val angleType: String,
        val similarity: Float,
        val distance: Float,
        val rank: Int
    )

    data class FaissSearchResult(
        val candidates: List<FaissCandidate>,
        val queryLatencyMicros: Long,
        val totalIndexed: Int,
        val indexType: IndexType
    )

    private val lock = ReentrantReadWriteLock()

    // ── Primary Storage ──
    private val rawItems = ConcurrentHashMap<String, FaissIndexItem>()

    // ── HNSW Graph Sub-Engine ──
    private val hnswEngine = HnswVectorIndex(
        dimension = dimension,
        maxNeighbors = hnswMaxNeighbors,
        efConstruction = hnswEfConstruction,
        efSearch = hnswEfSearch
    )

    // ── IVF Inverted Lists ──
    private var ivfCentroids: Array<FloatArray>? = null
    private val ivfInvertedLists = ConcurrentHashMap<Int, MutableList<String>>()
    private var isTrained: Boolean = false

    val totalIndexed: Int get() = rawItems.size

    /**
     * Adds an identity biometric embedding into the FAISS index.
     */
    fun add(
        id: String,
        studentRoll: String,
        angleType: String,
        vector: FloatArray
    ) {
        val normalized = l2Normalize(vector.copyOf(dimension))
        val item = FaissIndexItem(id, studentRoll, angleType, normalized)

        lock.write {
            rawItems[id] = item

            when (indexType) {
                IndexType.HNSW_FLAT -> {
                    hnswEngine.insert(id, studentRoll, angleType, normalized)
                }
                IndexType.IVF_FLAT -> {
                    if (isTrained && ivfCentroids != null) {
                        val cellIdx = findNearestCentroid(normalized, ivfCentroids!!)
                        ivfInvertedLists.getOrPut(cellIdx) { mutableListOf() }.add(id)
                    }
                }
                IndexType.FLAT_IP -> {
                    // Stored directly in rawItems
                }
            }
        }
    }

    /**
     * Batch inserts multiple embeddings into the FAISS index.
     */
    fun addBatch(items: List<FaissIndexItem>) {
        lock.write {
            for (item in items) {
                val normalized = l2Normalize(item.vector.copyOf(dimension))
                val normalizedItem = item.copy(vector = normalized)
                rawItems[item.id] = normalizedItem

                if (indexType == IndexType.HNSW_FLAT) {
                    hnswEngine.insert(item.id, item.studentRoll, item.angleType, normalized)
                }
            }

            if (indexType == IndexType.IVF_FLAT && rawItems.size >= ivfNList * 4) {
                trainIvf()
            }
        }
    }

    /**
     * Trains the IVF-FLAT index via K-Means clustering on the accumulated vector dataset.
     */
    fun trainIvf(maxIters: Int = 10) {
        lock.write {
            if (rawItems.isEmpty()) return@write
            val k = min(ivfNList, rawItems.size)
            val vectors = rawItems.values.map { it.vector }.toList()

            // Initialize centroids via k-means++ or random selection
            val centroids = Array(k) { vectors[it % vectors.size].copyOf(dimension) }

            for (iter in 0 until maxIters) {
                val clusters = Array(k) { mutableListOf<FloatArray>() }
                for (vec in vectors) {
                    val bestIdx = findNearestCentroid(vec, centroids)
                    clusters[bestIdx].add(vec)
                }

                // Update centroids
                for (cIdx in 0 until k) {
                    val cluster = clusters[cIdx]
                    if (cluster.isNotEmpty()) {
                        val mean = FloatArray(dimension)
                        for (vec in cluster) {
                            for (d in 0 until dimension) {
                                mean[d] += vec[d]
                            }
                        }
                        val inv = 1.0f / cluster.size
                        for (d in 0 until dimension) {
                            mean[d] *= inv
                        }
                        centroids[cIdx] = l2Normalize(mean)
                    }
                }
            }

            ivfCentroids = centroids
            ivfInvertedLists.clear()

            // Populate inverted posting lists
            for ((id, item) in rawItems) {
                val cellIdx = findNearestCentroid(item.vector, centroids)
                ivfInvertedLists.getOrPut(cellIdx) { mutableListOf() }.add(id)
            }
            isTrained = true
        }
    }

    /**
     * Executes high-speed K-Nearest Neighbor (k-NN) cosine similarity search.
     *
     * @param queryVector 512-D face embedding
     * @param k Number of top matching candidates to retrieve
     * @param nprobe Number of Voronoi cells to visit in IVF mode
     * @return Search result containing ranked candidates with similarity scores and query latency
     */
    fun search(
        queryVector: FloatArray,
        k: Int = 10,
        nprobe: Int = 4
    ): FaissSearchResult {
        val t0 = System.nanoTime()
        val normalizedQuery = l2Normalize(queryVector.copyOf(dimension))

        val candidates: List<FaissCandidate> = lock.read {
            if (rawItems.isEmpty()) {
                return@read emptyList()
            }

            val topKCount = min(k, rawItems.size)

            when (indexType) {
                IndexType.HNSW_FLAT -> {
                    val knn = hnswEngine.searchKnn(normalizedQuery, topKCount)
                    knn.mapIndexed { index, (node, sim) ->
                        val dist = if (metricType == MetricType.L2_DISTANCE) {
                            sqrt(max(0.0f, 2.0f - 2.0f * sim))
                        } else {
                            1.0f - sim
                        }
                        FaissCandidate(
                            id = node.id,
                            studentRoll = node.studentRoll,
                            angleType = node.angleType,
                            similarity = sim,
                            distance = dist,
                            rank = index + 1
                        )
                    }
                }

                IndexType.IVF_FLAT -> {
                    if (!isTrained || ivfCentroids == null) {
                        searchFlatExact(normalizedQuery, topKCount)
                    } else {
                        searchIvf(normalizedQuery, topKCount, nprobe)
                    }
                }

                IndexType.FLAT_IP -> {
                    searchFlatExact(normalizedQuery, topKCount)
                }
            }
        }

        val elapsedMicros = (System.nanoTime() - t0) / 1_000L

        return FaissSearchResult(
            candidates = candidates,
            queryLatencyMicros = elapsedMicros,
            totalIndexed = rawItems.size,
            indexType = indexType
        )
    }

    /**
     * Range Search: Finds all biometric templates with similarity exceeding threshold.
     */
    fun rangeSearch(
        queryVector: FloatArray,
        minSimilarityThreshold: Float = 0.55f
    ): List<FaissCandidate> {
        val normalized = l2Normalize(queryVector.copyOf(dimension))
        return lock.read {
            rawItems.values
                .map { item ->
                    val sim = dotProductUnrolled(normalized, item.vector)
                    val dist = sqrt(max(0.0f, 2.0f - 2.0f * sim))
                    FaissCandidate(
                        id = item.id,
                        studentRoll = item.studentRoll,
                        angleType = item.angleType,
                        similarity = sim,
                        distance = dist,
                        rank = 0
                    )
                }
                .filter { it.similarity >= minSimilarityThreshold }
                .sortedByDescending { it.similarity }
                .mapIndexed { idx, candidate -> candidate.copy(rank = idx + 1) }
        }
    }

    /**
     * Exact Maximum Inner Product Search with 8-way loop unrolling for SIMD acceleration.
     */
    private fun searchFlatExact(query: FloatArray, k: Int): List<FaissCandidate> {
        // Min-heap of size K to retain Top-K largest inner products
        val queue = PriorityQueue<FaissCandidate>(k) { a, b ->
            a.similarity.compareTo(b.similarity)
        }

        for (item in rawItems.values) {
            val sim = dotProductUnrolled(query, item.vector)
            val dist = if (metricType == MetricType.L2_DISTANCE) {
                sqrt(max(0.0f, 2.0f - 2.0f * sim))
            } else {
                1.0f - sim
            }

            val cand = FaissCandidate(
                id = item.id,
                studentRoll = item.studentRoll,
                angleType = item.angleType,
                similarity = sim,
                distance = dist,
                rank = 0
            )

            if (queue.size < k) {
                queue.offer(cand)
            } else if (sim > queue.peek()!!.similarity) {
                queue.poll()
                queue.offer(cand)
            }
        }

        val result = mutableListOf<FaissCandidate>()
        while (queue.isNotEmpty()) {
            result.add(queue.poll()!!)
        }
        result.reverse() // Sort descending

        return result.mapIndexed { idx, cand -> cand.copy(rank = idx + 1) }
    }

    /**
     * Inverted File Index search across top `nprobe` nearest Voronoi centroid cells.
     */
    private fun searchIvf(query: FloatArray, k: Int, nprobe: Int): List<FaissCandidate> {
        val centroids = ivfCentroids ?: return searchFlatExact(query, k)

        // Find top nprobe nearest centroids
        val centroidScores = centroids.indices.map { idx ->
            Pair(idx, dotProductUnrolled(query, centroids[idx]))
        }.sortedByDescending { it.second }.take(nprobe)

        val queue = PriorityQueue<FaissCandidate>(k) { a, b ->
            a.similarity.compareTo(b.similarity)
        }

        for ((cellIdx, _) in centroidScores) {
            val idsInCell = ivfInvertedLists[cellIdx] ?: continue
            for (id in idsInCell) {
                val item = rawItems[id] ?: continue
                val sim = dotProductUnrolled(query, item.vector)
                val dist = sqrt(max(0.0f, 2.0f - 2.0f * sim))
                val cand = FaissCandidate(
                    id = item.id,
                    studentRoll = item.studentRoll,
                    angleType = item.angleType,
                    similarity = sim,
                    distance = dist,
                    rank = 0
                )

                if (queue.size < k) {
                    queue.offer(cand)
                } else if (sim > queue.peek()!!.similarity) {
                    queue.poll()
                    queue.offer(cand)
                }
            }
        }

        val result = mutableListOf<FaissCandidate>()
        while (queue.isNotEmpty()) {
            result.add(queue.poll()!!)
        }
        result.reverse()

        return result.mapIndexed { idx, cand -> cand.copy(rank = idx + 1) }
    }

    /**
     * Updates an existing embedding in place (e.g. continuous learning adaptation).
     */
    fun update(id: String, updatedVector: FloatArray) {
        lock.write {
            val existing = rawItems[id] ?: return@write
            val normalized = l2Normalize(updatedVector.copyOf(dimension))
            rawItems[id] = existing.copy(vector = normalized)

            if (indexType == IndexType.HNSW_FLAT) {
                hnswEngine.updateVector(id, normalized)
            }
        }
    }

    /**
     * Clears all indexed items and resets index topology.
     */
    fun reset() {
        lock.write {
            rawItems.clear()
            hnswEngine.clear()
            ivfCentroids = null
            ivfInvertedLists.clear()
            isTrained = false
        }
    }

    /**
     * Reconstructs vector for a given identifier.
     */
    fun reconstruct(id: String): FloatArray? {
        return rawItems[id]?.vector?.clone()
    }

    // ── Mathematical Kernel Optimizations ──

    private fun findNearestCentroid(vec: FloatArray, centroids: Array<FloatArray>): Int {
        var bestIdx = 0
        var bestSim = -Float.MAX_VALUE
        for (i in centroids.indices) {
            val sim = dotProductUnrolled(vec, centroids[i])
            if (sim > bestSim) {
                bestSim = sim
                bestIdx = i
            }
        }
        return bestIdx
    }

    /**
     * 8-way loop unrolled dot product for ARM NEON / SIMD cache acceleration.
     */
    private fun dotProductUnrolled(a: FloatArray, b: FloatArray): Float {
        var sum0 = 0.0f
        var sum1 = 0.0f
        var sum2 = 0.0f
        var sum3 = 0.0f
        var sum4 = 0.0f
        var sum5 = 0.0f
        var sum6 = 0.0f
        var sum7 = 0.0f

        val len = min(a.size, b.size)
        val unrollLimit = len - (len % 8)
        var i = 0

        while (i < unrollLimit) {
            sum0 += a[i] * b[i]
            sum1 += a[i + 1] * b[i + 1]
            sum2 += a[i + 2] * b[i + 2]
            sum3 += a[i + 3] * b[i + 3]
            sum4 += a[i + 4] * b[i + 4]
            sum5 += a[i + 5] * b[i + 5]
            sum6 += a[i + 6] * b[i + 6]
            sum7 += a[i + 7] * b[i + 7]
            i += 8
        }

        var total = sum0 + sum1 + sum2 + sum3 + sum4 + sum5 + sum6 + sum7

        while (i < len) {
            total += a[i] * b[i]
            i++
        }

        return total
    }

    private fun l2Normalize(vector: FloatArray): FloatArray {
        var sumSq = 0.0f
        for (v in vector) {
            sumSq += v * v
        }
        val norm = sqrt(sumSq)
        if (norm > 1e-8f) {
            val inv = 1.0f / norm
            for (i in vector.indices) {
                vector[i] *= inv
            }
        }
        return vector
    }
}
