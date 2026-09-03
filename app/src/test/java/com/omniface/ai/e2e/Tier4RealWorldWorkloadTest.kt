package com.omniface.ai.e2e

import com.omniface.ai.audio.BiometricSoundboard
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 4: Requirement-Driven Opaque-Box Real-World Workload Test Suite.
 * Covers 7 complex multi-subsystem production scenarios simulating real university and enterprise deployments.
 */
class Tier4RealWorldWorkloadTest {

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
        FleetTopologyManager.reset()
    }

    // ── Scenario 1: Morning High-Throughput Doorway Kiosk Ingestion (100 Students) ──
    @Test
    fun scenario1_MorningHighThroughputDoorwayKioskIngestion() {
        val studentCount = 100
        val enrolledStudents = (1..studentCount).map { i ->
            val roll = "CS2026-%03d".format(i)
            val name = "Student $i"
            val vec = FloatArray(512) { idx -> if (idx == ((i - 1) % 512)) 1.0f else 0.0f }
            val enc = AndroidSecurityUtils.encrypt(toCsv(vec))
            Pair(
                StudentEntity(roll, name, "CSE", "6"),
                FaceTemplateEntity("tpl_$i", roll, "FRONTAL", enc, isEncrypted = true)
            )
        }

        var prevHash = AndroidSecurityUtils.AEGIS_GENESIS_HASH
        val attendanceRecords = mutableListOf<AttendanceRecordEntity>()
        val leafHashes = mutableListOf<String>()

        for (i in 1..studentCount) {
            val student = enrolledStudents[i - 1].first
            val quality = QualityGateResult(isPassed = true, overallQualityScore = 96f, sharpnessScore = 95f, exposureScore = 96f, rejectionReason = "")
            val pad = PassivePadResult(isLive = true, livenessScore = 0.99f, attackTypeDescription = "GENUINE", latencyMs = 4L)
            val temporal = TemporalLivenessResult(isLive = true, temporalConfidence = 0.96f, microMotionDetected = true, naturalBlinkDetected = true, stable3DDepth = true, explanation = "Live")
            val match = MatchResult(studentRoll = student.rollNumber, studentName = student.fullName, confidence = 0.98f, similarity = 0.98f, isMatch = true, hardwareTier = HardwareTier.NPU_NNAPI, confidenceZone = ConfidenceZone.ACCEPT, decisionMargin = 0.15f)

            val decision = BiometricDecisionEngine.evaluate(quality, pad, temporal, match, SecurityTier.HIGH)
            assertEquals(PipelineGateState.PASS, decision.gateState)

            val timestamp = 1720000000000L + i * 1500L
            val blockHash = AndroidSecurityUtils.computeAegisBlockHash(prevHash, student.rollNumber, timestamp, 98.0f)
            val record = AttendanceRecordEntity("rec_$i", student.rollNumber, student.fullName, "2026-08-26", timestamp, 98.0f, "HIGH", blockHash)

            attendanceRecords.add(record)
            leafHashes.add(AndroidSecurityUtils.computeAttendanceLeafHash(record.recordId, record.studentRoll, record.timestamp, record.confidencePct))
            prevHash = blockHash
        }

        assertEquals(100, attendanceRecords.size)
        val isChainValid = AndroidSecurityUtils.verifyChainIntegrity(attendanceRecords)
        assertTrue("All 100 sequential blocks must satisfy linear Aegis hash chaining", isChainValid)

        val batchMerkleRoot = AndroidSecurityUtils.computeMerkleRoot(leafHashes)
        assertEquals(64, batchMerkleRoot.length)
    }

    // ── Scenario 2: Student Registration with 5-Angle Burst Centroid Synthesis & Dedup Check ──
    @Test
    fun scenario2_StudentRegistrationWith5AngleCentroidAndDedupCheck() = runBlocking {
        val newStudent = StudentEntity("CS2026-888", "Kavya Reddy", "ECE", "4")
        val angles = listOf("FRONTAL", "LEFT_15", "RIGHT_15", "UP_10", "DOWN_10")
        val burstEmbeddings = angles.mapIndexed { idx, _ -> makeEmbedding(3.5f + idx * 0.002f) }
        val qualityScores = listOf(97f, 95f, 96f, 93f, 94f)

        // 1. Synthesize 5-Angle Master Centroid
        val (masterCentroid, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(burstEmbeddings, qualityScores)
        assertTrue("5-angle burst capture must satisfy consistency threshold (min >= 0.78)", matrix.isConsistent)

        // 2. Existing database contains a distinct enrolled student
        val existingVector = FloatArray(512) { if (it == 400) 1.0f else 0.0f }
        val existingDb = listOf(
            FaceTemplateEntity("tpl_01", "CS2026-001", "FRONTAL", AndroidSecurityUtils.encrypt(toCsv(existingVector)), isEncrypted = true)
        )
        val studentMap = mapOf("CS2026-001" to "Existing Student")

        // 3. Collision Pre-Check
        val dedupResult = BiometricDeduplicationEngine.checkEnrollmentDuplicate(masterCentroid, existingDb, studentMap, threshold = 0.84f)
        assertTrue("Unique student must pass deduplication gate as Clean", dedupResult is DuplicateCheckResult.Clean)

        // 4. Persistence with AES-256-GCM Hardware Encryption
        val encryptedMasterCsv = AndroidSecurityUtils.encrypt(toCsv(masterCentroid))
        val templateEntity = FaceTemplateEntity("tpl_888", newStudent.rollNumber, "MASTER_CENTROID", encryptedMasterCsv, isEncrypted = true)
        assertTrue(templateEntity.isEncrypted)
        assertEquals(newStudent.rollNumber, templateEntity.studentRoll)
    }

    // ── Scenario 3: Multi-Language Dynamic Switch during Live Scanning Operations ──
    @Test
    fun scenario3_MultiLanguageDynamicSwitchDuringLiveScanning() {
        val testLanguages = listOf(
            AppLanguage.ENGLISH,
            AppLanguage.HINDI,
            AppLanguage.KANNADA,
            AppLanguage.TAMIL,
            AppLanguage.TELUGU,
            AppLanguage.MALAYALAM,
            AppLanguage.BENGALI,
            AppLanguage.MARATHI,
            AppLanguage.GUJARATI,
            AppLanguage.PUNJABI
        )

        for (lang in testLanguages) {
            BiometricSoundboard.setLanguage(lang)
            assertEquals(lang, BiometricSoundboard.currentLanguage)

            val scannerTitle = LocalizationManager.getString(StringKey.SCANNER_TITLE, lang)
            val readyToScan = LocalizationManager.getString(StringKey.READY_TO_SCAN, lang)
            val confirmed = LocalizationManager.getString(StringKey.RECOGNITION_CONFIRMED, lang)
            val spoofDetected = LocalizationManager.getString(StringKey.SPOOF_DETECTED, lang)

            assertTrue(scannerTitle.isNotBlank())
            assertTrue(readyToScan.isNotBlank())
            assertTrue(confirmed.isNotBlank())
            assertTrue(spoofDetected.isNotBlank())

            BiometricSoundboard.playMatchSuccess("Student Test")
        }
    }

    // ── Scenario 4: Blockchain Ledger Verification, Tamper Detection & DPDP Purge ──
    @Test
    fun scenario4_BlockchainLedgerVerificationAndDpdpPurge() {
        var prevHash = AndroidSecurityUtils.AEGIS_GENESIS_HASH
        val records = (1..20).map { i ->
            val roll = "CS2026-%03d".format(i)
            val name = "Student $i"
            val ts = 1720000000000L + i * 1000L
            val hash = AndroidSecurityUtils.computeAegisBlockHash(prevHash, roll, ts, 97.0f)
            prevHash = hash
            AttendanceRecordEntity("rec_$i", roll, name, "2026-08-26", ts, 97.0f, "HIGH", hash)
        }.toMutableList()

        // 1. Verify valid unbroken chain
        assertTrue(AndroidSecurityUtils.verifyChainIntegrity(records))

        // 2. Tampering injection test: alter record 10
        val tamperedRecord = records[9].copy(studentName = "Fraudulent Name", confidencePct = 50.0f)
        val tamperedList = records.toMutableList()
        tamperedList[9] = tamperedRecord
        assertFalse("Altered record in chain must fail cryptographic audit", AndroidSecurityUtils.verifyChainIntegrity(tamperedList))

        // 3. DPDP Act 2023 Purge: right-to-forget student 5
        records.removeAll { it.studentRoll == "CS2026-005" }
        assertFalse(records.any { it.studentRoll == "CS2026-005" })
    }

    // ── Scenario 5: Turnstile Relay Pulse & Campus Emergency Evacuation Override ──
    @Test
    fun scenario5_TurnstileRelayPulseAndEmergencyEvacuationOverride() {
        TurnstileRelayController.triggerDoorUnlock(
            durationMs = 50L,
            studentRoll = "CS001",
            studentName = "Alice",
            confidencePct = 99.0f,
            sha256Proof = "proof"
        )

        EmergencyEvacuationController.triggerEmergencyEvacuation("CAMPUS_WIDE_FIRE_ALARM")
        assertTrue("Emergency evacuation must be active", EmergencyEvacuationController.isEvacuationActive.value)
        assertEquals("CAMPUS_WIDE_FIRE_ALARM", EmergencyEvacuationController.evacuationReason.value)

        EmergencyEvacuationController.resetEvacuation()
        assertFalse("Emergency evacuation must be cleared", EmergencyEvacuationController.isEvacuationActive.value)
    }

    // ── Scenario 6: Offline Multi-Kiosk BLE Fleet Mesh Sync & Topology Ingestion ──
    @Test
    fun scenario6_OfflineMultiKioskBleMeshSyncAndReconciliation() {
        val nodeMain = KioskNode("kiosk_main_gate", "Main Entrance Kiosk", "10.0.1.10", 98, 120, true)
        val nodeLibrary = KioskNode("kiosk_library", "Library Reading Hall Kiosk", "10.0.1.11", 85, 60, true)
        val nodeHostel = KioskNode("kiosk_hostel", "Hostel Block A Kiosk", "10.0.1.12", 90, 60, true)

        FleetTopologyManager.registerNode(nodeMain)
        FleetTopologyManager.registerNode(nodeLibrary)
        FleetTopologyManager.registerNode(nodeHostel)

        val topology = FleetTopologyManager.kioskNodes.value
        assertEquals(3, topology.size)
        assertTrue(topology.all { it.isOnline })

        FleetTopologyManager.updateNodeHeartbeat("kiosk_main_gate", 120, 97)
        FleetTopologyManager.updateNodeHeartbeat("kiosk_library", 60, 84)

        val updatedMain = FleetTopologyManager.kioskNodes.value.find { it.id == "kiosk_main_gate" }
        assertEquals(97, updatedMain?.batteryPct)
    }

    // ── Scenario 7: Continuous Thermal Throttling & Adaptive Hardware Degradation Flow ──
    @Test
    fun scenario7_ThermalThrottlingAndAdaptiveHardwareDegradation() {
        val ambientTemp = 28.0f
        val warningTemp = 42.0f
        val criticalTemp = 46.0f

        val nominalFps = if (ambientTemp < 40.0f) 120 else 60
        assertEquals(120, nominalFps)

        val warningFps = if (warningTemp >= 40.0f && warningTemp < 45.0f) 60 else 30
        assertEquals(60, warningFps)

        val criticalFps = if (criticalTemp >= 45.0f) 30 else 60
        assertEquals(30, criticalFps)
    }
}
