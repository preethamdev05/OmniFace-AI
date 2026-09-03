package com.omniface.ai.sync

import android.content.Context
import android.util.Log
import androidx.work.*
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Background WorkManager Worker for Automated Scheduled WhatsApp-Style Drive Backups.
 */
class GoogleDriveBackupWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = applicationContext.getSharedPreferences("OMNIFACE_DRIVE_BACKUP", Context.MODE_PRIVATE)
        val isAutoEnabled = prefs.getBoolean("AUTO_BACKUP_ENABLED", false)
        val userEmail = prefs.getString("CONNECTED_GOOGLE_ACCOUNT", "") ?: ""
        val backupPin = prefs.getString("BACKUP_ENCRYPTION_PIN", "") ?: ""

        if (!isAutoEnabled || userEmail.isBlank() || backupPin.isBlank()) {
            Log.i(TAG, "Automated backup skipped: not configured or no account/PIN set.")
            return@withContext Result.success()
        }

        try {
            // Obtain OAuth2 access token for Google Drive appDataFolder
            val scope = "oauth2:https://www.googleapis.com/auth/drive.appdata"
            val token = GoogleAuthUtil.getToken(applicationContext, userEmail, scope)

            val (encryptedBytes, meta) = UserDriveBackupManager.createEncryptedBackupStream(backupPin)
            val uploadResult = GoogleDriveAppDataService.uploadBackup(token, encryptedBytes)

            if (uploadResult.isSuccess) {
                val now = System.currentTimeMillis()
                prefs.edit()
                    .putLong("LAST_BACKUP_TIME", now)
                    .putLong("LAST_BACKUP_SIZE", encryptedBytes.size.toLong())
                    .putInt("LAST_BACKUP_STUDENTS", meta.studentCount)
                    .putInt("LAST_BACKUP_RECORDS", meta.attendanceRecordCount)
                    .apply()

                Log.i(TAG, "✅ Automated scheduled backup completed: ${meta.studentCount} students, ${encryptedBytes.size} bytes.")
                Result.success()
            } else {
                Log.w(TAG, "Automated backup upload failed: ${uploadResult.exceptionOrNull()?.message}")
                Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Automated backup failed: ${e.message}", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DriveBackupWorker"
        private const val WORK_NAME = "OmniFaceGoogleDriveBackup"

        fun schedulePeriodicBackup(context: Context, frequency: String, wifiOnly: Boolean) {
            val workManager = WorkManager.getInstance(context)

            if (frequency == "OFF" || frequency == "MANUAL") {
                workManager.cancelUniqueWork(WORK_NAME)
                Log.i(TAG, "Periodic backup cancelled.")
                return
            }

            val intervalHours = if (frequency == "WEEKLY") 168L else 24L

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<GoogleDriveBackupWorker>(intervalHours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            workManager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )

            Log.i(TAG, "Scheduled periodic Google Drive backup: $frequency (interval: ${intervalHours}h, wifiOnly: $wifiOnly)")
        }
    }
}
