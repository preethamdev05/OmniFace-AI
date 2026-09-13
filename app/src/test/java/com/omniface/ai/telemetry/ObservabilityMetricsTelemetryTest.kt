package com.omniface.ai.telemetry

import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.verification.domain.HardwareBackend
import com.omniface.ai.ml.verification.domain.HardwareTelemetry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Phase 19: Observability, Metrics & Telemetry Test Suite.
 *
 * Verifies:
 * 1. FPS counter calculation, moving average convergence, and pause handling.
 * 2. Latency percentiles (min, max, avg, p50, p95, p99) accuracy on bounded buffers.
 * 3. HardwareTelemetry and CompositeTelemetrySnapshot state aggregation.
 * 4. ThermalGovernor state transitions, reactive StateFlow updates, and throttle flags.
 * 5. Bounded memory constraints (zero heap leakage over 10,000+ continuous samples).
 * 6. Thread-safe concurrent telemetry emission across multi-threaded pipelines.
 */
class ObservabilityMetricsTelemetryTest {

    @Before
    fun setUp() {
        ThermalGovernor.setSimulationOverride(null)
        ThermalGovernor.setAutoScalingEnabled(true)
    }

    @Test
    fun testFpsCounterConvergence() {
        val counter = FpsCounter(windowSize = 30)
        assertEquals(0.0f, counter.currentFps, 0.0f)

        // Simulate 60 frames arriving at 30 FPS (33,333,333 nanoseconds per frame = 33.33ms)
        val frameIntervalNanos = 33_333_333L
        var currentNanos = 1_000_000_000L

        for (i in 1..60) {
            currentNanos += frameIntervalNanos
            counter.recordFrame(currentNanos)
        }

        // At 33.33ms per frame, FPS should converge to ~30.0 FPS
        assertTrue("FPS must converge near 30 FPS (was ${counter.currentFps})", counter.currentFps in 29.0f..31.0f)
    }

    @Test
    fun testFpsCounterFluctuatingPacing() {
        val counter = FpsCounter(windowSize = 10)
        var currentNanos = 1_000_000_000L

        // Fast 60 FPS burst (16.66ms per frame)
        for (i in 1..20) {
            currentNanos += 16_666_667L
            counter.recordFrame(currentNanos)
        }
        assertTrue("FPS should be around 60 FPS (was ${counter.currentFps})", counter.currentFps in 58.0f..62.0f)

        // Sudden drop / pause (200ms gap)
        currentNanos += 200_000_000L
        val droppedFps = counter.recordFrame(currentNanos)
        assertTrue("FPS must drop following a pause", droppedFps < 50.0f)
        assertTrue("FPS must remain positive", droppedFps > 0.0f)

        counter.reset()
        assertEquals(0.0f, counter.currentFps, 0.0f)
    }

    @Test
    fun testLatencyTrackerPercentileAccuracy() {
        val tracker = LatencyTracker(capacity = 100)

        // Record exactly 100 values from 1.0 to 100.0 ms
        for (i in 1..100) {
            tracker.recordLatencyMs(i.toDouble())
        }

        val summary = tracker.getSummary()
        assertEquals(100, summary.count)
        assertEquals(1.0, summary.minMs, 1e-4)
        assertEquals(100.0, summary.maxMs, 1e-4)
        assertEquals(50.5, summary.averageMs, 1e-4)
        assertEquals(50.0, summary.p50Ms, 1.0)
        assertEquals(95.0, summary.p95Ms, 1.0)
        assertEquals(99.0, summary.p99Ms, 1.0)
    }

    @Test
    fun testLatencyTrackerEmptyAndSingleSample() {
        val tracker = LatencyTracker(capacity = 50)

        val emptySummary = tracker.getSummary()
        assertEquals(0, emptySummary.count)
        assertEquals(0.0, emptySummary.averageMs, 0.0)
        assertEquals(0.0, emptySummary.p95Ms, 0.0)

        tracker.recordLatencyMs(7.5)
        val singleSummary = tracker.getSummary()
        assertEquals(1, singleSummary.count)
        assertEquals(7.5, singleSummary.minMs, 1e-4)
        assertEquals(7.5, singleSummary.maxMs, 1e-4)
        assertEquals(7.5, singleSummary.averageMs, 1e-4)
        assertEquals(7.5, singleSummary.p50Ms, 1e-4)
        assertEquals(7.5, singleSummary.p95Ms, 1e-4)
        assertEquals(7.5, singleSummary.p99Ms, 1e-4)
    }

    @Test
    fun testRealTimeMetricsTrackerSnapshotAggregation() {
        val tracker = RealTimeMetricsTracker()
        val initialSnapshot = tracker.snapshot.value
        assertEquals(ThermalState.NOMINAL, initialSnapshot.thermalState)
        assertFalse(initialSnapshot.isThrottled)

        // Update hardware telemetry to GPU WARM state
        val gpuTelemetry = HardwareTelemetry(
            backend = HardwareBackend.GPU_DELEGATE,
            resolvedBackendLabel = "Mobile GPU Delegate (FP16)",
            thermalState = ThermalState.WARM,
            deviceTemperature = 46.5f,
            latencyMs = 9L
        )
        tracker.updateHardwareTelemetry(gpuTelemetry)

        val updatedSnapshot = tracker.snapshot.value
        assertEquals(HardwareBackend.GPU_DELEGATE, updatedSnapshot.hardwareTelemetry.backend)
        assertEquals(ThermalState.WARM, updatedSnapshot.thermalState)
        assertTrue("WARM thermal state must trigger isThrottled = true", updatedSnapshot.isThrottled)

        // Record a pipeline frame
        tracker.recordPipelineExecution(frameNanos = 1_000_000_000L, latencyMs = 8.5)
        tracker.recordPipelineExecution(frameNanos = 1_033_333_333L, latencyMs = 9.2)

        val activeSnapshot = tracker.snapshot.value
        assertEquals(2, activeSnapshot.latencySummary.count)
        assertTrue(activeSnapshot.fps > 0.0f)
    }

    @Test
    fun testThermalGovernorStateSubscription() {
        assertEquals(ThermalState.NOMINAL, ThermalGovernor.thermalState.value)
        assertEquals(1.0f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)
        assertEquals(1L, ThermalGovernor.thermalState.value.frameSkipMod)
        assertEquals(30, ThermalGovernor.thermalState.value.maxFps)

        // Simulate transition to WARM
        ThermalGovernor.setSimulationOverride(ThermalState.WARM)
        assertEquals(ThermalState.WARM, ThermalGovernor.thermalState.value)
        assertEquals(0.75f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)
        assertEquals(2L, ThermalGovernor.thermalState.value.frameSkipMod)
        assertEquals(20, ThermalGovernor.thermalState.value.maxFps)

        // Simulate transition to CRITICAL
        ThermalGovernor.setSimulationOverride(ThermalState.CRITICAL)
        assertEquals(ThermalState.CRITICAL, ThermalGovernor.thermalState.value)
        assertEquals(0.50f, ThermalGovernor.thermalState.value.downscaleFactor, 1e-4f)
        assertEquals(3L, ThermalGovernor.thermalState.value.frameSkipMod)
        assertEquals(10, ThermalGovernor.thermalState.value.maxFps)

        // Reset
        ThermalGovernor.setSimulationOverride(null)
        assertEquals(ThermalState.NOMINAL, ThermalGovernor.thermalState.value)
    }

    @Test
    fun testBoundedCircularBuffersZeroMemoryLeak() {
        val fps = FpsCounter(30)
        val lat = LatencyTracker(100)

        var t = 1_000_000_000L
        for (i in 1..10_000) {
            t += 33_333_333L
            fps.recordFrame(t)
            lat.recordLatencyMs((i % 20).toDouble() + 1.0)
        }

        // Bounded capacity assertion
        val summary = lat.getSummary()
        assertEquals("Capacity must stay fixed at 100 without memory expansion", 100, summary.count)
        assertTrue(summary.minMs >= 1.0)
        assertTrue(summary.maxMs <= 20.0)
        assertTrue(fps.currentFps in 29.0f..31.0f)
    }

    @Test
    fun testConcurrentTelemetryUpdatesUnderLoad() {
        val tracker = RealTimeMetricsTracker(FpsCounter(30), LatencyTracker(100))
        val threadCount = 16
        val iterations = 100
        val executor = Executors.newFixedThreadPool(threadCount)

        val tasks = (0 until threadCount).map { threadIdx ->
            Callable {
                var nanos = 1_000_000_000L + (threadIdx * 1000L)
                for (iter in 0 until iterations) {
                    nanos += 33_333_333L
                    tracker.recordPipelineExecution(nanos, 4.0 + (iter % 5))
                }
                true
            }
        }

        val futures: List<Future<Boolean>> = executor.invokeAll(tasks)
        for (f in futures) {
            assertTrue("Concurrent telemetry task must complete without error", f.get())
        }

        executor.shutdown()
        val summary = tracker.snapshot.value.latencySummary
        assertEquals(100, summary.count)
    }
}
