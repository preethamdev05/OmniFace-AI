package com.omniface.ai.qualcomm

import android.os.Build
import java.io.File
import java.util.Locale

enum class SnapdragonChipset(val displayName: String, val generation: String, val tops: Float) {
    SNAPDRAGON_8_ELITE_GEN5("Snapdragon® 8 Elite Gen 5 (SM8850)", "8 Elite Gen 5", 60.0f),
    SNAPDRAGON_8_ELITE("Snapdragon® 8 Elite (SM8750)", "8 Elite", 45.0f),
    SNAPDRAGON_8_GEN3("Snapdragon® 8 Gen 3 (SM8650)", "8 Gen 3", 45.0f),
    SNAPDRAGON_8_GEN2("Snapdragon® 8 Gen 2 (SM8550)", "8 Gen 2", 30.0f),
    SNAPDRAGON_8_GEN1("Snapdragon® 8 Gen 1 / 8+ Gen 1 (SM8450/SM8475)", "8 Gen 1", 27.0f),
    SNAPDRAGON_888("Snapdragon® 888 / 888+ (SM8350)", "888", 26.0f),
    OTHER("Non-Qualcomm or Legacy SoC", "Standard", 0.0f)
}

object SnapdragonDetector {

    val currentChipset: SnapdragonChipset by lazy { detectChipset() }

    val isQualcommSnapdragon: Boolean
        get() = currentChipset != SnapdragonChipset.OTHER

    private fun detectChipset(): SnapdragonChipset {
        val hardware = Build.HARDWARE.lowercase(Locale.US)
        val board = Build.BOARD.lowercase(Locale.US)
        val socModel = getSystemProp("ro.soc.model").lowercase(Locale.US)
        val socManuf = getSystemProp("ro.soc.manufacturer").lowercase(Locale.US)
        val platform = getSystemProp("ro.board.platform").lowercase(Locale.US)
        val cpuInfo = readCpuInfo().lowercase(Locale.US)

        val combined = "$hardware $board $socModel $socManuf $platform $cpuInfo"

        return when {
            // Snapdragon 8 Elite Gen 5 (SM8850 / HTP 60 TOPS)
            combined.contains("sm8850") || combined.contains("sun") && combined.contains("gen5") ->
                SnapdragonChipset.SNAPDRAGON_8_ELITE_GEN5

            // Snapdragon 8 Elite (SM8750 / HTP 45 TOPS)
            combined.contains("sm8750") || combined.contains("sun") || combined.contains("8 elite") ->
                SnapdragonChipset.SNAPDRAGON_8_ELITE

            // Snapdragon 8 Gen 3 (SM8650 / Pineapple)
            combined.contains("sm8650") || combined.contains("pineapple") || combined.contains("8 gen 3") ->
                SnapdragonChipset.SNAPDRAGON_8_GEN3

            // Snapdragon 8 Gen 2 (SM8550 / Kalama)
            combined.contains("sm8550") || combined.contains("kalama") || combined.contains("8 gen 2") ->
                SnapdragonChipset.SNAPDRAGON_8_GEN2

            // Snapdragon 8 Gen 1 / 8+ Gen 1 (SM8450 / SM8475 / Taro / Cape)
            combined.contains("sm8450") || combined.contains("sm8475") || combined.contains("taro") || combined.contains("cape") || combined.contains("8 gen 1") ->
                SnapdragonChipset.SNAPDRAGON_8_GEN1

            // Snapdragon 888 / 888+ (SM8350 / Lahaina)
            combined.contains("sm8350") || combined.contains("lahaina") || combined.contains("888") ->
                SnapdragonChipset.SNAPDRAGON_888

            // Generic Qualcomm Check
            socManuf.contains("qcom") || socManuf.contains("qualcomm") || hardware.startsWith("qcom") ->
                SnapdragonChipset.SNAPDRAGON_8_GEN2 // Default to 8 Gen 2 level capability for unlisted Snapdragon

            else -> SnapdragonChipset.OTHER
        }
    }

    private fun getSystemProp(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            process.inputStream.bufferedReader().use { it.readLine() ?: "" }
        } catch (_: Exception) {
            ""
        }
    }

    private fun readCpuInfo(): String {
        return try {
            val file = File("/proc/cpuinfo")
            if (file.exists() && file.canRead()) file.readText() else ""
        } catch (_: Exception) {
            ""
        }
    }
}
