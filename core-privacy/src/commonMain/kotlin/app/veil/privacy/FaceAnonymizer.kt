package app.veil.privacy

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeSource

/**
 * Applies a privacy effect to face regions only. Pixels outside the feathered
 * ellipse of a face are never written, so the rest of the photograph keeps its
 * original bytes.
 */
object FaceAnonymizer {

    /** Width of the soft edge, as a fraction of the ellipse radius. */
    private const val FEATHER = 0.22f

    /** Minimum effect size in pixels so that tiny faces are still unreadable. */
    private const val MIN_BLUR_RADIUS = 5
    private const val MIN_BLOCK_SIZE = 3

    fun anonymize(
        image: PixelImage,
        faces: List<FaceRegion>,
        effect: PrivacyEffect = PrivacyEffect.BLUR,
        strength: PrivacyStrength = PrivacyStrength.BALANCED,
        maskColor: Int = 0xFF141821.toInt(),
    ): AnonymizationResult {
        val start = TimeSource.Monotonic.markNow()
        var modified = 0
        var protectedFaces = 0
        for (raw in faces) {
            val face = raw.scaled(strength.marginFactor)
            val touched = protectFace(image, face, effect, strength, maskColor)
            if (touched > 0) {
                modified += touched
                protectedFaces++
            }
        }
        return AnonymizationResult(
            facesProtected = protectedFaces,
            modifiedPixels = modified,
            totalPixels = image.width * image.height,
            elapsedMillis = start.elapsedNow().inWholeMilliseconds,
        )
    }

    private fun protectFace(
        image: PixelImage,
        face: FaceRegion,
        effect: PrivacyEffect,
        strength: PrivacyStrength,
        maskColor: Int,
    ): Int {
        val roi = face.bounds(pad = 2f).clampTo(image.width, image.height)
        if (roi.isEmpty()) return 0

        val w = roi.width
        val h = roi.height
        val src = IntArray(w * h)
        for (y in 0 until h) {
            val from = (roi.top + y) * image.width + roi.left
            image.pixels.copyInto(src, y * w, from, from + w)
        }

        val minRadius = min(face.radiusX, face.radiusY)
        val processed = when (effect) {
            PrivacyEffect.BLUR -> {
                val radius = max(MIN_BLUR_RADIUS, (minRadius * 0.55f * strength.intensity).roundToInt())
                BoxBlur.blur(src, w, h, radius)
            }
            PrivacyEffect.PIXELATE -> {
                val block = max(MIN_BLOCK_SIZE, (minRadius * 2f / (8f / strength.intensity)).roundToInt())
                pixelate(src, w, h, block)
            }
            PrivacyEffect.MASK -> IntArray(w * h) { maskColor }
        }

        val rad = radians(face.rotationDegrees)
        val cosR = cos(rad).toFloat()
        val sinR = sin(rad).toFloat()
        val inner = 1f - FEATHER
        var touched = 0

        for (y in 0 until h) {
            val py = roi.top + y + 0.5f - face.centerY
            val rowBase = (roi.top + y) * image.width + roi.left
            for (x in 0 until w) {
                val px = roi.left + x + 0.5f - face.centerX
                val lx = (px * cosR + py * sinR) / face.radiusX
                val ly = (-px * sinR + py * cosR) / face.radiusY
                val d = sqrt(lx * lx + ly * ly)
                if (d >= 1f) continue
                val alpha = if (d <= inner) 1f else smoothstep((1f - d) / FEATHER)
                if (alpha <= 0.002f) continue
                val dstIndex = rowBase + x
                image.pixels[dstIndex] = blend(image.pixels[dstIndex], processed[y * w + x], alpha)
                touched++
            }
        }
        return touched
    }

    private fun pixelate(src: IntArray, w: Int, h: Int, block: Int): IntArray {
        val out = IntArray(src.size)
        var by = 0
        while (by < h) {
            var bx = 0
            while (bx < w) {
                val x1 = min(bx + block, w)
                val y1 = min(by + block, h)
                var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (y in by until y1) {
                    var i = y * w + bx
                    for (x in bx until x1) {
                        val p = src[i++]
                        a += (p ushr 24 and 0xFF).toLong(); r += (p shr 16 and 0xFF).toLong()
                        g += (p shr 8 and 0xFF).toLong(); b += (p and 0xFF).toLong()
                        n++
                    }
                }
                val avg = ((a / n).toInt() shl 24) or ((r / n).toInt() shl 16) or
                    ((g / n).toInt() shl 8) or (b / n).toInt()
                for (y in by until y1) {
                    var i = y * w + bx
                    for (x in bx until x1) out[i++] = avg
                }
                bx += block
            }
            by += block
        }
        return out
    }

    private fun blend(dst: Int, src: Int, alpha: Float): Int {
        if (alpha >= 0.998f) return src
        val ia = 1f - alpha
        val a = ((dst ushr 24 and 0xFF) * ia + (src ushr 24 and 0xFF) * alpha).roundToInt()
        val r = ((dst shr 16 and 0xFF) * ia + (src shr 16 and 0xFF) * alpha).roundToInt()
        val g = ((dst shr 8 and 0xFF) * ia + (src shr 8 and 0xFF) * alpha).roundToInt()
        val b = ((dst and 0xFF) * ia + (src and 0xFF) * alpha).roundToInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun smoothstep(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Fraction of pixels that differ between two same sized images. */
    fun changedFraction(a: PixelImage, b: PixelImage, threshold: Int = 6): Float {
        require(a.width == b.width && a.height == b.height)
        var changed = 0
        for (i in a.pixels.indices) {
            if (channelDistance(a.pixels[i], b.pixels[i]) > threshold) changed++
        }
        return changed.toFloat() / a.pixels.size
    }

    internal fun channelDistance(p: Int, q: Int): Int = max(
        abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)),
        max(abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)), abs((p and 0xFF) - (q and 0xFF))),
    )
}
