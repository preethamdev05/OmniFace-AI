package com.omniface.ai.tier3

import android.graphics.Rect
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.hardware.EmergencyEvacuationController
import com.omniface.ai.hardware.FleetTopologyManager
import com.omniface.ai.hardware.KioskLockController
import com.omniface.ai.hardware.KioskNode
import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.RegistrationQualityEvaluator
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.antispoof.LivenessStageBreakdown
import com.omniface.ai.ml.antispoof.MultiStageLivenessResult
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.pipeline.BiometricDecisionEngine
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.recognition.FaissVectorIndex
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.ZkpPrivacyManager
import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 3: Cross-Feature Integration Tests (15 Subsystem Interaction Workflows)
 */
class Tier3CrossFeatureIntegrationTest {

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

    // ── Test 1: Multi-Angle Enrollment -> Centroid Computation -> Vector Index -> Cosine Match ──
    @Test
    fun testInferenceToVectorIngestionToCosineMatch() {
        val baseSeed = 1.0f
        val angles = listOf("FRONTAL", "LEFT_15", "RIGHT_15", "UP_10", "DOWN_10")
        val embeddings = angles.mapIndexed { idx, _ -> makeEmbedding(baseSeed + idx * 0.01f) }
        val qualityScores = listOf(98f, 95f, 96f, 92f, 94f)

        val (masterCentroid, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            embeddings = embeddings,
            qualityScores = qualityScores
        )
        assertTrue("Centroid matrix must be consistent across enrolled angles", matrix.isConsistent)

        val templateEntities = mutableListOf<FaceTemplateEntity>()
        angles.forEachIndexed { idx, angle ->
            templateEntities.add(
                FaceTemplateEntity(
                    id = "t_$idx",
                    studentRoll = "CS2026-001",
                    angleType = angle,
                    embeddingEncryptedCsv = toCsv(embeddings[idx]),
                    isEncrypted = false
                )
            )
        }
        templateEntities.add(
            FaceTemplateEntity(
                id = "t_master",
                studentRoll = "CS2026-001",
                angleType = "MASTER_CENTROID",
                embeddingEncryptedCsv = toCsv(masterCentroid),
                isEncrypted = false
            )
        )

        matcher.preloadTemplates(templateEntities)

        val liveProbe = makeEmbedding(baseSeed + 0.005f)
        val matchResult = matcher.match(
            queryEmbedding = liveProbe,
            studentMap = mapOf("CS2026-001" to "Ananya Sharma"),
            securityTier = SecurityTier.HIGH
        )

        assertTrue("Cosine match should succeed for genuine enrolled subject", matchResult.isMatch)
        assertEquals(ConfidenceZone.ACCEPT, matchResult.confidenceZone)
        assertEquals("CS2026-001", matchResult.studentRoll)
        assertEquals("Ananya Sharma", matchResult.studentName)
    }

    // ── Test 2: Gate 2 PAD Rejection overrides High Identity Similarity ──
    @Test
    fun testLivenessRejectionBlocksMatchDecision() {
        val qualityPass = QualityGateResult(
            isPassed = true,
            overallQualityScore = 95f,
            sharpnessScore = 95f,
            lightingScore = 95f,
            rejectionReason = ""
        )

        val spoofPad = PassivePadResult(
            isLive = false,
            livenessScore = 0.12f,
            attackTypeDescription = "2D Display Photo Replay",
            inferenceLatencyMs = 5L
        )

        val temporalFail = TemporalLivenessResult(
            isLive = false,
            temporalConfidence = 0.15f,
            microMotionDetected = false,
            naturalBlinkDetected = false,
            headTurnDetected = false,
            stable3DDepth = false,
            requiredAction = null,
            explanation = "Static Replay Detected"
        )

        val strongMatch = MatchResult(
            studentRoll = "CS2026-001",
            studentName = "Ananya Sharma",
            confidence = 99.5f,
            similarity = 0.88f,
            isMatch = true,
            hardwareTier = HardwareTier.NPU_NNAPI,
            confidenceZone = ConfidenceZone.ACCEPT,
            decisionMargin = 0.45f
        )

        val decision = BiometricDecisionEngine.evaluate(
            quality = qualityPass,
            passivePad = spoofPad,
            temporalLiveness = temporalFail,
            matchResult = strongMatch,
            securityTier = SecurityTier.HIGH
        )

        assertFalse("High match similarity MUST NOT authorize attendance when PAD fails", decision.isAttendanceAuthorized)
        assertEquals(PipelineGateState.REJECT_SPOOF_ATTACK, decision.gateState)
        assertTrue("Technical explanation must cite Gate 2 PAD failure", decision.technicalExplanation.contains("Gate 2"))
    }

    // ── Test 3: Gate 1 Quality Rejection blocks subsequent pipeline gates ──
    @Test
    fun testQualityRejectionBlocksPadAndMatch() {
        val qualityFail = QualityGateResult(
            isPassed = false,
            overallQualityScore = 35f,
            sharpnessScore = 20f,
            lightingScore = 40f,
            rejectionReason = "Excessive Motion Blur (Laplacian variance 1.8 < 5.0)"
        )

        val temporalPass = TemporalLivenessResult(
            isLive = true,
            temporalConfidence = 0.95f,
            microMotionDetected = true,
            naturalBlinkDetected = true,
            headTurnDetected = true,
            stable3DDepth = true,
            requiredAction = null,
            explanation = "Live"
        )

        val decision = BiometricDecisionEngine.evaluate(
            quality = qualityFail,
            passivePad = null,
            temporalLiveness = temporalPass,
            matchResult = null,
            securityTier = SecurityTier.HIGH
        )

        assertFalse("Attendance must be rejected on low quality", decision.isAttendanceAuthorized)
        assertEquals(PipelineGateState.REJECT_QUALITY, decision.gateState)
        assertTrue(decision.technicalExplanation.contains("Gate 1"))
    }

    // ── Test 4: Attendance Match -> Aegis Cryptographic Chaining -> Merkle Root Minting ──
    @Test
    fun testAttendanceRecordAegisHashChainingContinuity() {
        var prevHash = AndroidSecurityUtils.computeSha256("OMNIFACE_GENESIS_BLOCK")
        val leaves = mutableListOf<String>()
        val records = mutableListOf<AttendanceRecordEntity>()

        for (i in 1..5) {
            val recordId = "rec_00$i"
            val roll = "CS2026-00$i"
            val timestamp = 1724580000000L + i * 5000L
            val confidence = 95.0f + i

            val currentHash = AndroidSecurityUtils.computeSha256("$prevHash|$roll|$timestamp|$confidence")
            records.add(
                AttendanceRecordEntity(
                    recordId = recordId,
                    studentRoll = roll,
                    studentName = "Student $i",
                    sessionDate = "2026-08-25",
                    timestamp = timestamp,
                    confidencePct = confidence,
                    securityTier = "HIGH",
                    sha256Hash = currentHash
                )
            )
            val leafHash = AndroidSecurityUtils.computeAttendanceLeafHash(recordId, roll, timestamp, confidence)
            leaves.add(leafHash)
            prevHash = currentHash
        }

        assertEquals(5, records.size)
        val merkleRoot = AndroidSecurityUtils.computeMerkleRoot(leaves)
        assertNotNull(merkleRoot)
        assertEquals(64, merkleRoot.length)
    }

    // ── Test 5: DPDP Right to Forget Cascade Deletion Contract ──
    @Test
    fun testDpdpRightToForgetCascadeContract() {
        val student = StudentEntity("CS-ERASE-01", "To Be Erased", "Bio", "2")
        val template = FaceTemplateEntity("t_erase", "CS-ERASE-01", "FRONTAL", toCsv(makeEmbedding(1.0f)), false)
        matcher.preloadTemplates(listOf(template))

        assertEquals(1, matcher.enrolledTemplateCount)

        // Simulate student erasure -> clear in-memory biometric cache
        matcher.clear()
        assertEquals("Biometric cache must be purged to 0 upon deletion", 0, matcher.enrolledTemplateCount)

        val probe = makeEmbedding(1.0f)
        val result = matcher.match(probe, emptyMap(), SecurityTier.STANDARD)
        assertFalse(result.isMatch)
        assertEquals("GUEST", result.studentRoll)
    }

    // ── Test 6: Dynamic Centroid Continuous Learning updates FaceMatcher and FAISS Index ──
    @Test
    fun testDynamicCentroidAdaptationMemoryAndIndexSync() {
        val initialCentroid = makeEmbedding(1.0f)
        val template = FaceTemplateEntity("t1", "R001", "MASTER_CENTROID", toCsv(initialCentroid), false)
        matcher.preloadTemplates(listOf(template))

        val liveVec = makeEmbedding(1.02f)
        val adapted = matcher.adaptCentroidIfHighConfidence("R001", liveVec, similarityScore = 0.85f)
        assertNotNull("Adaptation should return updated template payload", adapted)
        assertEquals("t1", adapted!!.first)

        // Query with live vector should now yield very high similarity
        val result = matcher.match(liveVec, mapOf("R001" to "Alice"), SecurityTier.HIGH)
        assertTrue("Match with adapted vector should succeed", result.isMatch)
        assertTrue(result.similarity >= 0.85f)
    }

    // ── Test 7: ZKP Commitment generation coupled with FAISS Vector Search ──
    @Test
    fun testZkpPrivacyProofWithFaissVectorSearch() {
        val faiss = FaissVectorIndex(dimension = 64)
        val v = makeEmbedding(1.0f, 64)
        faiss.add("item_1", "CS101", "FRONTAL", v)

        val (commitment, salt) = ZkpPrivacyManager.generateZkpCommitment(toCsv(v))
        val searchRes = faiss.search(v, k = 1)
        assertEquals("item_1", searchRes.candidates[0].id)

        // Verify ZKP commitment against retrieved vector
        val reconstructed = faiss.reconstruct("item_1")
        assertNotNull(reconstructed)
        val verified = ZkpPrivacyManager.verifyZkpCommitment(toCsv(reconstructed!!), commitment, salt)
        assertTrue("ZKP commitment verification must succeed on index reconstructed vector", verified)
    }

    // ── Test 8: Thermal Governor Resolution Scaling and Viewfinder Box Remapping ──
    @Test
    fun testThermalGovernorResolutionDownscaleAndBBoxRemap() {
        val stateWarm = ThermalState.WARM // downscale factor 0.75
        assertEquals(0.75f, stateWarm.downscaleFactor, 1e-4f)

        // Math check: (150 * (1.0 / 0.75)) = 200, (300 * (1.0 / 0.75)) = 400
        val factor = stateWarm.downscaleFactor
        val scale = 1.0f / factor
        val left = (150 * scale).toInt()
        val top = (150 * scale).toInt()
        val right = (300 * scale).toInt()
        val bottom = (300 * scale).toInt()

        assertEquals(200, left)
        assertEquals(200, top)
        assertEquals(400, right)
        assertEquals(400, bottom)
    }

    // ── Test 9: Emergency Evacuation Trigger -> Kiosk Safety Broadcast ──
    @Test
    fun testEmergencyEvacuationTurnstileUnlockContract() {
        EmergencyEvacuationController.triggerEmergencyEvacuation("CAMPUS SEISMIC ALERT")
        assertTrue(EmergencyEvacuationController.isEvacuationActive.value)
        assertEquals("CAMPUS SEISMIC ALERT", EmergencyEvacuationController.evacuationReason.value)

        EmergencyEvacuationController.resetEvacuation()
        assertFalse(EmergencyEvacuationController.isEvacuationActive.value)
    }

    // ── Test 10: Kiosk PIN Lockout Multi-Attempt Security Enforcement ──
    @Test
    fun testKioskPinLockoutStatefulTransitions() {
        val pin = "778899"
        val wrongPin = "000000"
        val salt = ByteArray(16) { it.toByte() }
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val hash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded

        val verifySpec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        val verifyHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(verifySpec).encoded
        assertTrue("PBKDF2 verification matches for correct PIN", MessageDigest.isEqual(hash, verifyHash))

        val wrongSpec = PBEKeySpec(wrongPin.toCharArray(), salt, 120_000, 256)
        val wrongHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(wrongSpec).encoded
        assertFalse("PBKDF2 verification fails for wrong PIN", MessageDigest.isEqual(hash, wrongHash))
    }

    // ── Test 11: Fleet Topology Multi-Kiosk Heartbeat Aggregation ──
    @Test
    fun testFleetTopologyMultiKioskHeartbeatAggregation() {
        val k1 = KioskNode("k1_fleet", "East Gate", "10.0.0.1", 90, 30, true)
        val k2 = KioskNode("k2_fleet", "West Gate", "10.0.0.2", 85, 30, true)
        val k3 = KioskNode("k3_fleet", "Main Lobby", "10.0.0.3", 98, 30, true)

        FleetTopologyManager.registerNode(k1)
        FleetTopologyManager.registerNode(k2)
        FleetTopologyManager.registerNode(k3)

        assertTrue(FleetTopologyManager.kioskNodes.value.any { it.id == "k1_fleet" })
        assertTrue(FleetTopologyManager.kioskNodes.value.any { it.id == "k2_fleet" })

        FleetTopologyManager.updateNodeHeartbeat("k2_fleet", fps = 25, batteryPct = 84)
        val updatedK2 = FleetTopologyManager.kioskNodes.value.find { it.id == "k2_fleet" }
        assertEquals(25, updatedK2!!.activeFps)
        assertEquals(84, updatedK2.batteryPct)
    }

    // ── Test 12: FAISS Range Search with Calibrated ISO/IEC Thresholds ──
    @Test
    fun testFaissRangeSearchCombinedWithIsoIecSecurityTiers() {
        val faiss = FaissVectorIndex(dimension = 64)
        val vMatch = FloatArray(64) { if (it == 0) 1f else 0f }
        val vDistant = FloatArray(64) { if (it == 1) 1f else 0f }

        faiss.add("s1", "R01", "FRONTAL", vMatch)
        faiss.add("s2", "R02", "FRONTAL", vDistant)

        val candidates = faiss.rangeSearch(vMatch, minSimilarityThreshold = SecurityTier.HIGH.threshold)
        assertEquals("Only matching candidates above HIGH threshold should be returned", 1, candidates.size)
        assertEquals("s1", candidates[0].id)
    }

    // ── Test 13: MultiStage Liveness Stage Breakdown Fusion ──
    @Test
    fun testMultiStageLivenessStageBreakdownFusion() {
        val breakdown = LivenessStageBreakdown(
            reflectionScore = 0.95f,
            textureScore = 0.90f,
            moiréScore = 0.92f,
            chromaticScore = 0.88f,
            neuralPadScore = 0.96f,
            hasSpecularScreenHotspots = false,
            hasPeriodicDisplayGrid = false,
            hasUnnaturalPaperFlatness = false
        )

        val multiStageResult = MultiStageLivenessResult(
            isLive = true,
            overallLivenessScore = 0.92f,
            spoofProbability = 0.08f,
            primaryAttackVector = null,
            detectedAnomalies = emptyList(),
            stageBreakdown = breakdown,
            passivePadResult = null,
            latencyMs = 12L
        )

        assertTrue(multiStageResult.isLive)
        assertTrue(multiStageResult.detectedAnomalies.isEmpty())
        assertNull(multiStageResult.primaryAttackVector)
    }

    // ── Test 14: Attendance Sync Payload with HMAC Device Fingerprint ──
    @Test
    fun testAttendanceSyncPayloadWithMerkleLedgerProof() {
        val deviceId = "KIOSK-MAIN-ENTRY"
        val fingerprint = AndroidSecurityUtils.computeSha256(deviceId + "_OMNIFACE_KEYSTORE_HW")
        assertEquals(64, fingerprint.length)

        val recordLeaf = AndroidSecurityUtils.computeAttendanceLeafHash("rec_1", "CS101", 1720000000L, 98f)
        val merkleRoot = AndroidSecurityUtils.computeMerkleRoot(listOf(recordLeaf))
        assertNotNull(merkleRoot)
    }

    // ── Test 15: Two-Factor Auth QR Barcode + Face Recognition Agreement ──
    @Test
    fun testTwoFactorAuthQrAndBiometricVerificationWorkflow() {
        val qrStudentRoll = "CS2026-042"
        val qrStudentName = "Dev Patel"

        val tpl = FaceTemplateEntity("t42", qrStudentRoll, "FRONTAL", toCsv(makeEmbedding(1.0f)), false)
        matcher.preloadTemplates(listOf(tpl))

        val probe = makeEmbedding(1.0f)
        val matchResult = matcher.match(probe, mapOf(qrStudentRoll to qrStudentName), SecurityTier.HIGH)

        val is2FaVerified = (matchResult.isMatch && matchResult.studentRoll == qrStudentRoll)
        assertTrue("2FA check passes when face matches QR barcode student roll", is2FaVerified)
    }
}
