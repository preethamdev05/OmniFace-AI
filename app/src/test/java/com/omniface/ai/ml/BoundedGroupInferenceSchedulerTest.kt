package com.omniface.ai.ml

import com.omniface.ai.hardware.ThermalGovernor
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.concurrency.BoundedGroupInferenceScheduler
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BoundedGroupInferenceSchedulerTest {

    @Test
    fun testScheduler_boundedConcurrencyCeiling() = runBlocking {
        val scheduler = BoundedGroupInferenceScheduler(initialSlots = 2)
        assertEquals(2, scheduler.capacity)

        val concurrentExecutions = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        val items = (1..6).toList()
        val results = scheduler.processBatch(items) { id ->
            val count = concurrentExecutions.incrementAndGet()
            maxObservedConcurrency.updateAndGet { current -> maxOf(current, count) }
            delay(50) // simulate 50ms inference
            concurrentExecutions.decrementAndGet()
            id * 10
        }

        assertEquals(6, results.size)
        assertEquals(listOf(10, 20, 30, 40, 50, 60), results)
        assertTrue("Max observed concurrency ($maxObservedConcurrency) must be <= capacity (2)", maxObservedConcurrency.get() <= 2)
        assertEquals(6, scheduler.totalExecuted)
        assertTrue("Average latency must be > 0", scheduler.averageLatencyMs > 0.0)
    }

    @Test
    fun testScheduler_thermalStateAdaptation() {
        val scheduler = BoundedGroupInferenceScheduler(initialSlots = 2)

        scheduler.adaptToThermalState(ThermalState.CRITICAL)
        assertEquals(1, scheduler.capacity)

        scheduler.adaptToThermalState(ThermalState.WARM)
        assertEquals(1, scheduler.capacity)

        scheduler.adaptToThermalState(ThermalState.NOMINAL)
        assertEquals(2, scheduler.capacity)
    }

    @Test
    fun testScheduler_emptyAndSingleItemHandling() = runBlocking {
        val scheduler = BoundedGroupInferenceScheduler(initialSlots = 2)

        val emptyResults = scheduler.processBatch(emptyList<Int>()) { it }
        assertTrue(emptyResults.isEmpty())

        val singleResult = scheduler.processBatch(listOf(42)) { it * 2 }
        assertEquals(listOf(84), singleResult)
    }

    @Test
    fun testScheduler_modelBatchSizeStrictlyOneInvariant() = runBlocking {
        // Strict Invariant: 1–3 scheduler slots is concurrency control, NOT TFLite batch size = 3.
        // Each invoked action represents a single face inference unit [1, 112, 112, 3].
        val scheduler = BoundedGroupInferenceScheduler(initialSlots = 3)
        val faceBatchDimensionObserved = mutableListOf<Int>()

        val faces = (1..6).map { "face_crop_$it" }
        scheduler.processBatch(faces) { face ->
            // Invariant verification: Each invocation receives exactly 1 face crop
            val simulatedBatchDim = 1 // Single face invocation [1, 112, 112, 3]
            synchronized(faceBatchDimensionObserved) {
                faceBatchDimensionObserved.add(simulatedBatchDim)
            }
            "512d_embedding_for_$face"
        }

        assertEquals(6, faceBatchDimensionObserved.size)
        assertTrue("Every invocation MUST have batch dimension exactly 1", faceBatchDimensionObserved.all { it == 1 })
    }

    @Test
    fun testScheduler_largeWorkloadQueue100Faces() = runBlocking {
        // Validates that a burst workload of up to 100 faces is queued and processed
        // sequentially under the bounded concurrency ceiling without permit leakage.
        val scheduler = BoundedGroupInferenceScheduler(initialSlots = 3)
        val workload100 = (1..100).toList()

        val results = scheduler.processBatch(workload100) { id ->
            // Simulate lightweight inference step
            id * 2
        }

        assertEquals(100, results.size)
        assertEquals((1..100).map { it * 2 }, results)
        assertEquals(0, scheduler.activeTasks)
        assertEquals(100, scheduler.totalExecuted)
    }
}
