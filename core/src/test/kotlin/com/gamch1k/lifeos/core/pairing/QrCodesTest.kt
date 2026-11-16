package com.gamch1k.lifeos.core.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class QrCodesTest {
    private val link = "lifeos://pair?host=https%3A%2F%2Flifeos.example.com&code=K7QM-2XRP-9VDA-H4TC"

    /** The QR code as a photo would have it: [size] pixels, somewhere in a larger grey picture. */
    private fun photo(text: String, size: Int = 300, width: Int = 800, height: Int = 600, inverted: Boolean = false): IntArray {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val dark = if (inverted) WHITE else BLACK
        val light = if (inverted) BLACK else WHITE
        val pixels = IntArray(width * height) { GREY }
        val left = (width - matrix.width) / 3
        val top = (height - matrix.height) / 2
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                pixels[(top + y) * width + left + x] = if (matrix[x, y]) dark else light
            }
        }
        return pixels
    }

    @Test
    fun `the pairing link is read from a photo of the Dashboard's QR code`() {
        assertEquals(link, QrCodes.decode(photo(link), 800, 600))
        assertTrue(PairingLink.parse(QrCodes.decode(photo(link), 800, 600)!!) is PairingLink.Code)
    }

    @Test
    fun `a light-on-dark code (a dark screen) is read too`() {
        assertEquals(link, QrCodes.decode(photo(link, inverted = true), 800, 600))
    }

    @Test
    fun `a photo without a code reads as nothing`() {
        val random = Random(7)
        val noise = IntArray(400 * 300) { if (random.nextBoolean()) BLACK else WHITE }
        assertNull(QrCodes.decode(noise, 400, 300))
        assertNull(QrCodes.decode(IntArray(200 * 200) { WHITE }, 200, 200))
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF9E9E9E.toInt()
    }
}
