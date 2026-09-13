import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "com.omniface.ai"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.omniface.ai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "2.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.testLogging {
                events("passed", "skipped", "failed")
            }
        }
    }

    // ── Release Signing: credentials loaded from keystore.properties (gitignored)
    // or environment variables — never hardcoded in VCS.
    val keystoreProps = Properties().apply {
        val propsFile = rootProject.file("keystore.properties")
        if (propsFile.exists()) propsFile.inputStream().use { load(it) }
    }
    val releaseStoreFilePath = keystoreProps.getProperty("storeFile")
        ?: System.getenv("OMNIFACE_STORE_FILE") ?: ""
    val releaseStorePassword = keystoreProps.getProperty("storePassword")
        ?: System.getenv("OMNIFACE_STORE_PASSWORD") ?: ""
    val releaseKeyAlias = keystoreProps.getProperty("keyAlias")
        ?: System.getenv("OMNIFACE_KEY_ALIAS") ?: ""
    val releaseKeyPassword = keystoreProps.getProperty("keyPassword")
        ?: System.getenv("OMNIFACE_KEY_PASSWORD") ?: ""

    signingConfigs {
        create("debugConfig") {
            storeFile = file("${rootDir}/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseStoreFilePath.isNotBlank()) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseStoreFilePath.isNotBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debugConfig")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            keepDebugSymbols += "**/*.so"
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // AndroidX Core & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Jetpack Compose & Material 3
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)

    // CameraX 30-60 FPS Ingestion
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Google ML Kit Face, Text OCR & 2D Barcode Scanning
    implementation(libs.face.detection)
    implementation(libs.text.recognition)
    implementation(libs.barcode.scanning)
    implementation(libs.core)

    // LiteRT (Google's official successor to TensorFlow Lite)
    implementation(libs.litert)
    implementation(libs.litert.gpu)
    implementation(libs.litert.api)

    // Room SQLite Offline Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // WorkManager Cloud Sync
    implementation(libs.androidx.work.runtime.ktx)

    // Secure Network & Model Downloader
    implementation(libs.okhttp)

    // EncryptedSharedPreferences for HMAC secrets & HF token vault
    implementation(libs.androidx.security.crypto)

    // AndroidX Biometric Prompt for Device Inbuilt Fingerprint / Face / Screen Lock
    implementation(libs.androidx.biometric)

    // Google Play Services Auth for User-Owned Google Drive Backup
    implementation(libs.play.services.auth)

    // Google Play Billing Library
    implementation(libs.billing.ktx)

    // Google Mobile Ads SDK (AdMob)
    implementation(libs.play.services.ads)

    // Firebase
    val firebaseBom = platform(libs.firebase.bom)
    implementation(firebaseBom)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
