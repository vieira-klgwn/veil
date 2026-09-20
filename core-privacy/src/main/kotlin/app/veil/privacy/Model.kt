package app.veil.privacy

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A mutable ARGB_8888 image buffer. Platform independent so the anonymization
 * pipeline can be shared between Android, JVM tests and (later) other targets.
 */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(pixels.size == width * height) { "pixel buffer does not match dimensions" }
    }

    fun copy(): PixelImage = PixelImage(width, height, pixels.copyOf())

    inline fun index(x: Int, y: Int): Int = y * width + x
}

/**
 * The privacy region for a single face: a rotated ellipse in image coordinates.
 * An ellipse follows the shape of a head far more closely than a bounding box,
 * which is what keeps the protected area small.
 */
data class FaceRegion(
    val centerX: Float,
    val centerY: Float,
    val radiusX: Float,
    val radiusY: Float,
    val rotationDegrees: Float = 0f,
) {
    val area: Float get() = (Math.PI * radiusX * radiusY).toFloat()

    /** Axis aligned bounds of the rotated ellipse, expanded by [pad] pixels. */
    fun bounds(pad: Float = 0f): IntRect {
        val rad = Math.toRadians(rotationDegrees.toDouble())
        val c = kotlin.math.cos(rad)
        val s = kotlin.math.sin(rad)
        val halfW = kotlin.math.sqrt(radiusX * radiusX * c * c + radiusY * radiusY * s * s)
        val halfH = kotlin.math.sqrt(radiusX * radiusX * s * s + radiusY * radiusY * c * c)
        return IntRect(
            left = (centerX - halfW - pad).toInt(),
            top = (centerY - halfH - pad).toInt(),
            right = (centerX + halfW + pad).roundToInt() + 1,
            bottom = (centerY + halfH + pad).roundToInt() + 1,
        )
    }

    fun scaled(factor: Float): FaceRegion =
        copy(radiusX = radiusX * factor, radiusY = radiusY * factor)

    /** True when the point falls inside the ellipse grown by [margin]. */
    fun contains(x: Float, y: Float, margin: Float = 1f): Boolean {
        val rad = Math.toRadians(rotationDegrees.toDouble())
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        val px = x - centerX
        val py = y - centerY
        val lx = (px * c + py * s) / (radiusX * margin)
        val ly = (py * c - px * s) / (radiusY * margin)
        return lx * lx + ly * ly <= 1f
    }
}

data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Int get() = max(0, width) * max(0, height)

    fun clampTo(w: Int, h: Int) = IntRect(
        left = max(0, min(left, w)),
        top = max(0, min(top, h)),
        right = max(0, min(right, w)),
        bottom = max(0, min(bottom, h)),
    )

    fun isEmpty(): Boolean = width <= 0 || height <= 0
}

enum class PrivacyEffect { BLUR, PIXELATE, MASK }

/** How aggressively faces are protected. Affects blur radius / block size / margin. */
enum class PrivacyStrength(val marginFactor: Float, val intensity: Float) {
    BALANCED(1.06f, 1.0f),
    MAXIMUM(1.18f, 1.45f),
}

data class AnonymizationResult(
    val facesProtected: Int,
    val modifiedPixels: Int,
    val totalPixels: Int,
    val elapsedMillis: Long,
) {
    val modifiedFraction: Float get() = modifiedPixels.toFloat() / totalPixels
}
