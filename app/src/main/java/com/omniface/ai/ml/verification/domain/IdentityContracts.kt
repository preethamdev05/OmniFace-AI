package com.omniface.ai.ml.verification.domain

import kotlinx.coroutines.flow.Flow
import java.util.Arrays

/**
 * Pure Domain Representation of an Enrolled Biometric Identity.
 *
 * Sourced from persistence/sync layers, completely decoupled from Room annotations.
 * Contains the decrypted 512-D L2 normalized embedding vector and metadata.
 */
data class IdentityTemplate(
    val identityId: String,
    val displayName: String,
    val role: String,
    val embedding: FloatArray,
    val version: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as IdentityTemplate
        if (identityId != other.identityId) return false
        if (displayName != other.displayName) return false
        if (role != other.role) return false
        if (version != other.version) return false
        return Arrays.equals(embedding, other.embedding)
    }

    override fun hashCode(): Int {
        var result = identityId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + Arrays.hashCode(embedding)
        result = 31 * result + version.hashCode()
        return result
    }
}

/**
 * Clean Identity Persistence Seam (Port).
 *
 * Provides reactive streams of enrolled identities to the verification engine.
 */
interface IdentityStore {
    fun observeTemplates(): Flow<List<IdentityTemplate>>
    suspend fun getTemplateCount(): Int
}
