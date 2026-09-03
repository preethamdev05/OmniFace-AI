package com.omniface.ai.tier2

import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.ZkpPrivacyManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 6: Cryptographic Bounds & ZKP Robustness
 */
class Tier2AesCryptoBoundaryTest {

    @Test
    fun testZkpVerificationBlankInputs() {
        assertFalse(ZkpPrivacyManager.verifyZkpCommitment("", "abc", "def"))
        assertFalse(ZkpPrivacyManager.verifyZkpCommitment("csv", "", "def"))
        assertFalse(ZkpPrivacyManager.verifyZkpCommitment("csv", "abc", ""))
    }

    @Test
    fun testZkpVerificationOddLengthSaltHex() {
        // Hex strings must have an even length (byte pairs)
        val oddSalt = "abcde" // 5 chars
        val result = ZkpPrivacyManager.verifyZkpCommitment("csv_data", "commitment_hex", oddSalt)
        assertFalse("Odd length hex salt must be rejected safely", result)
    }

    @Test
    fun testZkpVerificationNonHexCharacters() {
        val nonHexSalt = "0123456789xyz!"
        val result = ZkpPrivacyManager.verifyZkpCommitment("csv_data", "commitment_hex", nonHexSalt)
        assertFalse("Non-hex salt characters must be rejected without exception", result)
    }

    @Test
    fun testMerkleRootOddNumberOfLeaves() {
        val leaf1 = AndroidSecurityUtils.computeSha256("leaf1")
        val leaf2 = AndroidSecurityUtils.computeSha256("leaf2")
        val leaf3 = AndroidSecurityUtils.computeSha256("leaf3")

        val root = AndroidSecurityUtils.computeMerkleRoot(listOf(leaf1, leaf2, leaf3))
        assertNotNull(root)
        assertEquals(64, root.length)

        // Repeat calculation to verify deterministic output
        val rootRepeat = AndroidSecurityUtils.computeMerkleRoot(listOf(leaf1, leaf2, leaf3))
        assertEquals(root, rootRepeat)
    }

    @Test
    fun testAttendanceLeafHashNullLikeEmptyStrings() {
        val hash = AndroidSecurityUtils.computeAttendanceLeafHash("", "", 0L, 0.0f)
        assertEquals(64, hash.length)
        assertEquals(AndroidSecurityUtils.computeSha256("__0_0.0"), hash)
    }
}
