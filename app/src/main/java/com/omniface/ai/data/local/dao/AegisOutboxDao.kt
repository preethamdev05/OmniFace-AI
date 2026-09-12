package com.omniface.ai.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.omniface.ai.data.local.entity.AegisOutboxEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AegisOutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOutbox(entry: AegisOutboxEntity)

    @Query("SELECT * FROM aegis_outbox WHERE status = 'PENDING_MINT' ORDER BY timestamp ASC LIMIT :limit")
    suspend fun getPendingOutbox(limit: Int = 50): List<AegisOutboxEntity>

    @Query("UPDATE aegis_outbox SET status = :status WHERE record_id = :recordId")
    suspend fun updateStatus(recordId: String, status: String)

    @Query("UPDATE aegis_outbox SET retry_count = retry_count + 1 WHERE record_id = :recordId")
    suspend fun incrementRetryCount(recordId: String)

    @Query("SELECT COUNT(*) FROM aegis_outbox WHERE status = 'PENDING_MINT'")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM aegis_outbox WHERE status = 'PENDING_MINT'")
    suspend fun getPendingCount(): Int
}
