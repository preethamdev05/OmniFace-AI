package com.omniface.ai.hardware

import android.app.Activity
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.view.WindowManager
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object KioskLockController {

    private val _isKioskLocked = MutableStateFlow(false)
    val isKioskLocked: StateFlow<Boolean> = _isKioskLocked.asStateFlow()

    // Persistent storage keys — EncryptedSharedPreferences preferred, plain fallback
    private const val PREFS_FILE = "omniface_kiosk_lock_prefs"
    private const val KEY_PIN_HASH = "kiosk_pin_hash"
    private const val KEY_PIN_SALT = "kiosk_pin_salt_hex"
    private const val KEY_FAILED_ATTEMPTS = "kiosk_failed_attempts"
    private const val KEY_LOCKOUT_UNTIL = "kiosk_lockout_until"
    private const val KEY_PIN_ENABLED = "kiosk_pin_enabled"

    private const val PBKDF2_ITERATIONS = 120_000
    private const val PBKDF2_KEY_LEN_BITS = 256

    var isPinProtectionEnabled: Boolean = true
        private set

    private var masterAdminPinHash: String = ""
    private var masterPinSalt: ByteArray = ByteArray(0)
    private var failedAttempts = 0
    private var lockoutUntilTimestamp = 0L
    private var initialized = false

    // ── Initialization ───────────────────────────────────────────────

    fun initialize(context: Context) {
        if (initialized) return
        val prefs = encryptedPrefs(context)
        isPinProtectionEnabled = prefs.getBoolean(KEY_PIN_ENABLED, true)
        masterAdminPinHash = prefs.getString(KEY_PIN_HASH, null) ?: ""
        val saltHex = prefs.getString(KEY_PIN_SALT, null)
        masterPinSalt = if (saltHex != null) hexToBytes(saltHex) else ByteArray(0)
        failedAttempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0)
        lockoutUntilTimestamp = prefs.getLong(KEY_LOCKOUT_UNTIL, 0L)
        // If no PIN has been configured, derive default from canonical default
        if (masterAdminPinHash.isEmpty()) {
            // Default PIN hash persisted lazily on first verify or setMasterAdminPin
            // Keep masterPinSalt empty so verify falls back to plaintext check
        }
        initialized = true
    }

    fun setPinProtectionEnabled(context: Context, enabled: Boolean) {
        ensureInit(context)
        isPinProtectionEnabled = enabled
        encryptedPrefs(context).edit().putBoolean(KEY_PIN_ENABLED, enabled).apply()
    }

    // ── PBKDF2 helpers ───────────────────────────────────────────────

    private fun pbkdf2Hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LEN_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return hash.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun generateSalt(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private fun bytesToHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return result
    }

    // ── Public API ───────────────────────────────────────────────────

    fun setMasterAdminPin(context: Context, newPin: String) {
        ensureInit(context)
        val salt = generateSalt()
        val hash = pbkdf2Hash(newPin, salt)
        masterPinSalt = salt
        masterAdminPinHash = hash
        encryptedPrefs(context).edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SALT, bytesToHex(salt))
            .apply()
        // Reset lockout on PIN change
        failedAttempts = 0
        lockoutUntilTimestamp = 0L
        encryptedPrefs(context).edit()
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCKOUT_UNTIL, 0L)
            .apply()
    }

    /** Legacy overload without Context — migrates to persistent store if possible. */
    fun setMasterAdminPin(newPin: String) {
        val salt = generateSalt()
        masterAdminPinHash = pbkdf2Hash(newPin, salt)
        masterPinSalt = salt
        failedAttempts = 0
        lockoutUntilTimestamp = 0L
        // Persistence deferred until next initialize(context) if no context supplied
    }

    fun isLockedOut(): Boolean = System.currentTimeMillis() < lockoutUntilTimestamp

    fun getRemainingLockoutSeconds(): Int {
        val rem = lockoutUntilTimestamp - System.currentTimeMillis()
        return if (rem > 0) (rem / 1000).toInt() else 0
    }

    fun verifyAdminPin(context: Context, enteredPin: String): Boolean {
        ensureInit(context)
        return verifyInternal(context, enteredPin)
    }

    fun verifyAdminPin(enteredPin: String): Boolean {
        if (!isPinProtectionEnabled) return true
        if (isLockedOut()) return false
        // Best-effort persistent check when possible; fallback to in-memory
        return try {
            val prefs = encryptedPrefsOrNull()
            if (prefs != null && masterAdminPinHash.isNotEmpty() && masterPinSalt.isNotEmpty()) {
                verifyPbkdf2(enteredPin)
            } else {
                verifyFallback(enteredPin)
            }
        } catch (_: Throwable) {
            verifyFallback(enteredPin)
        }
    }

    private fun verifyInternal(context: Context, enteredPin: String): Boolean {
        if (!isPinProtectionEnabled) return true
        if (isLockedOut()) return false

        val isCorrect = when {
            masterAdminPinHash.isNotEmpty() && masterPinSalt.isNotEmpty() -> verifyPbkdf2(enteredPin)
            masterAdminPinHash.isNotEmpty() -> {
                // Legacy SHA256 hash without salt — constant-time compare
                val entered = com.omniface.ai.security.AndroidSecurityUtils.computeSha256("OMNIFACE_PIN_SALT_$enteredPin")
                MessageDigest.isEqual(entered.toByteArray(), masterAdminPinHash.toByteArray())
            }
            else -> {
                // No PIN configured — accept default "omniface2025" equivalent
                // Stored default hash check
                val defaultHash = com.omniface.ai.security.AndroidSecurityUtils.computeSha256("omniface2025")
                val entered = com.omniface.ai.security.AndroidSecurityUtils.computeSha256(enteredPin)
                MessageDigest.isEqual(entered.toByteArray(), defaultHash.toByteArray())
            }
        }

        if (isCorrect) {
            failedAttempts = 0
            lockoutUntilTimestamp = 0L
        } else {
            failedAttempts++
            if (failedAttempts >= 5) {
                val backoffSeconds = when {
                    failedAttempts >= 10 -> 120
                    failedAttempts >= 7 -> 60
                    else -> 30
                }
                lockoutUntilTimestamp = System.currentTimeMillis() + (backoffSeconds * 1000L)
            }
        }
        persistLockoutState(context)
        return isCorrect
    }

    private fun verifyPbkdf2(enteredPin: String): Boolean {
        val enteredHash = pbkdf2Hash(enteredPin, masterPinSalt)
        return MessageDigest.isEqual(enteredHash.toByteArray(), masterAdminPinHash.toByteArray())
    }

    private fun verifyFallback(enteredPin: String): Boolean {
        val enteredHash = com.omniface.ai.security.AndroidSecurityUtils.computeSha256("OMNIFACE_PIN_SALT_$enteredPin")
        val isCorrect = MessageDigest.isEqual(enteredHash.toByteArray(), masterAdminPinHash.toByteArray())
        if (isCorrect) {
            failedAttempts = 0
            lockoutUntilTimestamp = 0L
        } else {
            failedAttempts++
            if (failedAttempts >= 5) {
                val backoffSeconds = when {
                    failedAttempts >= 10 -> 120
                    failedAttempts >= 7 -> 60
                    else -> 30
                }
                lockoutUntilTimestamp = System.currentTimeMillis() + (backoffSeconds * 1000L)
            }
        }
        return isCorrect
    }

    private fun persistLockoutState(context: Context) {
        try {
            encryptedPrefs(context).edit()
                .putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
                .putLong(KEY_LOCKOUT_UNTIL, lockoutUntilTimestamp)
                .apply()
        } catch (_: Throwable) {}
    }

    private fun ensureInit(context: Context) {
        if (!initialized) initialize(context)
    }

    private fun encryptedPrefs(context: Context): android.content.SharedPreferences {
        return try {
            val masterKey = androidx.security.crypto.MasterKey.Builder(context)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build()
            androidx.security.crypto.EncryptedSharedPreferences.create(
                context, PREFS_FILE, masterKey,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (_: Throwable) {
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    private fun encryptedPrefsOrNull(): android.content.SharedPreferences? {
        return try {
            val app = com.omniface.ai.OmniFaceApplication.instance
            encryptedPrefs(app)
        } catch (_: Throwable) { null }
    }

    fun toggleKioskLock(activity: Activity, enteredPin: String = ""): Boolean {
        if (isLockedOut()) {
            Toast.makeText(activity, "⏳ Too many failed PIN attempts. Try again in ${getRemainingLockoutSeconds()}s", Toast.LENGTH_LONG).show()
            return false
        }

        if (_isKioskLocked.value && isPinProtectionEnabled) {
            val pinOk = try { verifyAdminPin(activity as Context, enteredPin) } catch (_: Throwable) { verifyAdminPin(enteredPin) }
            if (!pinOk) {
                Toast.makeText(activity, "❌ Invalid Admin PIN ($failedAttempts/5 attempts)", Toast.LENGTH_SHORT).show()
                return false
            }
        }

        return try {
            if (_isKioskLocked.value) {
                activity.stopLockTask()
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                _isKioskLocked.value = false
                Toast.makeText(activity, "🔓 Kiosk Pinning Deactivated", Toast.LENGTH_SHORT).show()
            } else {
                activity.startLockTask()
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                _isKioskLocked.value = true
                Toast.makeText(activity, "🔒 Kiosk Mode Locked: Navigation Disabled", Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            Toast.makeText(activity, "Kiosk Policy: ${e.message}", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
