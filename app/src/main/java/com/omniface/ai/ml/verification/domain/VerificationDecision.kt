package com.omniface.ai.ml.verification.domain

/**
 * Presentation Attack Detection (Anti-Spoof) Failure Reason.
 */
enum class SpoofReason {
    TEXTURE_ANOMALY,
    DEPTH_VARIANCE_FLAT,
    GAZE_DIVERGENT,
    EYE_BLINK_ABSENT,
    TEMPORAL_JITTER
}

/**
 * Biometric Quality Envelope Failure Reason.
 */
enum class QualityReason {
    BLURRY,
    OVEREXPOSED,
    UNDEREXPOSED,
    POSE_PITCH_EXCEEDED,
    POSE_YAW_EXCEEDED,
    FACE_TOO_SMALL
}

/**
 * Pure Domain Face Geometry (Decoupled from Compose UI types).
 */
data class DomainFaceGeometry(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val rollAngle: Float = 0f,
    val pitchAngle: Float = 0f,
    val yawAngle: Float = 0f
)

/**
 * Compile-Time Exhaustive Biometric Decision Hierarchy.
 *
 * Represents the immediate result of analyzing a single camera frame.
 */
sealed interface VerificationDecision {

    /** No face is present in the visual frame. */
    data object Idle : VerificationDecision

    /**
     * Genuine identity matched with confidence & margin above calibrated threshold.
     */
    data class Verified(
        val identityId: String,
        val displayName: String,
        val role: String,
        val confidence: Float,
        val liveness: Float,
        val leafHash: String,
        val geometry: DomainFaceGeometry? = null
    ) : VerificationDecision

    /**
     * Genuine identity recognized, but already marked present in active session cooldown.
     */
    data class Duplicate(
        val identityId: String,
        val displayName: String,
        val role: String,
        val remainingCooldownMs: Long,
        val geometry: DomainFaceGeometry? = null
    ) : VerificationDecision

    /**
     * Genuine face detected, but no matching enrolled identity exists in store.
     */
    data class Unknown(
        val confidence: Float,
        val geometry: DomainFaceGeometry? = null
    ) : VerificationDecision

    /**
     * Presentation attack / spoof attempt defeated.
     */
    data class SpoofRejected(
        val reason: SpoofReason,
        val confidence: Float,
        val geometry: DomainFaceGeometry? = null
    ) : VerificationDecision

    /**
     * Face detected, but violates quality envelopes (blur, angle, scale).
     */
    data class PoorQuality(
        val reason: QualityReason,
        val geometry: DomainFaceGeometry? = null
    ) : VerificationDecision
}
