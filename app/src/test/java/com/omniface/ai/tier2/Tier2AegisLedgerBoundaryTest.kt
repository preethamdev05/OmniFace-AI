package com.omniface.ai.tier2

import com.omniface.ai.security.AndroidSecurityUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 7: Aegis Blockchain Hash Continuity
 */
class Tier2AegisLedgerBoundaryTest {

    @Test
    fun testAegisChainingWithEmptyIntermediateRecords() {
        val genesis = AndroidSecurityUtils.computeSha256("GENESIS")
        val block1 = AndroidSecurityUtils.computeSha256("$genesis|")
        val block2 = AndroidSecurityUtils.computeSha256("$block1|")

        assertNotNull(block1)
        assertNotNull(block2)
        assertEquals(64, block1.length)
        assertEquals(64, block2.length)
        assertNotEquals(block1, block2)
    }

    @Test
    fun testAegisBatchWithSingleRecord() {
        val leaf = AndroidSecurityUtils.computeSha256("single_record")
        val root = AndroidSecurityUtils.computeMerkleRoot(listOf(leaf))
        assertEquals("Single leaf Merkle root must equal leaf hash", leaf, root)
    }

    @Test
    fun testAegisLongRecordPayload() {
        val longString = "A".repeat(10_000)
        val hash = AndroidSecurityUtils.computeSha256(longString)
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun testAegisChainBreakAtFirstBlock() {
        val b0 = AndroidSecurityUtils.computeSha256("GENESIS_CORRECT")
        val b0Tampered = AndroidSecurityUtils.computeSha256("GENESIS_TAMPERED")

        val b1 = AndroidSecurityUtils.computeSha256("$b0|DATA1")
        val b1Tampered = AndroidSecurityUtils.computeSha256("$b0Tampered|DATA1")

        assertNotEquals(b1, b1Tampered)
    }

    @Test
    fun testAegisZeroConfidenceLeaf() {
        val hash = AndroidSecurityUtils.computeAttendanceLeafHash("rec_zero", "GUEST", 1720000000L, 0.0f)
        assertEquals(64, hash.length)
    }
}
