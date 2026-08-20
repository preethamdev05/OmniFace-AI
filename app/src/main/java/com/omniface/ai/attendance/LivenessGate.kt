package com.omniface.ai.attendance

import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import com.omniface.ai.ml.LivenessDetector
import com.omniface.ai.ml.LivenessState
import com.omniface.ai.ml.QualcommFaceIntelligenceEngine

class LivenessGate(private val detector: LivenessDetector = LivenessDetector()) {

    fun verifyLiveness(
        face: Face,
        faceCrop: Bitmap,
        qualcommEngine: QualcommFaceIntelligenceEngine? = null
    ): LivenessState {
        return detector.evaluateLiveness(face, faceCrop, qualcommEngine)
    }

    fun isLivenessPassed(state: LivenessState): Boolean {
        return state == LivenessState.PASS
    }
}
