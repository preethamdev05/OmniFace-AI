package com.omniface.ai.ml

import androidx.compose.ui.geometry.Rect
import com.omniface.ai.hardware.QrBarcode2FaScanner
import com.omniface.ai.hardware.TwoFactorStatus
import com.omniface.ai.ml.pipeline.BiometricSynthesisDecision
import com.omniface.ai.ml.pipeline.PipelineGateState
import com.omniface.ai.ml.tracking.FaceTracker
import com.omniface.ai.ml.tracking.IdentityClassification
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FaceTrackerDisambiguationTest {

    private lateinit var tracker: FaceTracker

    @Before
    fun setUp() {
        tracker = FaceTracker()
    }

    @Test
    fun testNoTrackHijacking_differentMlKitTrackIds() {
        val rect1 = Rect(100f, 100f, 250f, 250f)
        val state1 = tracker.getOrCreateTrackState(mlKitTrackId = 101, rawRect = rect1)
        assertEquals(101, state1.trackId)

        // Lock state 1 to Student Alice
        val aliceDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "CS2026-ALICE",
            matchedStudentName = "Alice Smith",
            matchConfidence = 92f,
            matchSimilarity = 0.85f,
            decisionMargin = 0.25f,
            qualityScore = 95f,
            livenessScore = 0.98f,
            title = "AUTHENTICATED: ALICE SMITH",
            subtitle = "Roll: CS2026-ALICE",
            technicalExplanation = "Passed all gates"
        )
        val stabilized1 = tracker.stabilizeDecision(101, aliceDecision)
        assertTrue(stabilized1.isAttendanceAuthorized)
        assertEquals("CS2026-ALICE", stabilized1.matchedStudentRoll)
        assertTrue(state1.isClassificationLocked)

        // Second face arrives nearby with its own ML Kit ID 102
        val rect2 = Rect(120f, 120f, 270f, 270f)
        val state2 = tracker.getOrCreateTrackState(mlKitTrackId = 102, rawRect = rect2)

        // State 2 MUST have assigned ID 102, MUST NOT hijack State 1, and MUST NOT inherit Alice's identity
        assertEquals(102, state2.trackId)
        assertFalse(state2.isClassificationLocked)
        assertTrue(state2.studentRoll.isBlank())
        assertTrue(state2.studentName.isBlank())
        assertEquals(IdentityClassification.UNCONFIRMED, state2.classification)
    }

    @Test
    fun testIdentitySwitch_differentEnrolledStudentAuthorizes() {
        val rect = Rect(150f, 150f, 300f, 300f)
        val state = tracker.getOrCreateTrackState(mlKitTrackId = 201, rawRect = rect)

        // Lock to Alice
        val aliceDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "CS-ALICE",
            matchedStudentName = "Alice",
            matchConfidence = 90f,
            matchSimilarity = 0.80f,
            decisionMargin = 0.20f,
            qualityScore = 90f,
            livenessScore = 0.95f,
            title = "ALICE",
            subtitle = "CS-ALICE",
            technicalExplanation = "Gate pass"
        )
        tracker.stabilizeDecision(201, aliceDecision)
        assertEquals("CS-ALICE", state.studentRoll)

        // Next, a different enrolled student Bob steps in on the same track
        val bobDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "CS-BOB",
            matchedStudentName = "Bob Jones",
            matchConfidence = 88f,
            matchSimilarity = 0.78f,
            decisionMargin = 0.18f,
            qualityScore = 92f,
            livenessScore = 0.96f,
            title = "BOB",
            subtitle = "CS-BOB",
            technicalExplanation = "Gate pass"
        )
        val stabilizedBob = tracker.stabilizeDecision(201, bobDecision)

        // The lock must immediately switch to Bob
        assertEquals("CS-BOB", stabilizedBob.matchedStudentRoll)
        assertEquals("Bob Jones", stabilizedBob.matchedStudentName)
        assertEquals("CS-BOB", state.studentRoll)
        assertEquals("Bob Jones", state.studentName)
        assertTrue(stabilizedBob.isAttendanceAuthorized)
    }

    @Test
    fun testUnknownVisitor_doesNotInheritLockedIdentity() {
        val rect = Rect(100f, 100f, 250f, 250f)
        val state = tracker.getOrCreateTrackState(mlKitTrackId = 301, rawRect = rect)

        // Lock to Alice
        val aliceDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "CS-ALICE",
            matchedStudentName = "Alice",
            matchConfidence = 90f,
            matchSimilarity = 0.80f,
            decisionMargin = 0.20f,
            qualityScore = 90f,
            livenessScore = 0.95f,
            title = "ALICE",
            subtitle = "CS-ALICE",
            technicalExplanation = "Gate pass"
        )
        tracker.stabilizeDecision(301, aliceDecision)
        assertTrue(state.isClassificationLocked)

        // Face changes to an unknown stranger (similarity 0.40, typical non-match)
        val strangerDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.REJECT_UNKNOWN_IDENTITY,
            isAttendanceAuthorized = false,
            matchedStudentRoll = "GUEST",
            matchedStudentName = "Unknown Visitor",
            matchConfidence = 0f,
            matchSimilarity = 0.40f,
            decisionMargin = 0f,
            qualityScore = 90f,
            livenessScore = 0.95f,
            title = "UNKNOWN IDENTITY",
            subtitle = "No enrolled match",
            technicalExplanation = "Low similarity"
        )
        val stabilizedStranger = tracker.stabilizeDecision(301, strangerDecision)

        // Lock MUST be revoked because matchSimilarity < 0.58f
        assertFalse("Locked identity should be cleared when stranger arrives", state.isClassificationLocked)
        assertFalse("Attendance MUST NOT be authorized for unknown stranger", stabilizedStranger.isAttendanceAuthorized)
        assertEquals(IdentityClassification.UNKNOWN, state.classification)
        assertTrue(state.studentRoll.isBlank())
    }

    @Test
    fun testSpoofAttack_immediatelyRevokesLockedIdentity() {
        val rect = Rect(100f, 100f, 250f, 250f)
        val state = tracker.getOrCreateTrackState(mlKitTrackId = 401, rawRect = rect)

        // Lock to Alice
        val aliceDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "CS-ALICE",
            matchedStudentName = "Alice",
            matchConfidence = 90f,
            matchSimilarity = 0.80f,
            decisionMargin = 0.20f,
            qualityScore = 90f,
            livenessScore = 0.95f,
            title = "ALICE",
            subtitle = "CS-ALICE",
            technicalExplanation = "Gate pass"
        )
        tracker.stabilizeDecision(401, aliceDecision)
        assertTrue(state.isClassificationLocked)

        // Spoof attack occurs
        val spoofDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.REJECT_SPOOF_ATTACK,
            isAttendanceAuthorized = false,
            matchedStudentRoll = "",
            matchedStudentName = "",
            matchConfidence = 0f,
            matchSimilarity = 0f,
            decisionMargin = 0f,
            qualityScore = 80f,
            livenessScore = 0.12f,
            title = "SPOOF ATTACK DETECTED",
            subtitle = "Photo print attack",
            technicalExplanation = "Gate 2 failed"
        )
        val stabilizedSpoof = tracker.stabilizeDecision(401, spoofDecision)

        assertFalse(state.isClassificationLocked)
        assertFalse(stabilizedSpoof.isAttendanceAuthorized)
        assertEquals(IdentityClassification.SPOOF_ATTACK, state.classification)
        assertTrue(state.studentRoll.isBlank())
        assertTrue(state.studentName.isBlank())
    }

    @Test
    fun testQrBarcode2FaScannerToggle_persistenceAndCorrelation() {
        // Toggle OFF
        QrBarcode2FaScanner.setTwoFactorEnabled(null, false)
        assertFalse(QrBarcode2FaScanner.isTwoFactorModeEnabled)

        // Toggle ON
        QrBarcode2FaScanner.setTwoFactorEnabled(null, true)
        assertTrue(QrBarcode2FaScanner.isTwoFactorModeEnabled)

        // Verify correlation: Match
        val matchRes = QrBarcode2FaScanner.correlateBiometricAndCard("CS-101", "CS-101")
        assertEquals(TwoFactorStatus.TWO_FA_PASS, matchRes.status)

        // Verify correlation: Fraud Mismatch
        val fraudRes = QrBarcode2FaScanner.correlateBiometricAndCard("CS-IMPOSTOR", "CS-VICTIM")
        assertEquals(TwoFactorStatus.TWO_FA_MISMATCH_FRAUD, fraudRes.status)
    }

    @Test
    fun testMultipleFacesInSameFrame_distinctTracksAssigned() {
        val claimedSet = mutableSetOf<Int>()
        val rect1 = Rect(100f, 100f, 200f, 200f)
        val rect2 = Rect(120f, 100f, 220f, 200f) // Nearby face in same frame

        val state1 = tracker.getOrCreateTrackState(mlKitTrackId = 0, rawRect = rect1, claimedTrackIds = claimedSet)
        val state2 = tracker.getOrCreateTrackState(mlKitTrackId = 0, rawRect = rect2, claimedTrackIds = claimedSet)

        assertNotEquals("Two faces in the same frame must have distinct track IDs", state1.trackId, state2.trackId)
        assertTrue("Claimed track set must contain both track IDs", claimedSet.contains(state1.trackId))
        assertTrue("Claimed track set must contain both track IDs", claimedSet.contains(state2.trackId))
    }

    @Test
    fun testUnknownVisitor_nearThresholdSimilarity_immediatelyClearsLock() {
        val rect = Rect(100f, 100f, 250f, 250f)
        val state = tracker.getOrCreateTrackState(mlKitTrackId = 501, rawRect = rect)

        // Lock to Alice
        val aliceDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.PASS,
            isAttendanceAuthorized = true,
            matchedStudentRoll = "CS-ALICE",
            matchedStudentName = "Alice",
            matchConfidence = 92f,
            matchSimilarity = 0.85f,
            decisionMargin = 0.22f,
            qualityScore = 95f,
            livenessScore = 0.98f,
            title = "ALICE",
            subtitle = "CS-ALICE",
            technicalExplanation = "Gate pass"
        )
        tracker.stabilizeDecision(501, aliceDecision)
        assertTrue(state.isClassificationLocked)
        assertEquals("CS-ALICE", state.studentRoll)

        // Stranger arrives with similarity 0.65 (which is >= 0.58, but rejected by FaceMatcher because 0.65 < 0.72)
        val strangerDecision = BiometricSynthesisDecision(
            gateState = PipelineGateState.REJECT_UNKNOWN_IDENTITY,
            isAttendanceAuthorized = false,
            matchedStudentRoll = "GUEST",
            matchedStudentName = "Unknown Visitor",
            matchConfidence = 50f,
            matchSimilarity = 0.65f,
            decisionMargin = 0.05f,
            qualityScore = 92f,
            livenessScore = 0.95f,
            title = "UNKNOWN IDENTITY",
            subtitle = "Visitor / Unregistered",
            technicalExplanation = "Score 0.650 < threshold 0.720"
        )
        val stabilizedStranger = tracker.stabilizeDecision(501, strangerDecision)

        assertFalse("Locked identity MUST be revoked on unknown visitor decision", state.isClassificationLocked)
        assertFalse("Attendance MUST NOT be authorized for stranger", stabilizedStranger.isAttendanceAuthorized)
        assertEquals(IdentityClassification.UNKNOWN, state.classification)
        assertTrue("Student roll must be blanked on unknown face", state.studentRoll.isBlank())
        assertTrue("Student name must be blanked on unknown face", state.studentName.isBlank())
    }

    @Test
    fun testFaceMatcher_emptyEmbedding_returnsSafeReject() {
        val matcher = com.omniface.ai.ml.recognition.FaceMatcher()
        val templates = listOf(
            com.omniface.ai.data.local.entity.FaceTemplateEntity("t1", "R001", "FRONTAL", "0.1,0.2,0.3", false)
        )
        matcher.preloadTemplates(templates)

        val result = matcher.match(
            queryEmbedding = FloatArray(0),
            studentMap = mapOf("R001" to "Alice"),
            securityTier = SecurityTier.HIGH
        )

        assertFalse("Empty query embedding must not match", result.isMatch)
        assertEquals("GUEST", result.studentRoll)
        assertEquals(ConfidenceZone.REJECT, result.confidenceZone)
        assertEquals(0.0f, result.confidence, 0.001f)
    }
}
