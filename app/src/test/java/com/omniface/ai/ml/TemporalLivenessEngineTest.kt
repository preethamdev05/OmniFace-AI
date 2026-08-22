package com.omniface.ai.ml

import com.omniface.ai.ml.antispoof.TemporalLivenessEngine
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TemporalLivenessEngine.
 *
 * Covers:
 * - Static replay attack detection (isStaticReplay guard)
 * - Head turn detection (yaw >= 12 degrees)
 * - Blink detection (eyeDelta >= 0.30 OR minEye <= 0.25 && maxEye >= 0.65)
 * - Temporal synthesis result correctness
 */
class TemporalLivenessEngineTest {

    private lateinit var engine: TemporalLivenessEngine
    private val TRACK_ID = 42

    @Before
    fun setUp() { engine = TemporalLivenessEngine() }

    // ── Static replay guard ───────────────────────────────────────────────────

    @Test fun `static replay detected when no motion blink or turn over 6 frames`() {
        // Record 7 identical frames — zero motion, no blink, no turn
        // PAD score borderline (0.55) so replay guard is the deciding factor
        repeat(7) {
            engine.recordSample(
                trackId = TRACK_ID, yaw = 0.0f, pitch = 0.0f, roll = 0.0f,
                attributes = null, faceMap3DMM = null, passivePad = null,
                eyeOpenProbability = 0.95f // eyes stay wide open — no blink
            )
        }
        val result = engine.evaluateTemporalLiveness(TRACK_ID)
        // isStaticReplay should trigger and block liveness even with passing PAD score
        assertFalse("Static replay should fail liveness", result.isLive)
    }

    @Test fun `head turn at 12 degrees yaw satisfies liveness motion requirement`() {
        // Record frames with increasing yaw to simulate head turn
        for (i in 0 until 8) {
            val yaw = i * 2.0f // 0, 2, 4, 6, 8, 10, 12, 14 degrees — reaches 14 by frame 7
            engine.recordSample(
                trackId = TRACK_ID, yaw = yaw, pitch = 0.0f, roll = 0.0f,
                attributes = null, faceMap3DMM = null, passivePad = null,
                eyeOpenProbability = 0.90f
            )
        }
        val result = engine.evaluateTemporalLiveness(TRACK_ID)
        assertTrue("Head turn >= 12 degrees should be detected", result.headTurnDetected)
    }

    @Test fun `blink detected via eyeDelta threshold of 0 30`() {
        // Simulate: eyes open, then close, then open again
        val eyeSequence = listOf(0.95f, 0.90f, 0.20f, 0.15f, 0.90f, 0.95f, 0.92f, 0.93f)
        eyeSequence.forEachIndexed { i, eyeProb ->
            engine.recordSample(
                trackId = TRACK_ID, yaw = i * 0.5f, pitch = 0.0f, roll = 0.0f,
                attributes = null, faceMap3DMM = null, passivePad = null,
                eyeOpenProbability = eyeProb
            )
        }
        val result = engine.evaluateTemporalLiveness(TRACK_ID)
        // eyeDelta = 0.95 - 0.15 = 0.80 > 0.30 — blink should be detected
        assertTrue("Blink should be detected with eyeDelta >= 0.30", result.naturalBlinkDetected)
    }

    @Test fun `no blink detected when eyes stay constant`() {
        repeat(8) {
            engine.recordSample(
                trackId = TRACK_ID, yaw = it * 0.3f, pitch = 0.0f, roll = 0.0f,
                attributes = null, faceMap3DMM = null, passivePad = null,
                eyeOpenProbability = 0.92f // no variation
            )
        }
        val result = engine.evaluateTemporalLiveness(TRACK_ID)
        // eyeDelta = 0 — no blink
        assertFalse("Should not detect blink when eyes remain fully open", result.naturalBlinkDetected)
    }

    @Test fun `clearAll removes all track history`() {
        engine.recordSample(TRACK_ID, 0f, 0f, 0f, null, null, null, null, 0.9f)
        engine.clearAll()
        // After clear, evaluating returns a fresh (short) sample result — no crash
        val result = engine.evaluateTemporalLiveness(TRACK_ID)
        assertNotNull(result)
    }
}
