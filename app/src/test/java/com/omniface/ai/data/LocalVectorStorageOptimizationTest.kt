package com.omniface.ai.data

import com.omniface.ai.data.local.adapter.FakeIdentityStore
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.CachedBiometric
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.verification.domain.IdentityTemplate
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Arrays
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.sqrt

/**
 * Phase 17: Local-First Vector Storage Optimization Test Suite.
 *
 * Verifies:
 * 1. Hardware Keystore AES-256-GCM encryption/decryption round-trip.
 * 2. Zero plaintext vector persistence and rejection of corrupted/truncated ciphertexts.
 * 3. Pre-warming volatile cache synchronization (immediate recognition without restart).
 * 4. In-memory L2 normalization invariance (||v||2 = 1.0 +- 1e-4).
 * 5. Dynamic centroid adaptation EMA convergence (alpha=0.05) and persistence.
 * 6. Adaptation guardrails (threshold < 0.72, blank/GUEST roll, unregistered IDs).
 * 7. Volatile memory zeroization on cleanup.
 * 8. Thread-safe concurrent read/write isolation under heavy load.
 * 9. IdentityStore persistence seam contract.
 */
class LocalVectorStorageOptimizationTest {

    private lateinit var matcher: FaceMatcher

    @Before
    fun setUp() {
        AndroidSecurityUtils.invalidateKeyCache()
        matcher = FaceMatcher()
    }

    private fun l2Normalize(vec: FloatArray): FloatArray {
        var sumSquares = 0.0f
        for (v in vec) sumSquares += v * v
        val norm = sqrt(sumSquares)
        if (norm > 1e-7f) {
            val inv = 1.0f / norm
            for (i in vec.indices) vec[i] *= inv
        }
        return vec
    }

    private fun computeL2Norm(vec: FloatArray): Float {
        var sumSquares = 0.0f
        for (v in vec) sumSquares += v * v
        return sqrt(sumSquares)
    }

    private fun createNormalizedEmbedding(dim: Int = 512, seed: Float = 1.0f): FloatArray {
        val arr = FloatArray(dim) { i -> seed + (i * 0.005f) }
        return l2Normalize(arr)
    }

    private fun toCsv(vec: FloatArray): String =
        vec.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }

    @Test
    fun testAes256GcmEncryptionDecryptionRoundtrip() {
        val originalVec = createNormalizedEmbedding(512, 1.25f)
        val plainCsv = toCsv(originalVec)

        // Encrypt plain CSV
        val encryptedBase64 = AndroidSecurityUtils.encrypt(plainCsv)
        assertNotNull(encryptedBase64)
        assertTrue("Ciphertext must be non-empty", encryptedBase64.isNotBlank())
        assertFalse("Ciphertext must not match plain CSV", encryptedBase64 == plainCsv)
        assertFalse("Ciphertext must not contain plaintext commas", encryptedBase64.contains(","))

        // Decrypt ciphertext
        val decryptedCsv = AndroidSecurityUtils.decrypt(encryptedBase64)
        assertEquals("Decrypted CSV must match original plaintext CSV", plainCsv, decryptedCsv)

        val restoredFloats = decryptedCsv.split(",").map { it.trim().toFloat() }.toFloatArray()
        assertEquals(512, restoredFloats.size)
        for (i in 0 until 512) {
            assertEquals("Float at index $i must match within float precision", originalVec[i], restoredFloats[i], 1e-4f)
        }
    }

    @Test
    fun testZeroPlaintextVectorPersistence() {
        val originalVec = createNormalizedEmbedding(512, 2.5f)
        val plainCsv = toCsv(originalVec)
        val encryptedCsv = AndroidSecurityUtils.encrypt(plainCsv)

        val entity = FaceTemplateEntity(
            id = "tpl_secure_001",
            studentRoll = "CS2026-999",
            angleType = "MASTER_CENTROID",
            embeddingEncryptedCsv = encryptedCsv,
            isEncrypted = true,
            qualityScore = 98.0f
        )

        // 1. Plaintext check on stored entity
        assertTrue("Entity must be marked as encrypted", entity.isEncrypted)
        assertFalse("Stored entity CSV must never contain raw float delimiters", entity.embeddingEncryptedCsv.contains(","))
        assertTrue("Stored ciphertext must be at least 28 bytes base64 encoded", entity.embeddingEncryptedCsv.length >= 28)

        // 2. Preload into FaceMatcher
        matcher.preloadTemplates(listOf(entity))
        assertEquals(1, matcher.enrolledTemplateCount)

        // 3. Match against query vector
        val studentMap = mapOf("CS2026-999" to "Ada Lovelace")
        val matchResult = matcher.match(originalVec, studentMap, SecurityTier.HIGH)
        assertTrue("Enrolled student must match query vector", matchResult.isMatch)
        assertEquals("CS2026-999", matchResult.studentRoll)
        assertEquals("Ada Lovelace", matchResult.studentName)
        assertEquals(ConfidenceZone.ACCEPT, matchResult.confidenceZone)

        // 4. Corrupted ciphertext safely rejected without crash
        val corruptedEntity = FaceTemplateEntity(
            id = "tpl_corrupted_002",
            studentRoll = "CS2026-888",
            angleType = "FRONTAL",
            embeddingEncryptedCsv = java.util.Base64.getEncoder().encodeToString(ByteArray(15)), // Under 28 bytes
            isEncrypted = true
        )
        matcher.preloadTemplates(listOf(corruptedEntity))
        assertEquals("Corrupted template must be skipped during preload", 0, matcher.enrolledTemplateCount)
    }

    @Test
    fun testPrewarmingCacheSynchronization() = runBlocking {
        val fakeStore = FakeIdentityStore()
        assertEquals(0, fakeStore.getTemplateCount())

        val vec1 = createNormalizedEmbedding(512, 1.0f)
        val tpl1 = IdentityTemplate(
            identityId = "ROLL_01",
            displayName = "Alice Smith",
            role = "STUDENT",
            embedding = vec1,
            version = 1000L
        )

        // Emit new template into store
        fakeStore.addTemplate(tpl1)
        assertEquals(1, fakeStore.getTemplateCount())

        val emitted = fakeStore.observeTemplates().first()
        assertEquals(1, emitted.size)
        assertEquals("ROLL_01", emitted[0].identityId)

        // Preload directly into matcher cache (simulating engine updateVolatileTemplates)
        val cachedList = emitted.map {
            CachedBiometric(
                templateId = it.identityId,
                studentRoll = it.identityId,
                angleType = "FRONTAL",
                embedding = it.embedding
            )
        }
        matcher.preloadCachedBiometrics(cachedList)
        assertEquals(1, matcher.enrolledTemplateCount)

        val result = matcher.match(vec1, mapOf("ROLL_01" to "Alice Smith"), SecurityTier.HIGH)
        assertTrue("Immediate match without cold reboot", result.isMatch)
        assertEquals("ROLL_01", result.studentRoll)
    }

    @Test
    fun testInMemoryL2NormalizationInvariance() {
        // Generate vectors with various non-unit norms
        val highNormVec = FloatArray(512) { 50.0f }
        val smallNormVec = FloatArray(512) { 0.001f }
        val mixedVec = FloatArray(512) { i -> if (i % 2 == 0) -3.0f else 4.0f }

        val norm1 = computeL2Norm(l2Normalize(highNormVec))
        val norm2 = computeL2Norm(l2Normalize(smallNormVec))
        val norm3 = computeL2Norm(l2Normalize(mixedVec))

        assertEquals("L2 norm of high norm vector must equal 1.0", 1.0f, norm1, 1e-4f)
        assertEquals("L2 norm of small norm vector must equal 1.0", 1.0f, norm2, 1e-4f)
        assertEquals("L2 norm of mixed vector must equal 1.0", 1.0f, norm3, 1e-4f)

        // Preload unnormalized CSV into matcher - verify matcher normalizes on ingest
        val unnormalizedCsv = highNormVec.joinToString(",") { "%.4f".format(java.util.Locale.US, it) }
        val enc = AndroidSecurityUtils.encrypt(unnormalizedCsv)
        val entity = FaceTemplateEntity(
            id = "tpl_unnorm",
            studentRoll = "ROLL_NORM",
            angleType = "MASTER_CENTROID",
            embeddingEncryptedCsv = enc,
            isEncrypted = true
        )
        matcher.preloadTemplates(listOf(entity))
        assertEquals(1, matcher.enrolledTemplateCount)

        // Perfect match against normalized query
        val normalizedQuery = l2Normalize(highNormVec.copyOf())
        val res = matcher.match(normalizedQuery, mapOf("ROLL_NORM" to "Test Student"), SecurityTier.HIGH)
        assertTrue(res.isMatch)
        assertEquals(1.0f, res.similarity, 1e-3f)
    }

    @Test
    fun testDynamicCentroidAdaptationEmaConvergence() {
        val initialCentroid = createNormalizedEmbedding(512, 1.0f)
        val targetProbe = createNormalizedEmbedding(512, 1.2f)

        val initialEntity = FaceTemplateEntity(
            id = "tpl_centroid_001",
            studentRoll = "CS_EMA_01",
            angleType = "MASTER_CENTROID",
            embeddingEncryptedCsv = AndroidSecurityUtils.encrypt(toCsv(initialCentroid)),
            isEncrypted = true
        )
        matcher.preloadTemplates(listOf(initialEntity))

        var currentCentroid = initialCentroid.copyOf()
        var lastSim = 0.0f

        // Run 10 adaptation iterations
        for (step in 1..10) {
            val adaptedPair = matcher.adaptCentroidIfHighConfidence("CS_EMA_01", targetProbe, 0.85f)
            assertNotNull("Adaptation must succeed at step $step", adaptedPair)
            assertEquals("tpl_centroid_001", adaptedPair!!.first)

            val rawEncrypted = adaptedPair.second
            val decryptedCsv = AndroidSecurityUtils.decrypt(rawEncrypted)
            assertTrue("Decrypted adapted CSV must not be blank", decryptedCsv.isNotBlank())

            val adaptedVec = decryptedCsv.split(",").map { it.trim().toFloat() }.toFloatArray()
            assertEquals(512, adaptedVec.size)

            val norm = computeL2Norm(adaptedVec)
            assertEquals("Adapted vector must maintain unit L2 norm", 1.0f, norm, 1e-4f)

            // Verify similarity to target probe increases monotonically
            var sim = 0.0f
            for (i in 0 until 512) sim += adaptedVec[i] * targetProbe[i]
            if (step > 1) {
                assertTrue("Similarity to target probe must increase monotonically ($sim >= $lastSim)", sim >= lastSim - 1e-5f)
            }
            lastSim = sim
            currentCentroid = adaptedVec
        }

        assertTrue("Final adapted centroid should be significantly closer to target probe", lastSim > 0.80f)
    }

    @Test
    fun testDynamicCentroidAdaptationThresholdAndIdentityGuard() {
        val initialCentroid = createNormalizedEmbedding(512, 1.0f)
        val entity = FaceTemplateEntity(
            id = "tpl_guard_001",
            studentRoll = "CS_GUARD_01",
            angleType = "MASTER_CENTROID",
            embeddingEncryptedCsv = AndroidSecurityUtils.encrypt(toCsv(initialCentroid)),
            isEncrypted = true
        )
        matcher.preloadTemplates(listOf(entity))

        val probe = createNormalizedEmbedding(512, 1.5f)

        // 1. Sub-threshold rejection (similarity < 0.72)
        val lowSimResult = matcher.adaptCentroidIfHighConfidence("CS_GUARD_01", probe, 0.71f)
        assertNull("Similarity 0.71 must not trigger adaptation", lowSimResult)

        // 2. Blank student roll rejection
        val blankRollResult = matcher.adaptCentroidIfHighConfidence("", probe, 0.95f)
        assertNull("Blank student roll must be rejected", blankRollResult)

        // 3. GUEST student roll rejection
        val guestRollResult = matcher.adaptCentroidIfHighConfidence("GUEST", probe, 0.95f)
        assertNull("GUEST roll must never trigger centroid adaptation", guestRollResult)

        // 4. Unenrolled identity rejection
        val unknownRollResult = matcher.adaptCentroidIfHighConfidence("CS_UNKNOWN", probe, 0.95f)
        assertNull("Unregistered roll number must return null", unknownRollResult)

        // 5. Empty live embedding rejection
        val emptyVecResult = matcher.adaptCentroidIfHighConfidence("CS_GUARD_01", FloatArray(0), 0.95f)
        assertNull("Empty probe vector must return null", emptyVecResult)
    }

    @Test
    fun testVolatileCacheZeroizationOnCleanup() {
        val vec = createNormalizedEmbedding(512, 3.0f)
        val cached = CachedBiometric(
            templateId = "tpl_zeroize",
            studentRoll = "ROLL_ZEROIZE",
            angleType = "FRONTAL",
            embedding = vec.copyOf()
        )
        matcher.preloadCachedBiometrics(listOf(cached))
        assertEquals(1, matcher.enrolledTemplateCount)

        // Ensure embedding is non-zero
        assertTrue(computeL2Norm(cached.embedding) > 0.99f)

        // Clear matcher volatile caches
        matcher.clear()
        assertEquals(0, matcher.enrolledTemplateCount)

        // Verify that internal float arrays are zeroized
        for (v in cached.embedding) {
            assertEquals("Memory byte zeroization must leave float elements at 0.0f", 0.0f, v, 0.0f)
        }
    }

    @Test
    fun testConcurrentReadWriteThreadSafetyUnderHeavyLoad() {
        val templates = (1..20).map { i ->
            CachedBiometric(
                templateId = "tpl_$i",
                studentRoll = "ROLL_$i",
                angleType = "MASTER_CENTROID",
                embedding = createNormalizedEmbedding(512, i.toFloat())
            )
        }
        matcher.preloadCachedBiometrics(templates)

        val threadCount = 16
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threadCount)
        val studentMap = (1..20).associate { "ROLL_$it" to "Student $it" }

        val tasks = mutableListOf<Callable<Boolean>>()

        // Reader tasks
        for (t in 0 until 10) {
            tasks.add(Callable {
                val query = createNormalizedEmbedding(512, (t + 1).toFloat())
                for (iter in 0 until iterationsPerThread) {
                    val result = matcher.match(query, studentMap, SecurityTier.HIGH)
                    assertNotNull(result)
                }
                true
            })
        }

        // Writer tasks (dynamic centroid adaptation)
        for (t in 10 until 16) {
            val rollId = "ROLL_${t - 9}"
            tasks.add(Callable {
                val liveProbe = createNormalizedEmbedding(512, (t - 9).toFloat() + 0.05f)
                for (iter in 0 until iterationsPerThread) {
                    matcher.adaptCentroidIfHighConfidence(rollId, liveProbe, 0.85f)
                }
                true
            })
        }

        val futures: List<Future<Boolean>> = executor.invokeAll(tasks)
        for (future in futures) {
            assertTrue("Concurrent task must complete successfully without exception", future.get())
        }

        executor.shutdown()
        assertEquals(20, matcher.enrolledTemplateCount)
    }

    @Test
    fun testIdentityStoreUpdateTemplateEmbeddingPersistence() = runBlocking {
        val fakeStore = FakeIdentityStore()
        val templateId = "tpl_adapt_store_01"

        fakeStore.addTemplate(
            IdentityTemplate(
                identityId = "ROLL_ADAPT",
                displayName = "Grace Hopper",
                role = "STUDENT",
                embedding = createNormalizedEmbedding(512, 1.0f),
                version = 100L
            )
        )

        val updatedEncryptedCsv = AndroidSecurityUtils.encrypt("1.05,2.05,3.05")
        fakeStore.updateTemplateEmbedding(templateId, updatedEncryptedCsv)

        assertTrue("FakeIdentityStore must record updated embedding", fakeStore.updatedEmbeddings.containsKey(templateId))
        assertEquals(updatedEncryptedCsv, fakeStore.updatedEmbeddings[templateId])
    }
}
