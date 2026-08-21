# OmniFace AI ProGuard Rules

# ── Room Database ─────────────────────────────────────────────────────────────
-keep class com.omniface.ai.data.local.entity.** { *; }
-keep class com.omniface.ai.data.local.dao.** { *; }
-keep class com.omniface.ai.data.local.AppDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# ── TensorFlow Lite ───────────────────────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.gpu.** { *; }
-dontwarn org.tensorflow.**

# ── Google ML Kit ─────────────────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ── Kotlin Coroutines ─────────────────────────────────────────────────────────
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── OkHttp & Okio ─────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── AndroidX Security (EncryptedSharedPreferences) ───────────────────────────
-keep class androidx.security.crypto.** { *; }

# ── WorkManager ───────────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── Kotlin Serialization ──────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# ── OmniFace Security & ML core (keep names for Keystore alias resolution) ────
-keep class com.omniface.ai.security.AndroidSecurityUtils { *; }
-keep class com.omniface.ai.ml.HfSecureGateway { *; }
-keep class com.omniface.ai.hardware.TurnstileRelayController { *; }
-keep class com.omniface.ai.OmniFaceApplication { *; }

# ── Enums (required for SecurityTier, ThermalState, etc.) ────────────────────
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ── Compose runtime (prevent stripping of internal lambdas) ──────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
