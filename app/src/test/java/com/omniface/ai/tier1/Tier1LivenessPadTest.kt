package com.omniface.ai.tier1

import com.omniface.ai.ml.antispoof.ActiveChallengeEngine
import com.omniface.ai.ml.antispoof.LivenessChallengeType
import com.omniface.ai.ml.antispoof.TemporalLivenessEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tier 1: Feature 4 - MultiStage & Temporal Liveness Presentation Attack Detection (PAD)
 */
class Tier1LivenessPadTest {

    private lateinit var temporalEngine: TemporalLivenessEngine
    private lateinit var challengeEngine: ActiveChallengeEngine

    @Before
    fun setUp() {
        temporalEngine = TemporalLivenessEngine()
        challengeEngine = ActiveChallengeEngine()
    }

    @Test
    fun testTemporalLivenessBlinkDetection() {
        val trackId = 1001
        // Simulate eye opening values representing a blink (open -> close -> open)
        val eyeSeq = listOf(0.95f, 0.90f, 0.15f, 0.10f, 0.85f, 0.95f)
        for ((idx, eye) in eyeSeq.withIndex()) {
            temporalEngine.recordSample(
                trackId = trackId,
                yaw = idx * 0.5f,
                pitch = 0f,
                roll = 0f,
                attributes = null,
                faceMap3DMM = null,
                passivePad = null,
                landmarks5Pts = null,
                eyeOpenProbability = eye
            )
        }

        val result = temporalEngine.evaluateTemporalLiveness(trackId)
        assertTrue("Natural blink should be detected when eye delta >= 0.30", result.naturalBlinkDetected)
    }

    @Test
    fun testTemporalLivenessHeadTurnDetection() {
        val trackId = 1002
        // Simulate yaw angle reaching 14 degrees
        for (i in 0 until 8) {
            temporalEngine.recordSample(
                trackId = trackId,
                yaw = i * 2.0f, // Reaches 14.0f
                pitch = 0f,
                roll = 0f,
                attributes = null,
                faceMap3DMM = null,
                passivePad = null,
                landmarks5Pts = null,
                eyeOpenProbability = 0.9f
            )
        }

        val result = temporalEngine.evaluateTemporalLiveness(trackId)
        assertTrue("Head turn >= 12.0 degrees yaw should be flagged", result.headTurnDetected)
    }

    @Test
    fun testStaticReplayAttackDetection() {
        val trackId = 1003
        // 7 static identical frames
        repeat(7) {
            temporalEngine.recordSample(
                trackId = trackId,
                yaw = 0f,
                pitch = 0f,
                roll = 0f,
                attributes = null,
                faceMap3DMM = null,
                passivePad = null,
                landmarks5Pts = null,
                eyeOpenProbability = 0.95f
            )
        }

        val result = temporalEngine.evaluateTemporalLiveness(trackId)
        assertFalse("Static replay attack with zero micro-motion must fail liveness", result.isLive)
    }

    @Test
    fun testActiveChallengeEngineRandomization() {
        val state = challengeEngine.startRandomChallenge()
        assertNotNull("Active challenge must be initialized", state.currentChallenge)
        assertTrue("Challenge prompt must be non-empty", state.guidance.isNotBlank())
        assertFalse("Newly started challenge must not be pre-completed", state.isCompleted)
        assertEquals("Initial progress should be bounded in [0, 1]", 0f, state.progressPct, 0.01f)
    }

    @Test
    fun testActiveChallengeMotionEvaluation() {
        val initial = challengeEngine.startRandomChallenge()

        // Provide motion matching whichever challenge was randomly selected
        val result = when (initial.currentChallenge) {
            LivenessChallengeType.TURN_LEFT -> challengeEngine.evaluateMotion(yaw = -20f, pitch = 0f, smileScore = 0f, eyeOpenness = 1f)
            LivenessChallengeType.TURN_RIGHT -> challengeEngine.evaluateMotion(yaw = 20f, pitch = 0f, smileScore = 0f, eyeOpenness = 1f)
            LivenessChallengeType.TILT_UP -> challengeEngine.evaluateMotion(yaw = 0f, pitch = 15f, smileScore = 0f, eyeOpenness = 1f)
            LivenessChallengeType.TILT_DOWN -> challengeEngine.evaluateMotion(yaw = 0f, pitch = -15f, smileScore = 0f, eyeOpenness = 1f)
            LivenessChallengeType.SMILE -> challengeEngine.evaluateMotion(yaw = 0f, pitch = 0f, smileScore = 0.9f, eyeOpenness = 1f)
            LivenessChallengeType.BLINK -> challengeEngine.evaluateMotion(yaw = 0f, pitch = 0f, smileScore = 0f, eyeOpenness = 0.05f)
        }

        assertTrue("Matching active motion must complete or advance challenge", result.isCompleted || result.progressPct > 0f)

        challengeEngine.reset()
        val resetState = challengeEngine.startRandomChallenge()
        assertFalse("After reset, challenge is fresh", resetState.isCompleted)
    }
}
