package com.omniface.ai.attendance

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.security.AndroidSecurityUtils

/**
 * Sovereign Authority for Aegis Blockchain SHA-256 Block Hashing & Merkle Chain Integrity.
 *
 * Guarantees zero drift between in-memory verification leaves and persisted ledger blocks.
 * Formula: H_i = SHA-256(H_{i-1} || studentRoll || timestamp || confidence)
 */
object AegisLedgerHasher {

    const val GENESIS_HASH: String = AndroidSecurityUtils.AEGIS_GENESIS_HASH

    /**
     * Computes an Aegis SHA-256 Blockchain Block Hash.
     * Uses genesis hash if previousHash is null or blank.
     */
    fun computeBlockHash(
        previousHash: String?,
        studentRoll: String,
        timestamp: Long,
        confidencePct: Float
    ): String {
        return AndroidSecurityUtils.computeAegisBlockHash(
            previousHash = previousHash,
            studentRoll = studentRoll,
            timestamp = timestamp,
            confidencePct = confidencePct
        )
    }

    /**
     * Verifies the cryptographic chain integrity of a sequential list of attendance records (oldest to newest).
     * Returns true if all hashes match their expected SHA-256 chain, false if any block is tampered with.
     */
    fun verifyChainIntegrity(records: List<AttendanceRecordEntity>): Boolean {
        return AndroidSecurityUtils.verifyChainIntegrity(records)
    }
}
