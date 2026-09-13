package com.omniface.ai.ml.recognition

/**
 * Biometric recognition engine backend abstraction.
 * Production attendance decisions MUST ALWAYS route through [MOBILEFACENET]
 * until [OMNIFACE_UNIFIED_V2] empirically passes every Phase M acceptance gate.
 */
enum class RecognitionBackend(val label: String, val isAuthoritative: Boolean) {
    /**
     * Production Baseline: MobileFaceNet 512-D ArcFace + MiniFASNetV2 PAD.
     * Sole authoritative backend for attendance ledger minting.
     */
    MOBILEFACENET("MobileFaceNet ArcFace (Production)", isAuthoritative = true),

    /**
     * Experimental Unified Multi-Task Neural Network (V2).
     * Operates exclusively in shadow evaluation mode; never writes attendance.
     */
    OMNIFACE_UNIFIED_V2("OmniFace Unified Multi-Task V2 (Experimental)", isAuthoritative = false);

    companion object {
        val DEFAULT = MOBILEFACENET
    }
}
