package com.omniface.ai.ml.antispoof

import kotlin.random.Random

enum class LivenessChallengeType(val prompt: String) {
    TURN_LEFT("Turn head slightly to the left ←"),
    TURN_RIGHT("Turn head slightly to the right →"),
    TILT_UP("Tilt head slightly upward ↑"),
    TILT_DOWN("Tilt head slightly downward ↓"),
    SMILE("Smile at the camera 😊"),
    BLINK("Blink both eyes 👁️")
}

data class ActiveChallengeState(
    val currentChallenge: LivenessChallengeType,
    val isCompleted: Boolean,
    val progressPct: Float,
    val remainingTimeMs: Long,
    val guidance: String
)

/**
 * Randomized Interactive Liveness Protocol for High-Security / Banking Tier Attendance.
 *
 * Enforces unpredictable user actions verified through Qualcomm FaceMap 3DMM & FaceAttribNet.
 */
class ActiveChallengeEngine {

    companion object {
        private const val CHALLENGE_TIMEOUT_MS = 5000L
    }

    private var activeChallenge: LivenessChallengeType = LivenessChallengeType.TURN_LEFT
    private var challengeStartTimeMs: Long = 0L
    private var isChallengePassed: Boolean = false

    fun startRandomChallenge(): ActiveChallengeState {
        val types = LivenessChallengeType.values()
        activeChallenge = types[Random.nextInt(types.size)]
        challengeStartTimeMs = System.currentTimeMillis()
        isChallengePassed = false
        return getStatus(0f, 0f, 0f, 1.0f)
    }

    fun evaluateMotion(
        yaw: Float,
        pitch: Float,
        smileScore: Float,
        eyeOpenness: Float
    ): ActiveChallengeState {
        val now = System.currentTimeMillis()
        val elapsed = now - challengeStartTimeMs
        val remaining = (CHALLENGE_TIMEOUT_MS - elapsed).coerceAtLeast(0L)

        if (isChallengePassed) {
            return ActiveChallengeState(
                currentChallenge = activeChallenge,
                isCompleted = true,
                progressPct = 1.0f,
                remainingTimeMs = remaining,
                guidance = "✓ Challenge Verified!"
            )
        }

        val passed = when (activeChallenge) {
            LivenessChallengeType.TURN_LEFT -> yaw <= -14.0f
            LivenessChallengeType.TURN_RIGHT -> yaw >= 14.0f
            LivenessChallengeType.TILT_UP -> pitch >= 10.0f
            LivenessChallengeType.TILT_DOWN -> pitch <= -10.0f
            LivenessChallengeType.SMILE -> smileScore >= 0.70f
            LivenessChallengeType.BLINK -> eyeOpenness <= 0.20f
        }

        if (passed) {
            isChallengePassed = true
            return ActiveChallengeState(
                currentChallenge = activeChallenge,
                isCompleted = true,
                progressPct = 1.0f,
                remainingTimeMs = remaining,
                guidance = "✓ Challenge Passed!"
            )
        }

        val progress = when (activeChallenge) {
            LivenessChallengeType.TURN_LEFT -> (-yaw / 14f).coerceIn(0f, 1f)
            LivenessChallengeType.TURN_RIGHT -> (yaw / 14f).coerceIn(0f, 1f)
            LivenessChallengeType.TILT_UP -> (pitch / 10f).coerceIn(0f, 1f)
            LivenessChallengeType.TILT_DOWN -> (-pitch / 10f).coerceIn(0f, 1f)
            LivenessChallengeType.SMILE -> smileScore.coerceIn(0f, 1f)
            LivenessChallengeType.BLINK -> (1f - eyeOpenness).coerceIn(0f, 1f)
        }

        return ActiveChallengeState(
            currentChallenge = activeChallenge,
            isCompleted = false,
            progressPct = progress,
            remainingTimeMs = remaining,
            guidance = activeChallenge.prompt
        )
    }

    private fun getStatus(yaw: Float, pitch: Float, smile: Float, eye: Float): ActiveChallengeState {
        return evaluateMotion(yaw, pitch, smile, eye)
    }

    fun reset() {
        challengeStartTimeMs = 0L
        isChallengePassed = false
    }
}
