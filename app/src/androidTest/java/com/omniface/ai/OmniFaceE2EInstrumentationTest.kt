package com.omniface.ai

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omniface.ai.data.local.AppDatabase
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.PersonEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OmniFaceE2EInstrumentationTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    @Throws(IOException::class)
    fun closeDb() {
        db.close()
    }

    @Test
    fun testEnrollPersonAndVerifyRoomStorage() = runBlocking {
        val person = PersonEntity(
            rollNumber = "INS-001",
            fullName = "Priya Raman",
            department = "Computer Science",
            semester = "IV",
            role = "STUDENT"
        )
        db.personDao().insertPerson(person)

        val retrieved = db.personDao().getPersonByRoll("INS-001")
        assertNotNull(retrieved)
        assertEquals("Priya Raman", retrieved?.fullName)
        assertEquals("Computer Science", retrieved?.department)
    }

    @Test
    fun testFaceTemplateStorageAndRetrieval() = runBlocking {
        val person = PersonEntity(
            rollNumber = "INS-002",
            fullName = "Ananya Sharma",
            department = "Electrical Engineering",
            semester = "III"
        )
        db.personDao().insertPerson(person)

        val dummyVector = FloatArray(512) { 0.04419f }
        val template = FaceTemplateEntity(
            id = UUID.randomUUID().toString(),
            studentRoll = "INS-002",
            angleType = "FRONTAL",
            embeddingEncryptedCsv = dummyVector.joinToString(","),
            isEncrypted = true,
            qualityScore = 99.0f
        )
        db.personDao().insertTemplates(listOf(template))

        val templates = db.personDao().getTemplatesForPerson("INS-002")
        assertEquals(1, templates.size)
        assertEquals("FRONTAL", templates[0].angleType)
        assertTrue(templates[0].isEncrypted)
    }

    @Test
    fun testAttendanceRecordMarkAsSynced() = runBlocking {
        val person = PersonEntity(
            rollNumber = "INS-003",
            fullName = "Rahul Dravid",
            department = "Physical Education",
            semester = "VIII"
        )
        db.personDao().insertPerson(person)

        val record = AttendanceRecordEntity(
            recordId = "att_test_001",
            studentRoll = "INS-003",
            studentName = "Rahul Dravid",
            sessionDate = "2026-09-11",
            timestamp = System.currentTimeMillis(),
            confidencePct = 98.4f,
            securityTier = "HIGH",
            sha256Hash = "hash123",
            isSynced = false
        )
        db.attendanceDao().insertRecord(record)

        val unsyncedBefore = db.attendanceDao().getUnsyncedRecordsPaged(limit = 10)
        assertEquals(1, unsyncedBefore.size)
        assertFalse(unsyncedBefore[0].isSynced)

        db.attendanceDao().markAsSynced(listOf("att_test_001"))

        val unsyncedAfter = db.attendanceDao().getUnsyncedRecordsPaged(limit = 10)
        assertEquals(0, unsyncedAfter.size)
    }

    @Test
    fun testDeduplicationAndPurgeContract() = runBlocking {
        val person = PersonEntity(
            rollNumber = "INS-004",
            fullName = "Kavita Krishnan",
            department = "Mathematics",
            semester = "II",
            role = "STUDENT"
        )
        db.personDao().insertPerson(person)
        assertEquals(1, db.personDao().getPersonCount())

        // Purge under DPDP Act 2023
        db.personDao().deletePersonByRoll("INS-004")
        db.personDao().deleteTemplatesForPerson("INS-004")

        assertNull(db.personDao().getPersonByRoll("INS-004"))
        assertEquals(0, db.personDao().getTemplatesForPerson("INS-004").size)
    }

    @Test
    fun testUnifiedFaceModelEngineOnHardware() {
        val engine = com.omniface.ai.ml.unified.UnifiedFaceModelEngine(context)
        assertTrue("UnifiedFaceModelEngine must initialize successfully on hardware", engine.isReady)
        val dummyBitmap = android.graphics.Bitmap.createBitmap(112, 112, android.graphics.Bitmap.Config.ARGB_8888)
        val result = engine.processFace(dummyBitmap)
        assertNotNull("Unified inference result must not be null", result)
        assertEquals(512, result?.identityEmbedding?.size)
        assertEquals(3, result?.padProbabilities?.size)
        assertEquals(4, result?.qualityScores?.size)
        assertEquals(1404, result?.meshLandmarks?.size)
        assertEquals(265, result?.geometry3DMM?.size)
        assertEquals(2, result?.gazeAngles?.size)
        assertEquals(5, result?.attributeProbabilities?.size)
        assertTrue("Latency must be positive", (result?.latencyMs ?: 0L) >= 0L)
        engine.close()
    }
}
