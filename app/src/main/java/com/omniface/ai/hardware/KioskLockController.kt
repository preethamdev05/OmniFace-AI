package com.omniface.ai.hardware

import android.app.Activity
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import android.view.WindowManager
import com.omniface.ai.security.AndroidSecurityUtils

object KioskLockController {

    private val _isKioskLocked = MutableStateFlow(false)
    val isKioskLocked: StateFlow<Boolean> = _isKioskLocked.asStateFlow()

    private var masterAdminPinHash: String = AndroidSecurityUtils.computeSha256("OMNIFACE_PIN_SALT_1234")
    var isPinProtectionEnabled: Boolean = true

    private var failedAttempts = 0
    private var lockoutUntilTimestamp = 0L

    fun setMasterAdminPin(newPin: String) {
        masterAdminPinHash = AndroidSecurityUtils.computeSha256("OMNIFACE_PIN_SALT_$newPin")
    }

    fun isLockedOut(): Boolean {
        return System.currentTimeMillis() < lockoutUntilTimestamp
    }

    fun getRemainingLockoutSeconds(): Int {
        val rem = lockoutUntilTimestamp - System.currentTimeMillis()
        return if (rem > 0) (rem / 1000).toInt() else 0
    }

    fun verifyAdminPin(enteredPin: String): Boolean {
        if (!isPinProtectionEnabled) return true
        if (isLockedOut()) return false

        val enteredHash = AndroidSecurityUtils.computeSha256("OMNIFACE_PIN_SALT_$enteredPin")
        val isCorrect = enteredHash == masterAdminPinHash

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

    fun toggleKioskLock(activity: Activity, enteredPin: String = ""): Boolean {
        if (isLockedOut()) {
            Toast.makeText(activity, "⏳ Too many failed PIN attempts. Try again in ${getRemainingLockoutSeconds()}s", Toast.LENGTH_LONG).show()
            return false
        }

        if (_isKioskLocked.value && isPinProtectionEnabled && !verifyAdminPin(enteredPin)) {
            Toast.makeText(activity, "❌ Invalid Admin PIN ($failedAttempts/5 attempts)", Toast.LENGTH_SHORT).show()
            return false
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
