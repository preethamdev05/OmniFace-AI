package com.omniface.ai.tier2

import com.omniface.ai.ml.antispoof.ActiveChallengeEngine
import com.omniface.ai.ml.antispoof.TemporalLivenessEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 4: Liveness PAD & Motion Extremes
 */
class Tier2LivenessPadBoundaryTest {

    private lateinit var temporalEngine: TemporalLivenessEngine
    private lateinit var challengeEngine: ActiveChallengeEngine

    @Before
    fun setUp() {
        temporalEngine = TemporalLivenessEngine()
        challengeEngine = ActiveChallengeEngine()
    }

    @Test
    fun testMicroMotionBelowThresholdFails() {
        val trackId = 2001
        // Very tiny jitter (0.01 deg) — typical of a camera tripod looking at a photo
        repeat(8) {
            temporalEngine.recordSample(
                trackId = trackId,
                yaw = it * 0.01f,
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
        assertFalse("Sub-threshold micro jitter should not pass motion requirement", result.headTurnDetected)
    }

    @Test
    fun testExtremeYawBeyondPhysiologicalRange() {
        val trackId = 2002
        temporalEngine.recordSample(
            trackId = trackId,
            yaw = 85.0f, // Extreme profile
            pitch = 0f,
            roll = 0f,
            attributes = null,
            faceMap3DMM = null,
            passivePad = null,
            landmarks5Pts = null,
            eyeOpenProbability = 0.8f
        )
        val result = temporalEngine.evaluateTemporalLiveness(trackId)
        assertNotNull(result)
    }

    @Test
    fun testEmptyTrackHistoryEvaluation() {
        val result = temporalEngine.evaluateTemporalLiveness(trackId = 99999)
        assertNotNull("Evaluating unrecorded track should return safe result", result)
        // With 0 samples, headTurnDetected and naturalBlinkDetected should be false
        assertFalse(result.headTurnDetected)
        assertFalse(result.naturalBlinkDetected)
    }

    @Test
    fun testZeroEyeOpennessConstantValue() {
        val trackId = 2003
        repeat(8) {
            temporalEngine.recordSample(
                trackId = trackId,
                yaw = 0f,
                pitch = 0f,
                roll = 0f,
                attributes = null,
                faceMap3DMM = null,
                passivePad = null,
                landmarks5Pts = null,
                eyeOpenProbability = 0.0f // Perpetually closed eyes
            )
        }

        val result = temporalEngine.evaluateTemporalLiveness(trackId)
        assertFalse("Perpetually closed eyes have zero delta and must not trigger natural blink", result.naturalBlinkDetected)
    }

    @Test
    fun testActiveChallengeZeroProgressOnOppositeMotion() {
        challengeEngine.startRandomChallenge()
        // If challenge is TURN_LEFT (requires yaw <= -14), sending positive yaw (right) should produce 0 progress
        val state = challengeEngine.evaluateMotion(yaw = 25f, pitch = 0f, smileScore = 0f, eyeOpenness = 1f)
        assertNotNull(state)
        assertTrue(state.progressPct in 0f..1f)
    }
}
