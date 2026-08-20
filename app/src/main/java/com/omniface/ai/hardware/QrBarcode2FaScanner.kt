package com.omniface.ai.hardware

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

enum class TwoFactorStatus {
    TWO_FA_PASS,
    TWO_FA_MISMATCH_FRAUD,
    TWO_FA_FACE_ONLY_FALLBACK
}

data class TwoFactorResult(
    val status: TwoFactorStatus,
    val scannedCardRoll: String? = null,
    val isCardPresent: Boolean = false,
    val message: String = ""
)

object QrBarcode2FaScanner {

    private val scanner = BarcodeScanning.getClient()
    var isTwoFactorModeEnabled: Boolean = true

    fun scanCardQrFromBitmap(
        bitmap: Bitmap,
        onResult: (String?) -> Unit
    ) {
        if (!isTwoFactorModeEnabled) {
            onResult(null)
            return
        }

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
        matchedFaceRoll: String
    ): TwoFactorResult {
        if (!isTwoFactorModeEnabled || scannedCardRoll.isNullOrBlank()) {
            return TwoFactorResult(
                status = TwoFactorStatus.TWO_FA_FACE_ONLY_FALLBACK,
                isCardPresent = false,
                message = "Face Biometric Verified"
            )
        }

        val cardClean = scannedCardRoll.trim().uppercase()
        val faceClean = matchedFaceRoll.trim().uppercase()

        return if (cardClean == faceClean) {
            TwoFactorResult(
                status = TwoFactorStatus.TWO_FA_PASS,
                scannedCardRoll = cardClean,
                isCardPresent = true,
                message = "2FA Verified: ID Card ($cardClean) Matches Face"
            )
        } else {
            TwoFactorResult(
                status = TwoFactorStatus.TWO_FA_MISMATCH_FRAUD,
                scannedCardRoll = cardClean,
                isCardPresent = true,
                message = "🚨 2FA FRAUD: ID Card ($cardClean) does not belong to this person ($faceClean)"
            )
        }
    }
}
