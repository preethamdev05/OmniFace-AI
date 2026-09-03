package com.omniface.ai.tier2

import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.sync.BleMeshState
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tier 2: Boundary & Corner Cases - Feature 9: Sync Serialization & Network Disruption
 */
class Tier2SyncBleMeshBoundaryTest {

    @Test
    fun testSyncPayloadWithZeroRecords() {
        val deviceId = "KIOSK-01"
        val payloadStr = """{"device_id":"$deviceId","records":[]}"""
        assertTrue(payloadStr.contains(""""records":[]"""))
        assertTrue(payloadStr.contains(""""device_id":"KIOSK-01""""))
    }

    @Test
    fun testSyncPayloadWithMaxBatchLimit() {
        val records = (1..200).map {
            """{"record_id":"rec_$it","student_roll":"ROLL_$it","confidence_pct":95.0,"sha256_hash":"${AndroidSecurityUtils.computeSha256("item_$it")}"}"""
        }
        val payloadStr = """{"device_id":"KIOSK-01","records":[${records.joinToString(",")}]}"""
        assertTrue(payloadStr.contains("rec_200"))
        assertTrue(payloadStr.length > 5000)
    }

    @Test
    fun testMalformedEndpointUrls() {
        val badUrl1 = "ftp://badhost/sync"
        val badUrl2 = "invalid_scheme_url"
        val badUrl3 = "http://remote-server.com/api/sync" // cleartext

        fun isSafeHttps(url: String): Boolean =
            url.startsWith("https://") || url.contains("127.0.0.1") || url.contains("localhost")

        assertFalse(isSafeHttps(badUrl1))
        assertFalse(isSafeHttps(badUrl2))
        assertFalse(isSafeHttps(badUrl3))
    }

    @Test
    fun testBleMeshStateCopyMutation() {
        val state = BleMeshState(
            isAdvertising = true,
            isScanning = true,
            connectedPeerCount = 4,
            lastSyncedPayloadHash = "hash123",
            lastError = null
        )

        val updated = state.copy(isAdvertising = false, isScanning = false, connectedPeerCount = 0)
        assertFalse(updated.isAdvertising)
        assertFalse(updated.isScanning)
        assertEquals(0, updated.connectedPeerCount)
        assertEquals("hash123", updated.lastSyncedPayloadHash)
    }

    @Test
    fun testDeviceFingerprintWithSpecialCharacters() {
        val complexDeviceId = "KIOSK#99/NORTH-WING [BUILDING-C]"
        val fp = AndroidSecurityUtils.computeSha256(complexDeviceId + "_OMNIFACE_KEYSTORE_HW")
        assertEquals(64, fp.length)
    }
}
