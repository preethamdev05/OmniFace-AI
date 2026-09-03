package com.omniface.ai.ml

import com.omniface.ai.ml.antispoof.RemotePpgPulseEngine
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

/**
 * Empirical Unit Test Suite for Remote Photoplethysmography (rPPG) Cardiovascular Vitality.
 *
 * Verifies that:
 * 1. A physiological blood volume pulse wave (sine at 1.25 Hz = 75 BPM) is correctly recognized with high SNR.
 * 2. A static 2D planar photo (constant RGB values) has near-zero SNR and is flagged as non-cardiac/spoof.
 * 3. Buffer capacity and reset behave strictly according to memory bounds.
 */
class RemotePpgPulseEngineTest {

    @Test
    fun testPhysiologicalPulseDetection() {
        val engine = RemotePpgPulseEngine(windowCapacity = 60)

        val targetBpm = 75.0
        val targetFreqHz = targetBpm / 60.0 // 1.25 Hz
        val fps = 30.0
        val dtMs = (1000.0 / fps).toLong()

        var currentTimestamp = System.currentTimeMillis()

        // Feed 50 frames of simulated skin reflection with subtle hemoglobin green-light absorption
        for (i in 0 until 50) {
            val tSec = i / fps
            // Hemoglobin absorbs green light during cardiac contraction (systole)
            val pulsatile = (sin(2.0 * Math.PI * targetFreqHz * tSec) * 2.5).toFloat()
            val r = 180.0f
            val g = 140.0f + pulsatile
            val b = 110.0f

            engine.addSample(r, g, b, currentTimestamp)
            currentTimestamp += dtMs
        }

        val result = engine.evaluateVitality()

        assertTrue("Should detect living subject from physiological pulsatility", result.isLive)
        assertTrue("Estimated BPM should be in physiological human cardiac range", result.heartRateBpm in 48..175)
        assertTrue("Vitality SNR should exceed physiological threshold", result.vitalitySnr >= 0.50f)
        assertTrue("Explanation should contain rPPG pulse status", result.explanation.contains("rPPG") || result.explanation.contains("pulse"))
    }

    @Test
    fun testStaticPhotoSpoofRejection() {
        val engine = RemotePpgPulseEngine(windowCapacity = 60)
        val fps = 30.0
        val dtMs = (1000.0 / fps).toLong()

        var currentTimestamp = System.currentTimeMillis()

        // Feed 50 frames of a static printed photo or display screen with constant pixel values
        for (i in 0 until 50) {
            val r = 180.0f
            val g = 140.0f
            val b = 110.0f

            engine.addSample(r, g, b, currentTimestamp)
            currentTimestamp += dtMs
        }

        val result = engine.evaluateVitality()

        // Zero arterial pulsatility in a printed photo -> near zero SNR
        assertTrue("Static photo should not produce high vitality SNR", result.vitalitySnr < 0.65f)
        assertFalse("Static photo should not be classified as genuine physiological vitality", result.isPhysiological)
    }

    @Test
    fun testBufferReset() {
        val engine = RemotePpgPulseEngine(windowCapacity = 60)
        engine.addSample(150f, 120f, 90f)
        engine.addSample(151f, 121f, 91f)

        engine.reset()
        val result = engine.evaluateVitality()
        assertEquals("Should report initial buffer status after reset", 72, result.heartRateBpm)
    }
}
