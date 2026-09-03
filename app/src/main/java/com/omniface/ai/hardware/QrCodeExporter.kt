package com.omniface.ai.hardware

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object QrCodeExporter {

    /**
     * Renders a styled, high-resolution biometric identity card (960x1260 px)
     * containing the student credentials and 2FA QR verification matrix.
     */
    fun createStyledQrPassBitmap(
        rollNumber: String,
        fullName: String,
        department: String = "",
        semester: String = ""
    ): Bitmap? {
        val qrBitmap = QrBadgeGenerator.generateStudentQrBitmap(
            content = rollNumber,
            sizePx = 720,
            foregroundColor = Color.parseColor("#0F172A"),
            backgroundColor = Color.WHITE
        ) ?: return null

        val width = 960
        val height = 1260
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Background - sleek Apple card surface
        val bgPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#F8FAFC")
        }
        val cardRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(cardRect, 48f, 48f, bgPaint)

        // Top Accent Header Banner
        val headerPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#0F172A")
        }
        val headerRect = RectF(0f, 0f, width.toFloat(), 180f)
        canvas.drawRoundRect(headerRect, 48f, 48f, headerPaint)
        // Rectify bottom corners of header
        canvas.drawRect(0f, 120f, width.toFloat(), 180f, headerPaint)

        // Header Title
        val headerTitlePaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#38BDF8")
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            letterSpacing = 0.15f
        }
        canvas.drawText("OMNIFACE BIOMETRIC 2FA PASS", width / 2f, 75f, headerTitlePaint)

        val headerSubPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#94A3B8")
            textSize = 22f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Cryptographic Identity & Kiosk Verification Badge", width / 2f, 125f, headerSubPaint)

        // QR Code Container Box (White with rounded corners)
        val qrBoxPaint = Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
        }
        val qrBorderPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.parseColor("#E2E8F0")
        }
        val qrBoxRect = RectF(100f, 220f, width - 100f, 220f + 760f)
        canvas.drawRoundRect(qrBoxRect, 32f, 32f, qrBoxPaint)
        canvas.drawRoundRect(qrBoxRect, 32f, 32f, qrBorderPaint)

        // Draw QR Code centered inside box
        canvas.drawBitmap(qrBitmap, (width - qrBitmap.width) / 2f, 240f, null)

        // Student Full Name
        val namePaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#0F172A")
            textSize = 42f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(fullName.ifBlank { "Registered Student" }, width / 2f, 1040f, namePaint)

        // Student Roll Number & Department
        val detailsPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#0284C7")
            textSize = 30f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val rollText = "ROLL: $rollNumber"
        val extraText = listOfNotNull(
            department.takeIf { it.isNotBlank() },
            semester.takeIf { it.isNotBlank() }?.let { "Sem $it" }
        ).joinToString(" • ")

        canvas.drawText(rollText, width / 2f, 1095f, detailsPaint)

        if (extraText.isNotBlank()) {
            val extraPaint = Paint().apply {
                isAntiAlias = true
                color = Color.parseColor("#64748B")
                textSize = 24f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(extraText, width / 2f, 1140f, extraPaint)
        }

        // Footer Instruction
        val footerPaint = Paint().apply {
            isAntiAlias = true
            color = Color.parseColor("#94A3B8")
            textSize = 20f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText("Present this QR badge at attendance kiosks for 2FA confirmation", width / 2f, 1200f, footerPaint)

        return bitmap
    }

    /**
     * Saves the 2FA QR badge as a PNG to Pictures/OmniFace in the device gallery.
     */
    fun saveQrCodeToGallery(
        context: Context,
        rollNumber: String,
        fullName: String,
        department: String = "",
        semester: String = ""
    ): Boolean {
        return try {
            val passBitmap = createStyledQrPassBitmap(rollNumber, fullName, department, semester)
                ?: return false

            val filename = "OmniFace_2FA_${rollNumber.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.png"
            val resolver = context.contentResolver

            val imageUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/OmniFace")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        passBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }
                uri
            } else {
                val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val omniFaceDir = File(picturesDir, "OmniFace").apply { mkdirs() }
                val destFile = File(omniFaceDir, filename)
                FileOutputStream(destFile).use { out ->
                    passBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DATA, destFile.absolutePath)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                }
                resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            }

            if (imageUri != null) {
                Toast.makeText(context, "✓ QR Badge saved to Gallery (Pictures/OmniFace)", Toast.LENGTH_LONG).show()
                true
            } else {
                Toast.makeText(context, "Failed to save QR Badge", Toast.LENGTH_SHORT).show()
                false
            }
        } catch (e: Exception) {
            android.util.Log.e("QrCodeExporter", "Save to gallery failed", e)
            Toast.makeText(context, "Save failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
            false
        }
    }

    /**
     * Writes the 2FA QR badge to cache and triggers the native Android system share sheet.
     */
    fun shareQrCode(
        context: Context,
        rollNumber: String,
        fullName: String,
        department: String = "",
        semester: String = ""
    ) {
        try {
            val passBitmap = createStyledQrPassBitmap(rollNumber, fullName, department, semester)
                ?: run {
                    Toast.makeText(context, "Failed to generate QR Badge", Toast.LENGTH_SHORT).show()
                    return
                }

            val cacheDir = File(context.cacheDir, "qr_badges").apply { mkdirs() }
            val cleanRoll = rollNumber.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val imageFile = File(cacheDir, "${cleanRoll}_2fa_qr.png")

            FileOutputStream(imageFile).use { out ->
                passBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, imageFile)

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, "OmniFace 2FA Badge - $fullName ($rollNumber)")
                putExtra(Intent.EXTRA_TEXT, "OmniFace Two-Factor Authentication Biometric QR Pass for $fullName (Roll: $rollNumber).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, "Share 2FA QR Badge")
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
        } catch (e: Exception) {
            android.util.Log.e("QrCodeExporter", "Share QR failed", e)
            Toast.makeText(context, "Share failed: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }
}
