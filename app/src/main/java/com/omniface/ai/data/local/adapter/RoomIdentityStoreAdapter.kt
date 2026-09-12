package com.omniface.ai.data.local.adapter

import android.util.Log
import com.omniface.ai.data.local.AppDatabase
import com.omniface.ai.ml.verification.domain.IdentityStore
import com.omniface.ai.ml.verification.domain.IdentityTemplate
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlin.math.sqrt

/**
 * Production Adapter for IdentityStore Seam.
 *
 * Observes Room SQLite database, decrypts hardware Keystore AES-256-GCM ciphertexts in memory,
 * and emits clean domain IdentityTemplate records. Room entities NEVER leak past this adapter.
 */
class RoomIdentityStoreAdapter(
    private val database: AppDatabase
) : IdentityStore {

    companion object {
        private const val TAG = "RoomIdentityStore"
    }

    override fun observeTemplates(): Flow<List<IdentityTemplate>> {
        return database.personDao().getAllTemplatesFlow().map { templateEntities ->
            val personMap = try {
                database.personDao().getAllPersons().associateBy { it.rollNumber }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to load person records for template join: ${t.message}")
                emptyMap()
            }

            val domainList = mutableListOf<IdentityTemplate>()
            for (entity in templateEntities) {
                val decryptedCsv = try {
                    if (entity.isEncrypted) AndroidSecurityUtils.decrypt(entity.embeddingEncryptedCsv)
                    else entity.embeddingEncryptedCsv
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to decrypt template ${entity.id}: ${t.message}")
                    continue
                }

                if (decryptedCsv.isBlank()) continue
                val emb = parseAndNormalizeEmbedding(decryptedCsv)
                if (emb.isEmpty()) continue

                val person = personMap[entity.studentRoll]
                val name = person?.fullName ?: entity.studentRoll
                val role = person?.role ?: "STUDENT"

                domainList.add(
                    IdentityTemplate(
                        identityId = entity.studentRoll,
                        displayName = name,
                        role = role,
                        embedding = emb,
                        version = entity.createdAt
                    )
                )
            }
            domainList
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun getTemplateCount(): Int {
        return try {
            database.personDao().getAllTemplates().size
        } catch (_: Throwable) {
            0
        }
    }

    private fun parseAndNormalizeEmbedding(csv: String): FloatArray {
        return try {
            val arr = csv.split(",").map { it.trim().toFloat() }.toFloatArray()
            if (arr.isEmpty()) return FloatArray(0)
            var sumSquares = 0.0f
            for (v in arr) sumSquares += v * v
            val norm = sqrt(sumSquares)
            if (norm > 1e-6f) {
                val invNorm = 1.0f / norm
                for (i in arr.indices) arr[i] *= invNorm
            } else {
                java.util.Arrays.fill(arr, 0.0f)
            }
            arr
        } catch (_: Exception) {
            FloatArray(0)
        }
    }
}
