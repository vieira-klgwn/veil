package app.veil.privacy

import kotlin.math.max
import kotlin.math.min

/**
 * Separable box blur run three times, which closely approximates a Gaussian
 * blur at a fraction of the cost. Works on a small region of interest only,
 * so cost scales with face size and not with photo size.
 */
internal object BoxBlur {

    fun blur(pixels: IntArray, width: Int, height: Int, radius: Int): IntArray {
        if (radius < 1 || width <= 0 || height <= 0) return pixels
        var src = pixels
        var dst = IntArray(pixels.size)
        repeat(3) {
            horizontal(src, dst, width, height, radius)
            val tmp = IntArray(pixels.size)
            vertical(dst, tmp, width, height, radius)
            src = tmp
            dst = IntArray(pixels.size)
        }
        return src
    }

    private fun horizontal(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val r = min(radius, max(0, w - 1))
        val div = 2 * r + 1
        for (y in 0 until h) {
            val row = y * w
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[row + i.coerceIn(0, w - 1)]
                sa += p ushr 24 and 0xFF; sr += p shr 16 and 0xFF
                sg += p shr 8 and 0xFF; sb += p and 0xFF
            }
            for (x in 0 until w) {
                dst[row + x] = (sa / div shl 24) or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val out = src[row + (x - r).coerceIn(0, w - 1)]
                val inc = src[row + (x + r + 1).coerceIn(0, w - 1)]
                sa += (inc ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                sr += (inc shr 16 and 0xFF) - (out shr 16 and 0xFF)
                sg += (inc shr 8 and 0xFF) - (out shr 8 and 0xFF)
                sb += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }

    private fun vertical(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int) {
        val r = min(radius, max(0, h - 1))
        val div = 2 * r + 1
        for (x in 0 until w) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (i in -r..r) {
                val p = src[i.coerceIn(0, h - 1) * w + x]
                sa += p ushr 24 and 0xFF; sr += p shr 16 and 0xFF
                sg += p shr 8 and 0xFF; sb += p and 0xFF
            }
            for (y in 0 until h) {
                dst[y * w + x] = (sa / div shl 24) or (sr / div shl 16) or (sg / div shl 8) or (sb / div)
                val out = src[(y - r).coerceIn(0, h - 1) * w + x]
                val inc = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                sa += (inc ushr 24 and 0xFF) - (out ushr 24 and 0xFF)
                sr += (inc shr 16 and 0xFF) - (out shr 16 and 0xFF)
                sg += (inc shr 8 and 0xFF) - (out shr 8 and 0xFF)
                sb += (inc and 0xFF) - (out and 0xFF)
            }
        }
    }
}
