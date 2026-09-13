package com.omniface.ai.ml.recognition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.*
import org.junit.Test

class ShadowBiometricWorkerTest {

    @Test
    fun testRecognitionBackendHierarchy() {
        assertTrue("MobileFaceNet must be the sole authoritative production engine", RecognitionBackend.MOBILEFACENET.isAuthoritative)
        assertFalse("Unified V2 must NEVER be authoritative during shadow phase", RecognitionBackend.OMNIFACE_UNIFIED_V2.isAuthoritative)
        assertEquals(RecognitionBackend.MOBILEFACENET, RecognitionBackend.DEFAULT)
    }

    @Test
    fun testUnifiedEngineModelVersion() {
        assertEquals("2.0.0-alpha1", UnifiedBiometricEngineV2.MODEL_VERSION)
    }

    @Test
    fun testShadowComparisonTelemetryDataClass() {
        val telemetry = ShadowComparisonTelemetry(
            timestampMs = 123456789L,
            mobilefacenetConfidence = 0.96f,
            unifiedConfidence = 0.95f,
            cosineSimilarityDelta = 0.04f,
            padAgreement = true,
            mobilefacenetLatencyMs = 8L,
            unifiedLatencyMs = 11L,
            thermalCelsius = 36.2f
        )
        assertEquals(123456789L, telemetry.timestampMs)
        assertEquals(0.96f, telemetry.mobilefacenetConfidence, 1e-4f)
        assertTrue(telemetry.padAgreement)
        assertEquals(36.2f, telemetry.thermalCelsius, 1e-4f)
    }

    @Test
    fun testUnifiedBiometricResultContract() {
        val result = UnifiedBiometricResult(
            embedding = FloatArray(512) { 0.0f },
            padLogits = floatArrayOf(2.0f, -1.0f, -1.0f),
            isBonaFide = true,
            qualityScores = floatArrayOf(0.9f, 0.9f, 0.9f, 0.9f),
            gazeAngles = floatArrayOf(0.0f, 0.0f),
            inferenceLatencyMs = 10L,
            modelVersion = "2.0.0-alpha1"
        )
        assertEquals(512, result.embedding.size)
        assertEquals(3, result.padLogits.size)
        assertTrue(result.isBonaFide)
        assertEquals("2.0.0-alpha1", result.modelVersion)
    }
}
