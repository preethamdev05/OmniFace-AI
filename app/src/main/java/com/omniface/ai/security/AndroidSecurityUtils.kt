package com.omniface.ai.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AndroidSecurityUtils {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "OmniFaceMasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val IV_LENGTH = 12

    var isStrongBoxActive: Boolean = false
        private set

    fun initMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            // Attempt 1: StrongBox HSM Hardware Security Module (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setUserAuthenticationRequired(false)
                        .setIsStrongBoxBacked(true)
                        .build()
                    keyGenerator.init(spec)
                    keyGenerator.generateKey()
                    isStrongBoxActive = true
                    return
                } catch (e: Exception) {
                    // Fallback to Standard TEE / Keystore
                }
            }

            // Attempt 2: Standard Hardware Keystore (TEE)
            try {
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setUserAuthenticationRequired(false)
                    .build()
                keyGenerator.init(spec)
                keyGenerator.generateKey()
                isStrongBoxActive = false
            } catch (e: Exception) {
                // Keystore init fallback
            }
        }
    }

    private fun getSecretKey(): SecretKey {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            if (existing != null) return existing
        } catch (t: Throwable) {
            // Key load retry
        }
        initMasterKey()
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getKey(KEY_ALIAS, null) as? SecretKey) ?: run {
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            keyGen.generateKey()
        }
    }


    fun encrypt(plainText: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)
        val encryption = cipher.doFinal(plainBytes)
        java.util.Arrays.fill(plainBytes, 0.toByte())
        val combined = ByteArray(iv.size + encryption.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryption, 0, combined, iv.size, encryption.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decrypt(encryptedBase64: String): String {
        val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
        if (combined.size < IV_LENGTH) return ""
        val iv = ByteArray(IV_LENGTH)
        val ciphertext = ByteArray(combined.size - IV_LENGTH)
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH)
        System.arraycopy(combined, IV_LENGTH, ciphertext, 0, ciphertext.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
        val decryptedBytes = cipher.doFinal(ciphertext)
        val result = String(decryptedBytes, Charsets.UTF_8)
        java.util.Arrays.fill(decryptedBytes, 0.toByte())
        return result
    }

    fun computeSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Deterministic Attendance Merkle Leaf Preimage Constructor
     */
    fun computeAttendanceLeafHash(recordId: String, studentRoll: String, timestamp: Long, confidencePct: Float): String {
        return computeSha256("${recordId}_${studentRoll}_${timestamp}_${confidencePct}")
    }

    /**
     * Aegis Merkle Tree Batch Hash Minting
     * Computes the Merkle Root for a list of leaf hashes.
     */
    fun computeMerkleRoot(hashes: List<String>): String {
        if (hashes.isEmpty()) return computeSha256("OMNIFACE_GENESIS_BLOCK")
        if (hashes.size == 1) return hashes[0]

        var currentLevel = hashes
        while (currentLevel.size > 1) {
            val nextLevel = ArrayList<String>((currentLevel.size + 1) / 2)
            var i = 0
            while (i < currentLevel.size) {
                val left = currentLevel[i]
                val right = if (i + 1 < currentLevel.size) currentLevel[i + 1] else left
                val combinedHash = computeSha256(left + right)
                nextLevel.add(combinedHash)
                i += 2
            }
            currentLevel = nextLevel
        }
        return currentLevel[0]
    }

    // ── Encrypted Preferences Helper ──────────────────────────────────────────
    private const val ENCRYPTED_PREFS_FILE = "omniface_secure_prefs_enc"
    private const val PREF_ADMIN_PIN_HASH = "admin_pin_hash"
    private const val PREF_HMAC_SECRET = "relay_hmac_secret"

    // Default PIN = "omniface2025" — operator must change this via Settings
    private val DEFAULT_PIN_HASH by lazy { computeSha256("omniface2025") }

    /**
     * Opens (or creates) an AES256-GCM EncryptedSharedPreferences backed by the Android Keystore master key.
     * Falls back to plain MODE_PRIVATE prefs on devices that fail MasterKey creation (rare edge cases).
     */
    private fun getEncryptedPrefs(context: Context): android.content.SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            android.util.Log.w("AndroidSecurityUtils", "EncryptedSharedPreferences unavailable, falling back to plain prefs: ${t.message}")
            context.getSharedPreferences(ENCRYPTED_PREFS_FILE, android.content.Context.MODE_PRIVATE)
        }
    }

    /**
     * Returns a per-device HMAC secret for signing webhook payloads.
     * On first call, generates a cryptographically random 64-character hex secret and persists it
     * in EncryptedSharedPreferences. Never returns the old hardcoded literal default.
     */
    fun getOrCreateHmacSecret(context: Context): String {
        val prefs = getEncryptedPrefs(context)
        val existing = prefs.getString(PREF_HMAC_SECRET, null)
        if (!existing.isNullOrBlank() && existing != "OMNIFACE_RELAY_HMAC_SECRET") {
            return existing
        }
        // Generate a 32-byte (256-bit) cryptographically random secret
        val randomBytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(randomBytes)
        val generated = randomBytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_HMAC_SECRET, generated).apply()
        android.util.Log.i("AndroidSecurityUtils", "🔑 New HMAC relay secret generated and stored in encrypted vault.")
        return generated
    }

    /**
     * Returns the SHA-256 hash of the admin PIN stored in EncryptedSharedPreferences.
     * Falls back to the default hash if no PIN has been set yet.
     */
    fun getAdminPinHash(context: Context): String {
        return try {
            getEncryptedPrefs(context).getString(PREF_ADMIN_PIN_HASH, DEFAULT_PIN_HASH) ?: DEFAULT_PIN_HASH
        } catch (e: Exception) {
            android.util.Log.e("AndroidSecurityUtils", "getAdminPinHash failed", e)
            DEFAULT_PIN_HASH
        }
    }

    /**
     * Stores a new admin PIN as its SHA-256 hash in EncryptedSharedPreferences.
     * Call from the Settings screen PIN-change flow.
     */
    fun setAdminPin(context: Context, newPin: String) {
        try {
            getEncryptedPrefs(context).edit().putString(PREF_ADMIN_PIN_HASH, computeSha256(newPin)).apply()
        } catch (e: Exception) {
            android.util.Log.e("AndroidSecurityUtils", "setAdminPin failed", e)
        }
    }

    // ── Root & Device Integrity Attestation ─────────────────────────────────────
    private val KNOWN_ROOT_PATHS = arrayOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su"
    )

    fun isDeviceRooted(): Boolean {
        val buildTags = android.os.Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }
        for (path in KNOWN_ROOT_PATHS) {
            try {
                if (java.io.File(path).exists()) return true
            } catch (_: Throwable) {}
        }
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.destroy()
            line != null
        } catch (_: Throwable) {
            false
        }
    }
}
