package com.omniface.ai.tier2

import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import org.junit.Assert.*
import org.junit.Test

/**
 * Tier 2: Boundary & Corner Cases - Feature 8: Storage Entities & DPDP Boundaries
 */
class Tier2RoomStorageBoundaryTest {

    @Test
    fun testBlankStudentRollHandling() {
        val student = StudentEntity(
            rollNumber = "",
            fullName = "Anonymous Guest",
            department = "Visitor",
            semester = "N/A"
        )
        assertEquals("", student.rollNumber)
    }

    @Test
    fun testSpecialCharactersInStudentName() {
        val unicodeName = "José María Álvarez-García 🎓"
        val student = StudentEntity(
            rollNumber = "INT-001",
            fullName = unicodeName,
            department = "Languages",
            semester = "1"
        )
        assertEquals(unicodeName, student.fullName)
    }

    @Test
    fun testExtremeQualityScoreBounds() {
        val tplMin = FaceTemplateEntity("t_min", "R01", "FRONTAL", "csv", false, qualityScore = 0.0f)
        val tplMax = FaceTemplateEntity("t_max", "R01", "FRONTAL", "csv", false, qualityScore = 100.0f)

        assertEquals(0.0f, tplMin.qualityScore, 1e-4f)
        assertEquals(100.0f, tplMax.qualityScore, 1e-4f)
    }

    @Test
    fun testEmptyDepartmentAndSemester() {
        val student = StudentEntity("R009", "Jane Doe", "", "")
        assertEquals("", student.department)
        assertEquals("", student.semester)
    }

    @Test
    fun testAttendanceRecordBoundaryConfidenceValues() {
        val minConfidenceRecord = AttendanceRecordEntity(
            recordId = "rec_min",
            studentRoll = "R01",
            studentName = "Min",
            sessionDate = "2026-08-25",
            timestamp = 100L,
            confidencePct = 0.0f,
            securityTier = "STANDARD",
            sha256Hash = "hash"
        )
        val maxConfidenceRecord = AttendanceRecordEntity(
            recordId = "rec_max",
            studentRoll = "R01",
            studentName = "Max",
            sessionDate = "2026-08-25",
            timestamp = 100L,
            confidencePct = 100.0f,
            securityTier = "STRICT",
            sha256Hash = "hash"
        )

        assertEquals(0.0f, minConfidenceRecord.confidencePct, 1e-4f)
        assertEquals(100.0f, maxConfidenceRecord.confidencePct, 1e-4f)
    }
}
