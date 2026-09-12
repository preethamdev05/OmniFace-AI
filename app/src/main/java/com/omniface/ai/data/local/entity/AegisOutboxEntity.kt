package com.omniface.ai.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "aegis_outbox",
    indices = [
        Index(value = ["status"]),
        Index(value = ["timestamp"])
    ]
)
data class AegisOutboxEntity(
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String,

    @ColumnInfo(name = "student_roll")
    val studentRoll: String,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    @ColumnInfo(name = "confidence_pct")
    val confidencePct: Float,

    @ColumnInfo(name = "leaf_hash")
    val leafHash: String,

    @ColumnInfo(name = "status")
    val status: String = STATUS_PENDING_MINT,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_PENDING_MINT = "PENDING_MINT"
        const val STATUS_MINTED = "MINTED"
        const val STATUS_FAILED = "FAILED"
    }
}
