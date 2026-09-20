package app.veil.privacy

import kotlin.math.sin
import kotlin.random.Random

/** Synthetic "city skyline with people" scenes used by the image regression tests. */
object TestImages {

    fun skyline(width: Int, height: Int, seed: Int = 7): PixelImage {
        val rnd = Random(seed)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sky = y < height / 2
                val base = if (sky) {
                    val t = y.toFloat() / (height / 2f)
                    rgb((30 + 60 * t).toInt(), (80 + 90 * t).toInt(), (150 + 80 * t).toInt())
                } else {
                    // buildings: strong vertical/horizontal high frequency detail (windows)
                    val building = (x / 37) % 3
                    val window = if ((x % 11) < 5 && (y % 9) < 4) 205 else 45
                    val shade = 30 + building * 18
                    rgb(shade + window / 4, shade + window / 5, shade + window / 3)
                }
                val noise = rnd.nextInt(-9, 10)
                pixels[y * width + x] = addNoise(base, noise) or (0xFF shl 24)
            }
        }
        return PixelImage(width, height, pixels)
    }

    /** Draws a detailed synthetic face so that "detail removed" is measurable. */
    fun drawFace(image: PixelImage, region: FaceRegion, seed: Int = 3) {
        val rnd = Random(seed)
        val roi = region.bounds(pad = 1f).clampTo(image.width, image.height)
        for (y in roi.top until roi.bottom) {
            for (x in roi.left until roi.right) {
                if (!PrivacyAudit.contains(region, x + 0.5f, y + 0.5f)) continue
                val fx = (x - region.centerX) / region.radiusX
                val fy = (y - region.centerY) / region.radiusY
                val skin = rgb(225, 180, 150)
                val features = (sin(fx * 19f) * sin(fy * 23f) * 70f).toInt() + rnd.nextInt(-25, 26)
                image.pixels[y * image.width + x] = addNoise(skin, features) or (0xFF shl 24)
            }
        }
    }

    fun scene(width: Int = 900, height: Int = 700, faces: List<FaceRegion>): Pair<PixelImage, List<FaceRegion>> {
        val img = skyline(width, height)
        faces.forEachIndexed { i, f -> drawFace(img, f, seed = 3 + i) }
        return img to faces
    }

    fun rgb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private fun addNoise(color: Int, delta: Int): Int = rgb(
        (color shr 16 and 0xFF) + delta,
        (color shr 8 and 0xFF) + delta,
        (color and 0xFF) + delta,
    )
}
