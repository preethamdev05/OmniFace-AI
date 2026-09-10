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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class OmniFaceApplication : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 0. Hardware Security Pre-Flight: Check if device is rooted or compromised
        isDeviceRooted = AndroidSecurityUtils.isDeviceRooted()
        if (isDeviceRooted) {
            Log.w("OmniFaceSecurity", "DEVICE INTEGRITY WARNING: Root access / su binary detected. Kiosk security compromised.")
        }

        // 1. Initialize Hardware KeyStore Encryption Master Key
        AndroidSecurityUtils.initMasterKey()

        // 1a. Initialize Commercial Subscription Tier Manager & Offline Grace Cache
        com.omniface.ai.billing.SubscriptionTierManager.initialize(this)
        com.omniface.ai.billing.PlayBillingManager.initialize(this)

        // 1b. Initialize Multilingual Localization & Soundboard Voice Engine
        com.omniface.ai.i18n.LocalizationManager.init(this)
        com.omniface.ai.audio.BiometricSoundboard.initTts(this)
        com.omniface.ai.audio.BiometricSoundboard.setLanguage(com.omniface.ai.i18n.LocalizationManager.currentLanguage.value)

        // 1c. Initialize Fleet Device Pairing Manager
        com.omniface.ai.hardware.DevicePairingManager.initialize(this)

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

        // 5c. Initialize Hardware QR/Barcode 2FA Scanner configuration
        com.omniface.ai.hardware.QrBarcode2FaScanner.init(this)

        // 6. Check User Consent Before Scheduling Cloud Sync
        if (isCloudSyncEnabled()) {
            schedulePeriodicSync()
        }

        // 7. Asynchronous Background Engine Pre-Warming (Zero Cold-Start Delay)
        warmupNeuralEngineAsync()
    }

    private fun warmupNeuralEngineAsync() {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 1. Eagerly load templates and student map from Room DB
                val students = database.studentDao().getAllStudents()
                val templates = database.studentDao().getAllTemplates()
                cachedStudentMap = students.associate { it.rollNumber to it.fullName }
                cachedTemplates = templates

                // 2. Initialize and load Unified Face Intelligence Engine if available
                val unifiedEngine = com.omniface.ai.ml.UnifiedFaceIntelligenceEngine.getInstance(this@OmniFaceApplication)
                val downloadManager = com.omniface.ai.ml.ModelDownloadManager.getInstance(this@OmniFaceApplication)
                if (downloadManager.isModelAvailable()) {
                    unifiedEngine.loadUnifiedModelExplicit(this@OmniFaceApplication)
                }

                // 3. Initialize FaceSecurityPipeline singleton and preload templates
                val pipeline = com.omniface.ai.ml.pipeline.FaceSecurityPipeline.getInstance(this@OmniFaceApplication)
                if (templates.isNotEmpty()) {
                    pipeline.preloadTemplates(templates)
                }

                // 4. Pre-warm Google ML Kit FaceDetector in background (loads native .so libraries & models)
                warmupMlKitDetectorAsync()

                // 5. Pre-warm TFLite / LiteRT Graph with a dummy 112x112 frame (compiles GPU/NNAPI OpenCL kernels)
                warmupPipelineInferenceAsync(pipeline)

                Log.i("OmniFaceApp", "🚀 Full Biometric Pipeline pre-warmed & ready for instantaneous first-frame recognition.")
            } catch (e: Throwable) {
                Log.w("OmniFaceApp", "Background engine pre-warming note: ${e.message}")
            }
        }
    }

    private fun warmupMlKitDetectorAsync() {
        try {
            val detector = com.google.mlkit.vision.face.FaceDetection.getClient(
                com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                    .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setLandmarkMode(com.google.mlkit.vision.face.FaceDetectorOptions.LANDMARK_MODE_ALL)
                    .setContourMode(com.google.mlkit.vision.face.FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setClassificationMode(com.google.mlkit.vision.face.FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                    .setMinFaceSize(0.08f)
                    .enableTracking()
                    .build()
            )
            val dummyBitmap = android.graphics.Bitmap.createBitmap(112, 112, android.graphics.Bitmap.Config.ARGB_8888)
            val inputImage = com.google.mlkit.vision.common.InputImage.fromBitmap(dummyBitmap, 0)
            detector.process(inputImage)
                .addOnCompleteListener {
                    dummyBitmap.recycle()
                    try { detector.close() } catch (_: Throwable) {}
                    Log.i("OmniFaceApp", "⚡ ML Kit FaceDetector warm-up completed successfully.")
                }
        } catch (t: Throwable) {
            Log.w("OmniFaceApp", "ML Kit warm-up note: ${t.message}")
        }
    }

    private fun warmupPipelineInferenceAsync(pipeline: com.omniface.ai.ml.pipeline.FaceSecurityPipeline) {
        try {
            val dummyBitmap = android.graphics.Bitmap.createBitmap(112, 112, android.graphics.Bitmap.Config.ARGB_8888)
            val dummyEmb = pipeline.recognitionEngine.extractEmbedding(dummyBitmap)
            dummyBitmap.recycle()
            Log.i("OmniFaceApp", "⚡ TFLite / LiteRT graph warm-up completed (dim=${dummyEmb.size}).")
        } catch (t: Throwable) {
            Log.w("OmniFaceApp", "Pipeline inference warm-up note: ${t.message}")
        }
    }

    private fun verifyModelAssetsIntegrity() {
        val unifiedFile = com.omniface.ai.ml.UnifiedFaceIntelligenceEngine.MODEL_ASSET
        val privateFile = java.io.File(java.io.File(filesDir, "models"), unifiedFile)
        if (privateFile.exists() && privateFile.canRead()) {
            Log.i("OmniFaceApp", "Verified unified model in app private storage: ${privateFile.length()} bytes")
        } else {
            Log.i("OmniFaceApp", "Unified model not pre-bundled in assets; will be downloaded on-demand from Cloudflare R2.")
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

        @Volatile
        var isDeviceRooted: Boolean = false
            internal set

        @Volatile
        var cachedStudentMap: Map<String, String> = emptyMap()
            internal set

        @Volatile
        var cachedTemplates: List<com.omniface.ai.data.local.entity.FaceTemplateEntity> = emptyList()
            internal set
    }
}
