package com.omniface.ai.data

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.security.AndroidSecurityUtils
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class AegisBlockchainLedgerTest {

    @Test
    fun testGenesisBlockInitialization() {
        val genesis = AndroidSecurityUtils.AEGIS_GENESIS_HASH
        assertEquals(64, genesis.length)
        assertEquals("0000000000000000000000000000000000000000000000000000000000000000", genesis)
    }

    @Test
    fun testAegisBlockChainingSequence() {
        val records = mutableListOf<AttendanceRecordEntity>()
        var previousHash = AndroidSecurityUtils.AEGIS_GENESIS_HASH

        val testData = listOf(
            Triple("2024-CSE-001", "Aarav Sharma", 99.2f),
            Triple("2024-ECE-042", "Diya Patel", 96.8f),
            Triple("2024-MECH-015", "Rohan Verma", 94.5f),
            Triple("2024-IT-088", "Ananya Reddy", 98.1f),
            Triple("2024-AI-007", "Vikram Singh", 97.4f)
        )

        val baseTimestamp = 1756123000000L
        for ((index, data) in testData.withIndex()) {
            val ts = baseTimestamp + (index * 60000L)
            val currentHash = AndroidSecurityUtils.computeAegisBlockHash(
                previousHash = previousHash,
                studentRoll = data.first,
                timestamp = ts,
                confidencePct = data.third
            )

            val record = AttendanceRecordEntity(
                recordId = UUID.randomUUID().toString(),
                studentRoll = data.first,
                studentName = data.second,
                sessionDate = "2026-08-25",
                timestamp = ts,
                confidencePct = data.third,
                securityTier = "HIGH",
                sha256Hash = currentHash,
                isSynced = false
            )
            records.add(record)
            previousHash = currentHash
        }

        assertEquals(5, records.size)
        assertTrue("Chained attendance records must verify integrity", AndroidSecurityUtils.verifyChainIntegrity(records))
    }

    @Test
    fun testTamperDetectionOnHistoricalLedgerBlock() {
        val records = mutableListOf<AttendanceRecordEntity>()
        var previousHash = AndroidSecurityUtils.AEGIS_GENESIS_HASH

        for (i in 1..4) {
            val ts = 1000L * i
            val roll = "ROLL_$i"
            val hash = AndroidSecurityUtils.computeAegisBlockHash(previousHash, roll, ts, 95.0f)
            val record = AttendanceRecordEntity(
                recordId = "REC-$i",
                studentRoll = roll,
                studentName = "Student $i",
                sessionDate = "2026-08-25",
                timestamp = ts,
                confidencePct = 95.0f,
                securityTier = "STANDARD",
                sha256Hash = hash
            )
            records.add(record)
            previousHash = hash
        }

        assertTrue("Original ledger must be valid", AndroidSecurityUtils.verifyChainIntegrity(records))

        // Modify block 2's timestamp (e.g. backdated attendance fraud attempt)
        val tamperedRecords = records.toMutableList()
        tamperedRecords[1] = tamperedRecords[1].copy(timestamp = 9999L)
        assertFalse("Backdating / timestamp fraud must be caught by chain verification", AndroidSecurityUtils.verifyChainIntegrity(tamperedRecords))

        // Delete block 2 (record deletion attempt)
        val deletedBlockRecords = listOf(records[0], records[2], records[3])
        assertFalse("Deleted intermediate block must break chain verification", AndroidSecurityUtils.verifyChainIntegrity(deletedBlockRecords))
    }
}
