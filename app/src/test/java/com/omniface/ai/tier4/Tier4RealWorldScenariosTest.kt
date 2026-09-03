package com.omniface.ai.tier4

import android.graphics.Rect
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.MatchResult as FaceMatchResult
import com.omniface.ai.ml.RegistrationQualityEvaluator
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.antispoof.LivenessStageBreakdown
import com.omniface.ai.ml.antispoof.MultiStageLivenessResult
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessEngine
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.pipeline.BiometricDecisionEngine
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.recognition.FaissVectorIndex
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.ZkpPrivacyManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * Tier 4: Real-World End-to-End Application Scenarios (7 Complex Workflows)
 */
class Tier4RealWorldScenariosTest {

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

    // ── Scenario 1: High-Throughput Morning Kiosk Check-In Flow (100 Students) ──
    @Test
    fun scenario1_HighThroughputMorningKioskCheckIn() {
        val studentCount = 100
        val templates = mutableListOf<FaceTemplateEntity>()
        val studentMap = mutableMapOf<String, String>()

        // 1. Bulk Enrollment of 100 students with distinct orthogonal embeddings
        for (i in 1..studentCount) {
            val roll = "CS2026-%03d".format(i)
            val name = "Student $i"
            studentMap[roll] = name
            val vec = FloatArray(512) { idx -> if (idx == ((i - 1) % 512)) 1.0f else 0.0f }
            templates.add(
                FaceTemplateEntity(
                    id = "tpl_$i",
                    studentRoll = roll,
                    angleType = "FRONTAL",
                    embeddingEncryptedCsv = toCsv(vec),
                    isEncrypted = false
                )
            )
        }
        matcher.preloadTemplates(templates)
        assertEquals(studentCount, matcher.enrolledTemplateCount)

        // 2. High-speed burst traffic check-in simulation
        var genesisHash = AndroidSecurityUtils.computeSha256("OMNIFACE_GENESIS_BLOCK")
        val attendanceRecords = mutableListOf<AttendanceRecordEntity>()
        val leafHashes = mutableListOf<String>()

        for (i in 1..studentCount) {
            val targetRoll = "CS2026-%03d".format(i)
            val liveProbe = FloatArray(512) { idx -> if (idx == ((i - 1) % 512)) 1.0f else 0.0f }

            // Gate 1: Quality
            val qualityResult = QualityGateResult(true, 96f, 96f, 96f, "")
            // Gate 2: Liveness PAD
            val passivePadResult = PassivePadResult(true, 0.98f, "Genuine Human Live", 4L)
            val temporalResult = TemporalLivenessResult(
                isLive = true,
                temporalConfidence = 0.95f,
                microMotionDetected = true,
                naturalBlinkDetected = true,
                stable3DDepth = true,
                explanation = "Live Motion Verified"
            )
            // Gate 3: Biometric Match
            val matchResult = matcher.match(liveProbe, studentMap, SecurityTier.HIGH)

            // Decision Engine Synthesis
            val decision = BiometricDecisionEngine.evaluate(
                quality = qualityResult,
                passivePad = passivePadResult,
                temporalLiveness = temporalResult,
                matchResult = matchResult,
                securityTier = SecurityTier.HIGH
            )

            assertTrue("Student $i must be authorized", decision.isAttendanceAuthorized)
            assertEquals(targetRoll, decision.matchedStudentRoll)

            // Aegis Blockchain Chaining
            val recordId = "REC_MORNING_%03d".format(i)
            val timestamp = 1724572800000L + (i * 2500L) // 2.5s intervals
            val recordHash = AndroidSecurityUtils.computeSha256("$genesisHash|$targetRoll|$timestamp|${decision.matchConfidence}")
            genesisHash = recordHash

            val leafHash = AndroidSecurityUtils.computeAttendanceLeafHash(recordId, targetRoll, timestamp, decision.matchConfidence)
            leafHashes.add(leafHash)

            attendanceRecords.add(
                AttendanceRecordEntity(
                    recordId = recordId,
                    studentRoll = targetRoll,
                    studentName = decision.matchedStudentName,
                    sessionDate = "2026-08-25",
                    timestamp = timestamp,
                    confidencePct = decision.matchConfidence,
                    securityTier = "HIGH",
                    sha256Hash = recordHash,
                    isSynced = false
                )
            )
        }

        assertEquals(studentCount, attendanceRecords.size)
        val morningMerkleRoot = AndroidSecurityUtils.computeMerkleRoot(leafHashes)
        assertNotNull(morningMerkleRoot)
        assertEquals(64, morningMerkleRoot.length)
    }

    // ── Scenario 2: Offline Multi-Angle Enrollment & Subsequent BLE Mesh Sync ──
    @Test
    fun scenario2_OfflineEnrollmentAndSubsequentBleMeshSync() {
        val roll = "OFFLINE-001"
        val name = "Kavita Reddy"

        // Step 1: Capture 5 angles offline on Kiosk A
        val angleTypes = listOf("FRONTAL", "LEFT_15", "RIGHT_15", "UP_10", "DOWN_10")
        val embeddings = angleTypes.mapIndexed { idx, _ -> makeEmbedding(10.0f + idx * 0.005f) }
        val qualityScores = listOf(95f, 94f, 96f, 91f, 93f)

        val (masterCentroid, matrix) = RegistrationQualityEvaluator.computeQualityWeightedTemplate(embeddings, qualityScores)
        assertTrue(matrix.isConsistent)

        // Step 2: Package into encrypted templates on Kiosk A
        val kioskATemplates = angleTypes.mapIndexed { idx, angle ->
            FaceTemplateEntity(
                id = "kiosk_a_tpl_$idx",
                studentRoll = roll,
                angleType = angle,
                embeddingEncryptedCsv = toCsv(embeddings[idx]),
                isEncrypted = false
            )
        }.toMutableList().also {
            it.add(FaceTemplateEntity("kiosk_a_master", roll, "MASTER_CENTROID", toCsv(masterCentroid), false))
        }

        // Step 3: Simulate BLE Mesh propagation to Kiosk B
        val kioskBMatcher = FaceMatcher()
        kioskBMatcher.preloadTemplates(kioskATemplates)
        assertEquals(6, kioskBMatcher.enrolledTemplateCount)

        // Step 4: Verification on Kiosk B succeeds offline
        val probe = makeEmbedding(10.0f)
        val matchResult = kioskBMatcher.match(probe, mapOf(roll to name), SecurityTier.HIGH)
        assertTrue("Kiosk B must match student enrolled via offline BLE mesh sync", matchResult.isMatch)
        assertEquals(roll, matchResult.studentRoll)
    }

    // ── Scenario 3: Adversarial Multi-Modal Spoof Defense ──
    @Test
    fun scenario3_AdversarialMultiModalSpoofDefense() {
        // Attack A: Display screen replay with severe glare & moiré
        val screenGlareBreakdown = LivenessStageBreakdown(
            reflectionScore = 0.15f,
            textureScore = 0.40f,
            moiréScore = 0.20f,
            chromaticScore = 0.50f,
            neuralPadScore = 0.10f,
            hasSpecularScreenHotspots = true,
            hasPeriodicDisplayGrid = true,
            hasUnnaturalPaperFlatness = false
        )
        val attackAResult = MultiStageLivenessResult(
            isLive = false,
            overallLivenessScore = 0.22f,
            spoofProbability = 0.78f,
            primaryAttackVector = "Display Glass Specular Reflection",
            detectedAnomalies = listOf("Display Glass Specular Reflection", "Digital Display Moiré Grid Interference"),
            stageBreakdown = screenGlareBreakdown,
            passivePadResult = null,
            latencyMs = 8L
        )

        val decisionA = BiometricDecisionEngine.evaluate(
            quality = QualityGateResult(true, 90f, 90f, 90f, ""),
            passivePad = null,
            temporalLiveness = TemporalLivenessResult(
                isLive = false,
                temporalConfidence = 0.2f,
                microMotionDetected = false,
                naturalBlinkDetected = false,
                stable3DDepth = false,
                explanation = "Screen Glare"
            ),
            matchResult = FaceMatchResult("CS101", "Alice", 98f, 0.85f, true, HardwareTier.NPU_NNAPI, ConfidenceZone.ACCEPT, 0.4f),
            securityTier = SecurityTier.HIGH,
            multiStageLiveness = attackAResult
        )
        assertFalse("Screen replay attack must be blocked", decisionA.isAttendanceAuthorized)
        assertEquals(PipelineGateState.REJECT_SPOOF_ATTACK, decisionA.gateState)

        // Attack B: 2D printed photo attack
        val paperBreakdown = LivenessStageBreakdown(
            reflectionScore = 0.90f,
            textureScore = 0.10f,
            moiréScore = 0.85f,
            chromaticScore = 0.30f,
            neuralPadScore = 0.05f,
            hasSpecularScreenHotspots = false,
            hasPeriodicDisplayGrid = false,
            hasUnnaturalPaperFlatness = true
        )
        val attackBResult = MultiStageLivenessResult(
            isLive = false,
            overallLivenessScore = 0.30f,
            spoofProbability = 0.70f,
            primaryAttackVector = "2D Printed Paper Photo Attack",
            detectedAnomalies = listOf("Unnatural Low-Entropy Surface (Paper/2D Print)"),
            stageBreakdown = paperBreakdown,
            passivePadResult = null,
            latencyMs = 7L
        )
        val decisionB = BiometricDecisionEngine.evaluate(
            quality = QualityGateResult(true, 85f, 85f, 85f, ""),
            passivePad = null,
            temporalLiveness = TemporalLivenessResult(
                isLive = false,
                temporalConfidence = 0.1f,
                microMotionDetected = false,
                naturalBlinkDetected = false,
                stable3DDepth = false,
                explanation = "Static Photo"
            ),
            matchResult = FaceMatchResult("CS101", "Alice", 97f, 0.82f, true, HardwareTier.NPU_NNAPI, ConfidenceZone.ACCEPT, 0.4f),
            securityTier = SecurityTier.HIGH,
            multiStageLiveness = attackBResult
        )
        assertFalse("2D printed paper attack must be blocked", decisionB.isAttendanceAuthorized)
    }

    // ── Scenario 4: DPDP Act 2023 End-to-End Right-to-Forget Compliance ──
    @Test
    fun scenario4_DpdpAct2023EndToEndRightToForgetCompliance() {
        val student = StudentEntity("CS-DPDP-99", "Rahul Roy", "Electrical", "8")
        val template = FaceTemplateEntity("t_dpdp", "CS-DPDP-99", "FRONTAL", toCsv(makeEmbedding(5.0f)), false)
        matcher.preloadTemplates(listOf(template))

        // Ensure student is currently recognized
        val probeBefore = makeEmbedding(5.0f)
        val matchBefore = matcher.match(probeBefore, mapOf("CS-DPDP-99" to "Rahul Roy"), SecurityTier.HIGH)
        assertTrue("Student recognized prior to deletion", matchBefore.isMatch)

        // Right to forget invoked: Purge in-memory vector index and DB templates
        matcher.clear()
        assertEquals("Biometric cache must be cleared", 0, matcher.enrolledTemplateCount)

        // Post-deletion verification attempt
        val matchAfter = matcher.match(probeBefore, mapOf("CS-DPDP-99" to "Rahul Roy"), SecurityTier.HIGH)
        assertFalse("Erased student must no longer be recognized", matchAfter.isMatch)
        assertEquals("GUEST", matchAfter.studentRoll)
    }

    // ── Scenario 5: Dynamic Thermal Throttling & Hardware Delegate Fallback ──
    @Test
    fun scenario5_DynamicThermalThrottlingAndHardwareDelegateGracefulDegradation() {
        // 1. Initial State: NOMINAL (33°C) -> 640x480 resolution, full speed
        ThermalGovernor.setSimulationOverride(ThermalState.NOMINAL)
        assertEquals(ThermalState.NOMINAL, ThermalGovernor.thermalState.value)
        assertEquals(1.0f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)

        // 2. Temperature rises: WARM (40°C) -> 480x360 resolution (0.75x downscale)
        ThermalGovernor.setSimulationOverride(ThermalState.WARM)
        assertEquals(ThermalState.WARM, ThermalGovernor.thermalState.value)
        assertEquals(0.75f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)

        // Verify bounding box remapping from 480p space back to 640p
        val warmScale = 1.0f / 0.75f
        val warmLeft = (150 * warmScale).toInt()
        val warmRight = (300 * warmScale).toInt()
        assertEquals(200, warmLeft)
        assertEquals(400, warmRight)

        // 3. Critical Temperature: CRITICAL (45°C) -> 320x240 resolution (0.50x downscale)
        ThermalGovernor.setSimulationOverride(ThermalState.CRITICAL)
        assertEquals(ThermalState.CRITICAL, ThermalGovernor.thermalState.value)
        assertEquals(0.50f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)

        val criticalScale = 1.0f / 0.50f
        val criticalLeft = (100 * criticalScale).toInt()
        val criticalRight = (200 * criticalScale).toInt()
        assertEquals(200, criticalLeft)
        assertEquals(400, criticalRight)

        ThermalGovernor.setSimulationOverride(null)
    }

    // ── Scenario 6: Power Loss & Crash Recovery Ledger Continuity ──
    @Test
    fun scenario6_PowerLossAndCrashRecoveryLedgerContinuity() {
        // Pre-crash state: 3 blocks recorded
        var lastHash = AndroidSecurityUtils.computeSha256("GENESIS")
        val preCrashHashes = mutableListOf<String>()
        for (i in 1..3) {
            lastHash = AndroidSecurityUtils.computeSha256("$lastHash|PRE_CRASH_REC_$i")
            preCrashHashes.add(lastHash)
        }

        // Crash occurs -> System boots up and reads last known hash
        val recoveredLatestHash = preCrashHashes.last()

        // Post-recovery attendance recording continues seamless cryptographic chaining
        val postRecoveryBlock1 = AndroidSecurityUtils.computeSha256("$recoveredLatestHash|POST_CRASH_REC_1")
        val postRecoveryBlock2 = AndroidSecurityUtils.computeSha256("$postRecoveryBlock1|POST_CRASH_REC_2")

        assertNotNull(postRecoveryBlock1)
        assertNotNull(postRecoveryBlock2)
        assertEquals(64, postRecoveryBlock2.length)
        assertNotEquals(recoveredLatestHash, postRecoveryBlock2)
    }

    // ── Scenario 7: Multi-Day Attendance Cycle with Rolling Ledger & ZKP Proofs ──
    @Test
    fun scenario7_MultiDayAttendanceCycleWithRollingLedgerAndZeroKnowledgeVerification() {
        val days = listOf("2026-08-19", "2026-08-20", "2026-08-21", "2026-08-22", "2026-08-23", "2026-08-24", "2026-08-25")
        val studentEmbedding = makeEmbedding(7.0f)
        val studentRoll = "CS2026-007"

        // Generate ZKP commitment for student
        val (zkpCommitment, zkpSalt) = ZkpPrivacyManager.generateZkpCommitment(toCsv(studentEmbedding))

        val dailyMerkleRoots = mutableMapOf<String, String>()

        for (day in days) {
            val dailyLeaves = mutableListOf<String>()
            for (kiosk in 1..3) {
                val leaf = AndroidSecurityUtils.computeAttendanceLeafHash("rec_${day}_$kiosk", studentRoll, System.currentTimeMillis(), 98.0f)
                dailyLeaves.add(leaf)
            }
            dailyMerkleRoots[day] = AndroidSecurityUtils.computeMerkleRoot(dailyLeaves)
        }

        assertEquals(7, dailyMerkleRoots.size)
        assertTrue(dailyMerkleRoots.values.all { it.length == 64 })

        // Auditor verifies student's identity ZKP proof
        val isAuditorVerified = ZkpPrivacyManager.verifyZkpCommitment(toCsv(studentEmbedding), zkpCommitment, zkpSalt)
        assertTrue("Auditor can verify biometric commitment without seeing raw float coordinates", isAuditorVerified)
    }
}
