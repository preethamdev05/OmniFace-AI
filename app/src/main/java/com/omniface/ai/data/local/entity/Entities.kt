package com.omniface.ai.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "students",
    indices = [Index(value = ["roll_number"], unique = true)]
)
data class StudentEntity(
    @PrimaryKey
    @ColumnInfo(name = "roll_number")
    val rollNumber: String,
    
    @ColumnInfo(name = "full_name")
    val fullName: String,
    
    @ColumnInfo(name = "department")
    val department: String,
    
    @ColumnInfo(name = "semester")
    val semester: String,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "face_templates",
    foreignKeys = [
        ForeignKey(
            entity = StudentEntity::class,
            parentColumns = ["roll_number"],
            childColumns = ["student_roll"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["student_roll"]),
        Index(value = ["angle_type"])
    ]
)
data class FaceTemplateEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    
    @ColumnInfo(name = "student_roll")
    val studentRoll: String,
    
    @ColumnInfo(name = "angle_type")
    val angleType: String, // "FRONTAL", "LEFT_15", "RIGHT_15", "UP_10", "DOWN_10"
    
    @ColumnInfo(name = "embedding_encrypted_csv")
    val embeddingEncryptedCsv: String,
    
    @ColumnInfo(name = "is_encrypted")
    val isEncrypted: Boolean = true,
    
    @ColumnInfo(name = "quality_score", defaultValue = "100.0")
    val qualityScore: Float = 100.0f,
    
    @ColumnInfo(name = "sharpness_score", defaultValue = "100.0")
    val sharpnessScore: Float = 100.0f,
    
    @ColumnInfo(name = "lighting_score", defaultValue = "100.0")
    val lightingScore: Float = 100.0f,
    
    @ColumnInfo(name = "consistency_score", defaultValue = "100.0")
    val consistencyScore: Float = 100.0f,
    
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "attendance_records",
    indices = [
        Index(value = ["session_date", "timestamp"]),
        Index(value = ["session_date", "student_roll"]),
        Index(value = ["student_roll"]),
        Index(value = ["is_synced"])
    ]
)
data class AttendanceRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "record_id")
    val recordId: String,
    
    @ColumnInfo(name = "student_roll")
    val studentRoll: String,
    
    @ColumnInfo(name = "student_name")
    val studentName: String,
    
    @ColumnInfo(name = "session_date")
    val sessionDate: String, // "YYYY-MM-DD"
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),
    
    @ColumnInfo(name = "confidence_pct")
    val confidencePct: Float,
    
    @ColumnInfo(name = "security_tier")
    val securityTier: String, // "STANDARD", "HIGH", "STRICT"
    
    @ColumnInfo(name = "sha256_hash")
    val sha256Hash: String,
    
    @ColumnInfo(name = "is_synced")
    val isSynced: Boolean = false
)
