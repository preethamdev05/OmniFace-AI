package com.omniface.ai.tier1

import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.security.ZkpPrivacyManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 1: Feature 6 - Cryptographic Security & Zero-Knowledge Proofs
 */
class Tier1AesCryptoTest {

    @Test
    fun testSha256DeterministicOutput() {
        val emptyHash = AndroidSecurityUtils.computeSha256("")
        assertEquals(
            "SHA-256 of empty string must equal standard hash",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            emptyHash.lowercase()
        )

        val helloHash = AndroidSecurityUtils.computeSha256("OmniFace-AI-2026")
        assertEquals(64, helloHash.length)
        assertTrue(helloHash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testAttendanceLeafHashPreimage() {
        val leaf1 = AndroidSecurityUtils.computeAttendanceLeafHash("rec_1", "CS101", 1720000000000L, 98.5f)
        val leaf2 = AndroidSecurityUtils.computeAttendanceLeafHash("rec_1", "CS101", 1720000000000L, 98.5f)
        val leaf3 = AndroidSecurityUtils.computeAttendanceLeafHash("rec_2", "CS101", 1720000000000L, 98.5f)

        assertEquals("Identical record metadata must yield identical leaf hash", leaf1, leaf2)
        assertNotEquals("Altered record ID must yield different leaf hash", leaf1, leaf3)
    }

    @Test
    fun testMerkleRootSingleLeaf() {
        val leaf = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
        val root = AndroidSecurityUtils.computeMerkleRoot(listOf(leaf))
        assertEquals("Single leaf Merkle root is the leaf itself", leaf, root)
    }

    @Test
    fun testMerkleRootMultipleLeaves() {
        val leafA = AndroidSecurityUtils.computeSha256("leafA")
        val leafB = AndroidSecurityUtils.computeSha256("leafB")
        val expectedRoot = AndroidSecurityUtils.computeSha256(leafA + leafB)

        val actualRoot = AndroidSecurityUtils.computeMerkleRoot(listOf(leafA, leafB))
        assertEquals("2-leaf Merkle root must equal SHA256(leafA + leafB)", expectedRoot, actualRoot)
    }

    @Test
    fun testZkpCommitmentAndVerification() {
        val sampleEmbeddingCsv = "0.012345,-0.054321,0.987654,0.112233"
        val (commitment, salt) = ZkpPrivacyManager.generateZkpCommitment(sampleEmbeddingCsv)

        assertNotNull(commitment)
        assertNotNull(salt)
        assertEquals(64, commitment.length)
        assertEquals(64, salt.length)

        // Valid verification
        val isVerified = ZkpPrivacyManager.verifyZkpCommitment(sampleEmbeddingCsv, commitment, salt)
        assertTrue("Valid ZKP commitment must successfully verify", isVerified)

        // Tampered embedding
        val tamperedCsv = "0.012345,-0.054321,0.987654,0.999999"
        val isTamperedVerified = ZkpPrivacyManager.verifyZkpCommitment(tamperedCsv, commitment, salt)
        assertFalse("Tampered embedding must fail ZKP verification", isTamperedVerified)
    }
}
