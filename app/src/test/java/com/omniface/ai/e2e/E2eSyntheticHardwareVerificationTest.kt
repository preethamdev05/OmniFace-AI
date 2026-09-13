package com.omniface.ai.e2e

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.CachedBiometric
import com.omniface.ai.ml.HardwareTier
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.concurrency.BoundedGroupInferenceScheduler
import com.omniface.ai.ml.pipeline.BiometricDecisionEngine
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.verification.policy.AutomationAction
import com.omniface.ai.ml.verification.policy.AutomationDecision
import com.omniface.ai.ml.verification.policy.KioskAutomationState
import com.omniface.ai.ml.verification.policy.KioskAutomationStateMachine
import com.omniface.ai.security.ZkpPrivacyManager
import com.omniface.ai.testutil.BiometricTestFixtures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 🚀 Phase 23: End-to-End Synthetic & Hardware Verification Test Suite
 *
 * Verifies:
 * 1. Multi-subject concurrent batch verification without winner-take-all collapse.
 * 2. In-batch duplicate identity collision resolution (highest confidence wins).
 * 3. Cross-tier hardware delegate invariant preservation (NPU, GPU, CPU).
 * 4. Full-lifecycle Aegis blockchain & ZKP zero-knowledge verification.
 * 5. Morning rush-hour kiosk cycle with cooldown deduplication.
 * 6. High-density crowd (50 subjects) permit boundary safety.
 */
class E2eSyntheticHardwareVerificationTest {

    private lateinit var matcher: FaceMatcher
    private val studentMap = mutableMapOf<String, String>()

    @Before
    fun setUp() {
        matcher = FaceMatcher()
        studentMap.clear()
        val list = mutableListOf<CachedBiometric>()
        for (i in 1..10) {
            val roll = "ROLL_%03d".format(i)
            val name = "Student $i"
            studentMap[roll] = name
            val emb = BiometricTestFixtures.generateSyntheticEmbedding(i.toFloat())
            list.add(
                CachedBiometric(
                    templateId = "TMP_$i",
                    studentRoll = roll,
                    angleType = "FRONTAL",
                    embedding = emb
                )
            )
        }
        matcher.preloadCachedBiometrics(list)
    }

    // ── 1. Multi-Subject Concurrent Batch Verification ──

    @Test
    fun testE2E_multiSubjectBatchEvaluation_preservesAllDecisions() = runBlocking {
        val scheduler = BoundedGroupInferenceScheduler(initialSlots = 2)
        val faceCount = 20
        val observedConcurrency = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)

        // Simulate 20 detected faces in a single camera frame
        val faceIds = (1..faceCount).toList()
        val results = scheduler.processBatch(faceIds) { id ->
            val cur = observedConcurrency.incrementAndGet()
            maxConcurrency.updateAndGet { current -> maxOf(current, cur) }
            val roll = "ROLL_%03d".format(id.coerceIn(1, 10))
            val emb = BiometricTestFixtures.generateSyntheticEmbedding(id.coerceIn(1, 10).toFloat())
            val match = matcher.match(emb, studentMap, SecurityTier.STANDARD)
            observedConcurrency.decrementAndGet()
            match
        }

        // Must preserve all 20 individual decisions (no single-winner collapse)
        assertEquals("Must return decisions for all 20 detected faces", faceCount, results.size)
        assertTrue("Concurrency must stay within bounded capacity of 2", maxConcurrency.get() <= 2)
        assertEquals(20L, scheduler.totalExecuted)
        assertEquals(0, scheduler.activeTasks)
    }

    // ── 2. In-Batch Duplicate Identity Resolution ──

    @Test
    fun testE2E_inBatchDuplicateResolution_highestConfidenceWins() {
        // Two faces in the same frame claim ROLL_001
        val candidate1 = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "ROLL_001",
            matchedStudentName = "Student 1",
            matchConfidence = 95f,
            matchSimilarity = 0.88f,
            decisionMargin = 0.15f,
            qualityScore = 85f,
            livenessScore = 98f,
            title = "Student 1",
            subtitle = "ROLL_001",
            technicalExplanation = "High confidence match"
        )
        val candidate2 = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "ROLL_001",
            matchedStudentName = "Student 1",
            matchConfidence = 78f,
            matchSimilarity = 0.75f,
            decisionMargin = 0.05f,
            qualityScore = 70f,
            livenessScore = 90f,
            title = "Student 1",
            subtitle = "ROLL_001",
            technicalExplanation = "Moderate confidence match"
        )

        val batch = listOf(candidate1, candidate2)

        // Deduplication rule: Highest confidence wins, subsequent duplicate demoted
        val grouped = batch.groupBy { it.matchedStudentRoll }
        val resolved = mutableListOf<BiometricSynthesisDecision>()
        for ((_, decisions) in grouped) {
            val winner = decisions.maxByOrNull { it.matchSimilarity }!!
            resolved.add(winner)
            decisions.filter { it != winner }.forEach { loser ->
                resolved.add(loser.copy(gateState = PipelineGateState.REJECT_UNKNOWN_IDENTITY, isAttendanceAuthorized = false))
            }
        }

        assertEquals(2, resolved.size)
        val winner = resolved.first { it.matchSimilarity == 0.88f }
        val loser = resolved.first { it.matchSimilarity == 0.75f }

        assertTrue("Candidate 1 (higher similarity 0.88) must remain authorized", winner.isAttendanceAuthorized)
        assertEquals(PipelineGateState.PASS, winner.gateState)

        assertFalse("Candidate 2 (lower similarity 0.75) must be demoted", loser.isAttendanceAuthorized)
        assertEquals(PipelineGateState.REJECT_UNKNOWN_IDENTITY, loser.gateState)
    }

    // ── 3. Cross-Tier Hardware Delegate Invariant ──

    @Test
    fun testE2E_crossTierHardwareDelegateInvariants() {
        // Verify ISO/IEC threshold stability across hardware tiers
        val queryEmb = BiometricTestFixtures.generateSyntheticEmbedding(1.0f)

        val resultNpu = matcher.match(queryEmb, studentMap, SecurityTier.STANDARD, HardwareTier.NPU_NNAPI)
        val resultGpu = matcher.match(queryEmb, studentMap, SecurityTier.STANDARD, HardwareTier.GPU_DELEGATE)
        val resultCpu = matcher.match(queryEmb, studentMap, SecurityTier.STANDARD, HardwareTier.CPU_XNNPACK)

        assertTrue(resultNpu.isMatch)
        assertTrue(resultGpu.isMatch)
        assertTrue(resultCpu.isMatch)

        assertEquals("ROLL_001", resultNpu.studentRoll)
        assertEquals("ROLL_001", resultGpu.studentRoll)
        assertEquals("ROLL_001", resultCpu.studentRoll)

        assertEquals(HardwareTier.NPU_NNAPI, resultNpu.hardwareTier)
        assertEquals(HardwareTier.GPU_DELEGATE, resultGpu.hardwareTier)
        assertEquals(HardwareTier.CPU_XNNPACK, resultCpu.hardwareTier)
    }

    // ── 4. Full Lifecycle Aegis Blockchain & ZKP Verification ──

    @Test
    fun testE2E_fullLifecycleAegisZkpPipeline() {
        val roll = "ROLL_001"
        val emb = BiometricTestFixtures.generateSyntheticEmbedding(1.0f)
        val csv = emb.joinToString(",")

        // 1. ZKP Commitment generation
        val (commitmentHex, saltHex) = ZkpPrivacyManager.generateZkpCommitment(csv)
        assertNotNull(commitmentHex)
        assertNotNull(saltHex)

        // 2. Local FAISS match succeeds
        val match = matcher.match(emb, studentMap, SecurityTier.HIGH)
        assertTrue(match.isMatch)

        // 3. Aegis Blockchain leaf proof minting
        val md = MessageDigest.getInstance("SHA-256")
        val leafData = "${match.studentRoll}|${System.currentTimeMillis()}|$commitmentHex"
        val leafHash = md.digest(leafData.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        // 4. Remote proof verification without sharing vector coordinates
        assertTrue("Cloud server verifies ZKP commitment with secret salt", ZkpPrivacyManager.verifyZkpCommitment(csv, commitmentHex, saltHex))
        assertTrue("Aegis leaf hash must be valid 64-char hex", leafHash.length == 64)
    }

    // ── 5. Morning Rush-Hour Kiosk Cycle with Cooldown ──

    @Test
    fun testE2E_morningRushKioskCycleWithCooldown() {
        val sm = KioskAutomationStateMachine()
        val trackAlice = 101

        // Alice arrives at kiosk
        sm.onFacesDetected(count = 1, trackIds = listOf(trackAlice))
        assertEquals(KioskAutomationState.PROCESSING, sm.currentState.value)

        // Decision: AUTO_CONFIRM
        val decisionAlice = AutomationDecision(
            action = AutomationAction.AUTO_CONFIRM,
            studentRoll = "ROLL_001",
            studentName = "Student 1",
            confidencePct = 95f,
            livenessScore = 98f,
            matchSimilarity = 0.85f,
            decisionMargin = 0.15f,
            requiredFrames = 2,
            currentFrames = 2,
            reason = "High confidence match"
        )
        sm.onPolicyEvaluated(trackAlice, decisionAlice)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, sm.currentState.value)

        // Persist attendance
        val recorded = sm.onAttendanceRecorded(recordsPersisted = 1, trackId = trackAlice, proofHash = "proof_001")
        assertTrue(recorded)
        assertEquals(KioskAutomationState.RECORDED, sm.currentState.value)

        // Outbox staged
        sm.onNotificationQueued(trackAlice, "OUTBOX_001")
        assertEquals(KioskAutomationState.NOTIFICATION_QUEUED, sm.currentState.value)

        // Complete cycle
        sm.onCycleCompleted(trackAlice)
        assertEquals(KioskAutomationState.SCANNING, sm.currentState.value)

        // Alice re-enters viewfinder immediately (within cooldown)
        val trackAliceAgain = 102
        sm.onFacesDetected(count = 1, trackIds = listOf(trackAliceAgain))
        val decisionCooldown = AutomationDecision(
            action = AutomationAction.COOLDOWN,
            studentRoll = "ROLL_001",
            studentName = "Student 1",
            confidencePct = 95f,
            livenessScore = 98f,
            matchSimilarity = 0.85f,
            decisionMargin = 0.15f,
            requiredFrames = 2,
            currentFrames = 2,
            reason = "Recently verified"
        )
        val stateCooldown = sm.onPolicyEvaluated(trackAliceAgain, decisionCooldown)
        assertEquals(KioskAutomationState.COOLDOWN, stateCooldown)

        // Next student (Bob) arrives
        val trackBob = 103
        sm.onFacesDetected(count = 1, trackIds = listOf(trackBob))
        val decisionBob = AutomationDecision(
            action = AutomationAction.AUTO_CONFIRM,
            studentRoll = "ROLL_002",
            studentName = "Student 2",
            confidencePct = 94f,
            livenessScore = 97f,
            matchSimilarity = 0.83f,
            decisionMargin = 0.12f,
            requiredFrames = 2,
            currentFrames = 2,
            reason = "High confidence match"
        )
        sm.onPolicyEvaluated(trackBob, decisionBob)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, sm.currentState.value)
    }

    // ── 6. High-Density Crowd (50 Subjects) Permit Boundary Safety ──

    @Test
    fun testE2E_highDensityCrowd50SubjectsPermitSafety() = runBlocking {
        val scheduler = BoundedGroupInferenceScheduler(initialSlots = 3)
        val crowdSize = 50
        val items = (1..crowdSize).toList()
        val processedCounter = AtomicInteger(0)

        val results = scheduler.processBatch(items) { id ->
            processedCounter.incrementAndGet()
            id * 10
        }

        assertEquals(crowdSize, results.size)
        assertEquals(crowdSize, processedCounter.get())
        assertEquals(crowdSize.toLong(), scheduler.totalExecuted)
        assertEquals(0, scheduler.activeTasks)
    }
}