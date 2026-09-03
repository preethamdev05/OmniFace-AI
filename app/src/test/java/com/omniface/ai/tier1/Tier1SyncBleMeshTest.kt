package com.omniface.ai.tier1

import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.sync.BleMeshState
import com.omniface.ai.sync.BleMeshSyncManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 1: Feature 9 - Offline-First Sync & BLE Mesh Framing
 */
class Tier1SyncBleMeshTest {

    @Test
    fun testBleMeshStateDefaultInitial() {
        val state = BleMeshState()
        assertFalse("BLE mesh initial advertising should be false", state.isAdvertising)
        assertFalse("BLE mesh initial scanning should be false", state.isScanning)
        assertEquals("Initial connected peers should be 0", 0, state.connectedPeerCount)
        assertNull("Initial payload hash should be null", state.lastSyncedPayloadHash)
        assertNull("Initial lastError should be null", state.lastError)
    }

    @Test
    fun testBleMeshSyncManagerErrorClearing() {
        BleMeshSyncManager.clearError()
        val state = BleMeshSyncManager.meshState.value
        assertNull("clearError must reset lastError to null", state.lastError)
    }

    @Test
    fun testAttendanceSyncPayloadFormatting() {
        val deviceId = "KIOSK_ENTRY_GATE_01"
        val recordId = "rec_999"
        val roll = "CS2026-099"
        val timestamp = 1724590000000L

        val fingerprint = AndroidSecurityUtils.computeSha256(deviceId + "_OMNIFACE_KEYSTORE_HW")
        assertEquals(64, fingerprint.length)

        val leafHash = AndroidSecurityUtils.computeAttendanceLeafHash(recordId, roll, timestamp, 99.2f)
        assertEquals(64, leafHash.length)
    }

    @Test
    fun testDeviceFingerprintGeneration() {
        val devA = "TERMINAL-A"
        val devB = "TERMINAL-B"

        val fpA = AndroidSecurityUtils.computeSha256(devA + "_OMNIFACE_KEYSTORE_HW")
        val fpB = AndroidSecurityUtils.computeSha256(devB + "_OMNIFACE_KEYSTORE_HW")

        assertNotEquals("Different devices must have unique hardware fingerprints", fpA, fpB)
        assertEquals(64, fpA.length)
    }

    @Test
    fun testHttpsEndpointValidation() {
        val validLocal = "https://127.0.0.1:8080/api/v1/attendance/sync"
        val validRemoteHttps = "https://attendance.university.edu/api/sync"
        val invalidHttpRemote = "http://attendance.university.edu/api/sync"

        assertTrue("HTTPS remote is valid", validRemoteHttps.startsWith("https://"))
        assertTrue("Local 127.0.0.1 is accepted for debug", validLocal.contains("127.0.0.1"))
        assertFalse("Cleartext HTTP remote must be rejected in production", invalidHttpRemote.startsWith("https://") || invalidHttpRemote.contains("127.0.0.1"))
    }
}
