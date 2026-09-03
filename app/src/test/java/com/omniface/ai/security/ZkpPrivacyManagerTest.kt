package com.omniface.ai.security

import org.junit.Assert.*
import org.junit.Test

class ZkpPrivacyManagerTest {

    @Test
    fun testZkpCommitmentGenerationAndValidVerification() {
        val embeddingCsv = "0.0123,-0.0456,0.0789,0.1112,-0.1314"
        val (commitment, salt) = ZkpPrivacyManager.generateZkpCommitment(embeddingCsv)

        assertNotNull(commitment)
        assertNotNull(salt)
        assertEquals("SHA-256 commitment must be 64 hex characters", 64, commitment.length)
        assertEquals("32-byte salt must be 64 hex characters", 64, salt.length)

        val isValid = ZkpPrivacyManager.verifyZkpCommitment(embeddingCsv, commitment, salt)
        assertTrue("Matching embedding and salt must verify commitment", isValid)
    }

    @Test
    fun testZkpCommitmentRejectsTamperedEmbedding() {
        val originalCsv = "0.0123,-0.0456,0.0789"
        val tamperedCsv = "0.0123,-0.0456,0.0790"
        val (commitment, salt) = ZkpPrivacyManager.generateZkpCommitment(originalCsv)

        val isValid = ZkpPrivacyManager.verifyZkpCommitment(tamperedCsv, commitment, salt)
        assertFalse("Tampered embedding must fail commitment verification", isValid)
    }

    @Test
    fun testZkpCommitmentRejectsInvalidSaltOrEmptyInputs() {
        val embeddingCsv = "0.0123,-0.0456,0.0789"
        val (commitment, _) = ZkpPrivacyManager.generateZkpCommitment(embeddingCsv)

        assertFalse("Empty embedding should fail", ZkpPrivacyManager.verifyZkpCommitment("", commitment, "aabbcc"))
        assertFalse("Empty commitment should fail", ZkpPrivacyManager.verifyZkpCommitment(embeddingCsv, "", "aabbcc"))
        assertFalse("Empty salt should fail", ZkpPrivacyManager.verifyZkpCommitment(embeddingCsv, commitment, ""))
        assertFalse("Odd length hex salt should fail", ZkpPrivacyManager.verifyZkpCommitment(embeddingCsv, commitment, "abc"))
        assertFalse("Non-hex salt should fail", ZkpPrivacyManager.verifyZkpCommitment(embeddingCsv, commitment, "zzzzzz"))
    }
}
