package com.omniface.ai.ml.concurrency

import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * ⚡ Sovereign Bounded Group Inference Scheduler
 *
 * Enforces strict bounded concurrency (1–3 slots) for multi-face biometric evaluation.
 *
 * Critical Architecture Guarantee:
 * LiteRT neural Flatbuffers (INT8/FP16/FP32 MobileFaceNet, Qualcomm CavaFace) operate
 * strictly on static batch-1 dimensions [1, 112, 112, 3]. Dynamic runtime tensor resizing
 * to [B, 112, 112, 3] is strictly prohibited as it triggers OpenCL shader recompilations (300-850ms)
 * on GPUs and causes UnsupportedOperationException on Hexagon NPU / NNAPI delegates.
 *
 * This scheduler processes multiple simultaneous faces concurrently up to a safe
 * hardware-adapted ceiling (1–3 permits) via a coroutine semaphore, preventing:
 * 1. Memory bandwidth thrashing
 * 2. Thermal runaway on mid-range/budget chipsets
 * 3. JNI thread pool exhaustion
 */
class BoundedGroupInferenceScheduler(
    initialSlots: Int = DEFAULT_CONCURRENCY_SLOTS
) {
    companion object {
        const val MIN_CONCURRENCY_SLOTS = 1
        const val MAX_CONCURRENCY_SLOTS = 3
        const val DEFAULT_CONCURRENCY_SLOTS = 2

        @Volatile
        private var defaultInstance: BoundedGroupInferenceScheduler? = null

        fun getDefault(): BoundedGroupInferenceScheduler {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: BoundedGroupInferenceScheduler().also { defaultInstance = it }
            }
        }
    }

    private var currentCapacity: Int = initialSlots.coerceIn(MIN_CONCURRENCY_SLOTS, MAX_CONCURRENCY_SLOTS)
    private var semaphore = Semaphore(permits = currentCapacity)

    private val activeTasksCounter = AtomicInteger(0)
    private val totalExecutedCounter = AtomicLong(0)
    private val totalLatencyAccumulator = AtomicLong(0)

    val activeTasks: Int get() = activeTasksCounter.get()
    val totalExecuted: Long get() = totalExecutedCounter.get()
    val capacity: Int get() = currentCapacity
    val averageLatencyMs: Double
        get() {
            val total = totalExecutedCounter.get()
            return if (total > 0) totalLatencyAccumulator.get().toDouble() / total else 0.0
        }

    /**
     * Dynamically adapts the concurrency ceiling based on real-time thermal telemetry.
     */
    @Synchronized
    fun adaptToThermalState(thermalState: ThermalState) {
        val targetSlots = when (thermalState) {
            ThermalState.CRITICAL -> 1 // Strictly sequential to mitigate thermal emergency
            ThermalState.WARM -> 1     // Throttled concurrency
            ThermalState.NOMINAL -> DEFAULT_CONCURRENCY_SLOTS
        }
        if (targetSlots != currentCapacity) {
            currentCapacity = targetSlots
            semaphore = Semaphore(permits = targetSlots)
        }
    }

    /**
     * Executes a single inference unit of work under the bounded semaphore.
     */
    suspend fun <T> execute(action: suspend () -> T): T {
        return semaphore.withPermit {
            activeTasksCounter.incrementAndGet()
            val t0 = System.currentTimeMillis()
            try {
                action()
            } finally {
                val elapsed = System.currentTimeMillis() - t0
                totalLatencyAccumulator.addAndGet(elapsed)
                totalExecutedCounter.incrementAndGet()
                activeTasksCounter.decrementAndGet()
            }
        }
    }

    private val cancelledTracks = ConcurrentHashMap.newKeySet<Int>()

    /**
     * Registers a track ID as cancelled (e.g. lost from field of view or explicitly terminated).
     */
    fun cancelTrack(trackId: Int) {
        cancelledTracks.add(trackId)
    }

    /**
     * Checks if a track ID has been marked cancelled.
     */
    fun isTrackCancelled(trackId: Int): Boolean = cancelledTracks.contains(trackId)

    /**
     * Clears all cancelled track registrations.
     */
    fun clearCancelledTracks() {
        cancelledTracks.clear()
    }

    /**
     * Concurrently evaluates a list of items (e.g. detected faces) bounded by the slot ceiling.
     * Preserves item ordering in the returned output.
     */
    suspend fun <T, R> processBatch(
        items: List<T>,
        transform: suspend (T) -> R
    ): List<R> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList()
        if (items.size == 1) {
            return@coroutineScope listOf(execute { transform(items[0]) })
        }

        val deferredResults = items.map { item ->
            async {
                execute { transform(item) }
            }
        }
        deferredResults.map { it.await() }
    }

    /**
     * Concurrently evaluates a list of candidate items with lost-track cancellation support.
     * Items where [isItemCancelled] is true or where the coroutine context is inactive will
     * bypass semaphore acquisition, returning null immediately without consuming inference permits.
     */
    suspend fun <T, R> processBatchCancellable(
        items: List<T>,
        isItemCancelled: (T) -> Boolean = { false },
        transform: suspend (T) -> R
    ): List<R?> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList()
        if (items.size == 1) {
            val item = items[0]
            if (isItemCancelled(item) || !isActive) return@coroutineScope listOf(null)
            return@coroutineScope listOf(execute { transform(item) })
        }

        val deferredResults = items.map { item ->
            async {
                if (isItemCancelled(item) || !isActive) {
                    return@async null
                }
                semaphore.withPermit {
                    if (isItemCancelled(item) || !isActive) {
                        return@withPermit null
                    }
                    activeTasksCounter.incrementAndGet()
                    val t0 = System.currentTimeMillis()
                    try {
                        transform(item)
                    } finally {
                        val elapsed = System.currentTimeMillis() - t0
                        totalLatencyAccumulator.addAndGet(elapsed)
                        totalExecutedCounter.incrementAndGet()
                        activeTasksCounter.decrementAndGet()
                    }
                }
            }
        }
        deferredResults.map { it.await() }
    }
}
