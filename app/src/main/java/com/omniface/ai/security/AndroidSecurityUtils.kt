package com.omniface.ai.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object AndroidSecurityUtils {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "OmniFaceMasterKey"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH = 128
    private const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH / 8 // 16 bytes
    private const val IV_LENGTH = 12 // 12 bytes
    const val MIN_CIPHERTEXT_LENGTH = IV_LENGTH + GCM_TAG_LENGTH_BYTES // 28 bytes minimum

    const val AEGIS_GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

    @Volatile
    private var cachedSecretKey: SecretKey? = null
    private val keyLock = Any()

    var isStrongBoxActive: Boolean = false
        private set

    fun initMasterKey() {
        synchronized(keyLock) {
            try {
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
                            val key = keyGenerator.generateKey()
                            cachedSecretKey = key
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
                        val key = keyGenerator.generateKey()
                        cachedSecretKey = key
                        isStrongBoxActive = false
                    } catch (e: Exception) {
                        // Keystore init fallback
                    }
                }
            } catch (t: Throwable) {
                // In non-Android host JVM unit test environment, fallback to software AES key
                if (cachedSecretKey == null) {
                    try {
                        val keyGen = KeyGenerator.getInstance("AES")
                        keyGen.init(256)
                        cachedSecretKey = keyGen.generateKey()
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    /**
     * Invalidate in-memory cached SecretKey (e.g. on keystore reset or during unit testing).
     */
    fun invalidateKeyCache() {
        synchronized(keyLock) {
            cachedSecretKey = null
        }
    }

    /**
     * Set a custom secret key (primarily for unit testing across platforms).
     */
    fun setCustomSecretKeyForTesting(key: SecretKey?) {
        synchronized(keyLock) {
            cachedSecretKey = key
        }
    }

    private fun getSecretKey(): SecretKey {
        cachedSecretKey?.let { return it }
        return synchronized(keyLock) {
            cachedSecretKey?.let { return it }
            val key = try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            } catch (t: Throwable) {
                null
            } ?: run {
                initMasterKey()
                try {
                    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                    keyStore.getKey(KEY_ALIAS, null) as? SecretKey
                } catch (t: Throwable) {
                    null
                }
            } ?: cachedSecretKey ?: run {
                // Host JVM fallback for unit tests
                val keyGen = KeyGenerator.getInstance("AES")
                keyGen.init(256)
                val fallbackKey = keyGen.generateKey()
                fallbackKey
            }
            cachedSecretKey = key
            key
        }
    }

    private fun decodeBase64Safe(input: String): ByteArray? {
        return try {
            java.util.Base64.getDecoder().decode(input)
        } catch (_: Throwable) {
            try {
                Base64.decode(input, Base64.NO_WRAP)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun encodeBase64Safe(bytes: ByteArray): String {
        return try {
            java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            try {
                Base64.encodeToString(bytes, Base64.NO_WRAP)
            } catch (_: Throwable) {
                ""
            }
        }
    }

    fun encrypt(plainBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
        val iv = cipher.iv
        val encryption = cipher.doFinal(plainBytes)
        val combined = ByteArray(iv.size + encryption.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryption, 0, combined, iv.size, encryption.size)
        return combined
    }

    fun decrypt(ciphertextCombined: ByteArray): ByteArray {
        if (ciphertextCombined.size < MIN_CIPHERTEXT_LENGTH) return ByteArray(0)
        val iv = ByteArray(IV_LENGTH)
        val ciphertext = ByteArray(ciphertextCombined.size - IV_LENGTH)
        System.arraycopy(ciphertextCombined, 0, iv, 0, IV_LENGTH)
        System.arraycopy(ciphertextCombined, IV_LENGTH, ciphertext, 0, ciphertext.size)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            cipher.doFinal(ciphertext)
        } catch (_: Throwable) {
            ByteArray(0)
        }
    }

    fun encrypt(plainText: String): String {
        val plainBytes = plainText.toByteArray(Charsets.UTF_8)
        val combined = encrypt(plainBytes)
        java.util.Arrays.fill(plainBytes, 0.toByte())
        return encodeBase64Safe(combined)
    }

    fun decrypt(encryptedBase64: String): String {
        if (encryptedBase64.isBlank()) return ""
        val combined = decodeBase64Safe(encryptedBase64) ?: return ""
        val decryptedBytes = decrypt(combined)
        if (decryptedBytes.isEmpty()) return ""
        val result = String(decryptedBytes, Charsets.UTF_8)
        java.util.Arrays.fill(decryptedBytes, 0.toByte())
        return result
    }

    fun computeSha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * Computes an Aegis SHA-256 Blockchain Block Hash.
     * Formula: H_i = SHA-256(H_{i-1} || studentRoll || timestamp || confidence)
     */
    fun computeAegisBlockHash(
        previousHash: String?,
        studentRoll: String,
        timestamp: Long,
        confidencePct: Float
    ): String {
        val prev = if (previousHash.isNullOrBlank()) AEGIS_GENESIS_HASH else previousHash
        return computeSha256("${prev}|${studentRoll}|${timestamp}|${confidencePct}")
    }

    /**
     * Verifies the cryptographic chain integrity of a sequential list of attendance records (oldest to newest).
     * Returns true if all hashes match their expected SHA-256 chain, false if any block is tampered with.
     */
    fun verifyChainIntegrity(records: List<com.omniface.ai.data.local.entity.AttendanceRecordEntity>): Boolean {
        if (records.isEmpty()) return true
        var prevHash = AEGIS_GENESIS_HASH
        for (record in records) {
            val expectedHash = computeAegisBlockHash(
                previousHash = prevHash,
                studentRoll = record.studentRoll,
                timestamp = record.timestamp,
                confidencePct = record.confidencePct
            )
            if (!record.sha256Hash.equals(expectedHash, ignoreCase = true)) {
                return false
            }
            prevHash = record.sha256Hash
        }
        return true
    }

    /**
     * Computes an HMAC-SHA256 hex digest for request signing and server verification.
     */
    fun computeHmacSha256(secret: String, data: String): String {
        val secretKeySpec = javax.crypto.spec.SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(secretKeySpec)
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /**
     * Constant-time verification of HMAC-SHA256 signature.
     */
    fun verifyHmacSha256(secret: String, data: String, signature: String): Boolean {
        val expected = computeHmacSha256(secret, data)
        return MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            signature.toByteArray(Charsets.UTF_8)
        ) || MessageDigest.isEqual(
            expected.lowercase().toByteArray(Charsets.UTF_8),
            signature.lowercase().toByteArray(Charsets.UTF_8)
        )
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
        val generated = randomBytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
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

    // ── Encrypted Database Backup & WAL Checkpoint ─────────────────────────────
    val BACKUP_MAGIC_BYTES = byteArrayOf(0x4F, 0x4D, 0x4E, 0x49) // "OMNI"
    const val BACKUP_VERSION_V1: Byte = 1
    const val BACKUP_HEADER_SIZE = 48 // 4 (magic) + 1 (version) + 3 (flags) + 8 (plainLen) + 32 (sha256)

    /**
     * Executes SQLite WAL Checkpoint (PRAGMA wal_checkpoint(FULL)) on the Room database.
     * Ensures all pending writes in the -wal journal are committed and flushed to the master .db file.
     */
    fun checkpointWal(database: RoomDatabase): Boolean {
        return try {
            database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL);").use { cursor ->
                if (cursor.moveToFirst()) {
                    val busy = cursor.getInt(0)
                    val checkpointed = cursor.getInt(1)
                    val total = cursor.getInt(2)
                    android.util.Log.i("AndroidSecurityUtils", "WAL checkpoint executed: busy=$busy, checkpointed=$checkpointed, logPages=$total")
                }
            }
            true
        } catch (t: Throwable) {
            android.util.Log.w("AndroidSecurityUtils", "Room WAL checkpoint warning: ${t.message}")
            false
        }
    }

    /**
     * Executes SQLite WAL Checkpoint directly on SupportSQLiteDatabase instance.
     */
    fun checkpointWal(db: SupportSQLiteDatabase): Boolean {
        return try {
            db.query("PRAGMA wal_checkpoint(FULL);").use { cursor ->
                if (cursor.moveToFirst()) {
                    val busy = cursor.getInt(0)
                    val checkpointed = cursor.getInt(1)
                    val total = cursor.getInt(2)
                    android.util.Log.i("AndroidSecurityUtils", "SupportSQLiteDatabase WAL checkpoint executed: busy=$busy, checkpointed=$checkpointed, logPages=$total")
                }
            }
            true
        } catch (t: Throwable) {
            android.util.Log.w("AndroidSecurityUtils", "SupportSQLiteDatabase WAL checkpoint warning: ${t.message}")
            false
        }
    }

    /**
     * Creates an AES-256-GCM envelope-encrypted binary payload from raw database bytes.
     * Encapsulates:
     * - 4 bytes magic ('O', 'M', 'N', 'I')
     * - 1 byte version (0x01)
     * - 3 reserved/flag bytes
     * - 8 bytes original plaintext size (Long)
     * - 32 bytes SHA-256 integrity hash of unencrypted database
     * - AES-256-GCM ciphertext with prepended 12-byte IV and appended 16-byte authentication tag
     */
    fun createEncryptedBackupPayload(plainBytes: ByteArray): ByteArray {
        val sha256Digest = MessageDigest.getInstance("SHA-256").digest(plainBytes)
        val encryptedData = encrypt(plainBytes)

        val buffer = ByteBuffer.allocate(BACKUP_HEADER_SIZE + encryptedData.size)
        buffer.put(BACKUP_MAGIC_BYTES)
        buffer.put(BACKUP_VERSION_V1)
        buffer.put(0.toByte()).put(0.toByte()).put(0.toByte())
        buffer.putLong(plainBytes.size.toLong())
        buffer.put(sha256Digest)
        buffer.put(encryptedData)
        return buffer.array()
    }

    /**
     * Decrypts and authenticates an AES-256-GCM envelope-encrypted database backup payload.
     * Validates magic bytes, version, GCM authentication tag, and SHA-256 plaintext integrity checksum.
     */
    fun decryptBackupPayload(envelopeBytes: ByteArray): ByteArray {
        if (envelopeBytes.size < BACKUP_HEADER_SIZE + MIN_CIPHERTEXT_LENGTH) {
            throw GeneralSecurityException("Invalid backup payload: size (${envelopeBytes.size} bytes) is below minimum envelope threshold")
        }
        val buffer = ByteBuffer.wrap(envelopeBytes)
        val magic = ByteArray(4)
        buffer.get(magic)
        if (!magic.contentEquals(BACKUP_MAGIC_BYTES)) {
            throw GeneralSecurityException("Invalid backup archive: Header magic bytes mismatch")
        }
        val version = buffer.get()
        if (version != BACKUP_VERSION_V1) {
            throw GeneralSecurityException("Unsupported backup archive version: $version")
        }
        buffer.position(buffer.position() + 3) // Skip reserved bytes
        val expectedPlainSize = buffer.long
        val expectedSha256 = ByteArray(32)
        buffer.get(expectedSha256)

        val encryptedPayload = ByteArray(buffer.remaining())
        buffer.get(encryptedPayload)

        val decryptedBytes = decrypt(encryptedPayload)
        if (decryptedBytes.isEmpty()) {
            throw GeneralSecurityException("Decryption or AES-256-GCM authentication tag verification failed")
        }
        if (decryptedBytes.size.toLong() != expectedPlainSize) {
            throw GeneralSecurityException("Decrypted payload size mismatch: expected $expectedPlainSize, got ${decryptedBytes.size}")
        }
        val computedSha256 = MessageDigest.getInstance("SHA-256").digest(decryptedBytes)
        if (!MessageDigest.isEqual(computedSha256, expectedSha256)) {
            throw GeneralSecurityException("SHA-256 cryptographic checksum mismatch on decrypted database")
        }
        return decryptedBytes
    }

    /**
     * End-to-end database backup export pipeline:
     * 1. Issues PRAGMA wal_checkpoint(FULL) to flush pending journal pages.
     * 2. Reads raw SQLite database file.
     * 3. Creates AES-256-GCM envelope encrypted payload.
     * 4. Atomically writes to destination backup file.
     */
    fun createEncryptedDatabaseBackup(
        context: Context,
        database: RoomDatabase,
        backupDirectory: File = File(context.filesDir, "backups"),
        dbName: String = "omniface_biometrics.db"
    ): File {
        // 1. Checkpoint WAL
        checkpointWal(database)

        // 2. Read database file
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists() || dbFile.length() == 0L) {
            throw FileNotFoundException("Database file not found or empty at: ${dbFile.absolutePath}")
        }
        val rawBytes = dbFile.readBytes()

        // 3. Create envelope encrypted payload
        val encryptedPayload = createEncryptedBackupPayload(rawBytes)

        // 4. Atomically write to destination file
        if (!backupDirectory.exists()) {
            backupDirectory.mkdirs()
        }
        val timeTag = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val backupFile = File(backupDirectory, "omniface_encrypted_backup_$timeTag.db.enc")
        val tempFile = File(backupDirectory, "temp_backup_$timeTag.tmp")
        tempFile.writeBytes(encryptedPayload)
        if (!tempFile.renameTo(backupFile)) {
            tempFile.copyTo(backupFile, overwrite = true)
            tempFile.delete()
        }
        android.util.Log.i("AndroidSecurityUtils", "🔒 Encrypted database backup created: ${backupFile.absolutePath} (${backupFile.length()} bytes)")
        return backupFile
    }

    /**
     * Restores an encrypted database backup file to the specified target SQLite database file.
     * Verifies GCM auth tag and SHA-256 checksum before writing.
     */
    fun restoreEncryptedDatabaseBackup(
        encryptedBackupFile: File,
        targetDbFile: File
    ): Boolean {
        if (!encryptedBackupFile.exists()) {
            throw FileNotFoundException("Encrypted backup file not found: ${encryptedBackupFile.absolutePath}")
        }
        val envelopeBytes = encryptedBackupFile.readBytes()
        val decryptedBytes = decryptBackupPayload(envelopeBytes)

        targetDbFile.parentFile?.mkdirs()
        val tempFile = File(targetDbFile.parentFile, "${targetDbFile.name}.restore.tmp")
        tempFile.writeBytes(decryptedBytes)
        val success = if (tempFile.renameTo(targetDbFile)) {
            true
        } else {
            tempFile.copyTo(targetDbFile, overwrite = true)
            tempFile.delete()
            true
        }
        android.util.Log.i("AndroidSecurityUtils", "🔓 Encrypted database backup restored to: ${targetDbFile.absolutePath}")
        return success
    }
}
