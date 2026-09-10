package com.omniface.ai.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.data.local.entity.StudentEntity
import com.omniface.ai.ml.FaceRecognitionEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupArchiveMetadata(
    val studentCount: Int,
    val templateCount: Int,
    val attendanceRecordCount: Int,
    val timestampMs: Long,
    val originalSizeBytes: Long,
    val encryptedSizeBytes: Long
)

data class LocalSnapshotInfo(
    val file: File,
    val name: String,
    val timestampMs: Long,
    val sizeBytes: Long
)

/**
 * Sovereign Zero-Knowledge End-to-End Encrypted Backup Manager.
 *
 * Implements WhatsApp-style user-owned backup architecture:
 * 1. Serializes complete kiosk snapshot (Students, 512-D ArcFace Templates, Attendance Logs).
 * 2. Encrypts payload with PBKDF2-HMAC-SHA256 (10,000 iterations) + AES-256-GCM.
 * 3. Guarantees zero data leakage: Neither Google nor any cloud intermediary can decrypt the biometric archive.
 */
object UserDriveBackupManager {

    private const val TAG = "UserDriveBackupManager"
    private const val MAGIC_HEADER = "OMNI_BACKUP_V1"
    private const val PBKDF2_ITERATIONS = 10000
    private const val KEY_LENGTH_BITS = 256
    private const val SALT_SIZE_BYTES = 16
    private const val GCM_IV_SIZE_BYTES = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    /**
     * Creates a fully encrypted backup archive stream from the current on-device database.
     */
    suspend fun createEncryptedBackupStream(
        pin: String
    ): Pair<ByteArray, BackupArchiveMetadata> = withContext(Dispatchers.IO) {
        val db = OmniFaceApplication.instance.database
        val students = db.studentDao().getAllStudents()
        val templates = db.studentDao().getAllTemplates()
        val attendance = db.attendanceDao().getAllRecords()

        val json = JSONObject().apply {
            put("version", 1)
            put("timestamp", System.currentTimeMillis())
            put("app_id", "com.omniface.ai")

            val studentsArray = JSONArray()
            for (s in students) {
                val sObj = JSONObject().apply {
                    put("roll_number", s.rollNumber)
                    put("full_name", s.fullName)
                    put("department", s.department)
                    put("semester", s.semester)
                    put("created_at", s.createdAt)
                }
                studentsArray.put(sObj)
            }
            put("students", studentsArray)

            val templatesArray = JSONArray()
            for (t in templates) {
                val tObj = JSONObject().apply {
                    put("id", t.id)
                    put("student_roll", t.studentRoll)
                    put("angle_type", t.angleType)
                    put("embedding_encrypted_csv", t.embeddingEncryptedCsv)
                    put("is_encrypted", t.isEncrypted)
                    put("quality_score", t.qualityScore.toDouble())
                    put("sharpness_score", t.sharpnessScore.toDouble())
                    put("lighting_score", t.lightingScore.toDouble())
                    put("consistency_score", t.consistencyScore.toDouble())
                    put("created_at", t.createdAt)
                }
                templatesArray.put(tObj)
            }
            put("templates", templatesArray)

            val attendanceArray = JSONArray()
            for (a in attendance) {
                val aObj = JSONObject().apply {
                    put("record_id", a.recordId)
                    put("student_roll", a.studentRoll)
                    put("student_name", a.studentName)
                    put("timestamp", a.timestamp)
                    put("session_date", a.sessionDate)
                    put("confidence_pct", a.confidencePct.toDouble())
                    put("security_tier", a.securityTier)
                    put("sha256_hash", a.sha256Hash)
                    put("is_synced", a.isSynced)
                }
                attendanceArray.put(aObj)
            }
            put("attendance", attendanceArray)
        }

        val rawBytes = json.toString().toByteArray(StandardCharsets.UTF_8)
        val encryptedBytes = encryptBytesWithPin(rawBytes, pin)

        val metadata = BackupArchiveMetadata(
            studentCount = students.size,
            templateCount = templates.size,
            attendanceRecordCount = attendance.size,
            timestampMs = System.currentTimeMillis(),
            originalSizeBytes = rawBytes.size.toLong(),
            encryptedSizeBytes = encryptedBytes.size.toLong()
        )

        Log.i(TAG, "📦 Created encrypted backup archive: ${encryptedBytes.size} bytes (${students.size} students, ${templates.size} templates, ${attendance.size} records)")
        Pair(encryptedBytes, metadata)
    }

    /**
     * Decrypts and restores the backup archive into the local SQLite database.
     */
    suspend fun restoreEncryptedBackup(
        encryptedBytes: ByteArray,
        pin: String,
        context: Context
    ): Result<BackupArchiveMetadata> = withContext(Dispatchers.IO) {
        try {
            val decryptedBytes = decryptBytesWithPin(encryptedBytes, pin)
            val jsonString = String(decryptedBytes, StandardCharsets.UTF_8)
            val root = JSONObject(jsonString)

            val timestamp = root.optLong("timestamp", System.currentTimeMillis())
            val db = OmniFaceApplication.instance.database

            val studentsArray = root.getJSONArray("students")
            val restoredStudents = mutableListOf<StudentEntity>()
            for (i in 0 until studentsArray.length()) {
                val obj = studentsArray.getJSONObject(i)
                restoredStudents.add(
                    StudentEntity(
                        rollNumber = obj.getString("roll_number"),
                        fullName = obj.getString("full_name"),
                        department = obj.optString("department", "CSE"),
                        semester = obj.optString("semester", "S1"),
                        createdAt = obj.optLong("created_at", timestamp)
                    )
                )
            }

            val templatesArray = root.optJSONArray("templates") ?: JSONArray()
            val restoredTemplates = mutableListOf<FaceTemplateEntity>()
            for (i in 0 until templatesArray.length()) {
                val obj = templatesArray.getJSONObject(i)
                restoredTemplates.add(
                    FaceTemplateEntity(
                        id = obj.getString("id"),
                        studentRoll = obj.getString("student_roll"),
                        angleType = obj.optString("angle_type", "FRONTAL"),
                        embeddingEncryptedCsv = obj.getString("embedding_encrypted_csv"),
                        isEncrypted = obj.optBoolean("is_encrypted", true),
                        qualityScore = obj.optDouble("quality_score", 100.0).toFloat(),
                        sharpnessScore = obj.optDouble("sharpness_score", 100.0).toFloat(),
                        lightingScore = obj.optDouble("lighting_score", 100.0).toFloat(),
                        consistencyScore = obj.optDouble("consistency_score", 100.0).toFloat(),
                        createdAt = obj.optLong("created_at", timestamp)
                    )
                )
            }

            val attendanceArray = root.getJSONArray("attendance")
            val restoredAttendance = mutableListOf<AttendanceRecordEntity>()
            for (i in 0 until attendanceArray.length()) {
                val obj = attendanceArray.getJSONObject(i)
                restoredAttendance.add(
                    AttendanceRecordEntity(
                        recordId = obj.getString("record_id"),
                        studentRoll = obj.getString("student_roll"),
                        studentName = obj.getString("student_name"),
                        timestamp = obj.getLong("timestamp"),
                        sessionDate = obj.getString("session_date"),
                        confidencePct = obj.getDouble("confidence_pct").toFloat(),
                        securityTier = obj.optString("security_tier", "HIGH"),
                        sha256Hash = obj.getString("sha256_hash"),
                        isSynced = obj.optBoolean("is_synced", false)
                    )
                )
            }

            // Atomically restore in Room Database Transaction
            db.withTransaction {
                db.studentDao().insertStudents(restoredStudents)
                db.studentDao().insertTemplates(restoredTemplates)
                db.attendanceDao().insertRecords(restoredAttendance)
            }

            // Immediately synchronize volatile in-memory caches across the application
            OmniFaceApplication.cachedStudentMap = restoredStudents.associate { it.rollNumber to it.fullName }
            OmniFaceApplication.cachedTemplates = restoredTemplates

            // Preload restored biometric templates into FaceSecurityPipeline & FaceRecognitionEngine
            try {
                com.omniface.ai.ml.pipeline.FaceSecurityPipeline.getInstance(context).preloadTemplates(restoredTemplates)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed updating FaceSecurityPipeline cache: ${t.message}")
            }

            try {
                val engine = FaceRecognitionEngine.getInstance(context)
                engine.preloadTemplates(restoredTemplates)
            } catch (t: Throwable) {
                Log.w(TAG, "Failed updating biometric engine cache: ${t.message}")
            }

            val meta = BackupArchiveMetadata(
                studentCount = restoredStudents.size,
                templateCount = restoredTemplates.size,
                attendanceRecordCount = restoredAttendance.size,
                timestampMs = timestamp,
                originalSizeBytes = decryptedBytes.size.toLong(),
                encryptedSizeBytes = encryptedBytes.size.toLong()
            )

            Log.i(TAG, "✅ Restored ${restoredStudents.size} students, ${restoredTemplates.size} templates, and ${restoredAttendance.size} records.")
            Result.success(meta)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed restoring backup: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Derives AES-256 key via PBKDF2 and encrypts using AES-GCM.
     */
    fun encryptBytesWithPin(plainBytes: ByteArray, pin: String): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE_BYTES).apply { random.nextBytes(this) }
        val iv = ByteArray(GCM_IV_SIZE_BYTES).apply { random.nextBytes(this) }

        val keySpec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKeyBytes = secretKeyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec)
        val cipherText = cipher.doFinal(plainBytes)

        val headerBytes = MAGIC_HEADER.toByteArray(StandardCharsets.UTF_8)
        val outStream = ByteArrayOutputStream()
        outStream.write(headerBytes)
        outStream.write(salt)
        outStream.write(iv)
        outStream.write(cipherText)

        return outStream.toByteArray()
    }

    /**
     * Verifies magic header and decrypts AES-GCM cipher with PIN.
     */
    fun decryptBytesWithPin(encryptedBytes: ByteArray, pin: String): ByteArray {
        val headerBytes = MAGIC_HEADER.toByteArray(StandardCharsets.UTF_8)
        if (encryptedBytes.size < (headerBytes.size + SALT_SIZE_BYTES + GCM_IV_SIZE_BYTES)) {
            throw IllegalArgumentException("Invalid backup file: file is too small.")
        }

        // Check magic header
        for (i in headerBytes.indices) {
            if (encryptedBytes[i] != headerBytes[i]) {
                throw IllegalArgumentException("Invalid backup file: missing OMNI_BACKUP magic header.")
            }
        }

        var offset = headerBytes.size
        val salt = encryptedBytes.copyOfRange(offset, offset + SALT_SIZE_BYTES)
        offset += SALT_SIZE_BYTES
        val iv = encryptedBytes.copyOfRange(offset, offset + GCM_IV_SIZE_BYTES)
        offset += GCM_IV_SIZE_BYTES

        val cipherText = encryptedBytes.copyOfRange(offset, encryptedBytes.size)

        val keySpec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val secretKeyFactory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val secretKeyBytes = secretKeyFactory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(secretKeyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec)

        return cipher.doFinal(cipherText)
    }

    /**
     * Gets the on-device secure directory where encrypted backup snapshots are preserved.
     */
    fun getBackupsDirectory(context: Context): File {
        val dir = File(context.filesDir, "backups")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Saves an encrypted backup snapshot locally in the app's secure sandbox.
     * Retains the latest 5 snapshots and prunes older archives.
     */
    fun saveLocalSnapshot(
        context: Context,
        encryptedBytes: ByteArray,
        metadata: BackupArchiveMetadata
    ): File {
        val dir = getBackupsDirectory(context)
        val timestamp = metadata.timestampMs
        val file = File(dir, "omniface_backup_${timestamp}.omni")
        file.writeBytes(encryptedBytes)
        Log.i(TAG, "💾 Saved local backup snapshot: ${file.absolutePath} (${encryptedBytes.size} bytes)")

        pruneLocalSnapshots(context, keepCount = 5)
        return file
    }

    /**
     * Lists all encrypted backup snapshots available in local storage.
     */
    fun listLocalSnapshots(context: Context): List<LocalSnapshotInfo> {
        val dir = getBackupsDirectory(context)
        val files = dir.listFiles { f -> f.extension == "omni" || f.extension == "enc" } ?: emptyArray()
        return files.map { f ->
            val ts = f.name.removePrefix("omniface_backup_").removeSuffix(".omni").removeSuffix(".enc").toLongOrNull() ?: f.lastModified()
            LocalSnapshotInfo(
                file = f,
                name = f.name,
                timestampMs = ts,
                sizeBytes = f.length()
            )
        }.sortedByDescending { it.timestampMs }
    }

    /**
     * Returns the most recent local backup snapshot, if one exists.
     */
    fun getLatestLocalSnapshot(context: Context): LocalSnapshotInfo? {
        return listLocalSnapshots(context).firstOrNull()
    }

    /**
     * Deletes a local backup snapshot.
     */
    fun deleteLocalSnapshot(file: File): Boolean {
        return try {
            file.delete()
        } catch (_: Exception) {
            false
        }
    }

    private fun pruneLocalSnapshots(context: Context, keepCount: Int = 5) {
        val snapshots = listLocalSnapshots(context)
        if (snapshots.size > keepCount) {
            snapshots.drop(keepCount).forEach {
                try {
                    it.file.delete()
                    Log.i(TAG, "Pruned older local backup snapshot: ${it.name}")
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Shares an encrypted backup file to Google Drive, Files, or other apps via the Android Sharesheet.
     */
    fun shareBackup(context: Context, encryptedBytes: ByteArray, fileName: String) {
        try {
            val cacheDir = File(context.cacheDir, "backups").apply { if (!exists()) mkdirs() }
            val shareFile = File(cacheDir, fileName)
            shareFile.writeBytes(encryptedBytes)

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                shareFile
            )

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/octet-stream"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OmniFace AI Biometric Backup Archive")
                putExtra(Intent.EXTRA_TEXT, "Encrypted OmniFace AI Biometric & Attendance Backup.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(sendIntent, "Save or Send Backup (Google Drive, Files, etc.)")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            Log.e(TAG, "Failed sharing backup: ${e.message}", e)
        }
    }
}
