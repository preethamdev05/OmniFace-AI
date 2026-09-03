package com.omniface.ai.ml

import com.omniface.ai.ml.pipeline.BiometricDecisionEngine
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.antispoof.PassivePadResult
import com.omniface.ai.ml.antispoof.TemporalLivenessResult
import com.omniface.ai.ml.recognition.FaissVectorIndex
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/**
 * Unit test suite covering:
 * 1. SecurityTier calibrated ISO/IEC decision thresholds & cosine distance calculations
 * 2. 3-Gate Biometric Verification Architecture (Quality -> Anti-Spoof PAD -> Match)
 * 3. FAISS Vector Indexing & Cosine Distance Search
 * 4. Qualcomm AI Hub Result Data Classes & Telemetry
 */
class FaceRecognitionEngineTest {

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 1e-7f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    // ── 1. Calibrated Decision Thresholds ─────────────────────────────────────

    @Test
    fun testSecurityTier_calibratedThresholdsAndDistance() {
        // STANDARD (1:10 FAR)
        assertEquals(0.650f, SecurityTier.STANDARD.threshold, 1e-4f)
        assertEquals(0.040f, SecurityTier.STANDARD.marginThreshold, 1e-4f)
        assertEquals(0.350f, SecurityTier.STANDARD.cosineDistanceThreshold, 1e-4f)
        assertEquals("Standard", SecurityTier.STANDARD.displayName)

        // HIGH (1:100 FAR)
        assertEquals(0.720f, SecurityTier.HIGH.threshold, 1e-4f)
        assertEquals(0.045f, SecurityTier.HIGH.marginThreshold, 1e-4f)
        assertEquals(0.280f, SecurityTier.HIGH.cosineDistanceThreshold, 1e-4f)
        assertEquals("High", SecurityTier.HIGH.displayName)

        // STRICT (1:1,000 FAR)
        assertEquals(0.800f, SecurityTier.STRICT.threshold, 1e-4f)
        assertEquals(0.050f, SecurityTier.STRICT.marginThreshold, 1e-4f)
        assertEquals(0.200f, SecurityTier.STRICT.cosineDistanceThreshold, 1e-4f)
        assertEquals("Strict", SecurityTier.STRICT.displayName)
    }

    // ── 2. 3-Gate Biometric Verification Pipeline ─────────────────────────────

    @Test
    fun testThreeGatePipeline_gate1QualityFailure_blocksAttendance() {
        val failedQuality = QualityGateResult(
            isPassed = false,
            overallQualityScore = 35.0f,
            sharpnessScore = 20.0f,
            exposureScore = 40.0f,
            poseScore = 50.0f,
            sizeScore = 30.0f,
            rejectionReason = "Extreme Motion Blur"
        )
        val passingPad = PassivePadResult(true, 0.95f, 0.05f, "Live", 5L)
        val passingTemporal = TemporalLivenessResult(
            isLive = true,
            temporalConfidence = 0.95f,
            microMotionDetected = true,
            naturalBlinkDetected = true,
            headTurnDetected = true,
            stable3DDepth = true,
            requiredAction = null,
            explanation = "Passed"
        )
        val matchResult = MatchResult(
            studentRoll = "21BCA001",
            studentName = "Alice",
            confidence = 96.5f,
            similarity = 0.88f,
            isMatch = true,
            hardwareTier = HardwareTier.GPU_DELEGATE
        )

        val decision = BiometricDecisionEngine.evaluate(
            quality = failedQuality,
            passivePad = passingPad,
            temporalLiveness = passingTemporal,
            matchResult = matchResult,
            securityTier = SecurityTier.HIGH
        )

        assertEquals(PipelineGateState.REJECT_QUALITY, decision.gateState)
        assertFalse(decision.isAttendanceAuthorized)
        assertEquals(0f, decision.matchConfidence, 1e-4f)
    }

    @Test
    fun testThreeGatePipeline_gate2SpoofFailure_blocksAttendanceEvenWithStrongMatch() {
        val passingQuality = QualityGateResult(
            isPassed = true,
            overallQualityScore = 92.0f,
            sharpnessScore = 90.0f,
            exposureScore = 95.0f,
            poseScore = 92.0f,
            sizeScore = 90.0f,
            rejectionReason = ""
        )
        val spoofPad = PassivePadResult(false, 0.15f, 0.85f, "High-Confidence Screen Replay Attack", 5L)
        val passingTemporal = TemporalLivenessResult(
            isLive = true,
            temporalConfidence = 0.95f,
            microMotionDetected = true,
            naturalBlinkDetected = true,
            headTurnDetected = true,
            stable3DDepth = true,
            requiredAction = null,
            explanation = "Passed"
        )
        val matchResult = MatchResult(
            studentRoll = "21BCA001",
            studentName = "Alice",
            confidence = 98.0f,
            similarity = 0.92f,
            isMatch = true,
            hardwareTier = HardwareTier.GPU_DELEGATE
        )

        val decision = BiometricDecisionEngine.evaluate(
            quality = passingQuality,
            passivePad = spoofPad,
            temporalLiveness = passingTemporal,
            matchResult = matchResult,
            securityTier = SecurityTier.HIGH
        )

        assertEquals(PipelineGateState.REJECT_SPOOF_ATTACK, decision.gateState)
        assertFalse(decision.isAttendanceAuthorized)
        assertEquals(0f, decision.matchConfidence, 1e-4f)
    }

    @Test
    fun testThreeGatePipeline_allGatesPass_authorizesAttendance() {
        val passingQuality = QualityGateResult(
            isPassed = true,
            overallQualityScore = 95.0f,
            sharpnessScore = 95.0f,
            exposureScore = 95.0f,
            poseScore = 95.0f,
            sizeScore = 95.0f,
            rejectionReason = ""
        )
        val passingPad = PassivePadResult(true, 0.92f, 0.08f, "Authentic 3D Human Face", 5L)
        val passingTemporal = TemporalLivenessResult(
            isLive = true,
            temporalConfidence = 0.90f,
            microMotionDetected = true,
            naturalBlinkDetected = true,
            headTurnDetected = true,
            stable3DDepth = true,
            requiredAction = null,
            explanation = "Passed"
        )
        val matchResult = MatchResult(
            studentRoll = "21BCA001",
            studentName = "Alice",
            confidence = 95.5f,
            similarity = 0.82f,
            isMatch = true,
            hardwareTier = HardwareTier.GPU_DELEGATE,
            confidenceZone = ConfidenceZone.ACCEPT,
            decisionMargin = 0.25f,
            explanation = "Verified Alice"
        )

        val decision = BiometricDecisionEngine.evaluate(
            quality = passingQuality,
            passivePad = passingPad,
            temporalLiveness = passingTemporal,
            matchResult = matchResult,
            securityTier = SecurityTier.HIGH
        )

        assertEquals(PipelineGateState.PASS, decision.gateState)
        assertTrue(decision.isAttendanceAuthorized)
        assertEquals("21BCA001", decision.matchedStudentRoll)
        assertEquals("Alice", decision.matchedStudentName)
        assertTrue(decision.matchConfidence >= 85.0f)
    }

    // ── 3. FAISS Vector Index Tests ───────────────────────────────────────────

    @Test
    fun testFaissVectorIndex_addAndSearch_exactInnerProduct() {
        val faiss = FaissVectorIndex(
            dimension = 64,
            indexType = FaissVectorIndex.IndexType.FLAT_IP,
            metricType = FaissVectorIndex.MetricType.INNER_PRODUCT
        )
        val vecA = l2Normalize(FloatArray(64) { 1.0f })
        val vecB = l2Normalize(FloatArray(64) { if (it % 2 == 0) 1.0f else -1.0f })

        faiss.add("id_a", "R001", "FRONTAL", vecA)
        faiss.add("id_b", "R002", "FRONTAL", vecB)

        assertEquals(2, faiss.totalIndexed)

        val searchResult = faiss.search(vecA, k = 1)
        assertEquals(1, searchResult.candidates.size)
        assertEquals("id_a", searchResult.candidates[0].id)
        assertEquals("R001", searchResult.candidates[0].studentRoll)
        assertEquals(1.0f, searchResult.candidates[0].similarity, 1e-4f)
    }

    @Test
    fun testFaissVectorIndex_rangeSearch_filtersByThreshold() {
        val faiss = FaissVectorIndex(
            dimension = 64,
            indexType = FaissVectorIndex.IndexType.HNSW_FLAT,
            metricType = FaissVectorIndex.MetricType.INNER_PRODUCT
        )
        val vecA = l2Normalize(FloatArray(64) { 1.0f })
        val vecB = l2Normalize(FloatArray(64) { if (it % 2 == 0) 1.0f else -1.0f })

        faiss.add("id_a", "R001", "FRONTAL", vecA)
        faiss.add("id_b", "R002", "FRONTAL", vecB)

        val matches = faiss.rangeSearch(vecA, minSimilarityThreshold = 0.70f)
        assertEquals(1, matches.size)
        assertEquals("id_a", matches[0].id)
    }

    // ── 4. Qualcomm AI Hub Data Class Tests ───────────────────────────────────

    @Test
    fun testQualcommFaceIntelligenceDataClasses() {
        val params265 = FloatArray(265) { 0.05f }
        val faceMapResult = FaceMap3DMMResult(
            parameters265 = params265,
            depthVariance = 0.012f,
            isTrue3DSurface = true,
            executionTimeMs = 8.5f
        )
        assertTrue(faceMapResult.isTrue3DSurface)
        assertEquals(265, faceMapResult.parameters265.size)

        val attrResult = FaceAttributesResult(
            smileScore = 0.85f,
            eyeglassesScore = 0.05f,
            poseYawScore = 2.1f,
            rawProbabilities = floatArrayOf(0.85f, 0.05f, 2.1f, 0.1f, 0.0f),
            executionTimeMs = 5.2f
        )
        assertEquals(0.85f, attrResult.smileScore, 1e-4f)

        val eyeGazeResult = EyeGazeResult(
            pitch = 0.05f,
            yaw = -0.02f,
            gazeVectorNorm = 0.0538f,
            eyeLandmarks34x2 = Array(34) { FloatArray(2) },
            isGazeAttentive = true,
            executionTimeMs = 3.8f
        )
        assertTrue(eyeGazeResult.isGazeAttentive)
    }
}
