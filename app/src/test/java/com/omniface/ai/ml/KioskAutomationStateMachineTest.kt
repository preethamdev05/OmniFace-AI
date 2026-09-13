package com.omniface.ai.ml

import com.omniface.ai.ml.verification.policy.AutomationAction
import com.omniface.ai.ml.verification.policy.AutomationDecision
import com.omniface.ai.ml.verification.policy.KioskAutomationState
import com.omniface.ai.ml.verification.policy.KioskAutomationStateMachine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 🧪 Unit Test Suite for [KioskAutomationStateMachine] (Phase 13).
 *
 * Verifies:
 * 1. Golden Path: SCANNING -> PROCESSING -> AUTO_CONFIRMED -> RECORDED -> NOTIFICATION_QUEUED -> SCANNING
 * 2. Invariant Guards: Bypassing AUTO_CONFIRMED or persisting 0 records is strictly forbidden.
 * 3. Review Path: Ambiguous candidates transition to NEEDS_REVIEW.
 * 4. Cooldown and Rejection: Cooldown prevents duplicates; spoof attacks enter REJECTED.
 * 5. Audit Transition History: Every lifecycle change is recorded with timestamps and causal reasons.
 */
class KioskAutomationStateMachineTest {

    private lateinit var stateMachine: KioskAutomationStateMachine

    @Before
    fun setUp() {
        stateMachine = KioskAutomationStateMachine()
    }

    private fun createDecision(
        action: AutomationAction,
        reason: String = "Test reason"
    ): AutomationDecision {
        return AutomationDecision(
            action = action,
            studentRoll = "ROLL_001",
            studentName = "Rahul Sharma",
            confidencePct = 95f,
            livenessScore = 98f,
            matchSimilarity = 0.82f,
            decisionMargin = 0.15f,
            requiredFrames = 2,
            currentFrames = 2,
            reason = reason
        )
    }

    @Test
    fun testGoldenPath_scanningToNotificationQueuedToScanning() {
        assertEquals(KioskAutomationState.SCANNING, stateMachine.currentState.value)

        // 1. Ingress: Faces detected
        val trackId = 101
        stateMachine.onFacesDetected(count = 1, trackIds = listOf(trackId))
        assertEquals(KioskAutomationState.PROCESSING, stateMachine.currentState.value)
        assertEquals(KioskAutomationState.PROCESSING, stateMachine.getTrackState(trackId))

        // 2. Policy Evaluation: Auto-Confirmed
        val autoConfirmDecision = createDecision(AutomationAction.AUTO_CONFIRM, "High confidence match")
        val evaluatedState = stateMachine.onPolicyEvaluated(trackId, autoConfirmDecision)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, evaluatedState)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, stateMachine.currentState.value)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, stateMachine.getTrackState(trackId))

        // 3. Attendance Persisted via AttendanceService
        val recorded = stateMachine.onAttendanceRecorded(recordsPersisted = 1, trackId = trackId, proofHash = "sha256_proof_leaf")
        assertTrue(recorded)
        assertEquals(KioskAutomationState.RECORDED, stateMachine.currentState.value)
        assertEquals(KioskAutomationState.RECORDED, stateMachine.getTrackState(trackId))

        // 4. Outbox Staged for Asynchronous Cloud/WhatsApp Notification
        stateMachine.onNotificationQueued(trackId = trackId, outboxId = "sha256_proof_leaf")
        assertEquals(KioskAutomationState.NOTIFICATION_QUEUED, stateMachine.currentState.value)
        assertEquals(KioskAutomationState.NOTIFICATION_QUEUED, stateMachine.getTrackState(trackId))

        // 5. Cycle Reset Back to Scanning
        stateMachine.onCycleCompleted(trackId = trackId)
        assertEquals(KioskAutomationState.SCANNING, stateMachine.currentState.value)
        assertEquals(KioskAutomationState.SCANNING, stateMachine.getTrackState(trackId))
    }

    @Test
    fun testInvariantGuard_cannotRecordDirectlyFromScanning() {
        // Attempting to record attendance without previous face detection and confirmation
        val recorded = stateMachine.onAttendanceRecorded(recordsPersisted = 1, trackId = 102, proofHash = "fake_hash")
        assertFalse(recorded)
        assertEquals(KioskAutomationState.SCANNING, stateMachine.currentState.value)
    }

    @Test
    fun testInvariantGuard_cannotRecordDirectlyFromProcessing() {
        stateMachine.onFacesDetected(count = 1, trackIds = listOf(103))
        assertEquals(KioskAutomationState.PROCESSING, stateMachine.currentState.value)

        // Cannot bypass policy evaluation to record directly
        val recorded = stateMachine.onAttendanceRecorded(recordsPersisted = 1, trackId = 103, proofHash = "fake_hash")
        assertFalse(recorded)
        assertEquals(KioskAutomationState.PROCESSING, stateMachine.currentState.value)
    }

    @Test
    fun testInvariantGuard_cannotRecordZeroRecords() {
        val trackId = 104
        stateMachine.onFacesDetected(count = 1, trackIds = listOf(trackId))
        stateMachine.onPolicyEvaluated(trackId, createDecision(AutomationAction.AUTO_CONFIRM))
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, stateMachine.currentState.value)

        // 0 records persisted must fail
        val recorded = stateMachine.onAttendanceRecorded(recordsPersisted = 0, trackId = trackId, proofHash = "zero_hash")
        assertFalse(recorded)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, stateMachine.currentState.value)
    }

    @Test
    fun testNeedsReviewLifecycle() {
        val trackId = 105
        stateMachine.onFacesDetected(count = 1, trackIds = listOf(trackId))

        val reviewDecision = createDecision(AutomationAction.NEEDS_REVIEW, "Ambiguous margin across 4 frames")
        stateMachine.onPolicyEvaluated(trackId, reviewDecision)

        assertEquals(KioskAutomationState.NEEDS_REVIEW, stateMachine.currentState.value)
        assertEquals(KioskAutomationState.NEEDS_REVIEW, stateMachine.getTrackState(trackId))

        stateMachine.onCycleCompleted(trackId)
        assertEquals(KioskAutomationState.SCANNING, stateMachine.currentState.value)
    }

    @Test
    fun testCooldownLifecycle() {
        val trackId = 106
        stateMachine.onFacesDetected(count = 1, trackIds = listOf(trackId))

        val cooldownDecision = createDecision(AutomationAction.COOLDOWN, "Identity recently recorded")
        stateMachine.onPolicyEvaluated(trackId, cooldownDecision)

        assertEquals(KioskAutomationState.COOLDOWN, stateMachine.currentState.value)
        assertEquals(KioskAutomationState.COOLDOWN, stateMachine.getTrackState(trackId))
    }

    @Test
    fun testRejectionOnSpoofAttack() {
        val trackId = 107
        stateMachine.onFacesDetected(count = 1, trackIds = listOf(trackId))

        val spoofDecision = createDecision(AutomationAction.REJECT_OR_WAIT, "Spoof attack: 2D texture anomaly detected")
        stateMachine.onPolicyEvaluated(trackId, spoofDecision)

        assertEquals(KioskAutomationState.REJECTED, stateMachine.currentState.value)
        assertEquals(KioskAutomationState.REJECTED, stateMachine.getTrackState(trackId))
    }

    @Test
    fun testNoFacesDetected_resetsProcessingToScanning() {
        stateMachine.onFacesDetected(count = 1, trackIds = listOf(108))
        assertEquals(KioskAutomationState.PROCESSING, stateMachine.currentState.value)

        stateMachine.onNoFacesDetected()
        assertEquals(KioskAutomationState.SCANNING, stateMachine.currentState.value)
    }

    @Test
    fun testTransitionHistoryAuditTrail() {
        val trackId = 109
        stateMachine.onFacesDetected(1, listOf(trackId))
        stateMachine.onPolicyEvaluated(trackId, createDecision(AutomationAction.AUTO_CONFIRM, "Confidence 95%"))
        stateMachine.onAttendanceRecorded(1, trackId, "leaf_hash_123")
        stateMachine.onNotificationQueued(trackId, "leaf_hash_123")
        stateMachine.onCycleCompleted(trackId)

        val transitions = stateMachine.history
        assertTrue(transitions.size >= 5)

        assertEquals(KioskAutomationState.SCANNING, transitions[0].fromState)
        assertEquals(KioskAutomationState.PROCESSING, transitions[0].toState)

        assertEquals(KioskAutomationState.PROCESSING, transitions[1].fromState)
        assertEquals(KioskAutomationState.AUTO_CONFIRMED, transitions[1].toState)

        assertEquals(KioskAutomationState.AUTO_CONFIRMED, transitions[2].fromState)
        assertEquals(KioskAutomationState.RECORDED, transitions[2].toState)

        assertEquals(KioskAutomationState.RECORDED, transitions[3].fromState)
        assertEquals(KioskAutomationState.NOTIFICATION_QUEUED, transitions[3].toState)

        assertEquals(KioskAutomationState.NOTIFICATION_QUEUED, transitions[4].fromState)
        assertEquals(KioskAutomationState.SCANNING, transitions[4].toState)
    }
}
