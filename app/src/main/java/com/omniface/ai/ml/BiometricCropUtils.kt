package com.omniface.ai.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import androidx.camera.core.ImageProxy

object BiometricCropUtils {
    /**
     * Converts a CameraX ImageProxy to a correctly rotated Bitmap.
     * Safely handles buffer orientation, rotation matrix, and recycles intermediate bitmap if needed.
     */
    fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val bitmap = imageProxy.toBitmap()
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                }
                rotated
            } else {
                bitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Extracts an undistorted, 1:1 aspect ratio square face bounding box
     * centered on the detected face centroid, including optimal margin for ArcFace.
     */
    fun extractSquareFaceCrop(fullBitmap: Bitmap, box: Rect, marginMultiplier: Float = 1.25f): Bitmap? {
        if (fullBitmap.isRecycled || box.width() <= 0 || box.height() <= 0) return null

        val cx = box.centerX()
        val cy = box.centerY()
        val maxDim = maxOf(box.width(), box.height())
        val cropSize = (maxDim * marginMultiplier).toInt().coerceAtLeast(40)
        val halfSize = cropSize / 2

        val rawLeft = cx - halfSize
        val rawTop = cy - halfSize
        val rawRight = cx + halfSize
        val rawBottom = cy + halfSize

        val srcLeft = rawLeft.coerceIn(0, fullBitmap.width)
        val srcTop = rawTop.coerceIn(0, fullBitmap.height)
        val srcRight = rawRight.coerceIn(srcLeft, fullBitmap.width)
        val srcBottom = rawBottom.coerceIn(srcTop, fullBitmap.height)

        val srcW = srcRight - srcLeft
        val srcH = srcBottom - srcTop
        if (srcW < 20 || srcH < 20) return null

        return try {
            if (rawLeft >= 0 && rawTop >= 0 && rawRight <= fullBitmap.width && rawBottom <= fullBitmap.height) {
                Bitmap.createBitmap(fullBitmap, rawLeft, rawTop, cropSize, cropSize)
            } else {
                val square = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(square)
                val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
                val dstLeft = srcLeft - rawLeft
                val dstTop = srcTop - rawTop
                val dstRect = Rect(dstLeft, dstTop, dstLeft + srcW, dstTop + srcH)
                canvas.drawBitmap(fullBitmap, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
                square
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extracts an undistorted 1:1 aspect ratio face crop centered on the face centroid,
     * rendered directly into a fixed-size buffer [targetSize] (default 192px).
     * Eliminates multi-megabyte intermediate Bitmap allocations in high-res camera loops,
     * reducing per-frame vision heap pressure by over 90%.
     */
    fun extractDirectFaceCrop(
        fullBitmap: Bitmap,
        box: Rect,
        targetSize: Int = 192,
        marginMultiplier: Float = 1.25f
    ): Bitmap? {
        if (fullBitmap.isRecycled || box.width() <= 0 || box.height() <= 0) return null

        val cx = box.centerX()
        val cy = box.centerY()
        val maxDim = maxOf(box.width(), box.height())
        val cropSize = (maxDim * marginMultiplier).toInt().coerceAtLeast(40)
        val halfSize = cropSize / 2

        val rawLeft = cx - halfSize
        val rawTop = cy - halfSize
        val rawRight = cx + halfSize
        val rawBottom = cy + halfSize

        val srcLeft = rawLeft.coerceIn(0, fullBitmap.width)
        val srcTop = rawTop.coerceIn(0, fullBitmap.height)
        val srcRight = rawRight.coerceIn(srcLeft, fullBitmap.width)
        val srcBottom = rawBottom.coerceIn(srcTop, fullBitmap.height)

        val srcW = srcRight - srcLeft
        val srcH = srcBottom - srcTop
        if (srcW < 20 || srcH < 20) return null

        return try {
            val target = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(target)
            val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)

            val scale = targetSize.toFloat() / cropSize.toFloat()
            val dstLeft = ((srcLeft - rawLeft) * scale).toInt()
            val dstTop = ((srcTop - rawTop) * scale).toInt()
            val dstRight = (dstLeft + srcW * scale).toInt().coerceAtMost(targetSize)
            val dstBottom = (dstTop + srcH * scale).toInt().coerceAtMost(targetSize)
            val dstRect = Rect(dstLeft, dstTop, dstRight, dstBottom)

            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            canvas.drawBitmap(fullBitmap, srcRect, dstRect, paint)
            target
        } catch (_: Exception) {
            null
        }
    }
}
