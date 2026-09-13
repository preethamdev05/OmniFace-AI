package com.omniface.ai.ml

import androidx.compose.ui.geometry.Rect
import com.omniface.ai.hardware.ThermalState
import com.omniface.ai.ml.concurrency.BoundedGroupInferenceScheduler
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.tracking.FaceTracker
import com.omniface.ai.ml.tracking.IdentityClassification
import com.omniface.ai.ml.tracking.TrackLifecycleState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GroupRecognitionHardeningTest {

    private lateinit var tracker: FaceTracker
    private lateinit var scheduler: BoundedGroupInferenceScheduler

    @Before
    fun setUp() {
        tracker = FaceTracker()
        scheduler = BoundedGroupInferenceScheduler(initialSlots = 2)
    }

    @Test
    fun testTrackLifecycle_activeToLostToPurged() {
        val rect = Rect(100f, 100f, 200f, 200f)
        val state = tracker.getOrCreateTrackState(mlKitTrackId = 501, rawRect = rect)

        assertEquals(501, state.trackId)
        assertEquals(TrackLifecycleState.ACTIVE, state.lifecycleState)
        assertEquals(0, state.lostFrameCount)

        // Frame 1 missing
        var purged = tracker.onFrameTracksUpdated(emptySet())
        assertTrue(purged.isEmpty())
        assertEquals(TrackLifecycleState.LOST, state.lifecycleState)
        assertEquals(1, state.lostFrameCount)

        // Frames 2, 3, 4 missing
        for (f in 2..4) {
            purged = tracker.onFrameTracksUpdated(emptySet())
            assertTrue(purged.isEmpty())
            assertEquals(TrackLifecycleState.LOST, state.lifecycleState)
            assertEquals(f, state.lostFrameCount)
        }

        // Frame 5 missing: reaches MAX_LOST_FRAMES (5) -> PURGED
        purged = tracker.onFrameTracksUpdated(emptySet())
        assertEquals(listOf(501), purged)
        assertEquals(TrackLifecycleState.PURGED, state.lifecycleState)
        assertEquals(0, tracker.activeTrackCount)
    }

    @Test
    fun testTrackLifecycle_reacquiredBeforePurge() {
        val rect = Rect(100f, 100f, 200f, 200f)
        val state = tracker.getOrCreateTrackState(mlKitTrackId = 502, rawRect = rect)

        // Miss 3 frames
        for (f in 1..3) {
            tracker.onFrameTracksUpdated(emptySet())
            assertEquals(TrackLifecycleState.LOST, state.lifecycleState)
            assertEquals(f, state.lostFrameCount)
        }

        // Frame 4 re-detects track 502
        val purged = tracker.onFrameTracksUpdated(setOf(502))
        assertTrue(purged.isEmpty())
        assertEquals(TrackLifecycleState.ACTIVE, state.lifecycleState)
        assertEquals(0, state.lostFrameCount)
        assertEquals(1, tracker.activeTrackCount)
    }

    @Test
    fun testExplicitTrackRemoval() {
        val rect = Rect(150f, 150f, 250f, 250f)
        val state = tracker.getOrCreateTrackState(mlKitTrackId = 503, rawRect = rect)
        assertEquals(1, tracker.activeTrackCount)

        val removed = tracker.removeTrack(503)
        assertTrue(removed)
        assertEquals(TrackLifecycleState.PURGED, state.lifecycleState)
        assertEquals(0, tracker.activeTrackCount)

        val removedAgain = tracker.removeTrack(503)
        assertFalse(removedAgain)
    }

    @Test
    fun testTrackSwapDefense_resetsAllClassificationAndHistory() {
        val rect = Rect(100f, 100f, 200f, 200f)
        val state = tracker.getOrCreateTrackState(mlKitTrackId = 504, rawRect = rect)

        // Prime track with Alice identity and locked state
        val aliceEmbedding = FloatArray(512) { 0.1f }
        state.pushEmbedding(aliceEmbedding, 0.95f)

        val aliceDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "CS-ALICE",
            matchedStudentName = "Alice Smith",
            matchConfidence = 95f,
            matchSimilarity = 0.88f,
            decisionMargin = 0.25f,
            qualityScore = 96f,
            livenessScore = 0.99f,
            title = "ALICE SMITH",
            subtitle = "CS-ALICE",
            technicalExplanation = "Passed all gates"
        )
        tracker.stabilizeDecision(504, aliceDecision)

        assertTrue(state.isClassificationLocked)
        assertEquals("CS-ALICE", state.studentRoll)
        assertEquals("Alice Smith", state.studentName)
        assertEquals(IdentityClassification.KNOWN, state.classification)
        assertNotNull(state.lastDecision)

        // Push drastically different embedding (orthogonal unit vector, sim ~ 0.04 << 0.60f threshold)
        val intruderEmbedding = FloatArray(512) { if (it == 0) 1.0f else 0.0f }
        state.pushEmbedding(intruderEmbedding, 0.90f)

        // Verify that track-swap defense purged ALL state
        assertFalse("Classification lock must be broken on track swap", state.isClassificationLocked)
        assertEquals("Student roll must be wiped on track swap", "", state.studentRoll)
        assertEquals("Student name must be wiped on track swap", "", state.studentName)
        assertEquals(0, state.consecutiveKnownHits)
        assertEquals(0, state.consecutiveUnknownHits)
        assertEquals(0, state.consecutiveSpoofHits)
        assertEquals(IdentityClassification.UNCONFIRMED, state.classification)
        assertNull("Last decision must be cleared on track swap", state.lastDecision)
    }

    @Test
    fun testScheduler_cancellationRegistry() {
        assertFalse(scheduler.isTrackCancelled(601))

        scheduler.cancelTrack(601)
        assertTrue(scheduler.isTrackCancelled(601))

        scheduler.cancelTrack(602)
        assertTrue(scheduler.isTrackCancelled(601))
        assertTrue(scheduler.isTrackCancelled(602))

        scheduler.clearCancelledTracks()
        assertFalse(scheduler.isTrackCancelled(601))
        assertFalse(scheduler.isTrackCancelled(602))
    }

    @Test
    fun testScheduler_processBatchCancellable_skipsCancelledItemsWithoutConsumingPermits() = runBlocking {
        val concurrentCount = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)

        // 6 items; items 2 and 4 are cancelled
        val items = (1..6).toList()
        val results = scheduler.processBatchCancellable(
            items = items,
            isItemCancelled = { id -> id == 2 || id == 4 }
        ) { id ->
            val c = concurrentCount.incrementAndGet()
            maxObservedConcurrency.updateAndGet { curr -> maxOf(curr, c) }
            delay(30)
            concurrentCount.decrementAndGet()
            id * 10
        }

        assertEquals(6, results.size)
        assertEquals(10, results[0])
        assertNull(results[1]) // item 2 cancelled
        assertEquals(30, results[2])
        assertNull(results[3]) // item 4 cancelled
        assertEquals(50, results[4])
        assertEquals(60, results[5])

        assertTrue("Concurrency ceiling (2) must be respected", maxObservedConcurrency.get() <= 2)
        assertEquals("Only non-cancelled items (4) should count towards total executed", 4, scheduler.totalExecuted)
        assertEquals(0, scheduler.activeTasks)
    }

    @Test
    fun testLargeGroupWorkload100Faces_thermalThrottling() = runBlocking {
        // Under CRITICAL thermal state, capacity throttles down to 1
        scheduler.adaptToThermalState(ThermalState.CRITICAL)
        assertEquals(1, scheduler.capacity)

        val concurrentCount = AtomicInteger(0)
        val maxObserved = AtomicInteger(0)

        val faces100 = (1..100).toList()
        val results = scheduler.processBatchCancellable(
            items = faces100,
            isItemCancelled = { id -> id > 95 } // cancel last 5
        ) { id ->
            val c = concurrentCount.incrementAndGet()
            maxObserved.updateAndGet { curr -> maxOf(curr, c) }
            concurrentCount.decrementAndGet()
            "face_result_$id"
        }

        assertEquals(100, results.size)
        for (i in 1..95) {
            assertEquals("face_result_$i", results[i - 1])
        }
        for (i in 96..100) {
            assertNull(results[i - 1])
        }

        assertEquals("In CRITICAL state, max concurrency must strictly equal 1", 1, maxObserved.get())
        assertEquals(95, scheduler.totalExecuted)
        assertEquals(0, scheduler.activeTasks)

        // Restore to NOMINAL
        scheduler.adaptToThermalState(ThermalState.NOMINAL)
        assertEquals(2, scheduler.capacity)
    }
}
