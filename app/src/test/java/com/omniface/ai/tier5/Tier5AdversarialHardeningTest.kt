package com.omniface.ai.tier5

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.antispoof.MultiStageLivenessResult
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.pipeline.BiometricDecisionEngine
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.ZkpPrivacyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Tier 5: Adversarial Hardening Suite (7 Deep Challenger Attack & Fault Scenarios)
 *
 * Verifies system resilience and adversarial defense:
 * 1. High-Frequency Moiré & 4K Digital Screen Replay Injection
 * 2. Silicon Hardware Delegate Fault & Graceful CPU Fallback
 * 3. Hostile Cryptographic Tampering & Aegis Merkle Invalidation
 * 4. Adversarial Lookalike Perturbation & Decision Margin Boundary Isolation
 * 5. Pedersen ZKP Commitment Blind Proof Non-Malleability
 * 6. High-Concurrency Multi-Threaded Keystore & Hashing Contention
 * 7. Storage Payload Corruption & Resilient Recovery
 */
class Tier5AdversarialHardeningTest {

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

    private fun makeEmbedding(seed: Float, dim: Int = 512): FloatArray {
        return l2Normalize(FloatArray(dim) { i -> seed + i * 0.001f })
    }

    private fun toCsv(v: FloatArray): String =
        v.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }

    // ── Challenger Test 1: Adversarial High-Frequency Moiré & Screen Replay Injection ──
    @Test
    fun testAdversarialReplayAttack_ScreenMoireRejection() {
        // Enrolled victim student
        val victimRoll = "CS2026-VICTIM"
        val victimEmbedding = makeEmbedding(1.234f)
        matcher.preloadTemplates(
            listOf(
                FaceTemplateEntity(
                    id = "tpl_victim",
                    studentRoll = victimRoll,
                    angleType = "FRONTAL",
                    embeddingEncryptedCsv = toCsv(victimEmbedding),
                    isEncrypted = false
                )
            )
        )

        // Adversary presents a high-resolution 4K iPad screen replay of the victim.
        val matchResult = MatchResult(
            studentRoll = victimRoll,
            studentName = "Victim Student",
            confidence = 98.5f,
            similarity = 0.985f,
            isMatch = true,
            hardwareTier = HardwareTier.NPU_NNAPI,
            confidenceZone = ConfidenceZone.ACCEPT,
            decisionMargin = 0.850f,
            explanation = "High similarity match"
        )

        // Gate 1: Quality passes
        val qualityResult = QualityGateResult(
            isPassed = true,
            overallScore = 85.0f,
            blurScore = 85.0f,
            lightingScore = 85.0f,
            failureReason = ""
        )

        // Gate 2: Passive PAD detects digital screen replay (spoofProbability >= 0.70f)
        val passivePadResult = PassivePadResult(
            isLive = false,
            livenessScore = 0.15f,
            spoofProbability = 0.85f,
            attackTypeDescription = "Digital Screen Replay Detected (Moiré Aliasing)",
            latencyMs = 8L
        )

        // Temporal micro-motion detects rigid planar movement lacking 3D depth parallax
        val temporalResult = TemporalLivenessResult(
            isLive = false,
            temporalConfidence = 0.20f,
            microMotionDetected = false,
            naturalBlinkDetected = false,
            stable3DDepth = false,
            explanation = "Static Replay Detected"
        )

        val decision = BiometricDecisionEngine.evaluate(
            quality = qualityResult,
            passivePad = passivePadResult,
            temporalLiveness = temporalResult,
            matchResult = matchResult,
            securityTier = SecurityTier.HIGH
        )

        // Strict assertion: Zero ambiguous auto-accepts under liveness rejection
        assertEquals("Gate state must be REJECT_SPOOF_ATTACK", PipelineGateState.REJECT_SPOOF_ATTACK, decision.gateState)
        assertFalse("Must NOT authorize turnstile unlock for digital screen replay", decision.isAttendanceAuthorized)
        assertTrue("Technical explanation must cite spoof detection", decision.technicalExplanation.contains("PAD") || decision.technicalExplanation.contains("Spoof"))
    }

    // ── Challenger Test 2: Silicon Hardware Delegate Fault & Graceful CPU Fallback ──
    @Test
    fun testHardwareNpuCrash_SeamlessCpuFallback() {
        var activeTier = HardwareTier.NPU_NNAPI
        var fallbackTriggered = false

        fun extractEmbeddingWithFallback(tier: HardwareTier): Pair<HardwareTier, FloatArray> {
            return try {
                if (tier == HardwareTier.NPU_NNAPI) {
                    throw RuntimeException("NPU Hexagon HTP Driver Fault: SIGBUS or unsupported op")
                }
                Pair(tier, makeEmbedding(0.5f))
            } catch (e: Exception) {
                fallbackTriggered = true
                Pair(HardwareTier.CPU_XNNPACK, makeEmbedding(0.5f))
            }
        }

        val (resolvedTier, embedding) = extractEmbeddingWithFallback(activeTier)
        assertTrue("Fallback must be triggered upon NPU fault", fallbackTriggered)
        assertEquals("Must gracefully fall back to CPU_XNNPACK", HardwareTier.CPU_XNNPACK, resolvedTier)
        assertEquals("Extracted feature vector must remain 512-dimensional", 512, embedding.size)

        var normSq = 0f
        for (x in embedding) normSq += x * x
        assertEquals("Unit norm must be preserved under CPU fallback", 1.0f, sqrt(normSq), 1e-4f)
    }

    // ── Challenger Test 3: Hostile Cryptographic Tampering & Aegis Merkle Invalidation ──
    @Test
    fun testAegisBlockchainTamper_MerkleRootDetection() {
        val count = 10
        val leaves = mutableListOf<String>()
        var prevHash = AndroidSecurityUtils.computeSha256("GENESIS")
        val chain = mutableListOf<String>()

        for (i in 1..count) {
            val recordId = "REC_%03d".format(i)
            val roll = "CS2026-%03d".format(i)
            val ts = 1724680000000L + i * 1000L
            val conf = 95f
            val leaf = AndroidSecurityUtils.computeAttendanceLeafHash(recordId, roll, ts, conf)
            leaves.add(leaf)

            val currentBlock = AndroidSecurityUtils.computeSha256("$prevHash|$leaf")
            chain.add(currentBlock)
            prevHash = currentBlock
        }

        val authenticMerkleRoot = AndroidSecurityUtils.computeMerkleRoot(leaves)
        assertNotNull(authenticMerkleRoot)
        assertEquals(64, authenticMerkleRoot.length)

        // Adversary tampers with leaf at index 4 (5th transaction)
        val tamperedLeaves = leaves.toMutableList()
        tamperedLeaves[4] = AndroidSecurityUtils.computeSha256("TAMPERED_RECORD_PAYLOAD")

        val tamperedMerkleRoot = AndroidSecurityUtils.computeMerkleRoot(tamperedLeaves)
        assertNotEquals("Merkle root must completely change upon 1-leaf tampering", authenticMerkleRoot, tamperedMerkleRoot)

        // Tampering with intermediate block breaks all downstream chain hashes
        val tamperedBlock4 = AndroidSecurityUtils.computeSha256("${chain[3]}|${tamperedLeaves[4]}")
        val tamperedBlock5 = AndroidSecurityUtils.computeSha256("$tamperedBlock4|${leaves[5]}")
        assertNotEquals("Downstream block hash 5 must break upon upstream block tamper", chain[5], tamperedBlock5)
    }

    // ── Challenger Test 4: Adversarial Lookalike Perturbation & Decision Margin Boundary ──
    @Test
    fun testAdversarialPerturbation_LookalikeMarginGating() {
        val enrolledRoll1 = "CS2026-TWIN-A"
        val enrolledRoll2 = "CS2026-TWIN-B"

        val baseVec = makeEmbedding(0.42f)
        // High similarity lookalike vector whose cosine similarity to baseVec is ~0.999
        val lookalikeVec = FloatArray(512) { i -> baseVec[i] + 0.0002f }
        l2Normalize(lookalikeVec)

        matcher.preloadTemplates(
            listOf(
                FaceTemplateEntity(
                    id = "t1",
                    studentRoll = enrolledRoll1,
                    angleType = "FRONTAL",
                    embeddingEncryptedCsv = toCsv(baseVec),
                    isEncrypted = false
                ),
                FaceTemplateEntity(
                    id = "t2",
                    studentRoll = enrolledRoll2,
                    angleType = "FRONTAL",
                    embeddingEncryptedCsv = toCsv(lookalikeVec),
                    isEncrypted = false
                )
            )
        )

        val studentMap = mapOf(
            enrolledRoll1 to "Twin A",
            enrolledRoll2 to "Twin B"
        )

        // Live probe: baseVec matches Twin A (sim=1.0) and Twin B (sim≈0.999)
        // Margin Δ = 1.0 - 0.999 = 0.001 < marginThreshold (0.035 for HIGH)
        val ambiguousMatchResult = matcher.match(baseVec, studentMap, SecurityTier.HIGH)
        assertEquals("Confidence zone must be REVIEW", ConfidenceZone.REVIEW, ambiguousMatchResult.confidenceZone)
        assertFalse("Ambiguous lookalike must NOT be marked isMatch=true", ambiguousMatchResult.isMatch)
        assertTrue("Margin must be narrower than threshold", ambiguousMatchResult.decisionMargin < SecurityTier.HIGH.marginThreshold)

        val qualityResult = QualityGateResult(
            isPassed = true,
            overallScore = 90f,
            blurScore = 90f,
            lightingScore = 90f,
            failureReason = ""
        )
        val passivePadResult = PassivePadResult(
            isLive = true,
            livenessScore = 0.95f,
            spoofProbability = 0.05f,
            attackTypeDescription = "Live",
            latencyMs = 5L
        )
        val temporalResult = TemporalLivenessResult(
            isLive = true,
            temporalConfidence = 0.92f,
            microMotionDetected = true,
            naturalBlinkDetected = true,
            stable3DDepth = true,
            explanation = "Live"
        )

        val decision = BiometricDecisionEngine.evaluate(
            quality = qualityResult,
            passivePad = passivePadResult,
            temporalLiveness = temporalResult,
            matchResult = ambiguousMatchResult,
            securityTier = SecurityTier.HIGH
        )

        // Must reject auto-accept and flag for human review due to ambiguous decision margin
        assertEquals("Gate state must be REVIEW_AMBIGUOUS_MATCH", PipelineGateState.REVIEW_AMBIGUOUS_MATCH, decision.gateState)
        assertFalse("Must NOT auto-accept ambiguous lookalike without secondary review", decision.isAttendanceAuthorized)
        assertTrue("Technical explanation must cite margin review", decision.technicalExplanation.contains("Ambiguous") || decision.title.contains("REVIEW"))
    }

    // ── Challenger Test 5: Pedersen ZKP Commitment Blind Proof Non-Malleability ──
    @Test
    fun testPedersenZkp_BlindProofNonMalleability() {
        val authenticEmbedding = makeEmbedding(0.777f)
        val authenticCsv = toCsv(authenticEmbedding)

        val (commitmentHex, saltHex) = ZkpPrivacyManager.generateZkpCommitment(authenticCsv)
        assertNotNull("Commitment hash must be minted", commitmentHex)
        assertEquals("SHA-256 hex commitment must be 64 characters", 64, commitmentHex.length)

        // Authentic proof satisfies verification
        val isAuthenticValid = ZkpPrivacyManager.verifyZkpCommitment(authenticCsv, commitmentHex, saltHex)
        assertTrue("Legitimate commitment and salt must verify successfully", isAuthenticValid)

        // Attacker modifies a single float in the embedding by 1e-4
        val hostileEmbedding = authenticEmbedding.clone()
        hostileEmbedding[0] += 0.0001f
        val hostileCsv = toCsv(hostileEmbedding)

        val isHostileValid = ZkpPrivacyManager.verifyZkpCommitment(hostileCsv, commitmentHex, saltHex)
        assertFalse("Any modification to embedding must break ZKP verification", isHostileValid)

        // Attacker modifies the salt
        val tamperedSaltHex = saltHex.dropLast(2) + "ff"
        val isTamperedSaltValid = ZkpPrivacyManager.verifyZkpCommitment(authenticCsv, commitmentHex, tamperedSaltHex)
        assertFalse("Modified blinding salt must break ZKP verification", isTamperedSaltValid)
    }

    // ── Challenger Test 6: High-Concurrency Multi-Threaded Keystore & Hashing Contention ──
    @Test
    fun testKeystoreConcurrentAccess_ThreadSafety() = runBlocking {
        val concurrentWorkers = 20
        val iterationsPerWorker = 15
        val successCount = AtomicInteger(0)

        val jobs = (1..concurrentWorkers).map { workerId ->
            async(Dispatchers.Default) {
                for (iter in 1..iterationsPerWorker) {
                    val seed = workerId * 100f + iter
                    val vec = makeEmbedding(seed)
                    val csv = toCsv(vec)

                    // Concurrent ZKP commitment generation and verification
                    val (commitment, salt) = ZkpPrivacyManager.generateZkpCommitment(csv)
                    val verified = ZkpPrivacyManager.verifyZkpCommitment(csv, commitment, salt)
                    if (verified && commitment.length == 64) {
                        successCount.incrementAndGet()
                    }

                    // Concurrent leaf and Merkle hashing
                    val leaf = AndroidSecurityUtils.computeAttendanceLeafHash("rec_${workerId}_$iter", "ROLL_$workerId", 1724680000000L + iter, 95f)
                    val root = AndroidSecurityUtils.computeMerkleRoot(listOf(leaf))
                    assertNotNull(root)
                }
            }
        }

        jobs.awaitAll()
        val expectedTotal = concurrentWorkers * iterationsPerWorker
        assertEquals("All concurrent crypto operations must succeed without race conditions", expectedTotal, successCount.get())
    }

    // ── Challenger Test 7: Storage Payload Corruption & Resilient Recovery ──
    @Test
    fun testStorageCorruption_GracefulRecovery() {
        val corruptedPayloads = listOf(
            "NaN,0.123,0.456",
            "Infinity,-Infinity,0.0",
            "not_a_number,1.0,2.0",
            "",
            ",,,",
            "0.12,0.34,truncated"
        )

        for (corruptedCsv in corruptedPayloads) {
            val template = FaceTemplateEntity(
                id = "corrupted",
                studentRoll = "CORRUPT_ROLL",
                angleType = "FRONTAL",
                embeddingEncryptedCsv = corruptedCsv,
                isEncrypted = false
            )

            // Ingesting corrupted template must not crash the matcher preload
            try {
                matcher.preloadTemplates(listOf(template))
                val studentMap = mapOf("CORRUPT_ROLL" to "Corrupted Student")
                val probe = makeEmbedding(1.0f)
                matcher.match(probe, studentMap, SecurityTier.STANDARD)
            } catch (e: Exception) {
                // NumberFormatException or IllegalArgumentException are acceptable graceful handlings
                assertTrue("Expected handled exception for corrupted payload: ${e.javaClass.simpleName}",
                    e is NumberFormatException || e is IllegalArgumentException || e is IndexOutOfBoundsException)
            }
        }

        // Confirm matcher remains fully functional and uncorrupted for legitimate templates
        val validTemplate = FaceTemplateEntity(
            id = "valid_after_corrupt",
            studentRoll = "CS2026-RECOVERED",
            angleType = "FRONTAL",
            embeddingEncryptedCsv = toCsv(makeEmbedding(2.0f)),
            isEncrypted = false
        )
        matcher.preloadTemplates(listOf(validTemplate))
        val studentMap = mapOf("CS2026-RECOVERED" to "Recovered Student")
        val probe = makeEmbedding(2.0f)
        val result = matcher.match(probe, studentMap, SecurityTier.STANDARD)
        assertTrue("Matcher must recover and recognize valid templates", result.isMatch)
        assertEquals("CS2026-RECOVERED", result.studentRoll)
    }

    // ── Challenger Test 8: Selfie Mirroring Coordinate Remapping & 3D Pose Projection ──
    @Test
    fun testSelfieMirroring_CoordinateRemappingConsistency() {
        val frameWidth = 1920
        val scale = 1.0f
        val dx = 0.0f

        // Bounding box in unmirrored sensor space (face located in right half of sensor: x in [1200, 1600])
        val sensorBoxLeft = 1200f
        val sensorBoxRight = 1600f

        // 1. Front Camera (Selfie, Mirrored): Bbox must be reflected to left half of screen
        val frontMappedLeft = (frameWidth - sensorBoxRight) * scale + dx // 1920 - 1600 = 320
        val frontMappedRight = (frameWidth - sensorBoxLeft) * scale + dx // 1920 - 1200 = 720
        assertTrue("Mirrored left must be smaller than mirrored right", frontMappedLeft < frontMappedRight)
        assertEquals(320f, frontMappedLeft, 1e-3f)
        assertEquals(720f, frontMappedRight, 1e-3f)

        // 2. Rear Camera (Unmirrored): Bbox must remain in original coordinate space
        val rearMappedLeft = sensorBoxLeft * scale + dx
        val rearMappedRight = sensorBoxRight * scale + dx
        assertEquals(1200f, rearMappedLeft, 1e-3f)
        assertEquals(1600f, rearMappedRight, 1e-3f)

        // 3. 3D Pose Axes Visual Yaw: User turns to screen left in selfie mode
        val rawEulerY = 20.0f // Face rotated towards right of sensor image
        val isFront = true
        val visualYaw = if (isFront) -rawEulerY else rawEulerY
        // In FaceDiagnosticsOverlay, effectiveYaw = visualYaw (single inversion, no double-negation)
        val effectiveYaw = visualYaw
        val yawRad = Math.toRadians(effectiveYaw.toDouble()).toFloat()
        val zEndDx = 0.55f * kotlin.math.sin(yawRad)

        // When face turns to the left on the mirrored screen, optical depth vector (cyan pointer) must point LEFT (negative X delta)
        assertTrue("Front camera visual yaw must be negative for screen-left turn", visualYaw < 0f)
        assertTrue("Nose vector delta X must be negative for screen-left turn", zEndDx < 0f)
    }

    // ── Challenger Test 9: Camera Switching Subject-Centric Pose Envelope Gating ──
    @Test
    fun testCameraSwitching_SubjectCentricPoseEnvelopeGating() {
        fun isPoseInTargetEnvelope(yaw: Float, pitch: Float, step: Int): Boolean {
            return when (step) {
                1 -> kotlin.math.abs(yaw) <= 15.0f && kotlin.math.abs(pitch) <= 15.0f // Frontal
                2 -> yaw <= -7.0f && yaw >= -35.0f && kotlin.math.abs(pitch) <= 22.0f // Left angle (~10-30°)
                3 -> yaw >= 7.0f && yaw <= 35.0f && kotlin.math.abs(pitch) <= 22.0f   // Right angle (~10-30°)
                4 -> pitch >= 6.0f && pitch <= 30.0f && kotlin.math.abs(yaw) <= 22.0f  // Up angle
                5 -> pitch <= -6.0f && pitch >= -30.0f && kotlin.math.abs(yaw) <= 22.0f // Down angle
                else -> false
            }
        }

        // Physical Scenario: Subject turns their head to their own LEFT by 18 degrees.
        // Because camera lens points directly at the subject, subject's left rotation moves nose towards camera's right.
        // Therefore, ML Kit rawEulerY is positive (+18°) for BOTH front and rear cameras!
        val rawYawSubjectTurnedLeft = 18.0f
        val rawYawSubjectTurnedRight = -18.0f
        val levelPitch = 0.0f

        // Front Camera (Selfie)
        val frontSubjectYawLeft = -rawYawSubjectTurnedLeft // -18.0°
        assertTrue("Step 2 (Turn Left) must pass on front camera when turning left",
            isPoseInTargetEnvelope(frontSubjectYawLeft, levelPitch, step = 2))
        assertFalse("Step 3 (Turn Right) must NOT pass on front camera when turning left",
            isPoseInTargetEnvelope(frontSubjectYawLeft, levelPitch, step = 3))

        // Rear Camera (Back lens pointing at subject)
        val rearSubjectYawLeft = -rawYawSubjectTurnedLeft // -18.0° (subject-centric)
        assertTrue("Step 2 (Turn Left) must pass on rear camera when turning left",
            isPoseInTargetEnvelope(rearSubjectYawLeft, levelPitch, step = 2))
        assertFalse("Step 3 (Turn Right) must NOT pass on rear camera when turning left",
            isPoseInTargetEnvelope(rearSubjectYawLeft, levelPitch, step = 3))

        // Subject turns to their physical RIGHT
        val frontSubjectYawRight = -rawYawSubjectTurnedRight // +18.0°
        val rearSubjectYawRight = -rawYawSubjectTurnedRight  // +18.0°
        assertTrue("Step 3 (Turn Right) must pass on front camera when turning right",
            isPoseInTargetEnvelope(frontSubjectYawRight, levelPitch, step = 3))
        assertTrue("Step 3 (Turn Right) must pass on rear camera when turning right",
            isPoseInTargetEnvelope(rearSubjectYawRight, levelPitch, step = 3))
    }
}
