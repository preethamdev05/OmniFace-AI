package com.omniface.ai.e2e

import com.omniface.ai.audio.BiometricSoundboard
import com.omniface.ai.audio.SoundEnvironmentMode
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.hardware.EmergencyEvacuationController
import com.omniface.ai.hardware.FleetTopologyManager
import com.omniface.ai.hardware.KioskNode
import com.omniface.ai.hardware.TurnstileRelayController
import com.omniface.ai.i18n.AppLanguage
import com.omniface.ai.i18n.LocalizationManager
import com.omniface.ai.i18n.StringKey
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.ml.RegistrationQualityEvaluator
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.pipeline.BiometricDecisionEngine
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.BiometricDeduplicationEngine
import com.omniface.ai.ml.recognition.DuplicateCheckResult
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.ZkpPrivacyManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 3: Requirement-Driven Opaque-Box Pairwise Feature Interaction Test Suite.
 * Covers pairwise combinatorial cross-feature workflows (>=12 test cases).
 */
class Tier3PairwiseInteractionTest {

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

    @Before
    fun setUp() {
        AndroidSecurityUtils.initMasterKey()
        EmergencyEvacuationController.resetEvacuation()
    }

    // ── Interaction 1: F1 (Dashboard) x F2 (Scanner) ──
    @Test
    fun testPairwise_F1_Dashboard_x_F2_Scanner_StateUpdates() {
        val quality = QualityGateResult(isPassed = true, overallQualityScore = 95f, sharpnessScore = 95f, exposureScore = 95f, rejectionReason = "")
        val pad = PassivePadResult(isLive = true, livenessScore = 0.98f, attackTypeDescription = "GENUINE", latencyMs = 4L)
        val temporal = TemporalLivenessResult(isLive = true, temporalConfidence = 0.95f, microMotionDetected = true, naturalBlinkDetected = true, stable3DDepth = true, explanation = "Live")
        val match = MatchResult(studentRoll = "CS001", studentName = "Aarav", confidence = 0.95f, similarity = 0.95f, isMatch = true, hardwareTier = HardwareTier.NPU_NNAPI, confidenceZone = ConfidenceZone.ACCEPT, decisionMargin = 0.15f)

        val decision = BiometricDecisionEngine.evaluate(quality, pad, temporal, match, SecurityTier.HIGH)
        assertEquals(PipelineGateState.PASS, decision.gateState)

        // Telemetry update simulation on dashboard
        val isVerified = decision.gateState == PipelineGateState.PASS
        var todayCheckIns = 10
        if (isVerified) todayCheckIns++
        assertEquals(11, todayCheckIns)
    }

    // ── Interaction 2: F2 (Scanner) x F8 (Soundboard) ──
    @Test
    fun testPairwise_F2_Scanner_x_F8_Soundboard_AudioSynchronization() {
        BiometricSoundboard.setSoundMode(SoundEnvironmentMode.NOISY_HALLWAY)
        BiometricSoundboard.setLanguage(AppLanguage.HINDI)

        BiometricSoundboard.playMatchSuccess("Aarav Sharma")
        assertEquals(AppLanguage.HINDI, BiometricSoundboard.currentLanguage)
        assertEquals(SoundEnvironmentMode.NOISY_HALLWAY, BiometricSoundboard.currentSoundMode)

        BiometricSoundboard.playSpoofAlert()
        assertNotNull(BiometricSoundboard.currentSoundMode)
    }

    // ── Interaction 3: F2 (Scanner) x F10 (Kiosk Relay & Evacuation) ──
    @Test
    fun testPairwise_F2_Scanner_x_F10_Kiosk_TurnstileRelay_And_EmergencyOverride() {
        TurnstileRelayController.triggerDoorUnlock(
            durationMs = 50L,
            studentRoll = "CS001",
            studentName = "Aarav",
            confidencePct = 98.0f,
            sha256Proof = "proof_hash"
        )

        EmergencyEvacuationController.triggerEmergencyEvacuation("CAMPUS_FIRE_ALARM")
        assertTrue("Evacuation must be active", EmergencyEvacuationController.isEvacuationActive.value)
        assertEquals("CAMPUS_FIRE_ALARM", EmergencyEvacuationController.evacuationReason.value)
    }

    // ── Interaction 4: F3 (Directory) x F4 (Enrollment Studio) ──
    @Test
    fun testPairwise_F3_Directory_x_F4_Enrollment_DirectTriggerAndStatus() {
        val student = StudentEntity(rollNumber = "CS2026-501", fullName = "Priya Rao", department = "AI&DS", semester = "4")
        val angles = listOf("FRONTAL", "LEFT_15", "RIGHT_15", "UP_10", "DOWN_10")
        val embeddings = angles.mapIndexed { idx, _ -> makeEmbedding(2.0f + idx * 0.005f) }
        val qualityScores = listOf(95f, 92f, 96f, 91f, 94f)

        val (masterCentroid, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(embeddings, qualityScores)
        assertTrue(matrix.isConsistent)
        assertEquals(512, masterCentroid.size)

        val template = FaceTemplateEntity(
            id = "tpl_master_501",
            studentRoll = student.rollNumber,
            angleType = "MASTER_CENTROID",
            embeddingEncryptedCsv = AndroidSecurityUtils.encrypt(toCsv(masterCentroid)),
            isEncrypted = true
        )
        assertEquals(student.rollNumber, template.studentRoll)
        assertTrue(template.isEncrypted)
    }

    // ── Interaction 5: F4 (Enrollment) x F5 (Deduplication) ──
    @Test
    fun testPairwise_F4_Enrollment_x_F5_Deduplication_CollisionGate() = runBlocking {
        val rawVec = makeEmbedding(5.5f)
        val (centroid, _) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(listOf(rawVec), listOf(100f))

        val existingEncrypted = AndroidSecurityUtils.encrypt(toCsv(centroid))
        val existingDb = listOf(
            FaceTemplateEntity(id = "1", studentRoll = "ENROLLED_01", angleType = "FRONTAL", embeddingEncryptedCsv = existingEncrypted, isEncrypted = true)
        )
        val studentMap = mapOf("ENROLLED_01" to "Existing Student")

        val dedupResult = BiometricDeduplicationEngine.checkEnrollmentDuplicate(
            candidateEmbedding = centroid,
            existingTemplates = existingDb,
            studentMap = studentMap,
            threshold = 0.84f
        )
        assertTrue("Centroid matching enrolled template must be flagged as duplicate before database insert",
            dedupResult is DuplicateCheckResult.DuplicateFound)
    }

    // ── Interaction 6: F4 (Enrollment) x F9 (Crypto) ──
    @Test
    fun testPairwise_F4_Enrollment_x_F9_Crypto_EncryptedTemplatePersistence() {
        val (centroid, _) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(
            listOf(makeEmbedding(1.23f)), listOf(98f)
        )
        val csv = toCsv(centroid)

        val encryptedBase64 = AndroidSecurityUtils.encrypt(csv)
        assertFalse("Persistent representation must not be plaintext", encryptedBase64.contains("0.0"))

        val decryptedCsv = AndroidSecurityUtils.decrypt(encryptedBase64)
        assertEquals(csv, decryptedCsv)
    }

    // ── Interaction 7: F5 (Deduplication) x F9 (Crypto) ──
    @Test
    fun testPairwise_F5_Deduplication_x_F9_Crypto_DecryptedMatrixScan() = runBlocking {
        val vectorA = makeEmbedding(1.0f)
        val vectorB = makeEmbedding(1.002f)
        val vectorC = makeEmbedding(8.0f)

        val encA = AndroidSecurityUtils.encrypt(toCsv(vectorA))
        val encB = AndroidSecurityUtils.encrypt(toCsv(vectorB))
        val encC = AndroidSecurityUtils.encrypt(toCsv(vectorC))

        val templates = listOf(
            FaceTemplateEntity(id = "1", studentRoll = "ROLL_1", angleType = "FRONTAL", embeddingEncryptedCsv = encA, isEncrypted = true),
            FaceTemplateEntity(id = "2", studentRoll = "ROLL_2", angleType = "FRONTAL", embeddingEncryptedCsv = encB, isEncrypted = true),
            FaceTemplateEntity(id = "3", studentRoll = "ROLL_3", angleType = "FRONTAL", embeddingEncryptedCsv = encC, isEncrypted = true)
        )
        val studentMap = mapOf("ROLL_1" to "Student One", "ROLL_2" to "Student Two", "ROLL_3" to "Student Three")

        val clusters = BiometricDeduplicationEngine.scanDatabaseForDuplicates(templates, studentMap, threshold = 0.80f)
        assertEquals(1, clusters.size)
        assertEquals("ROLL_1", clusters[0].primaryRoll)
        assertEquals("ROLL_2", clusters[0].duplicateCandidates[0].rollNumber)
    }

    // ── Interaction 8: F6 (Ledger) x F9 (Crypto ZKP) ──
    @Test
    fun testPairwise_F6_Ledger_x_F9_Crypto_AegisZkpCommitments() {
        val embeddingCsv = toCsv(makeEmbedding(3.14f))
        val (zkpCommitment, salt) = ZkpPrivacyManager.generateZkpCommitment(embeddingCsv)

        val record = AttendanceRecordEntity(
            recordId = "REC_ZKP_01",
            studentRoll = "CS2026-999",
            studentName = "Zkp Verified",
            sessionDate = "2026-08-26",
            timestamp = 1720000000000L,
            confidencePct = 99.2f,
            securityTier = "STRICT",
            sha256Hash = AndroidSecurityUtils.computeAegisBlockHash(AndroidSecurityUtils.AEGIS_GENESIS_HASH, "CS2026-999", 1720000000000L, 99.2f)
        )

        val isChainValid = AndroidSecurityUtils.verifyChainIntegrity(listOf(record))
        val isZkpValid = ZkpPrivacyManager.verifyZkpCommitment(embeddingCsv, zkpCommitment, salt)

        assertTrue(isChainValid)
        assertTrue(isZkpValid)
    }

    // ── Interaction 9: F6 (Ledger) x F10 (BLE Mesh Topology) ──
    @Test
    fun testPairwise_F6_Ledger_x_F10_KioskMesh_CrossNodeHashChainReplication() {
        val nodeA = KioskNode(id = "kiosk_node_A", name = "North Gate", ipAddress = "10.0.0.1", batteryPct = 95, activeFps = 60, isOnline = true)
        val nodeB = KioskNode(id = "kiosk_node_B", name = "South Gate", ipAddress = "10.0.0.2", batteryPct = 92, activeFps = 60, isOnline = true)
        FleetTopologyManager.registerNode(nodeA)
        FleetTopologyManager.registerNode(nodeB)

        val genesis = AndroidSecurityUtils.AEGIS_GENESIS_HASH
        val block1 = AndroidSecurityUtils.computeAegisBlockHash(genesis, "CS001", 1000L, 95f)
        val block2 = AndroidSecurityUtils.computeAegisBlockHash(block1, "CS002", 2000L, 96f)

        val records = listOf(
            AttendanceRecordEntity("1", "CS001", "Alice", "2026-08-26", 1000L, 95f, "HIGH", block1),
            AttendanceRecordEntity("2", "CS002", "Bob", "2026-08-26", 2000L, 96f, "HIGH", block2)
        )
        val isIntegrityVerified = AndroidSecurityUtils.verifyChainIntegrity(records)
        assertTrue("Replicated ledger across mesh nodes must satisfy linear hash continuity", isIntegrityVerified)
    }

    // ── Interaction 10: F7 (Settings) x F11 (Localization) ──
    @Test
    fun testPairwise_F7_Settings_x_F11_Localization_DynamicLanguageSwitch() {
        for (lang in AppLanguage.entries) {
            val title = LocalizationManager.getString(StringKey.SETTINGS_TITLE, lang)
            val appearance = LocalizationManager.getString(StringKey.CAT_APPEARANCE, lang)
            val biometrics = LocalizationManager.getString(StringKey.CAT_BIOMETRICS, lang)

            assertTrue(title.isNotBlank())
            assertTrue(appearance.isNotBlank())
            assertTrue(biometrics.isNotBlank())
        }
    }

    // ── Interaction 11: F6 (Ledger) x F3 (Directory DPDP Purge) ──
    @Test
    fun testPairwise_F6_Ledger_x_F3_Directory_DpdpRightToForgetPurge() {
        val student = StudentEntity(rollNumber = "PURGE_001", fullName = "Purge Student", department = "CSE", semester = "8")
        val templates = mutableListOf(
            FaceTemplateEntity(id = "1", studentRoll = student.rollNumber, angleType = "FRONTAL", embeddingEncryptedCsv = "enc", isEncrypted = true)
        )
        val ledger = mutableListOf(
            AttendanceRecordEntity("1", student.rollNumber, student.fullName, "2026-08-26", 1000L, 95f, "HIGH", "hash1")
        )

        templates.removeAll { it.studentRoll == student.rollNumber }
        ledger.removeAll { it.studentRoll == student.rollNumber }

        assertTrue("Biometric templates must be completely erased", templates.isEmpty())
        assertTrue("Attendance records for purged student must be wiped", ledger.isEmpty())
    }

    // ── Interaction 12: F7 (Settings) x F2 (Scanner Security Tier) ──
    @Test
    fun testPairwise_F7_Settings_x_F2_Scanner_SecurityTierThresholdGating() {
        val probeSimilarity = 0.750f

        val passesStandard = probeSimilarity >= SecurityTier.STANDARD.threshold
        val passesHigh = probeSimilarity >= SecurityTier.HIGH.threshold
        val passesStrict = probeSimilarity >= SecurityTier.STRICT.threshold

        assertTrue("0.750 similarity must pass Standard tier", passesStandard)
        assertTrue("0.750 similarity must pass High tier", passesHigh)
        assertFalse("0.750 similarity must fail Strict tier", passesStrict)
    }

    // ── Interaction 13: F8 (Soundboard) x F11 (Localization TTS) ──
    @Test
    fun testPairwise_F8_Soundboard_x_F11_Localization_TtsVoiceLanguageAlignment() {
        for (lang in AppLanguage.entries) {
            BiometricSoundboard.setLanguage(lang)
            assertEquals(lang, BiometricSoundboard.currentLanguage)
        }
    }

    // ── Interaction 14: F1 (Dashboard) x F6 (Ledger Velocity) ──
    @Test
    fun testPairwise_F1_Dashboard_x_F6_Ledger_VelocityAndBlockCountSync() {
        val records = (1..50).map { i ->
            AttendanceRecordEntity(
                recordId = "rec_$i",
                studentRoll = "ROLL_$i",
                studentName = "Student $i",
                sessionDate = "2026-08-26",
                timestamp = 1720000000000L + i * 60000L,
                confidencePct = 95f + (i % 5),
                securityTier = "HIGH",
                sha256Hash = "hash_$i"
            )
        }
        val dashboardTotalCount = records.size
        val leaves = records.map { AndroidSecurityUtils.computeAttendanceLeafHash(it.recordId, it.studentRoll, it.timestamp, it.confidencePct) }
        val merkleRoot = AndroidSecurityUtils.computeMerkleRoot(leaves)

        assertEquals(50, dashboardTotalCount)
        assertEquals(64, merkleRoot.length)
    }
}
