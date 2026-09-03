package com.omniface.ai.hardware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class QrBadgeGeneratorTest {

    @Test
    fun testGenerateStudentQrBitMatrix_validRollNumber() {
        val matrix = QrBadgeGenerator.generateBitMatrix("CS2026-001", sizePx = 256)
        assertNotNull("Generated QR matrix should not be null", matrix)
        assertEquals(256, matrix!!.width)
        assertEquals(256, matrix.height)
    }

    @Test
    fun testGenerateStudentQrBitMatrix_blankRollNumberReturnsNull() {
        val matrix = QrBadgeGenerator.generateBitMatrix("", sizePx = 256)
        assertNull("Blank content should return null", matrix)
    }

    @Test
    fun testTwoFactorCorrelation_matchingBadgeAndFace() {
        val result = QrBarcode2FaScanner.correlateBiometricAndCard(
            scannedCardRoll = "ROLL-404",
            matchedFaceRoll = "ROLL-404"
        )
        assertEquals(TwoFactorStatus.TWO_FA_PASS, result.status)
        assertEquals(true, result.isCardPresent)
    }

    @Test
    fun testTwoFactorCorrelation_mismatchingFraudBadge() {
        val result = QrBarcode2FaScanner.correlateBiometricAndCard(
            scannedCardRoll = "ROLL-IMPOSTOR",
            matchedFaceRoll = "ROLL-VICTIM"
        )
        assertEquals(TwoFactorStatus.TWO_FA_MISMATCH_FRAUD, result.status)
        assertEquals(true, result.isCardPresent)
    }

    @Test
    fun testTwoFactorCorrelation_noCardFaceOnlyFallback() {
        val result = QrBarcode2FaScanner.correlateBiometricAndCard(
            scannedCardRoll = null,
            matchedFaceRoll = "ROLL-101"
        )
        assertEquals(TwoFactorStatus.TWO_FA_FACE_ONLY_FALLBACK, result.status)
        assertEquals(false, result.isCardPresent)
    }
}
