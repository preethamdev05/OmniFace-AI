package com.omniface.ai.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF

/**
 * Standard Umeyama 2D Similarity Transform for 5-point facial landmark alignment.
 * Maps detected 5 fiducial landmarks (Left Eye, Right Eye, Nose Tip, Left Mouth Corner, Right Mouth Corner)
 * to standard 112x112 canonical reference points for MobileFaceNet & CavaFace backbones.
 */
object UmeyamaSimilarityTransform {

    // Canonical ArcFace 112x112 5-point reference coordinates
    private val ARCFACE_REFERENCE_5PTS = arrayOf(
        PointF(38.2946f, 51.6963f), // Left Eye
        PointF(73.5318f, 51.5014f), // Right Eye
        PointF(56.0252f, 71.7366f), // Nose Tip
        PointF(41.5493f, 92.3655f), // Left Mouth Corner
        PointF(70.7299f, 92.2041f)  // Right Mouth Corner
    )

    data class AlignmentResult(
        val alignedBitmap: Bitmap,
        val alignmentError: Float,
        val rotationDegrees: Float,
        val scale: Float
    )

    /**
     * Computes similarity transformation matrix and warps [fullBitmap] into a 112x112 canonical face crop.
     */
    fun alignFace5Points(
        fullBitmap: Bitmap,
        src5Points: Array<PointF>,
        targetWidth: Int = 112,
        targetHeight: Int = 112
    ): AlignmentResult? {
        if (fullBitmap.isRecycled || src5Points.size < 5) return null

        val dst5Points = ARCFACE_REFERENCE_5PTS

        // Ensure canonical geometric ordering (Image Left Eye, Image Right Eye, Nose, Image Left Mouth, Image Right Mouth)
        val pt0 = src5Points[0]
        val pt1 = src5Points[1]
        val pt2 = src5Points[2]
        val pt3 = src5Points[3]
        val pt4 = src5Points[4]

        val eyeLeft = if (pt0.x <= pt1.x) pt0 else pt1
        val eyeRight = if (pt0.x <= pt1.x) pt1 else pt0
        val mouthLeft = if (pt3.x <= pt4.x) pt3 else pt4
        val mouthRight = if (pt3.x <= pt4.x) pt4 else pt3
        val canonicalSrc = arrayOf(eyeLeft, eyeRight, pt2, mouthLeft, mouthRight)

        // 1. Compute Centroids
        var srcMeanX = 0f; var srcMeanY = 0f
        var dstMeanX = 0f; var dstMeanY = 0f
        for (i in 0 until 5) {
            srcMeanX += canonicalSrc[i].x
            srcMeanY += canonicalSrc[i].y
            dstMeanX += dst5Points[i].x
            dstMeanY += dst5Points[i].y
        }
        srcMeanX /= 5f; srcMeanY /= 5f
        dstMeanX /= 5f; dstMeanY /= 5f

        // 2. Compute Variances and Covariances
        var srcVar = 0f
        var covXX = 0f; var covXY = 0f
        var covYX = 0f; var covYY = 0f

        for (i in 0 until 5) {
            val sX = canonicalSrc[i].x - srcMeanX
            val sY = canonicalSrc[i].y - srcMeanY
            val dX = dst5Points[i].x - dstMeanX
            val dY = dst5Points[i].y - dstMeanY

            srcVar += sX * sX + sY * sY
            covXX += dX * sX
            covXY += dX * sY
            covYX += dY * sX
            covYY += dY * sY
        }
        srcVar /= 5f
        covXX /= 5f; covXY /= 5f
        covYX /= 5f; covYY /= 5f

        if (srcVar < 1e-6f) return null

        // 3. Estimate Rotation Angle θ and Scale s
        // In 2D similarity transform: R = [cos θ, -sin θ; sin θ, cos θ]
        val trace = covXX + covYY
        val diff = covYX - covXY
        val angleRad = kotlin.math.atan2(diff, trace)
        val rotationDeg = Math.toDegrees(angleRad.toDouble()).toFloat()

        val scale = kotlin.math.sqrt((trace * trace + diff * diff).toDouble()).toFloat() / srcVar

        // 4. Construct Android Matrix: Dst = S * R * (Src - SrcMean) + DstMean
        val matrix = Matrix()
        // Step A: Translate src centroid to origin
        matrix.postTranslate(-srcMeanX, -srcMeanY)
        // Step B: Scale and rotate
        matrix.postScale(scale, scale)
        matrix.postRotate(rotationDeg)
        // Step C: Translate to dst centroid
        matrix.postTranslate(dstMeanX, dstMeanY)

        // 5. Invert matrix to warp source image into 112x112 target canvas
        val inverseMatrix = Matrix()
        if (!matrix.invert(inverseMatrix)) return null

        val alignedBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(alignedBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(fullBitmap, matrix, paint)

        // 6. Compute Residual Alignment Error
        var residualError = 0f
        val mappedPt = FloatArray(2)
        for (i in 0 until 5) {
            val srcPt = floatArrayOf(canonicalSrc[i].x, canonicalSrc[i].y)
            matrix.mapPoints(mappedPt, srcPt)
            val dx = mappedPt[0] - dst5Points[i].x
            val dy = mappedPt[1] - dst5Points[i].y
            residualError += kotlin.math.sqrt(dx * dx + dy * dy)
        }
        residualError /= 5f

        return AlignmentResult(
            alignedBitmap = alignedBitmap,
            alignmentError = residualError,
            rotationDegrees = rotationDeg,
            scale = scale
        )
    }

    /**
     * Computes the 2D affine/similarity transformation matrix from 5 source points to canonical reference points.
     */
    fun computeSimilarityTransform(
        src5Points: Array<PointF>,
        dst5Points: Array<PointF> = ARCFACE_REFERENCE_5PTS
    ): Matrix? {
        if (src5Points.size < 5 || dst5Points.size < 5) return null

        val pt0 = src5Points[0]
        val pt1 = src5Points[1]
        val pt2 = src5Points[2]
        val pt3 = src5Points[3]
        val pt4 = src5Points[4]

        val eyeLeft = if (pt0.x <= pt1.x) pt0 else pt1
        val eyeRight = if (pt0.x <= pt1.x) pt1 else pt0
        val mouthLeft = if (pt3.x <= pt4.x) pt3 else pt4
        val mouthRight = if (pt3.x <= pt4.x) pt4 else pt3
        val canonicalSrc = arrayOf(eyeLeft, eyeRight, pt2, mouthLeft, mouthRight)

        var srcMeanX = 0f; var srcMeanY = 0f
        var dstMeanX = 0f; var dstMeanY = 0f
        for (i in 0 until 5) {
            srcMeanX += canonicalSrc[i].x; srcMeanY += canonicalSrc[i].y
            dstMeanX += dst5Points[i].x; dstMeanY += dst5Points[i].y
        }
        srcMeanX /= 5f; srcMeanY /= 5f
        dstMeanX /= 5f; dstMeanY /= 5f

        var srcVar = 0f
        var covXX = 0f; var covXY = 0f
        var covYX = 0f; var covYY = 0f
        for (i in 0 until 5) {
            val sX = canonicalSrc[i].x - srcMeanX
            val sY = canonicalSrc[i].y - srcMeanY
            val dX = dst5Points[i].x - dstMeanX
            val dY = dst5Points[i].y - dstMeanY
            srcVar += sX * sX + sY * sY
            covXX += dX * sX; covXY += dX * sY
            covYX += dY * sX; covYY += dY * sY
        }
        srcVar /= 5f
        covXX /= 5f; covXY /= 5f
        covYX /= 5f; covYY /= 5f

        if (srcVar < 1e-6f) return null

        val trace = covXX + covYY
        val diff = covYX - covXY
        val angleRad = kotlin.math.atan2(diff, trace)
        val rotationDeg = Math.toDegrees(angleRad.toDouble()).toFloat()
        val scale = kotlin.math.sqrt((trace * trace + diff * diff).toDouble()).toFloat() / srcVar

        val matrix = Matrix()
        matrix.postTranslate(-srcMeanX, -srcMeanY)
        matrix.postScale(scale, scale)
        matrix.postRotate(rotationDeg)
        matrix.postTranslate(dstMeanX, dstMeanY)
        return matrix
    }
}
