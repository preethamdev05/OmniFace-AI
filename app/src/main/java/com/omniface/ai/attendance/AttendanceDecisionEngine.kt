package com.omniface.ai.attendance

import android.content.Context
import com.omniface.ai.OmniFaceApplication
import com.omniface.ai.data.local.entity.AttendanceRecordEntity
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.MatchResult
import com.omniface.ai.security.AndroidSecurityUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class AttendanceDecision(
    val isGranted: Boolean,
    val confidenceZone: ConfidenceZone,
    val rollNumber: String,
    val studentName: String,
    val confidence: Float,
    val similarity: Float,
    val decisionMargin: Float,
    val qualityScore: Float,
    val livenessScore: Float,
    val activeBackend: String,
    val explanation: String,
    val timestampFormatted: String,
    val statusMessage: String
)

class AttendanceDecisionEngine(private val context: Context) {

    private val db = OmniFaceApplication.instance.database
    private val sessionCoolDown = mutableMapOf<String, Long>()
    private val COOLDOWN_DURATION_MS = 60_000L // 1 minute in-memory cooldown

    suspend fun evaluateAndRecordAttendance(
        matchResult: MatchResult,
        qualityGateResult: QualityGateResult,
        isLive: Boolean,
        livenessScore: Float = if (isLive) 1.0f else 0.0f
    ): AttendanceDecision = withContext(Dispatchers.IO) {
        val qualityScoreVal = (qualityGateResult.blurScore * 10f).coerceIn(0f, 100f)

        // 1. Anti-Spoofing Gate
        if (!isLive) {
            return@withContext AttendanceDecision(
                isGranted = false,
                confidenceZone = ConfidenceZone.REJECT,
                rollNumber = matchResult.studentRoll,
                studentName = matchResult.studentName,
                confidence = matchResult.confidence,
                similarity = matchResult.similarity,
                decisionMargin = matchResult.decisionMargin,
                qualityScore = qualityScoreVal,
                livenessScore = livenessScore,
                activeBackend = matchResult.hardwareTier.label,
                explanation = "Liveness Verification Failed — Spoof attack suspected (2D/screen/static)",
                timestampFormatted = "",
                statusMessage = "Liveness Verification Failed"
            )
        }

        // 2. Quality Gate
        if (!qualityGateResult.isPassed) {
            return@withContext AttendanceDecision(
                isGranted = false,
                confidenceZone = ConfidenceZone.REJECT,
                rollNumber = matchResult.studentRoll,
                studentName = matchResult.studentName,
                confidence = matchResult.confidence,
                similarity = matchResult.similarity,
                decisionMargin = matchResult.decisionMargin,
                qualityScore = qualityScoreVal,
                livenessScore = livenessScore,
                activeBackend = matchResult.hardwareTier.label,
                explanation = "Rejected by Quality Gate: ${qualityGateResult.rejectionReason}",
                timestampFormatted = "",
                statusMessage = qualityGateResult.rejectionReason ?: "Poor quality image"
            )
        }

        // 3. Match Confidence Zone Evaluation
        if (matchResult.confidenceZone == ConfidenceZone.REVIEW) {
            return@withContext AttendanceDecision(
                isGranted = false,
                confidenceZone = ConfidenceZone.REVIEW,
                rollNumber = matchResult.studentRoll,
                studentName = matchResult.studentName,
                confidence = matchResult.confidence,
                similarity = matchResult.similarity,
                decisionMargin = matchResult.decisionMargin,
                qualityScore = qualityScoreVal,
                livenessScore = livenessScore,
                activeBackend = matchResult.hardwareTier.label,
                explanation = matchResult.explanation,
                timestampFormatted = "",
                statusMessage = "Review Required: Ambiguous match margin (Δ=${"%.3f".format(matchResult.decisionMargin)})"
            )
        }

        if (matchResult.confidenceZone == ConfidenceZone.REJECT || !matchResult.isMatch) {
            return@withContext AttendanceDecision(
                isGranted = false,
                confidenceZone = ConfidenceZone.REJECT,
                rollNumber = "GUEST",
                studentName = "Unrecognized Face",
                confidence = matchResult.confidence,
                similarity = matchResult.similarity,
                decisionMargin = matchResult.decisionMargin,
                qualityScore = qualityScoreVal,
                livenessScore = livenessScore,
                activeBackend = matchResult.hardwareTier.label,
                explanation = matchResult.explanation.ifEmpty { "Similarity score below required decision threshold" },
                timestampFormatted = "",
                statusMessage = "Face not recognized in database"
            )
        }

        // 4. Match Verified (ACCEPT Zone)
        val roll = matchResult.studentRoll
        val name = matchResult.studentName
        val now = System.currentTimeMillis()

        // Check session in-memory cooldown
        val lastScanTime = sessionCoolDown[roll] ?: 0L
        if (now - lastScanTime < COOLDOWN_DURATION_MS) {
            val fmt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastScanTime))
            return@withContext AttendanceDecision(
                isGranted = true,
                confidenceZone = ConfidenceZone.ACCEPT,
                rollNumber = roll,
                studentName = name,
                confidence = matchResult.confidence,
                similarity = matchResult.similarity,
                decisionMargin = matchResult.decisionMargin,
                qualityScore = qualityScoreVal,
                livenessScore = livenessScore,
                activeBackend = matchResult.hardwareTier.label,
                explanation = "Already marked attendance today at $fmt",
                timestampFormatted = fmt,
                statusMessage = "Already marked at $fmt"
            )
        }

        // Persistent Room record with SHA-256 block hash
        val dateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))
        val prevHash = db.attendanceDao().getLatestHash() ?: "GENESIS_BLOCK_0000000000000000"
        val payload = "$roll|$name|$dateKey|$now|${matchResult.similarity}|$prevHash"
        val hash = AndroidSecurityUtils.computeSha256(payload)

        val record = AttendanceRecordEntity(
            recordId = UUID.randomUUID().toString(),
            studentRoll = roll,
            studentName = name,
            sessionDate = dateKey,
            timestamp = now,
            confidencePct = matchResult.confidence,
            securityTier = "HIGH",
            sha256Hash = hash,
            isSynced = false
        )

        db.attendanceDao().recordAttendanceIfNotExists(record)
        sessionCoolDown[roll] = now

        return@withContext AttendanceDecision(
            isGranted = true,
            confidenceZone = ConfidenceZone.ACCEPT,
            rollNumber = roll,
            studentName = name,
            confidence = matchResult.confidence,
            similarity = matchResult.similarity,
            decisionMargin = matchResult.decisionMargin,
            qualityScore = qualityScoreVal,
            livenessScore = livenessScore,
            activeBackend = matchResult.hardwareTier.label,
            explanation = matchResult.explanation,
            timestampFormatted = timeFmt,
            statusMessage = "Attendance Verified (Cosine: ${(matchResult.similarity * 100).toInt()}%, Δ: ${"%.3f".format(matchResult.decisionMargin)})"
        )
    }
}
