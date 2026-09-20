package app.veil.privacy

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YuvConverterTest {

    private fun convert(pixels: IntArray, width: Int, height: Int): Triple<ByteArray, ByteArray, ByteArray> {
        val chroma = ((width + 1) / 2) * ((height + 1) / 2)
        val y = ByteArray(width * height)
        val u = ByteArray(chroma)
        val v = ByteArray(chroma)
        YuvConverter.toI420(pixels, width, height, y, u, v)
        return Triple(y, u, v)
    }

    private fun ByteArray.at(index: Int) = this[index].toInt() and 0xFF

    @Test
    fun `grey scale stays neutral`() {
        val pixels = IntArray(4) { 0xFF808080.toInt() }
        val (y, u, v) = convert(pixels, 2, 2)
        assertTrue(abs(y.at(0) - 126) <= 2)
        assertTrue(abs(u.at(0) - 128) <= 2)
        assertTrue(abs(v.at(0) - 128) <= 2)
    }

    @Test
    fun `black and white hit the limited range ends`() {
        val (yBlack) = convert(IntArray(4) { 0xFF000000.toInt() }, 2, 2)
        val (yWhite) = convert(IntArray(4) { 0xFFFFFFFF.toInt() }, 2, 2)
        assertEquals(16, yBlack.at(0))
        assertTrue(yWhite.at(0) >= 234)
    }

    @Test
    fun `red and blue move chroma in opposite directions`() {
        val (_, uRed, vRed) = convert(IntArray(4) { 0xFFFF0000.toInt() }, 2, 2)
        val (_, uBlue, vBlue) = convert(IntArray(4) { 0xFF0000FF.toInt() }, 2, 2)
        assertTrue(vRed.at(0) > 200)
        assertTrue(uRed.at(0) < 100)
        assertTrue(uBlue.at(0) > 200)
        assertTrue(vBlue.at(0) < 128)
    }

    @Test
    fun `chroma is subsampled per two by two block`() {
        val width = 4
        val height = 4
        val pixels = IntArray(width * height) { 0xFF102030.toInt() }
        val (y, u, v) = convert(pixels, width, height)
        assertEquals(width * height, y.size)
        assertEquals(4, u.size)
        assertEquals(4, v.size)
        assertTrue(u.all { it == u[0] })
        assertTrue(v.all { it == v[0] })
    }
}
