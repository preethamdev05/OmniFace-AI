package com.omniface.ai.ui.enrollment

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.regex.Pattern

data class ParsedIdCard(
    val rollNumber: String? = null,
    val fullName: String? = null,
    val department: String? = null,
    val rawText: String = ""
)

object IdCardOcrScanner {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val rollPattern = Pattern.compile("(?i)(?:roll(?:\\s*no)?|id|reg(?:\\s*no)?)[\\s:.-]*([a-z0-9]{5,12})|\\b(\\d{2}[a-z]{2,4}\\d{2,4})\\b")
    private val namePattern = Pattern.compile("(?i)(?:name|student(?:\\s*name)?)[\\s:.-]*([A-Za-z\\s.]{3,30})")

    fun parseIdCardFromBitmap(
        bitmap: Bitmap,
        onResult: (ParsedIdCard) -> Unit
    ) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                var extractedRoll: String? = null
                var extractedName: String? = null
                var extractedDept: String? = null

                // Search line by line
                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()

                        // Check Roll Number regex
                        val rollMatcher = rollPattern.matcher(text)
                        if (rollMatcher.find() && extractedRoll == null) {
                            extractedRoll = rollMatcher.group(1) ?: rollMatcher.group(2)
                        }

                        // Check Name regex
                        val nameMatcher = namePattern.matcher(text)
                        if (nameMatcher.find() && extractedName == null) {
                            extractedName = nameMatcher.group(1)?.trim()
                        }

                        // Check Department keywords
                        val lower = text.lowercase()
                        if (extractedDept == null) {
                            when {
                                lower.contains("computer science") || lower.contains("cse") -> extractedDept = "Computer Science"
                                lower.contains("bca") -> extractedDept = "BCA"
                                lower.contains("mca") -> extractedDept = "MCA"
                                lower.contains("information technology") || lower.contains("it") -> extractedDept = "Information Tech"
                                lower.contains("artificial intelligence") || lower.contains("ai") -> extractedDept = "AI & Data Science"
                                lower.contains("mechanical") -> extractedDept = "Mechanical Eng"
                                lower.contains("electronics") || lower.contains("ece") -> extractedDept = "Electronics (ECE)"
                            }
                        }
                    }
                }

                // Fallback: If roll was not found by prefix, search all tokens
                if (extractedRoll == null) {
                    val tokens = fullText.split("\\s+".toRegex())
                    for (token in tokens) {
                        val clean = token.replace("[^A-Za-z0-9]".toRegex(), "")
                        if (clean.matches("\\d{2}[A-Za-z]{2,4}\\d{2,4}".toRegex()) || clean.matches("STU-\\d+".toRegex())) {
                            extractedRoll = clean.uppercase()
                            break
                        }
                    }
                }

                onResult(
                    ParsedIdCard(
                        rollNumber = extractedRoll?.uppercase(),
                        fullName = extractedName,
                        department = extractedDept,
                        rawText = fullText
                    )
                )
            }
            .addOnFailureListener {
                onResult(ParsedIdCard(rawText = "OCR Error: ${it.message}"))
            }
    }
}
