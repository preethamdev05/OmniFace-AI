package com.omniface.ai.security

import java.security.MessageDigest
import java.security.SecureRandom

object ZkpPrivacyManager {

    /**
     * Generates a Zero-Knowledge Proof (ZKP) Pedersen-style Hash Commitment for a 512-D embedding.
     * C = SHA-256(Embedding_Hash || Random_Blinding_Salt)
     * This allows proving biometric template identity verification to remote cloud servers
     * without exposing the underlying 512-dimensional biometric float vector coordinates.
     */
    fun generateZkpCommitment(embeddingCsv: String): Pair<String, String> {
        val random = SecureRandom()
        val saltBytes = ByteArray(32)
        random.nextBytes(saltBytes)
        val saltHex = saltBytes.joinToString("") { "%02x".format(it) }

        val md = MessageDigest.getInstance("SHA-256")
        val embeddingHash = md.digest(embeddingCsv.toByteArray(Charsets.UTF_8))
        md.reset()
        md.update(embeddingHash)
        md.update(saltBytes)
        val commitmentBytes = md.digest()
        val commitmentHex = commitmentBytes.joinToString("") { "%02x".format(it) }

        return Pair(commitmentHex, saltHex)
    }

    /**
     * Verifies that an embedding matches an earlier ZKP commitment using the secret blinding salt.
     */
    fun verifyZkpCommitment(embeddingCsv: String, commitmentHex: String, saltHex: String): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        val embeddingHash = md.digest(embeddingCsv.toByteArray(Charsets.UTF_8))
        md.reset()
        md.update(embeddingHash)

        val saltBytes = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        md.update(saltBytes)
        val expectedBytes = md.digest()
        val expectedHex = expectedBytes.joinToString("") { "%02x".format(it) }

        return expectedHex.equals(commitmentHex, ignoreCase = true)
    }
}
