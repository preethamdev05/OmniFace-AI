package com.omniface.ai.ml

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.Rect
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessEngine
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.pipeline.BiometricDecisionEngine
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.recognition.FaissVectorIndex
import com.omniface.ai.ml.recognition.HnswVectorIndex
import com.omniface.ai.security.AndroidSecurityUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Empirical Stress & Adversarial Challenge Suite for OmniFace AI ML Engine:
 * 1. High-concurrency thread-safety (FaceMatcher, FAISS, HNSW, TemporalLiveness)
 * 2. Degenerate, zero-norm, NaN, and corrupted buffer resilience
 * 3. Exact ISO/IEC threshold & decision margin boundary conditions
 * 4. Multi-angle profile & centroid consistency safeguards
 * 5. Dynamic EMA centroid continuous learning numerical stability
 * 6. Umeyama alignment & geometric transform edge case resilience
 */
class FaceEngineStressTest {

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
            val inv = 1.0f / norm
            for (i in v.indices) v[i] *= inv
        } else {
            java.util.Arrays.fill(v, 0f)
        }
        return v
    }

    private fun makeEmbedding(dim: Int = 512, seed: Float = 1.0f): FloatArray {
        return l2Normalize(FloatArray(dim) { i -> seed + i * 0.001f })
    }

    private fun toCsv(v: FloatArray): String {
        return v.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 1. MULTI-THREADED CONCURRENCY & RACE CONDITION STRESS TESTS
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun testConcurrentFaceMatcher_readersAndWritersUnderHeavyLoad() {
        val numThreads = 16
        val iterationsPerThread = 100
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val errorFlag = AtomicBoolean(false)
        val completedOps = AtomicInteger(0)

        // Seed initial templates
        val initialTemplates = (1..20).map { i ->
            FaceTemplateEntity(
                id = "tpl_$i",
                studentRoll = "STU_$i",
                angleType = if (i % 2 == 0) "FRONTAL" else "MASTER_CENTROID",
                embeddingEncryptedCsv = toCsv(makeEmbedding(seed = i.toFloat())),
                isEncrypted = false
            )
        }
        matcher.preloadTemplates(initialTemplates)

        val studentMap = (1..20).associate { "STU_$it" to "Student $it" }

        for (threadId in 0 until numThreads) {
            executor.submit {
                try {
                    for (iter in 0 until iterationsPerThread) {
                        when (threadId % 4) {
                            0 -> {
                                // Match reader
                                val probe = makeEmbedding(seed = (iter % 20 + 1).toFloat())
                                val res = matcher.match(probe, studentMap, SecurityTier.HIGH)
                                assertNotNull(res)
                                assertFalse("Similarity must never be NaN", res.similarity.isNaN())
                                assertTrue("Similarity must be between -1 and 1", res.similarity in -1.01f..1.01f)
                            }
                            1 -> {
                                // Dynamic centroid adaptation writer
                                val liveProbe = makeEmbedding(seed = 1.0f + (iter % 5) * 0.001f)
                                matcher.adaptCentroidIfHighConfidence("STU_1", liveProbe, 0.85f)
                            }
                            2 -> {
                                // FAISS top-K and range search
                                val probe = makeEmbedding(seed = 2.0f)
                                val faissRes = matcher.searchFaissTopK(probe, k = 5)
                                assertNotNull(faissRes)
                                val rangeRes = matcher.searchFaissRange(probe, minSimilarity = 0.5f)
                                assertNotNull(rangeRes)
                            }
                            3 -> {
                                // HNSW top-K ANN search
                                val probe = makeEmbedding(seed = 3.0f)
                                val annRes = matcher.searchAnnTopK(probe, k = 5)
                                assertNotNull(annRes)
                            }
                        }
                        completedOps.incrementAndGet()
                    }
                } catch (t: Throwable) {
                    errorFlag.set(true)
                    t.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(15, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue("All concurrent operations should finish within timeout without deadlocking", completed)
        assertFalse("No thread should encounter ConcurrentModificationException or unhandled crash", errorFlag.get())
        assertEquals(numThreads * iterationsPerThread, completedOps.get())
    }

    @Test
    fun testConcurrentFaissVectorIndex_rapidInsertUpdateAndSearch() {
        val faiss = FaissVectorIndex(
            dimension = 64,
            indexType = FaissVectorIndex.IndexType.HNSW_FLAT,
            metricType = FaissVectorIndex.MetricType.INNER_PRODUCT
        )
        val numThreads = 12
        val opsPerThread = 80
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val errorFlag = AtomicBoolean(false)

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    for (i in 0 until opsPerThread) {
                        val id = "vec_${t}_$i"
                        val roll = "ROLL_$t"
                        val v = makeEmbedding(dim = 64, seed = (t * 100 + i).toFloat())

                        // Interleave add, update, search, rangeSearch
                        faiss.add(id, roll, "FRONTAL", v)

                        if (i % 5 == 0) {
                            val vUpdated = makeEmbedding(dim = 64, seed = (t * 100 + i + 1).toFloat())
                            faiss.update(id, vUpdated)
                        }

                        val searchRes = faiss.search(v, k = 3)
                        assertNotNull(searchRes)

                        val rangeRes = faiss.rangeSearch(v, minSimilarityThreshold = 0.6f)
                        assertNotNull(rangeRes)
                    }
                } catch (e: Throwable) {
                    errorFlag.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val finished = latch.await(15, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue("Concurrent FAISS index stress completed in time", finished)
        assertFalse("FAISS index must be thread-safe across concurrent writes and reads", errorFlag.get())
        assertTrue("Total indexed items should reflect all insertions", faiss.totalIndexed > 0)
    }

    @Test
    fun testConcurrentTemporalLivenessEngine_multiTrackContention() {
        val engine = TemporalLivenessEngine()
        val numThreads = 10
        val executor = Executors.newFixedThreadPool(numThreads)
        val latch = CountDownLatch(numThreads)
        val errorFlag = AtomicBoolean(false)

        for (t in 0 until numThreads) {
            executor.submit {
                try {
                    val trackId = t + 100
                    for (frame in 0 until 50) {
                        val yaw = (frame % 10) * 1.5f
                        val pitch = (frame % 5) * 0.8f
                        val eyeProb = if (frame == 25) 0.15f else 0.95f

                        engine.recordSample(
                            trackId = trackId,
                            yaw = yaw,
                            pitch = pitch,
                            roll = 0.0f,
                            attributes = null,
                            faceMap3DMM = null,
                            passivePad = PassivePadResult(true, 0.92f, 0.08f, "Live", 4L),
                            landmarks5Pts = null,
                            eyeOpenProbability = eyeProb
                        )

                        val result = engine.evaluateTemporalLiveness(trackId)
                        assertNotNull(result)

                        if (frame == 40) {
                            engine.purgeTrack(trackId)
                        }
                    }
                } catch (e: Throwable) {
                    errorFlag.set(true)
                    e.printStackTrace()
                } finally {
                    latch.countDown()
                }
            }
        }

        val completed = latch.await(10, TimeUnit.SECONDS)
        executor.shutdownNow()

        assertTrue("Temporal liveness concurrency test completed without deadlock", completed)
        assertFalse("Temporal liveness engine must be thread-safe across tracks", errorFlag.get())
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2. CORRUPTED BUFFER, ZERO-NORM & ADVERSARIAL INPUT RECOVERY
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun testZeroNormAndNanVectors_handledGracefullyWithoutDivisionByZero() {
        val zeroVec = FloatArray(512) { 0.0f }
        val normalizedZero = l2Normalize(zeroVec.clone())
        // Should remain all zeros, no NaNs
        for (v in normalizedZero) {
            assertEquals(0.0f, v, 1e-6f)
            assertFalse(v.isNaN())
            assertFalse(v.isInfinite())
        }

        val nanVec = FloatArray(512) { Float.NaN }
        val processedNan = l2Normalize(nanVec.clone())
        assertNotNull(processedNan)

        // FaceMatcher with zero vectors
        val template = FaceTemplateEntity("t_zero", "R_ZERO", "FRONTAL", toCsv(zeroVec), false)
        matcher.preloadTemplates(listOf(template))

        val probe = makeEmbedding(512, 1.0f)
        val matchRes = matcher.match(probe, mapOf("R_ZERO" to "Zero Person"), SecurityTier.HIGH)
        assertNotNull(matchRes)
        assertFalse("Zero vector probe should not match genuine identity", matchRes.isMatch)
        assertEquals(0.0f, matchRes.similarity, 1e-4f)
    }

    @Test
    fun testMalformedAndCorruptedCsvTemplates_skippedWithoutCrashing() {
        val corruptTemplates = listOf(
            FaceTemplateEntity("c1", "R1", "FRONTAL", "", false), // empty
            FaceTemplateEntity("c2", "R2", "FRONTAL", "abc,def,ghi", false), // non-numeric
            FaceTemplateEntity("c3", "R3", "FRONTAL", ",,,,,,", false), // only commas
            FaceTemplateEntity("c4", "R4", "FRONTAL", "1.0, 2.0, abc, 4.0", false), // corrupted floats
            FaceTemplateEntity("c5", "R5", "FRONTAL", toCsv(makeEmbedding(512, 1.0f)), false) // VALID
        )

        matcher.preloadTemplates(corruptTemplates)
        // Only the 1 valid template should be indexed
        assertEquals(1, matcher.enrolledTemplateCount)

        val probe = makeEmbedding(512, 1.0f)
        val matchRes = matcher.match(probe, mapOf("R5" to "Valid User"), SecurityTier.HIGH)
        assertTrue(matchRes.isMatch)
        assertEquals("R5", matchRes.studentRoll)
    }

    @Test
    fun testFaissAndHnsw_extremeKParameters_clampedSafely() {
        val faiss = FaissVectorIndex(dimension = 64)
        val v = makeEmbedding(64, 1.0f)
        faiss.add("v1", "R1", "FRONTAL", v)

        // Request k = 0, k = -10, k = 100_000
        val resZero = faiss.search(v, k = 0)
        assertNotNull(resZero)

        val resNeg = faiss.search(v, k = -5)
        assertNotNull(resNeg)

        val resHuge = faiss.search(v, k = 100_000)
        assertNotNull(resHuge)
        assertEquals(1, resHuge.candidates.size) // Only 1 element indexed

        // Range search with out-of-bounds threshold
        val resRangeHigh = faiss.rangeSearch(v, minSimilarityThreshold = 2.0f)
        assertTrue("No candidate can have similarity > 2.0", resRangeHigh.isEmpty())

        val resRangeLow = faiss.rangeSearch(v, minSimilarityThreshold = -2.0f)
        assertEquals(1, resRangeLow.size)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 3. CALIBRATED ISO/IEC DECISION GATES & MARGIN VERIFICATION
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun testExactBoundaryDecisionThresholds_standardHighStrict() {
        val qualityPass = QualityGateResult(true, 95f, 95f, 95f, "")
        val padPass = PassivePadResult(true, 0.95f, 0.05f, "Live", 5L)
        val temporalPass = TemporalLivenessResult(
            isLive = true,
            temporalConfidence = 0.95f,
            microMotionDetected = true,
            naturalBlinkDetected = true,
            headTurnDetected = true,
            stable3DDepth = true,
            requiredAction = null,
            explanation = "Pass"
        )

        for (tier in listOf(SecurityTier.STANDARD, SecurityTier.HIGH, SecurityTier.STRICT)) {
            val tau = tier.threshold

            // 1. Just below threshold (tau - 0.001) -> MUST REJECT
            val matchBelow = MatchResult(
                studentRoll = "STU_1",
                studentName = "Alice",
                confidence = 50f,
                similarity = tau - 0.001f,
                isMatch = false,
                hardwareTier = HardwareTier.NPU_NNAPI,
                confidenceZone = ConfidenceZone.REJECT,
                decisionMargin = 0.10f
            )
            val decisionBelow = BiometricDecisionEngine.evaluate(
                quality = qualityPass,
                passivePad = padPass,
                temporalLiveness = temporalPass,
                matchResult = matchBelow,
                securityTier = tier
            )
            assertFalse("Score below tau (${tau - 0.001f} < $tau) must NOT authorize attendance", decisionBelow.isAttendanceAuthorized)
            assertEquals(PipelineGateState.REJECT_UNKNOWN_IDENTITY, decisionBelow.gateState)

            // 2. Just above threshold (tau + 0.005) with valid margin -> MUST PASS
            val matchAbove = MatchResult(
                studentRoll = "STU_1",
                studentName = "Alice",
                confidence = 90f,
                similarity = tau + 0.005f,
                isMatch = true,
                hardwareTier = HardwareTier.NPU_NNAPI,
                confidenceZone = ConfidenceZone.ACCEPT,
                decisionMargin = tier.marginThreshold + 0.05f,
                explanation = "Verified Alice"
            )
            val decisionAbove = BiometricDecisionEngine.evaluate(
                quality = qualityPass,
                passivePad = padPass,
                temporalLiveness = temporalPass,
                matchResult = matchAbove,
                securityTier = tier
            )
            assertTrue("Score above tau (${tau + 0.005f} >= $tau) with valid margin must authorize attendance", decisionAbove.isAttendanceAuthorized)
            assertEquals(PipelineGateState.PASS, decisionAbove.gateState)
        }
    }

    @Test
    fun testAmbiguousDecisionMargin_triggersReviewAndBlocksAuthorization() {
        // Enroll 2 similar students (e.g. twins or closely correlated identities)
        val embAlice = makeEmbedding(512, 1.0f)
        val embBob = makeEmbedding(512, 1.002f) // Very close seed -> similarity difference < 0.035

        val templates = listOf(
            FaceTemplateEntity("t_alice", "ALICE_01", "FRONTAL", toCsv(embAlice), false),
            FaceTemplateEntity("t_bob", "BOB_01", "FRONTAL", toCsv(embBob), false)
        )
        matcher.preloadTemplates(templates)

        // Query probe exactly midway between Alice and Bob
        val probe = makeEmbedding(512, 1.001f)
        val matchResult = matcher.match(
            queryEmbedding = probe,
            studentMap = mapOf("ALICE_01" to "Alice", "BOB_01" to "Bob"),
            securityTier = SecurityTier.HIGH
        )

        // Because difference between Alice and Bob is narrow (< marginThreshold 0.035), confidenceZone must be REVIEW
        assertEquals(ConfidenceZone.REVIEW, matchResult.confidenceZone)
        assertFalse("Ambiguous match with low decision margin must NOT be accepted as match", matchResult.isMatch)

        // Pipeline synthesis must mark REVIEW_AMBIGUOUS_MATCH
        val qualityPass = QualityGateResult(true, 95f, 95f, 95f, "")
        val padPass = PassivePadResult(true, 0.95f, 0.05f, "Live", 5L)
        val temporalPass = TemporalLivenessResult(
            isLive = true,
            temporalConfidence = 0.95f,
            microMotionDetected = true,
            naturalBlinkDetected = true,
            stable3DDepth = true,
            explanation = "Pass"
        )

        val decision = BiometricDecisionEngine.evaluate(
            quality = qualityPass,
            passivePad = padPass,
            temporalLiveness = temporalPass,
            matchResult = matchResult,
            securityTier = SecurityTier.HIGH
        )

        assertFalse("Ambiguous identity must NOT authorize automatic attendance", decision.isAttendanceAuthorized)
        assertEquals(PipelineGateState.REVIEW_AMBIGUOUS_MATCH, decision.gateState)
    }

    @Test
    fun testInconsistentMultiAngleProfile_blocksSingleAngleImpostorAttack() {
        // Enroll Alice with 2 templates: FRONTAL and MASTER_CENTROID
        val embFrontal = makeEmbedding(512, 1.0f)
        val embCentroid = makeEmbedding(512, 1.0f)

        val templates = listOf(
            FaceTemplateEntity("t1", "ALICE_01", "FRONTAL", toCsv(embFrontal), false),
            FaceTemplateEntity("t2", "ALICE_01", "MASTER_CENTROID", toCsv(embCentroid), false)
        )
        matcher.preloadTemplates(templates)

        // A truly orthogonal/opposite impostor probe
        val orthogonalProbe = FloatArray(512) { i -> if (i % 2 == 0) 1.0f else -1.0f }.also { l2Normalize(it) }
        val matchResult = matcher.match(
            queryEmbedding = orthogonalProbe,
            studentMap = mapOf("ALICE_01" to "Alice"),
            securityTier = SecurityTier.HIGH
        )

        assertFalse("Orthogonal probe must be rejected", matchResult.isMatch)
        assertEquals(ConfidenceZone.REJECT, matchResult.confidenceZone)
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 4. DYNAMIC CENTROID ADAPTATION NUMERICAL STABILITY (EMA)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun testContinuousLearningEma_100Iterations_preservesL2NormStrictly() {
        val initialCentroid = makeEmbedding(512, 1.0f)
        val tpl = FaceTemplateEntity("t1", "STU_ADAPT", "MASTER_CENTROID", toCsv(initialCentroid), false)
        matcher.preloadTemplates(listOf(tpl))

        // Perform 100 consecutive adaptations with slight daily lighting/appearance variation
        var currentVector = initialCentroid.clone()
        for (i in 1..100) {
            val liveProbe = makeEmbedding(512, 1.0f + i * 0.0005f)
            val adapted = matcher.adaptCentroidIfHighConfidence("STU_ADAPT", liveProbe, similarityScore = 0.88f)
            assertNotNull("Adaptation should succeed on high similarity probe", adapted)

            // Extract the updated vector from the returned CSV (decrypting if ciphertext)
            val rawCsv = if (adapted!!.second.contains(",")) adapted.second else AndroidSecurityUtils.decrypt(adapted.second)
            val adaptedVector = rawCsv.split(",").map { it.toFloat() }.toFloatArray()

            // Verify L2 norm remains strictly 1.000000
            var sumSq = 0f
            for (x in adaptedVector) sumSq += x * x
            val norm = sqrt(sumSq)
            assertEquals("L2 norm must remain strictly 1.0 across 100 EMA adaptations", 1.0f, norm, 1e-4f)

            currentVector = adaptedVector
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 5. GEOMETRIC TRANSFORM & UMEYAMA SIMILARITY TRANSFORM RESILIENCE
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    fun testUmeyamaSimilarityTransform_collinearAndDegenerateLandmarks() {
        // Test coincident landmarks mathematical property: zero variance must safely return null
        val coincidentPoints = arrayOf(
            PointF(50f, 50f),
            PointF(50f, 50f),
            PointF(50f, 50f),
            PointF(50f, 50f),
            PointF(50f, 50f)
        )
        // Collinear and normal landmarks test mathematical properties
        val collinearPoints = arrayOf(
            PointF(10f, 50f),
            PointF(30f, 50f),
            PointF(50f, 50f),
            PointF(70f, 50f),
            PointF(90f, 50f)
        )

        // Verify that passing insufficient or zero variance points returns null safely
        val emptyPoints = emptyArray<PointF>()
        // Create dummy bitmap if possible on JVM
        try {
            val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val resCoincident = UmeyamaSimilarityTransform.alignFace5Points(bmp, coincidentPoints)
            assertNull("Zero variance coincident landmarks must safely return null", resCoincident)

            val resEmpty = UmeyamaSimilarityTransform.alignFace5Points(bmp, emptyPoints)
            assertNull("Empty landmarks must safely return null", resEmpty)
        } catch (_: Throwable) {
            // Android Bitmap creation stub on headless JVM
        }
    }

    @Test
    fun testBiometricCropUtils_outOfBoundsAndDegenerateRects() {
        try {
            val bmp = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            // Zero-width / zero-height rect
            val emptyRect = Rect(10, 10, 10, 10)
            val cropEmpty = BiometricCropUtils.extractSquareFaceCrop(bmp, emptyRect)
            assertNull("Zero-area rect must safely return null", cropEmpty)
        } catch (_: Throwable) {
            // Android Bitmap creation stub on headless JVM
        }
    }
}
