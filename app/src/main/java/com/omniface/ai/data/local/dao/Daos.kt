package com.omniface.ai.data.local.dao

import androidx.room.*
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StudentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudent(student: StudentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStudents(students: List<StudentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTemplates(templates: List<FaceTemplateEntity>)

    @Query("SELECT COUNT(*) FROM students")
    suspend fun getStudentCount(): Int


    @Query("SELECT * FROM students WHERE roll_number = :roll LIMIT 1")
    suspend fun getStudentByRoll(roll: String): StudentEntity?

    @Query("SELECT * FROM students ORDER BY roll_number ASC")
    fun getAllStudentsFlow(): Flow<List<StudentEntity>>

    @Query("SELECT * FROM students")
    suspend fun getAllStudents(): List<StudentEntity>

    @Query("SELECT * FROM face_templates")
    suspend fun getAllTemplates(): List<FaceTemplateEntity>

    @Query("SELECT * FROM face_templates WHERE student_roll = :roll")
    suspend fun getTemplatesForStudent(roll: String): List<FaceTemplateEntity>

    @Query("SELECT COUNT(*) FROM students")
    fun getStudentCountFlow(): Flow<Int>


    @Query("UPDATE face_templates SET embedding_encrypted_csv = :newCsv WHERE id = :templateId")
    suspend fun updateTemplateEmbedding(templateId: String, newCsv: String)

    @Query("DELETE FROM face_templates WHERE student_roll = :roll")
    suspend fun deleteTemplatesForStudent(roll: String)

    @Transaction
    suspend fun enrollStudentWithTemplates(student: StudentEntity, templates: List<FaceTemplateEntity>) {
        insertStudent(student)
        insertTemplates(templates)
    }

    @Query("DELETE FROM students WHERE roll_number = :roll")
    suspend fun deleteStudentByRoll(roll: String)

    @Update
    suspend fun updateStudent(student: StudentEntity)

    @Delete
    suspend fun deleteStudent(student: StudentEntity)
}

@Dao
interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecord(record: AttendanceRecordEntity)

    @Query("SELECT * FROM attendance_records WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    fun getRecordsBetweenTimestampsFlow(startTime: Long, endTime: Long): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    fun getAllRecordsFlow(): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC")
    suspend fun getAllRecords(): List<AttendanceRecordEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecords(records: List<AttendanceRecordEntity>)

    @Query("SELECT * FROM attendance_records ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentRecordsFlow(limit: Int): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE session_date = :date ORDER BY timestamp DESC")
    fun getRecordsForDateFlow(date: String): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT * FROM attendance_records WHERE session_date = :date AND student_roll = :roll LIMIT 1")
    suspend fun getRecordForStudentOnDate(date: String, roll: String): AttendanceRecordEntity?

    @Query("SELECT * FROM attendance_records WHERE student_roll = :roll ORDER BY timestamp DESC")
    fun getRecordsForStudentFlow(roll: String): Flow<List<AttendanceRecordEntity>>

    @Query("SELECT COUNT(*) FROM attendance_records WHERE student_roll = :roll")
    suspend fun getAttendanceCountForStudent(roll: String): Int

    @Query("SELECT COUNT(*) FROM attendance_records WHERE session_date = :date")
    fun getCountForDateFlow(date: String): Flow<Int>

    @Query("SELECT * FROM attendance_records WHERE is_synced = 0")
    suspend fun getUnsyncedRecords(): List<AttendanceRecordEntity>

    @Query("SELECT * FROM attendance_records WHERE is_synced = 0 LIMIT :limit")
    suspend fun getUnsyncedRecordsPaged(limit: Int): List<AttendanceRecordEntity>

    @Query("UPDATE attendance_records SET is_synced = 1 WHERE record_id IN (:recordIds)")
    suspend fun markAsSynced(recordIds: List<String>)

    @Query("SELECT sha256_hash FROM attendance_records ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestHash(): String?

    @Query("DELETE FROM attendance_records WHERE timestamp < :cutoffTimestamp")
    suspend fun purgeLegacyRecordsBefore(cutoffTimestamp: Long): Int

    @Query("DELETE FROM attendance_records")
    suspend fun deleteAllRecords(): Int

    @Transaction
    suspend fun recordAttendanceIfNotExists(record: AttendanceRecordEntity): Boolean {
        val existing = getRecordForStudentOnDate(record.sessionDate, record.studentRoll)
        if (existing == null) {
            val prevHash = getLatestHash() ?: com.omniface.ai.security.AndroidSecurityUtils.AEGIS_GENESIS_HASH
            val finalRecord = if (record.sha256Hash.isNotBlank() && record.sha256Hash != prevHash) {
                record
            } else {
                val blockHash = com.omniface.ai.security.AndroidSecurityUtils.computeAegisBlockHash(
                    previousHash = prevHash,
                    studentRoll = record.studentRoll,
                    timestamp = record.timestamp,
                    confidencePct = record.confidencePct
                )
                record.copy(sha256Hash = blockHash)
            }
            insertRecord(finalRecord)
            return true
        }
        return false
    }

    @Transaction
    suspend fun insertWithAegisChaining(record: AttendanceRecordEntity): Long {
        val prevHash = getLatestHash() ?: com.omniface.ai.security.AndroidSecurityUtils.AEGIS_GENESIS_HASH
        val blockHash = com.omniface.ai.security.AndroidSecurityUtils.computeAegisBlockHash(
            previousHash = prevHash,
            studentRoll = record.studentRoll,
            timestamp = record.timestamp,
            confidencePct = record.confidencePct
        )
        val finalRecord = record.copy(sha256Hash = blockHash)
        insertRecord(finalRecord)
        return 1L
    }
}

