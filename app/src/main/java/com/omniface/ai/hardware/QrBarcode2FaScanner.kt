package com.omniface.ai.hardware

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

import android.content.Context
import com.omniface.ai.OmniFaceApplication

enum class TwoFactorStatus {
    TWO_FA_PASS,
    TWO_FA_MISMATCH_FRAUD,
    TWO_FA_FACE_ONLY_FALLBACK,
    TWO_FA_CARD_REQUIRED,
    TWO_FA_TEST_MODE_CARD_OK
}

data class TwoFactorResult(
    val status: TwoFactorStatus,
    val scannedCardRoll: String? = null,
    val isCardPresent: Boolean = false,
    val message: String = ""
)

object QrBarcode2FaScanner {

    private const val PREFS_NAME = "omniface_hardware_prefs"
    private const val KEY_2FA_ENABLED = "qr_2fa_enabled"

    private val scanner by lazy { BarcodeScanning.getClient() }

    @Volatile
    private var _cachedTwoFactorModeEnabled: Boolean? = null

    fun init(context: Context) {
        try {
            val v = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_2FA_ENABLED, true)
            _cachedTwoFactorModeEnabled = v
        } catch (_: Throwable) {}
    }

    var isTwoFactorModeEnabled: Boolean
        get() {
            val cached = _cachedTwoFactorModeEnabled
            if (cached != null) return cached
            val loaded = try {
                val context = OmniFaceApplication.instance
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_2FA_ENABLED, true)
            } catch (_: Throwable) {
                true
            }
            _cachedTwoFactorModeEnabled = loaded
            return loaded
        }
        set(value) {
            _cachedTwoFactorModeEnabled = value
            try {
                val context = OmniFaceApplication.instance
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_2FA_ENABLED, value)
                    .apply()
            } catch (_: Throwable) {}
        }

    fun setTwoFactorEnabled(context: Context?, enabled: Boolean) {
        _cachedTwoFactorModeEnabled = enabled
        if (context != null) {
            try {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_2FA_ENABLED, enabled)
                    .apply()
            } catch (_: Throwable) {}
        }
    }

    fun isTwoFactorEnabled(context: Context?): Boolean {
        if (context != null) {
            try {
                val v = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_2FA_ENABLED, isTwoFactorModeEnabled)
                _cachedTwoFactorModeEnabled = v
                return v
            } catch (_: Throwable) {}
        }
        return isTwoFactorModeEnabled
    }

    fun scanCardQrFromBitmap(
        bitmap: Bitmap,
        onResult: (String?) -> Unit
    ) {
        try {
            val inputImage = InputImage.fromBitmap(bitmap, 0)
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    var cardRoll: String? = null
                    for (barcode in barcodes) {
                        val raw = barcode.rawValue ?: barcode.displayValue
                        if (!raw.isNullOrBlank()) {
                            val clean = raw.trim()
                            // Match Roll number format or ID token
                            if (clean.matches("(?i)[a-z0-9-_]{4,20}".toRegex())) {
                                cardRoll = clean.uppercase()
                                break
                            }
                        }
                    }
                    onResult(cardRoll)
                }
                .addOnFailureListener {
                    onResult(null)
                }
        } catch (e: Exception) {
            onResult(null)
        }
    }

    fun correlateBiometricAndCard(
        scannedCardRoll: String?,
        matchedFaceRoll: String,
        faceSimilarity: Float = 1.0f,
        minHighConfidenceThreshold: Float = 0.72f
    ): TwoFactorResult {
        val cardClean = scannedCardRoll?.trim()?.uppercase()
        val faceClean = matchedFaceRoll.trim().uppercase()

        // 1. If a physical ID card QR is present in the frame
        if (!cardClean.isNullOrBlank()) {
            return if (faceClean.isNotBlank() && cardClean == faceClean) {
                TwoFactorResult(
                    status = TwoFactorStatus.TWO_FA_PASS,
                    scannedCardRoll = cardClean,
                    isCardPresent = true,
                    message = "2FA Verified: ID Card ($cardClean) Matches Face"
                )
            } else if (faceClean.isNotBlank()) {
                TwoFactorResult(
                    status = TwoFactorStatus.TWO_FA_MISMATCH_FRAUD,
                    scannedCardRoll = cardClean,
                    isCardPresent = true,
                    message = "🚨 2FA FRAUD: ID Card ($cardClean) does not belong to this person ($faceClean)"
                )
            } else {
                TwoFactorResult(
                    status = TwoFactorStatus.TWO_FA_CARD_REQUIRED,
                    scannedCardRoll = cardClean,
                    isCardPresent = true,
                    message = "Card Read ($cardClean) • Awaiting Face Correlation"
                )
            }
        }

        // 2. If ID card is NOT present in the frame
        return if (!isTwoFactorModeEnabled || faceSimilarity >= minHighConfidenceThreshold) {
            TwoFactorResult(
                status = TwoFactorStatus.TWO_FA_FACE_ONLY_FALLBACK,
                isCardPresent = false,
                message = "Face Biometric Verified"
            )
        } else {
            TwoFactorResult(
                status = TwoFactorStatus.TWO_FA_CARD_REQUIRED,
                isCardPresent = false,
                message = "Low Confidence • Hold Up ID Card QR"
            )
        }
    }
}
