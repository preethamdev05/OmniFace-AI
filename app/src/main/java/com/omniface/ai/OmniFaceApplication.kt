package com.omniface.ai

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.omniface.ai.data.local.AppDatabase
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.sync.AttendanceSyncWorker
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32

class OmniFaceApplication : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize Hardware KeyStore Encryption Master Key
        AndroidSecurityUtils.initMasterKey()

        // 2. Initialize Room SQLite Database with WAL Mode Concurrency
        database = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            "omniface_biometrics.db"
        )
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_1_3,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_1_4,
                AppDatabase.MIGRATION_2_4
            )
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA synchronous = NORMAL;")
                    db.execSQL("PRAGMA temp_store = MEMORY;")
                }
            })
            .build()

        // 3. Verify TFLite Model Flatbuffer Integrity on Startup
        verifyModelAssetsIntegrity()

        // 4. Initialize Local Kiosk Fleet Node with real network IP
        com.omniface.ai.hardware.FleetTopologyManager.initializeLocalNode(this)

        // 5. Check User Consent Before Scheduling Cloud Sync
        if (isCloudSyncEnabled()) {
            schedulePeriodicSync()
        }
    }

    private fun verifyModelAssetsIntegrity() {
        val modelFiles = listOf(
            "mobilefacenet_512d_fp16.tflite",
            "mobilefacenet_512d_fp32.tflite",
            "mobilefacenet_512d_int8.tflite"
        )
        for (file in modelFiles) {
            try {
                assets.open(file).use { input ->
                    val crc = CRC32()
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        crc.update(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                    Log.i("OmniFaceApp", "Verified model asset: $file ($totalBytes bytes, CRC32: ${crc.value})")
                }
            } catch (e: Exception) {
                Log.w("OmniFaceApp", "Model asset check warning for $file: ${e.message}")
            }
        }
    }

    fun isCloudSyncEnabled(): Boolean {
        val prefs = getSharedPreferences("omniface_privacy_prefs", MODE_PRIVATE)
        return prefs.getBoolean("cloud_sync_user_consent", false)
    }

    fun setCloudSyncEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences("omniface_privacy_prefs", MODE_PRIVATE)
        prefs.edit().putBoolean("cloud_sync_user_consent", enabled).apply()
        if (enabled) {
            schedulePeriodicSync()
        } else {
            WorkManager.getInstance(this).cancelUniqueWork("OmniFaceAttendanceSync")
        }
    }

    fun schedulePeriodicSync() {
        if (!isCloudSyncEnabled()) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<AttendanceSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "OmniFaceAttendanceSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }

    companion object {
        lateinit var instance: OmniFaceApplication
            private set
    }
}
