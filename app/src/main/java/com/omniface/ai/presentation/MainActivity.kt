package com.omniface.ai.presentation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.omniface.ai.ui.OmniFaceApp
import com.omniface.ai.ui.components.CameraProminentDisclosureDialog

class MainActivity : FragmentActivity() {

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

        setContent {
            var hasCameraPermission by remember {
                mutableStateOf(
                    ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                )
            }
            var showDisclosure by remember { mutableStateOf(!hasCameraPermission) }

            OmniFaceApp()

            if (showDisclosure && !hasCameraPermission) {
                CameraProminentDisclosureDialog(
                    onAccept = {
                        showDisclosure = false
                        requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    },
                    onDismiss = {
                        showDisclosure = false
                    }
                )
            }
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
     * Unlocks True 120Hz LTPO display refresh rate on Android (Xiaomi HyperOS, Samsung, OnePlus, Pixel).
     * Locks preferred display mode, sets preferred min/max refresh rate to 120Hz, and triggers SurfaceFlinger 120 FPS pacing.
     */
    private fun configureAdaptiveHighRefreshRate() {
        try {
            // 1. Enable Hardware Acceleration on Window
            window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )

            // 2. Lock 120Hz Display Mode & Preferred Refresh Rate
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
                        lp.preferredRefreshRate = targetMode.refreshRate.coerceAtLeast(120.0f)

                        // API 31+ (Android 12+) direct fields
                        try {
                            val minField = lp.javaClass.getField("preferredMinDisplayRefreshRate")
                            minField.setFloat(lp, 120.0f)
                            val maxField = lp.javaClass.getField("preferredMaxDisplayRefreshRate")
                            maxField.setFloat(lp, targetMode.refreshRate.coerceAtLeast(120.0f))
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
                val targetMode = modes?.maxByOrNull { it.refreshRate }

                if (targetMode != null) {
                    val lp = window.attributes
                    lp.preferredDisplayModeId = targetMode.modeId
                    window.attributes = lp
                }
            }

            // 3. Set Frame Rate directly on DecorView and Surface for SurfaceFlinger 120Hz pacing (Android 11+ / API 30+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val decorView = window.decorView
                    val setFrameRateMethod = decorView.javaClass.getMethod(
                        "setFrameRate",
                        Float::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType,
                        Int::class.javaPrimitiveType
                    )
                    // 120 FPS, FRAME_RATE_COMPATIBILITY_DEFAULT (0), CHANGE_FRAME_RATE_ALWAYS (1)
                    setFrameRateMethod.invoke(decorView, 120.0f, 0, 1)
                } catch (_: Throwable) {
                    try {
                        val decorView = window.decorView
                        val setFrameRateMethod = decorView.javaClass.getMethod(
                            "setFrameRate",
                            Float::class.javaPrimitiveType,
                            Int::class.javaPrimitiveType
                        )
                        setFrameRateMethod.invoke(decorView, 120.0f, 0)
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {
            // Graceful fallback
        }
    }
}
