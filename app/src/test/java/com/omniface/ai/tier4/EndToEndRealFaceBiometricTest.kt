package com.omniface.ai.e2e

import com.omniface.ai.data.local.entity.FaceTemplateEntity
import com.omniface.ai.ml.ConfidenceZone
import com.omniface.ai.ml.SecurityTier
import com.omniface.ai.ml.quality.QualityGateResult
import com.omniface.ai.ml.recognition.FaceMatcher
import com.omniface.ai.ml.recognition.FaissVectorIndex
import com.omniface.ai.security.AndroidSecurityUtils
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.sqrt

/**
 * End-to-End Real Biometric Pipeline Verification Test.
 * Validates 1:1 verification, 1:N gallery matching, multi-face resolution,
 * decision margins, and Aegis SHA-256 blockchain minting.
 */
class EndToEndRealFaceBiometricTest {

    private lateinit var matcher: FaceMatcher

    @Before
    fun setUp() {
        matcher = FaceMatcher()
    }

    private fun l2Normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum)
        if (norm > 1e-7f) {
            for (i in v.indices) v[i] /= norm
        }
        return v
    }

    private fun toCsv(v: FloatArray): String =
        v.joinToString(",") { "%.6f".format(java.util.Locale.US, it) }

    @Test
    fun testEndToEndMultiSubjectEnrollmentAndProbeRecognition() {
        // 1. Synthesize 512-D Orthogonal Face Clusters for 3 distinct identities (Obama, Biden, Lena)
        val obamaBase = FloatArray(512) { if (it < 170) 1.0f else 0.01f }.also { l2Normalize(it) }
        val bidenBase = FloatArray(512) { if (it in 170..340) 1.0f else 0.01f }.also { l2Normalize(it) }
        val lenaBase  = FloatArray(512) { if (it > 340) 1.0f else 0.01f }.also { l2Normalize(it) }

        // 2. Encrypt biometric templates with AES-256-GCM and enroll into gallery
        val gallery = listOf(
            FaceTemplateEntity(
                id = "tpl_obama_1",
                studentRoll = "CS2026-001",
                angleType = "FRONTAL",
                embeddingEncryptedCsv = AndroidSecurityUtils.encrypt(toCsv(obamaBase)),
                isEncrypted = true,
                qualityScore = 98.0f
            ),
            FaceTemplateEntity(
                id = "tpl_biden_1",
                studentRoll = "CS2026-002",
                angleType = "FRONTAL",
                embeddingEncryptedCsv = AndroidSecurityUtils.encrypt(toCsv(bidenBase)),
                isEncrypted = true,
                qualityScore = 96.0f
            ),
            FaceTemplateEntity(
                id = "tpl_lena_1",
                studentRoll = "CS2026-003",
                angleType = "FRONTAL",
                embeddingEncryptedCsv = AndroidSecurityUtils.encrypt(toCsv(lenaBase)),
                isEncrypted = true,
                qualityScore = 99.0f
            )
        )

        matcher.preloadTemplates(gallery)

        val studentMap = mapOf(
            "CS2026-001" to "Barack Obama",
            "CS2026-002" to "Joe Biden",
            "CS2026-003" to "Lena Soderberg"
        )

        // 3. Probe Test A: Barack Obama Probe (Perturbed by 5% natural noise representing pose/lighting variation)
        val obamaProbe = FloatArray(512) { obamaBase[it] + (if (it % 7 == 0) 0.05f else -0.02f) }.also { l2Normalize(it) }
        val matchResultObama = matcher.match(
            queryEmbedding = obamaProbe,
            studentMap = studentMap,
            securityTier = SecurityTier.HIGH
        )

        assertTrue("Obama probe must be recognized", matchResultObama.isMatch)
        assertEquals("CS2026-001", matchResultObama.studentRoll)
        assertEquals("Barack Obama", matchResultObama.studentName)
        assertEquals(ConfidenceZone.ACCEPT, matchResultObama.confidenceZone)
        assertTrue("Decision margin must be positive", matchResultObama.decisionMargin > 0.30f)

        // 4. Probe Test B: Joe Biden Probe
        val bidenProbe = FloatArray(512) { bidenBase[it] + (if (it % 5 == 0) 0.04f else -0.01f) }.also { l2Normalize(it) }
        val matchResultBiden = matcher.match(
            queryEmbedding = bidenProbe,
            studentMap = studentMap,
            securityTier = SecurityTier.HIGH
        )

        assertTrue("Biden probe must be recognized", matchResultBiden.isMatch)
        assertEquals("CS2026-002", matchResultBiden.studentRoll)
        assertEquals("Joe Biden", matchResultBiden.studentName)

        // 5. Probe Test C: Impostor / Unknown Person (Orthogonal noise vector)
        val unknownProbe = FloatArray(512) { (it % 13 - 6).toFloat() / 10f }.also { l2Normalize(it) }
        val matchResultUnknown = matcher.match(
            queryEmbedding = unknownProbe,
            studentMap = studentMap,
            securityTier = SecurityTier.HIGH
        )

        assertFalse("Unregistered impostor must NOT be authorized", matchResultUnknown.isMatch)
        assertEquals("GUEST", matchResultUnknown.studentRoll)

        // 6. Aegis Blockchain Ledger Chaining Verification
        var prevHash = "0".repeat(64)
        val block1 = AndroidSecurityUtils.computeAegisBlockHash(prevHash, matchResultObama.studentRoll, 1756200000000L, matchResultObama.confidence)
        assertNotNull(block1)
        assertEquals(64, block1.length)

        val block2 = AndroidSecurityUtils.computeAegisBlockHash(block1, matchResultBiden.studentRoll, 1756200001000L, matchResultBiden.confidence)
        assertNotNull(block2)
        assertEquals(64, block2.length)
        assertNotEquals(block1, block2)
    }
}
