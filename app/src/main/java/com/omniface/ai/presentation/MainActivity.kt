package com.omniface.ai.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.omniface.ai.ui.OmniFaceApp

class MainActivity : ComponentActivity() {

    private val requestCameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "Camera permission is required for live face recognition", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureAdaptiveHighRefreshRate()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            OmniFaceApp()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        configureAdaptiveHighRefreshRate()
    }

    override fun onResume() {
        super.onResume()
        configureAdaptiveHighRefreshRate()
    }

    /**
     * Unlocks 120Hz / 90Hz / 60Hz display refresh rates on all Android OEM panels (Samsung One UI, OnePlus, Xiaomi, Pixel, Oppo, Realme, Vivo).
     * Uses SurfaceFlinger setFrameRate + Display Mode ID + Window attributes override with reflection fallback.
     */
    private fun configureAdaptiveHighRefreshRate() {
        try {
            // 1. Select the highest refresh rate display mode matching native screen resolution
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val currentDisplay = this.display
                if (currentDisplay != null) {

                    val supportedModes = currentDisplay.supportedModes
                    val currentMode = currentDisplay.mode
                    val matchedModes = supportedModes.filter {
                        it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight
                    }
                    val targetMode = (if (matchedModes.isNotEmpty()) matchedModes else supportedModes.toList())
                        .maxByOrNull { it.refreshRate }

                    if (targetMode != null) {
                        val lp = window.attributes
                        lp.preferredDisplayModeId = targetMode.modeId
                        lp.preferredRefreshRate = targetMode.refreshRate

                        // Reflection for preferredMinDisplayRefreshRate & preferredMaxDisplayRefreshRate (API 31+ / Android 12+)
                        try {
                            val minField = lp.javaClass.getField("preferredMinDisplayRefreshRate")
                            minField.setFloat(lp, targetMode.refreshRate)
                            val maxField = lp.javaClass.getField("preferredMaxDisplayRefreshRate")
                            maxField.setFloat(lp, targetMode.refreshRate)
                        } catch (_: Throwable) {}

                        window.attributes = lp
                    }
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                @Suppress("DEPRECATION")
                val defaultDisplay = wm?.defaultDisplay
                val modes = defaultDisplay?.supportedModes
                val currentMode = defaultDisplay?.mode
                val matchedModes = modes?.filter {
                    currentMode == null || (it.physicalWidth == currentMode.physicalWidth && it.physicalHeight == currentMode.physicalHeight)
                }
                val targetMode = (if (!matchedModes.isNullOrEmpty()) matchedModes else modes?.toList())
                    ?.maxByOrNull { it.refreshRate }

                if (targetMode != null) {
                    val lp = window.attributes
                    lp.preferredDisplayModeId = targetMode.modeId
                    window.attributes = lp
                }
            }

            // 2. Set Frame Rate on DecorView / Surface Control via Reflection for SurfaceFlinger 120Hz Pacing
            try {
                val decorView = window.decorView
                val setFrameRateMethod = decorView.javaClass.getMethod(
                    "setFrameRate",
                    Float::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
                setFrameRateMethod.invoke(decorView, 120.0f, 0, 1)
            } catch (_: Throwable) {}
        } catch (t: Throwable) {
            // Graceful fallback
        }
    }
}
