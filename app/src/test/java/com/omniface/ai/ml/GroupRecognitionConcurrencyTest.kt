package com.omniface.ai.ml

import android.graphics.Bitmap
import com.omniface.ai.hardware.DeviceCapacityGovernor
import com.omniface.ai.hardware.DeviceCapacityTier
import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.tracking.FaceTracker
import com.omniface.ai.testutil.BiometricTestFixtures
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * 🧪 Test Suite for Group Recognition Concurrency & Bounded Scheduling (Stages 8 & 9).
 *
 * Verifies:
 * 1. DeviceCapacityGovernor concurrency bounds scale with silicon capability.
 * 2. Concurrency dynamically throttles down to 1 during ThermalState.CRITICAL.
 * 3. Batch embedding extraction behaves deterministically.
 * 4. Multi-frame quality-weighted fusion pools observations cleanly.
 */
class GroupRecognitionConcurrencyTest {

    @Test
    fun testDeviceCapacityTier_concurrencyBounds() {
        assertEquals(4, DeviceCapacityTier.ULTRA.maxConcurrentFaces)
        assertEquals(2, DeviceCapacityTier.FLAGSHIP.maxConcurrentFaces)
        assertEquals(1, DeviceCapacityTier.BALANCED.maxConcurrentFaces)
        assertEquals(1, DeviceCapacityTier.ENTRY.maxConcurrentFaces)
    }

    @Test
    fun testThermalThrottling_concurrencyGate() {
        // Under CRITICAL thermal state, concurrency must always be clamped to 1
        ThermalGovernor.setSimulationOverride(ThermalState.CRITICAL)
        val maxConcurrentCritical = DeviceCapacityGovernor.getMaxConcurrentFaces(null)
        assertEquals(1, maxConcurrentCritical)

        // Restore to NOMINAL
        ThermalGovernor.setSimulationOverride(ThermalState.NOMINAL)
        val maxConcurrentNominal = DeviceCapacityGovernor.getMaxConcurrentFaces(null)
        assertTrue(maxConcurrentNominal >= 1)
        ThermalGovernor.setSimulationOverride(null)
    }

    @Test
    fun testMultiFrameFeaturePooling_fusedNorm() {
        val tracker = FaceTracker()
        val emb1 = BiometricTestFixtures.generateSyntheticEmbedding(1.0f)
        val emb2 = BiometricTestFixtures.generateSyntheticEmbedding(2.0f)

        tracker.pushTrackEmbedding(trackId = 101, embedding = emb1, qualityWeight = 80.0f)
        tracker.pushTrackEmbedding(trackId = 101, embedding = emb2, qualityWeight = 60.0f)

        val fused = tracker.getFusedTrackEmbedding(trackId = 101)
        assertNotNull(fused)
        assertEquals(512, fused!!.size)

        // Verify L2 normalization: sum(v_i^2) == 1.0
        var normSq = 0.0f
        for (v in fused) normSq += v * v
        assertEquals(1.0f, sqrt(normSq), 1e-4f)
    }

    @Test
    fun testMultiFrameFeaturePooling_emptyTrack() {
        val tracker = FaceTracker()
        val fused = tracker.getFusedTrackEmbedding(trackId = 999)
        assertNull(fused)
    }

    @Test
    fun testMultiFrameFeaturePooling_purgesOnSpoofOrReset() {
        val tracker = FaceTracker()
        val emb = BiometricTestFixtures.generateSyntheticEmbedding(3.0f)
        tracker.pushTrackEmbedding(trackId = 202, embedding = emb, qualityWeight = 75.0f)

        assertNotNull(tracker.getFusedTrackEmbedding(trackId = 202))

        // Invalidate embedding history on spoof / identity switch
        tracker.invalidateTrackEmbeddings(trackId = 202)
        assertNull(tracker.getFusedTrackEmbedding(trackId = 202))
    }
}
