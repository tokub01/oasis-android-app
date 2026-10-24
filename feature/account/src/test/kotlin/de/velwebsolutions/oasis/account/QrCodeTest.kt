package de.velwebsolutions.oasis.account

import de.velwebsolutions.oasis.account.ui.components.QrCode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The QR encoder of the two-factor setup against the standard's worked examples. */
class QrCodeTest {
    @Test
    fun computesTheReedSolomonBytesOfTheStandardsExample() {
        // "HELLO WORLD" as version 1-M (thonky.com's QR tutorial): 16 data codewords, 10 error correction codewords.
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64, 236, 17, 236, 17, 236, 17).map { it.toByte() }.toByteArray()
        val ecc = intArrayOf(196, 35, 39, 119, 235, 215, 231, 226, 93, 23).map { it.toByte() }.toByteArray()
        assertArrayEquals(ecc, QrCode.reedSolomon(data, 10))
    }

    @Test
    fun writesTheFormatBitsOfLevelM() {
        val expected = listOf(
            "101010000010010",
            "101000100100101",
            "101111001111100",
            "101101101001011",
            "100010111111001",
            "100000011001110",
            "100111110010111",
            "100101010100000"
        )
        assertEquals(expected, (0 until 8).map { QrCode.formatBits(it).toString(2).padStart(15, '0') })
    }

    @Test
    fun padsTheDataLikeTheStandard() {
        val codewords = QrCode.dataCodewords("ab".toByteArray(), 1)
        assertEquals("16 data codewords in 1-M", 16, codewords.size)
        // 0100 (byte mode), 00000010 (count), 'a', 'b', 0000 terminator, then 0xEC 0x11 …
        assertEquals(0x40, codewords[0].toInt() and 0xFF)
        assertEquals(0x26, codewords[1].toInt() and 0xFF)
        assertEquals(0x16, codewords[2].toInt() and 0xFF)
        assertEquals(0x20, codewords[3].toInt() and 0xFF)
        assertEquals(0xEC, codewords[4].toInt() and 0xFF)
        assertEquals(0x11, codewords[5].toInt() and 0xFF)
    }

    @Test
    fun drawsTheFunctionPatternsOfAnOtpauthUrl() {
        val url = "otpauth://totp/Oasis:mira%40demo.oasis.tld?secret=ABCDEFGHIJKLMNOP&issuer=Oasis"
        val code = assertNotNull(QrCode.encode(url)).let { QrCode.encode(url)!! }
        assertEquals(code.version * 4 + 17, code.size)
        assertTrue("an otpauth URL fits into a small version", code.version in 4..7)
        // Finder pattern in the top left: dark ring, light ring, dark 3×3 core.
        for (i in 0..6) {
            assertTrue(code[i, 0] && code[0, i] && code[i, 6] && code[6, i])
        }
        assertTrue(!code[1, 1] && !code[5, 5] && code[3, 3])
        // Timing pattern and the dark module.
        for (i in 8 until code.size - 8) assertEquals(i % 2 == 0, code[i, 6])
        assertTrue(code[8, code.size - 8])
        // The format bits are written twice and agree.
        val bits = QrCode.formatBits(code.mask)
        for (i in 0..5) assertEquals((bits ushr i) and 1 == 1, code[8, i])
        for (i in 0 until 8) assertEquals((bits ushr i) and 1 == 1, code[code.size - 1 - i, 8])
    }

    @Test
    fun interleavesBlocksOfDifferentLengths() {
        // Version 5-M has two blocks of 43 data + 24 error correction codewords: 134 codewords in all.
        val data = ByteArray(86) { it.toByte() }
        val all = QrCode.addEccAndInterleave(data, 5)
        assertEquals(QrCode.rawDataModules(5) / 8, all.size)
        assertEquals(0.toByte(), all[0])
        assertEquals(43.toByte(), all[1])
        assertEquals(1.toByte(), all[2])
    }
}
