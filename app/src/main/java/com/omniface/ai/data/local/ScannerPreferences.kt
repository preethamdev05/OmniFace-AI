package com.omniface.ai.data.local

import android.content.Context
import androidx.core.content.edit
import com.omniface.ai.OmniFaceApplication

/**
 * 🎛️ ScannerMode: Operational profile for OmniFace biometric attendance.
 */
enum class ScannerMode(val title: String, val subtitle: String) {
    AUTO_KIOSK(
        "Auto Kiosk Mode",
        "Autonomous zero-touch walk-through check-in for turnstiles and wall kiosks"
    ),
    MANUAL_HANDHELD(
        "Handheld Staff Mode",
        "Battery-conscious standby with 5s burst shutter and auto-pause on match"
    )
}

/**
 * 🎛️ ScannerPreferences: Persisted operational state governor for OmniFace Scanner.
 * Controls Kiosk Auto-Scan vs Handheld Manual Scan and attendance auto-pause lifecycle.
 */
object ScannerPreferences {
    private const val PREFS_NAME = "OMNIFACE_PREFS"
    private const val KEY_SCANNER_MODE = "scanner_operation_mode"
    private const val KEY_AUTO_SCAN_ON_OPEN = "scanner_auto_scan_on_open"
    private const val KEY_AUTO_PAUSE_ON_MATCH = "scanner_auto_pause_on_match"

    private val prefs by lazy {
        OmniFaceApplication.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getScannerMode(): ScannerMode {
        val name = prefs.getString(KEY_SCANNER_MODE, null)
        if (name != null) {
            return try { ScannerMode.valueOf(name) } catch (_: Exception) { ScannerMode.AUTO_KIOSK }
        }
        val autoScan = prefs.getBoolean(KEY_AUTO_SCAN_ON_OPEN, true)
        return if (autoScan) ScannerMode.AUTO_KIOSK else ScannerMode.MANUAL_HANDHELD
    }

    fun setScannerMode(mode: ScannerMode) {
        prefs.edit {
            putString(KEY_SCANNER_MODE, mode.name)
            putBoolean(KEY_AUTO_SCAN_ON_OPEN, mode == ScannerMode.AUTO_KIOSK)
            putBoolean(KEY_AUTO_PAUSE_ON_MATCH, mode == ScannerMode.MANUAL_HANDHELD)
        }
    }

    fun isAutoScanOnOpen(): Boolean = getScannerMode() == ScannerMode.AUTO_KIOSK

    fun setAutoScanOnOpen(enabled: Boolean) {
        setScannerMode(if (enabled) ScannerMode.AUTO_KIOSK else ScannerMode.MANUAL_HANDHELD)
    }

    fun isAutoPauseOnMatch(): Boolean = getScannerMode() == ScannerMode.MANUAL_HANDHELD

    fun setAutoPauseOnMatch(enabled: Boolean) {
        setScannerMode(if (enabled) ScannerMode.MANUAL_HANDHELD else ScannerMode.AUTO_KIOSK)
    }
}
