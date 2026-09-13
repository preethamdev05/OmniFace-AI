package com.omniface.ai.telemetry

import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.verification.domain.HardwareBackend
import com.omniface.ai.ml.verification.domain.HardwareTelemetry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * High-performance, zero-allocation moving window FPS counter for camera viewfinder & neural pipelines.
 */
class FpsCounter(private val windowSize: Int = 30) {
    private val frameTimestamps = LongArray(windowSize)
    private var head = 0
    private var count = 0
    private val lock = ReentrantLock()

    @Volatile
    var currentFps: Float = 0.0f
        private set

    fun recordFrame(timestampNanos: Long = System.nanoTime()): Float = lock.withLock {
        frameTimestamps[head] = timestampNanos
        head = (head + 1) % windowSize
        if (count < windowSize) count++

        if (count < 2) {
            currentFps = 0.0f
            return 0.0f
        }

        // Oldest timestamp in current window
        val oldestIndex = if (count < windowSize) 0 else head
        val oldestNanos = frameTimestamps[oldestIndex]
        val deltaNanos = timestampNanos - oldestNanos
        if (deltaNanos <= 0) {
            return currentFps
        }

        val elapsedSeconds = deltaNanos / 1_000_000_000.0f
        currentFps = (count - 1) / elapsedSeconds
        currentFps
    }

    fun reset() = lock.withLock {
        head = 0
        count = 0
        currentFps = 0.0f
    }
}

/**
 * Statistical Latency Tracker computing running min, max, average, and p50/p95/p99 percentiles
 * across a fixed-capacity bounded circular buffer without garbage collection overhead.
 */
class LatencyTracker(private val capacity: Int = 100) {
    private val samples = DoubleArray(capacity)
    private var head = 0
    private var count = 0
    private val lock = ReentrantLock()

    fun recordLatencyMs(latencyMs: Double) = lock.withLock {
        samples[head] = latencyMs
        head = (head + 1) % capacity
        if (count < capacity) count++
    }

    data class LatencySummary(
        val count: Int,
        val minMs: Double,
        val maxMs: Double,
        val averageMs: Double,
        val p50Ms: Double,
        val p95Ms: Double,
        val p99Ms: Double
    )

    fun getSummary(): LatencySummary = lock.withLock {
        if (count == 0) {
            return LatencySummary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        }
        val currentSamples = DoubleArray(count)
        for (i in 0 until count) {
            currentSamples[i] = samples[i]
        }
        currentSamples.sort()

        val minVal = currentSamples[0]
        val maxVal = currentSamples[count - 1]
        val avgVal = currentSamples.sum() / count

        fun percentile(p: Double): Double {
            val idx = ((count - 1) * p).roundToInt().coerceIn(0, count - 1)
            return currentSamples[idx]
        }

        LatencySummary(
            count = count,
            minMs = minVal,
            maxMs = maxVal,
            averageMs = avgVal,
            p50Ms = percentile(0.50),
            p95Ms = percentile(0.95),
            p99Ms = percentile(0.99)
        )
    }

    fun reset() = lock.withLock {
        head = 0
        count = 0
    }
}

/**
 * Composite Telemetry Snapshot aggregating UI FPS, neural execution latency percentiles,
 * hardware backend acceleration tier, and thermal envelope state.
 */
data class CompositeTelemetrySnapshot(
    val fps: Float = 0.0f,
    val hardwareTelemetry: HardwareTelemetry = HardwareTelemetry(),
    val latencySummary: LatencyTracker.LatencySummary = LatencyTracker.LatencySummary(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0),
    val thermalState: ThermalState = ThermalState.NOMINAL,
    val isThrottled: Boolean = false
)

/**
 * Thread-safe Telemetry Aggregator providing battery-conscious 1Hz / on-demand telemetry streams.
 */
class RealTimeMetricsTracker(
    val fpsCounter: FpsCounter = FpsCounter(30),
    val latencyTracker: LatencyTracker = LatencyTracker(100)
) {
    private val _snapshot = MutableStateFlow(CompositeTelemetrySnapshot())
    val snapshot: StateFlow<CompositeTelemetrySnapshot> = _snapshot.asStateFlow()

    fun updateHardwareTelemetry(telemetry: HardwareTelemetry) {
        _snapshot.value = _snapshot.value.copy(
            hardwareTelemetry = telemetry,
            thermalState = telemetry.thermalState,
            isThrottled = telemetry.thermalState != ThermalState.NOMINAL
        )
    }

    fun recordPipelineExecution(frameNanos: Long, latencyMs: Double) {
        val currentFps = fpsCounter.recordFrame(frameNanos)
        latencyTracker.recordLatencyMs(latencyMs)
        val summary = latencyTracker.getSummary()

        _snapshot.value = _snapshot.value.copy(
            fps = currentFps,
            latencySummary = summary
        )
    }

    fun reset() {
        fpsCounter.reset()
        latencyTracker.reset()
        _snapshot.value = CompositeTelemetrySnapshot()
    }
}
