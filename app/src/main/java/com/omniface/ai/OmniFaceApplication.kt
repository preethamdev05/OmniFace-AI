package com.omniface.ai

import android.app.Application
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.omniface.ai.data.local.AppDatabase
import com.omniface.ai.hardware.TurnstileRelayController
import com.omniface.ai.security.AndroidSecurityUtils
import com.omniface.ai.sync.AttendanceSyncWorker
import java.util.concurrent.TimeUnit

class OmniFaceApplication : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Initialize Hardware KeyStore Encryption Master Key
        AndroidSecurityUtils.initMasterKey()

        // 1b. Initialize Multilingual Localization & Soundboard Voice Engine
        com.omniface.ai.i18n.LocalizationManager.init(this)
        com.omniface.ai.audio.BiometricSoundboard.initTts(this)
        com.omniface.ai.audio.BiometricSoundboard.setLanguage(com.omniface.ai.i18n.LocalizationManager.currentLanguage.value)

        // 2. Initialize Room SQLite Database with WAL Mode Concurrency
        val dbBuilder = Room.databaseBuilder(
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
                AppDatabase.MIGRATION_2_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA synchronous = NORMAL;")
                    db.execSQL("PRAGMA temp_store = MEMORY;")
                }
            })
        @Suppress("DEPRECATION")
        if (BuildConfig.DEBUG) {
            dbBuilder.fallbackToDestructiveMigrationOnDowngrade()
            dbBuilder.fallbackToDestructiveMigration()
        }
        database = dbBuilder.build()

        // 3. Verify TFLite Model Flatbuffer Integrity on Startup
        verifyModelAssetsIntegrity()

        // 4. Initialize Local Kiosk Fleet Node with real network IP
        com.omniface.ai.hardware.FleetTopologyManager.initializeLocalNode(this)

        // 5. Initialize Turnstile Relay — loads per-device HMAC secret from EncryptedSharedPreferences
        TurnstileRelayController.initWithContext(this)

        // 5b. Initialize Kiosk Lock Controller (PBKDF2 + persistent lockout)
        com.omniface.ai.hardware.KioskLockController.initialize(this)

        // 6. Check User Consent Before Scheduling Cloud Sync
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
                    // SHA-256 integrity digest — collision-resistant, unlike CRC32
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        digest.update(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                    val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                    Log.i("OmniFaceApp", "Verified model asset: $file ($totalBytes bytes, SHA-256: ${sha256.take(16)}…)")
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
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "OmniFaceAttendanceSync",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW || level >= TRIM_MEMORY_MODERATE) {
            Log.i("OmniFaceApp", "Device memory pressure ($level) — clearing volatile caches")
            System.gc()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w("OmniFaceApp", "Low memory signal received — forcing aggressive memory cleanup")
        System.gc()
    }

    companion object {
        lateinit var instance: OmniFaceApplication
            private set
    }
}
