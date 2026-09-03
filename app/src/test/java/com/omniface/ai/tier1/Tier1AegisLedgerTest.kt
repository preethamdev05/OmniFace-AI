package com.omniface.ai.tier1

import com.omniface.ai.security.AndroidSecurityUtils
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 1: Feature 7 - Aegis SHA-256 Blockchain Audit Ledger
 */
class Tier1AegisLedgerTest {

    @Test
    fun testAegisGenesisBlockMerkleRoot() {
        val emptyList = emptyList<String>()
        val genesisRoot = AndroidSecurityUtils.computeMerkleRoot(emptyList)
        val expectedGenesis = AndroidSecurityUtils.computeSha256("OMNIFACE_GENESIS_BLOCK")
        assertEquals("Empty batch must return OMNIFACE_GENESIS_BLOCK hash", expectedGenesis, genesisRoot)
    }

    @Test
    fun testAegisLinearHashChaining() {
        var prevHash = AndroidSecurityUtils.computeSha256("GENESIS")
        val chain = mutableListOf<String>()

        val records = listOf("REC1_ROLL001", "REC2_ROLL002", "REC3_ROLL003")
        for (rec in records) {
            val currentBlock = AndroidSecurityUtils.computeSha256("$prevHash|$rec")
            chain.add(currentBlock)
            prevHash = currentBlock
        }

        assertEquals(3, chain.size)
        // Verify block 1 depends on GENESIS, block 2 depends on block 1, block 3 depends on block 2
        val genesis = AndroidSecurityUtils.computeSha256("GENESIS")
        assertEquals(AndroidSecurityUtils.computeSha256("$genesis|REC1_ROLL001"), chain[0])
        assertEquals(AndroidSecurityUtils.computeSha256("${chain[0]}|REC2_ROLL002"), chain[1])
        assertEquals(AndroidSecurityUtils.computeSha256("${chain[1]}|REC3_ROLL003"), chain[2])
    }

    @Test
    fun testAegisTamperDetection() {
        val genesis = AndroidSecurityUtils.computeSha256("GENESIS")
        val b1 = AndroidSecurityUtils.computeSha256("$genesis|REC1_ROLL001")
        val b2 = AndroidSecurityUtils.computeSha256("$b1|REC2_ROLL002")
        val b3 = AndroidSecurityUtils.computeSha256("$b2|REC3_ROLL003")

        // Attacker alters REC2 to fraudulent record
        val tamperedB2 = AndroidSecurityUtils.computeSha256("$b1|REC2_FRAUDULENT")
        val tamperedB3 = AndroidSecurityUtils.computeSha256("$tamperedB2|REC3_ROLL003")

        assertNotEquals("Altering intermediate block must break downstream hash", b3, tamperedB3)
    }

    @Test
    fun testAegisBatchBlockMinting() {
        val leaves = (1..8).map { i ->
            AndroidSecurityUtils.computeAttendanceLeafHash("rec_$i", "ROLL_$i", 1720000000L + i * 1000, 95f + i)
        }

        val root = AndroidSecurityUtils.computeMerkleRoot(leaves)
        assertNotNull(root)
        assertEquals(64, root.length)
    }

    @Test
    fun testAegisProofVerification() {
        val leaf1 = AndroidSecurityUtils.computeSha256("leaf1")
        val leaf2 = AndroidSecurityUtils.computeSha256("leaf2")
        val leaf3 = AndroidSecurityUtils.computeSha256("leaf3")
        val leaf4 = AndroidSecurityUtils.computeSha256("leaf4")

        val parent1 = AndroidSecurityUtils.computeSha256(leaf1 + leaf2)
        val parent2 = AndroidSecurityUtils.computeSha256(leaf3 + leaf4)
        val expectedRoot = AndroidSecurityUtils.computeSha256(parent1 + parent2)

        val actualRoot = AndroidSecurityUtils.computeMerkleRoot(listOf(leaf1, leaf2, leaf3, leaf4))
        assertEquals("4-leaf Merkle root must match layer-by-layer reduction", expectedRoot, actualRoot)
    }
}
