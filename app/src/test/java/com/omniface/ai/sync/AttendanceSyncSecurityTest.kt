package com.omniface.ai.sync

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.security.AndroidSecurityUtils
import org.junit.Assert.*
import org.junit.Test

class AttendanceSyncSecurityTest {

    @Test
    fun testSyncPayloadBuildingAndHmacSigning() {
        val deviceId = "OMNIFACE-KIOSK-01"
        val timestamp = 1756123456789L
        val secret = "kiosk_master_hmac_secret_key_2026_xyz"

        val records = listOf(
            AttendanceRecordEntity(
                recordId = "REC-SYNC-01",
                studentRoll = "2024-CSE-001",
                studentName = "Aarav Sharma",
                sessionDate = "2026-08-25",
                timestamp = timestamp,
                confidencePct = 99.2f,
                securityTier = "HIGH",
                sha256Hash = "hash_01",
                isSynced = false
            ),
            AttendanceRecordEntity(
                recordId = "REC-SYNC-02",
                studentRoll = "2024-ECE-042",
                studentName = "Diya Patel",
                sessionDate = "2026-08-25",
                timestamp = timestamp + 1000L,
                confidencePct = 96.8f,
                securityTier = "HIGH",
                sha256Hash = "hash_02",
                isSynced = false
            )
        )

        val payload = AttendanceSyncWorker.buildPayloadString(deviceId, records)
        assertNotNull(payload)
        assertTrue(payload.isNotEmpty())
        assertTrue("Payload must contain device ID", payload.contains("\"device_id\":\"$deviceId\""))
        assertTrue("Payload must contain record 1", payload.contains("\"student_roll\":\"2024-CSE-001\""))
        assertTrue("Payload must contain record 2", payload.contains("\"student_roll\":\"2024-ECE-042\""))

        val signingData = AttendanceSyncWorker.generateSigningData(deviceId, timestamp, payload)
        val signature = AndroidSecurityUtils.computeHmacSha256(secret, signingData)

        assertTrue(
            "HMAC signature must verify against signing data",
            AndroidSecurityUtils.verifyHmacSha256(secret, signingData, signature)
        )
    }

    @Test
    fun testServerResponseValidationSuccess() {
        // Valid JSON responses
        assertTrue(AttendanceSyncWorker.validateSyncResponse("{\"status\": \"SUCCESS\", \"synced_count\": 2}"))
        assertTrue(AttendanceSyncWorker.validateSyncResponse("{\"success\": true, \"records_processed\": 5}"))
        assertTrue(AttendanceSyncWorker.validateSyncResponse("{\"status\": \"OK\"}"))
        assertTrue(AttendanceSyncWorker.validateSyncResponse("")) // Empty 204 OK
        assertTrue(AttendanceSyncWorker.validateSyncResponse("OK"))
    }

    @Test
    fun testServerResponseValidationFailure() {
        // Invalid or error responses
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"status\": \"ERROR\", \"message\": \"Unauthorized HMAC\"}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"status\": \"FAILED\", \"reason\": \"Database down\"}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"status\": \"REJECTED\"}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"success\": false}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("{\"error\": \"Invalid device ID\"}"))
        assertFalse(AttendanceSyncWorker.validateSyncResponse("Internal server error"))
    }
}
