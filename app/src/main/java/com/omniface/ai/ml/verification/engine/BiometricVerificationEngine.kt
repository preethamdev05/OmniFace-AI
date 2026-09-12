package com.omniface.ai.ml.verification.engine

import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.verification.domain.BiometricFrame
import com.omniface.ai.ml.verification.domain.BiometricTransientEvent
import com.omniface.ai.ml.verification.domain.HardwareTelemetry
import com.omniface.ai.ml.verification.domain.VerificationDecision
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Sovereign Master Biometric Verification Engine.
 *
 * Deep module encapsulating face detection, Kalman tracking, thermal scaling,
 * multi-modal anti-spoofing, hardware accelerator fallbacks, volatile template matching,
 * and temporal consensus behind a single high-leverage interface.
 */
interface BiometricVerificationEngine : AutoCloseable {

    /**
     * Verifies a single visual frame against enrolled identities.
     * Returns an immediate, strongly typed domain decision.
     */
    suspend fun verifyFrame(
        frame: BiometricFrame,
        securityTier: SecurityTier = SecurityTier.STANDARD
    ): VerificationDecision

    /**
     * Reactive hardware and neural execution telemetry.
     */
    val telemetry: StateFlow<HardwareTelemetry>

    /**
     * Non-durable transient events for audio cues, UI toasts, and haptic feedback.
     */
    val transientEvents: SharedFlow<BiometricTransientEvent>
}
