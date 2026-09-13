package com.omniface.ai.ml.verification.policy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Deterministic Lifecycle States for the Autonomous OmniFace Kiosk (Phase 13).
 *
 * Desired authoritative lifecycle:
 * SCANNING -> PROCESSING -> AUTO_CONFIRMED -> RECORDED -> NOTIFICATION_QUEUED -> SCANNING
 */
enum class KioskAutomationState {
    /** Idle scanning state; viewfinder is active awaiting subject detection. */
    SCANNING,

    /** Face subject detected and undergoing feature extraction, alignment, and multi-gate verification. */
    PROCESSING,

    /** High-confidence biometric and liveness match satisfies all policy criteria; auto-confirmed hands-free. */
    AUTO_CONFIRMED,

    /** Attendance record atomically persisted with Aegis blockchain leaf proof via AttendanceService. */
    RECORDED,

    /** Aegis outbox entry staged for background asynchronous cloud/fleet sync and notifications. */
    NOTIFICATION_QUEUED,

    /** Uncertain, ambiguous, or low-margin candidate requiring manual supervisor review. */
    NEEDS_REVIEW,

    /** Subject recently verified; in cooldown window to prevent rapid duplicate records. */
    COOLDOWN,

    /** Anti-spoofing rejection or low capture quality; transaction aborted. */
    REJECTED
}

/**
 * Audit transition entry recording state lifecycle changes.
 */
data class KioskAutomationTransition(
    val fromState: KioskAutomationState,
    val toState: KioskAutomationState,
    val timestampMs: Long = System.currentTimeMillis(),
    val trackId: Int = 0,
    val reason: String = ""
)

/**
 * Deterministic Kiosk Automation State Machine (Phase 13).
 *
 * Enforces the strict invariants:
 * 1. SCANNING -> PROCESSING -> AUTO_CONFIRMED -> RECORDED -> NOTIFICATION_QUEUED -> SCANNING
 * 2. Automatic behavior must never bypass AttendanceService (transition to RECORDED requires valid persistence).
 * 3. Uncertain/ambiguous faces enter NEEDS_REVIEW.
 * 4. Poor quality never triggers forced recognition.
 * 5. Cooldown prevents rapid duplicate attendance.
 * 6. Scanner remains 100% usable without network connectivity.
 */
class KioskAutomationStateMachine {

    private val _currentState = MutableStateFlow(KioskAutomationState.SCANNING)
    val currentState: StateFlow<KioskAutomationState> = _currentState.asStateFlow()

    private val trackStates = ConcurrentHashMap<Int, KioskAutomationState>()
    private val transitionHistory = CopyOnWriteArrayList<KioskAutomationTransition>()

    val history: List<KioskAutomationTransition>
        get() = transitionHistory.toList()

    /**
     * Retrieves the current automation state for a specific face track.
     */
    fun getTrackState(trackId: Int): KioskAutomationState =
        trackStates[trackId] ?: KioskAutomationState.SCANNING

    /**
     * Triggered at frame ingress when one or more faces are detected.
     */
    fun onFacesDetected(count: Int, trackIds: Collection<Int> = emptyList()) {
        if (count > 0 && _currentState.value == KioskAutomationState.SCANNING) {
            transitionTo(KioskAutomationState.PROCESSING, reason = "$count face(s) detected in viewfinder")
        }
        for (id in trackIds) {
            if (trackStates[id] == null || trackStates[id] == KioskAutomationState.SCANNING) {
                trackStates[id] = KioskAutomationState.PROCESSING
            }
        }
    }

    /**
     * Evaluates a decision produced by [AutomationPolicy] for a specific track.
     */
    fun onPolicyEvaluated(trackId: Int, decision: AutomationDecision): KioskAutomationState {
        val nextState = when (decision.action) {
            AutomationAction.AUTO_CONFIRM -> KioskAutomationState.AUTO_CONFIRMED
            AutomationAction.NEEDS_REVIEW -> KioskAutomationState.NEEDS_REVIEW
            AutomationAction.COOLDOWN -> KioskAutomationState.COOLDOWN
            AutomationAction.REJECT_OR_WAIT -> {
                if (decision.reason.contains("Spoof", ignoreCase = true)) {
                    KioskAutomationState.REJECTED
                } else {
                    KioskAutomationState.PROCESSING
                }
            }
            AutomationAction.OBSERVE_MORE_FRAMES -> KioskAutomationState.PROCESSING
        }

        trackStates[trackId] = nextState

        // Update overall kiosk state if appropriate
        val current = _currentState.value
        if (nextState == KioskAutomationState.AUTO_CONFIRMED) {
            transitionTo(KioskAutomationState.AUTO_CONFIRMED, trackId = trackId, reason = decision.reason)
        } else if (nextState == KioskAutomationState.NEEDS_REVIEW && current == KioskAutomationState.PROCESSING) {
            transitionTo(KioskAutomationState.NEEDS_REVIEW, trackId = trackId, reason = decision.reason)
        } else if (nextState == KioskAutomationState.COOLDOWN && current == KioskAutomationState.PROCESSING) {
            transitionTo(KioskAutomationState.COOLDOWN, trackId = trackId, reason = decision.reason)
        } else if (nextState == KioskAutomationState.REJECTED && current == KioskAutomationState.PROCESSING) {
            transitionTo(KioskAutomationState.REJECTED, trackId = trackId, reason = decision.reason)
        }

        return nextState
    }

    /**
     * Authoritative transition to RECORDED.
     *
     * INVARIANT GUARD:
     * Must originate from AUTO_CONFIRMED and must have persisted >= 1 record.
     * Prevents bypassing the biometric verification / policy pipeline.
     */
    fun onAttendanceRecorded(recordsPersisted: Int, trackId: Int = 0, proofHash: String = ""): Boolean {
        val current = _currentState.value
        if (current != KioskAutomationState.AUTO_CONFIRMED && trackStates[trackId] != KioskAutomationState.AUTO_CONFIRMED) {
            // Cannot record attendance without preceding auto-confirm
            return false
        }
        if (recordsPersisted <= 0) {
            return false
        }

        trackStates[trackId] = KioskAutomationState.RECORDED
        transitionTo(
            KioskAutomationState.RECORDED,
            trackId = trackId,
            reason = "Successfully persisted $recordsPersisted attendance record(s) [proof: $proofHash]"
        )
        return true
    }

    /**
     * Transitions from RECORDED to NOTIFICATION_QUEUED.
     * Documents that Aegis outbox entry is safely queued for background processing without blocking camera path.
     */
    fun onNotificationQueued(trackId: Int = 0, outboxId: String = "") {
        val current = _currentState.value
        if (current == KioskAutomationState.RECORDED || trackStates[trackId] == KioskAutomationState.RECORDED) {
            trackStates[trackId] = KioskAutomationState.NOTIFICATION_QUEUED
            transitionTo(
                KioskAutomationState.NOTIFICATION_QUEUED,
                trackId = trackId,
                reason = "Aegis outbox notification enqueued [id: $outboxId]"
            )
        }
    }

    /**
     * Resets the track and/or kiosk state back to SCANNING after visual feedback / cooldown.
     */
    fun onCycleCompleted(trackId: Int = 0) {
        if (trackId != 0) {
            trackStates.remove(trackId)
        } else {
            trackStates.clear()
        }
        transitionTo(KioskAutomationState.SCANNING, trackId = trackId, reason = "Cycle completed, ready to scan next subject")
    }

    /**
     * Triggered when no faces are detected in consecutive frames.
     */
    fun onNoFacesDetected() {
        val current = _currentState.value
        if (current == KioskAutomationState.PROCESSING) {
            transitionTo(KioskAutomationState.SCANNING, reason = "No faces in viewfinder")
        }
        trackStates.clear()
    }

    /**
     * Full state machine reset.
     */
    fun reset() {
        trackStates.clear()
        _currentState.value = KioskAutomationState.SCANNING
        transitionHistory.clear()
    }

    private fun transitionTo(newState: KioskAutomationState, trackId: Int = 0, reason: String = "") {
        val previous = _currentState.value
        if (previous == newState && trackId == 0) return
        _currentState.value = newState
        transitionHistory.add(
            KioskAutomationTransition(
                fromState = previous,
                toState = newState,
                timestampMs = System.currentTimeMillis(),
                trackId = trackId,
                reason = reason
            )
        )
    }
}
