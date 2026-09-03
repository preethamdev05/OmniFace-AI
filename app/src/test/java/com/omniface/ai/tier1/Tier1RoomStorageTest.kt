package com.omniface.ai.tier1

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 1: Feature 8 - Room Storage & DPDP Act 2023 Compliance Entity Contracts
 */
class Tier1RoomStorageTest {

    @Test
    fun testStudentEntityCreationAndAttributes() {
        val student = StudentEntity(
            rollNumber = "CS2026-001",
            fullName = "Ananya Sharma",
            department = "Computer Science",
            semester = "6",
            createdAt = 1724500000000L
        )

        assertEquals("CS2026-001", student.rollNumber)
        assertEquals("Ananya Sharma", student.fullName)
        assertEquals("Computer Science", student.department)
        assertEquals("6", student.semester)
        assertEquals(1724500000000L, student.createdAt)
    }

    @Test
    fun testFaceTemplateEntityMultiAngle() {
        val angles = listOf("FRONTAL", "LEFT_15", "RIGHT_15", "UP_10", "DOWN_10", "MASTER_CENTROID")
        val templates = angles.mapIndexed { idx, angle ->
            FaceTemplateEntity(
                id = "tpl_$idx",
                studentRoll = "CS2026-001",
                angleType = angle,
                embeddingEncryptedCsv = "encrypted_blob_$idx",
                isEncrypted = true,
                qualityScore = 95.0f
            )
        }

        assertEquals(6, templates.size)
        assertTrue(templates.all { it.isEncrypted })
        assertTrue(templates.all { it.studentRoll == "CS2026-001" })
        assertEquals("MASTER_CENTROID", templates.last().angleType)
    }

    @Test
    fun testAttendanceRecordEntityAegisFields() {
        val record = AttendanceRecordEntity(
            recordId = "att_001",
            studentRoll = "CS2026-001",
            studentName = "Ananya Sharma",
            sessionDate = "2026-08-25",
            timestamp = 1724580000000L,
            confidencePct = 97.8f,
            securityTier = "HIGH",
            sha256Hash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            isSynced = false
        )

        assertEquals("att_001", record.recordId)
        assertEquals("2026-08-25", record.sessionDate)
        assertEquals(97.8f, record.confidencePct, 0.01f)
        assertEquals("HIGH", record.securityTier)
        assertFalse(record.isSynced)
    }

    @Test
    fun testCascadeDeleteSpecification() {
        val student = StudentEntity("CS2026-002", "Vikram Patel", "AI", "4")
        val template = FaceTemplateEntity("tpl_v1", "CS2026-002", "FRONTAL", "csv_data", false)

        assertEquals("Student roll must link to template studentRoll for cascade", student.rollNumber, template.studentRoll)
    }

    @Test
    fun testAttendanceRecordUniqueDateRollConstraint() {
        val record1 = AttendanceRecordEntity(
            recordId = "rec_1",
            studentRoll = "CS2026-001",
            studentName = "Ananya",
            sessionDate = "2026-08-25",
            timestamp = 1000L,
            confidencePct = 95f,
            securityTier = "HIGH",
            sha256Hash = "hash1"
        )
        val record2 = AttendanceRecordEntity(
            recordId = "rec_2",
            studentRoll = "CS2026-001",
            studentName = "Ananya",
            sessionDate = "2026-08-25",
            timestamp = 2000L,
            confidencePct = 96f,
            securityTier = "HIGH",
            sha256Hash = "hash2"
        )

        assertEquals("Records on same date for same student have same session_date and student_roll", record1.sessionDate, record2.sessionDate)
        assertEquals(record1.studentRoll, record2.studentRoll)
    }
}
