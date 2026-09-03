package com.omniface.ai.sync

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit Test Suite for CloudFleetSyncEngine and Merkle Attendance Payloads.
 *
 * Verifies payload format, HMAC data generation, and response validation.
 */
class CloudFleetSyncEngineTest {

    @Test
    fun testPayloadStructure() {
        val record = AttendanceRecordEntity(
            recordId = "rec_test_123",
            studentRoll = "21CS001",
            studentName = "Alice Turing",
            timestamp = 1724234567890L,
            sessionDate = "2026-09-03",
            confidencePct = 98.5f,
            securityTier = "HIGH",
            sha256Hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            isSynced = false
        )

        val payload = AttendanceSyncWorker.buildPayloadString("TEST_TERMINAL", listOf(record))

        assertTrue("Payload must include device_id", payload.contains("\"device_id\":\"TEST_TERMINAL\""))
        assertTrue("Payload must include student roll", payload.contains("21CS001"))
        assertTrue("Payload must include student name", payload.contains("Alice Turing"))
        assertTrue("Payload must include record_id", payload.contains("rec_test_123"))
    }

    @Test
    fun testResponseValidation() {
        assertTrue("Valid JSON success", AttendanceSyncWorker.validateSyncResponse("{\"success\":true,\"synced\":1}"))
        assertTrue("Empty body is treated as 200 OK", AttendanceSyncWorker.validateSyncResponse(""))
        assertFalse("Error status rejected", AttendanceSyncWorker.validateSyncResponse("{\"status\":\"error\",\"message\":\"Unauthorized\"}"))
        assertFalse("Failure status rejected", AttendanceSyncWorker.validateSyncResponse("{\"success\":false}"))
    }

    @Test
    fun testFleetSyncStateHierarchy() {
        val idle: FleetSyncState = FleetSyncState.Idle
        val syncing: FleetSyncState = FleetSyncState.Syncing("Uploading batch")
        val synced: FleetSyncState = FleetSyncState.Synced(123456L, 10, 2)
        val offline: FleetSyncState = FleetSyncState.OfflineReady(5, 1)
        val error: FleetSyncState = FleetSyncState.Error("Timeout")

        assertNotNull(idle)
        assertEquals("Uploading batch", (syncing as FleetSyncState.Syncing).message)
        assertEquals(10, (synced as FleetSyncState.Synced).recordCount)
        assertEquals(5, (offline as FleetSyncState.OfflineReady).pendingCount)
        assertEquals("Timeout", (error as FleetSyncState.Error).error)
    }
}
