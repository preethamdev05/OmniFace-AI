package com.omniface.ai.ml.verification.domain

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.face.Face
import com.omniface.ai.ml.BiometricCropUtils

/**
 * Sovereign Visual Frame Abstraction for Biometric Verification.
 *
 * Decouples the verification engine from Android CameraX libraries,
 * enabling headless unit testing and offline benchmarks with raw Bitmaps.
 */
sealed interface BiometricFrame {
    val width: Int
    val height: Int
    val rotationDegrees: Int
    val isFrontFacing: Boolean
    val detectedFaces: List<Face> get() = emptyList()

    /**
     * Obtains the RGB Bitmap representation of the frame.
     * Implementations may cache or convert on demand.
     */
    fun toBitmap(): Bitmap?

    /**
     * Releases any underlying hardware buffers or image proxy handles.
     */
    fun close() {}
}

/**
 * Production CameraX ImageProxy Adapter.
 */
class ImageProxyBiometricFrame(
    private val imageProxy: ImageProxy,
    override val isFrontFacing: Boolean,
    override val detectedFaces: List<Face> = emptyList()
) : BiometricFrame {
    override val width: Int get() = imageProxy.width
    override val height: Int get() = imageProxy.height
    override val rotationDegrees: Int get() = imageProxy.imageInfo.rotationDegrees

    private var cachedBitmap: Bitmap? = null

    override fun toBitmap(): Bitmap? {
        if (cachedBitmap == null) {
            val raw = BiometricCropUtils.imageProxyToBitmap(imageProxy) ?: return null
            cachedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated != raw && !raw.isRecycled) raw.recycle()
                rotated
            } else {
                raw
            }
        }
        return cachedBitmap
    }

    override fun close() {
        cachedBitmap = null
        try {
            imageProxy.close()
        } catch (_: Throwable) {}
    }
}

/**
 * Headless & Testing Bitmap Adapter.
 */
class BitmapBiometricFrame(
    private val bitmap: Bitmap,
    override val rotationDegrees: Int = 0,
    override val isFrontFacing: Boolean = true,
    override val detectedFaces: List<Face> = emptyList()
) : BiometricFrame {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun toBitmap(): Bitmap? = if (bitmap.isRecycled) null else bitmap

    override fun close() {
        // No-op for test bitmaps unless explicitly managed by test harness
    }
}
