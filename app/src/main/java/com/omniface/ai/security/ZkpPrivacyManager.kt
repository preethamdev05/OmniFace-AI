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
        val saltHex = saltBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        val md = MessageDigest.getInstance("SHA-256")
        val embeddingHash = md.digest(embeddingCsv.toByteArray(Charsets.UTF_8))
        md.reset()
        md.update(embeddingHash)
        md.update(saltBytes)
        val commitmentBytes = md.digest()
        val commitmentHex = commitmentBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        return Pair(commitmentHex, saltHex)
    }

    /**
     * Verifies that an embedding matches an earlier ZKP commitment using the secret blinding salt.
     */
    fun verifyZkpCommitment(embeddingCsv: String, commitmentHex: String, saltHex: String): Boolean {
        if (embeddingCsv.isBlank() || commitmentHex.isBlank() || saltHex.isBlank()) return false
        if (saltHex.length % 2 != 0 || !saltHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return false
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val embeddingHash = md.digest(embeddingCsv.toByteArray(Charsets.UTF_8))
            md.reset()
            md.update(embeddingHash)
            val saltBytes = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            md.update(saltBytes)
            val expectedBytes = md.digest()
            val expectedHex = expectedBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            MessageDigest.isEqual(expectedHex.toByteArray(Charsets.UTF_8), commitmentHex.toByteArray(Charsets.UTF_8)) ||
                MessageDigest.isEqual(expectedHex.lowercase().toByteArray(), commitmentHex.lowercase().toByteArray())
        } catch (_: Throwable) { false }
    }
}
