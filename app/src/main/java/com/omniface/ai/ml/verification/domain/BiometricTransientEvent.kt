package com.omniface.ai.ml.verification.domain

/**
 * Transient, Non-Durable In-Memory Domain Events.
 *
 * Consumed by audio synthesizer, haptics, dynamic island toasts, and analytics.
 * Note: Durability of attendance DOES NOT depend on this stream.
 */
sealed interface BiometricTransientEvent {
    data class AttendanceConfirmed(
        val identityId: String,
        val displayName: String,
        val role: String,
        val confidence: Float,
        val leafHash: String,
        val timestamp: Long
    ) : BiometricTransientEvent

    data class DuplicateDetected(
        val identityId: String,
        val displayName: String,
        val role: String
    ) : BiometricTransientEvent

    data class SpoofAttemptBlocked(
        val reason: SpoofReason,
        val confidence: Float
    ) : BiometricTransientEvent
}
