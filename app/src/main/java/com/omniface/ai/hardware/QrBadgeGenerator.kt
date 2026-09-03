package com.omniface.ai.hardware

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrBadgeGenerator {

    /**
     * Generates a pure ZXing BitMatrix without Android graphics dependencies (JVM testable).
     */
    fun generateBitMatrix(
        content: String,
        sizePx: Int = 512
    ): com.google.zxing.common.BitMatrix? {
        if (content.isBlank()) return null
        return try {
            val hints = mapOf(
                EncodeHintType.CHARACTER_SET to "UTF-8",
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
                EncodeHintType.MARGIN to 1
            )
            QRCodeWriter().encode(
                content.trim().uppercase(),
                BarcodeFormat.QR_CODE,
                sizePx,
                sizePx,
                hints
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Generates a crisp, high-resolution QR code Bitmap for a student roll number or ID token.
     */
    fun generateStudentQrBitmap(
        content: String,
        sizePx: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        val bitMatrix = generateBitMatrix(content, sizePx) ?: return null
        return try {
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) foregroundColor else backgroundColor
                }
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (_: Exception) {
            null
        }
    }
}
