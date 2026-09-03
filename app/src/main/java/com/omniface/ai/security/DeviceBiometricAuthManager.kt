package com.omniface.ai.security

import android.content.Context
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * ??? Hardware Device Biometric & System Security Manager
 *
 * Enforces native device biometric authentication (Fingerprint, 3D Face Unlock, or Secure
 * Device PIN/Pattern/Password) for all high-risk and destructive operations across OmniFace AI:
 * - Attendance Ledger Historical Purge / Wipe
 * - DPDP Act 2023 90-Day Retention Purge
 * - Biometric Deduplication Student Merge / Deletion
 * - Kiosk PIN Lockdown & Emergency Turnstile Override
 * - Encrypted Database Snapshot Backup & Export
 */
object DeviceBiometricAuthManager {

    private const val TAG = "DeviceBiometricAuth"

    /**
     * Checks if the host device has enrolled biometrics or a secure lockscreen credential.
     */
    fun canAuthenticate(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Triggers the native Android hardware BiometricPrompt (Fingerprint / Face Unlock / System PIN).
     *
     * @param activity The current FragmentActivity context
     * @param title Title displayed in the system biometric dialog
     * @param subtitle Subtitle explaining the action requiring biometric authorization
     * @param onSuccess Callback executed immediately upon successful biometric or credential verification
     * @param onError Callback executed if authentication fails, is cancelled, or errors out
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String = "Biometric Authorization Required",
        subtitle: String = "Verify your fingerprint or screen lock to authorize this secure operation",
        onSuccess: () -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val executor = ContextCompat.getMainExecutor(activity)

        val biometricPrompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    Log.i(TAG, "Biometric authentication succeeded: ${result.authenticationType}")
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Log.w(TAG, "Biometric authentication error [$errorCode]: $errString")
                    // Do not treat user cancellation as an unhandled error
                    if (errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON &&
                        errorCode != BiometricPrompt.ERROR_CANCELED
                    ) {
                        onError(errString.toString())
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Log.d(TAG, "Biometric authentication attempt failed (unrecognized biometric)")
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                        BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()

        try {
            biometricPrompt.authenticate(promptInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch BiometricPrompt: ${e.message}", e)
            // Fail securely — NEVER grant unauthorized access on exception
            onError("Authentication could not be initiated: ${e.localizedMessage ?: "Security error"}")
        }
    }
}

/**
 * Traverses context wrappers to find the parent FragmentActivity.
 */
fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is android.content.ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
