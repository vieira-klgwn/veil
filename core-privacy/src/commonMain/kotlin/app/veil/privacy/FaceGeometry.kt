package app.veil.privacy

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

data class PointF2(val x: Float, val y: Float)

/**
 * Turns raw detector output into the smallest ellipse that still reliably
 * covers a face. Contour points are used when the detector provides them,
 * otherwise the bounding box is converted to an inscribed-plus-margin ellipse.
 */
object FaceGeometry {

    /** Never protect less than this radius, so distant faces stay unreadable. */
    private const val MIN_RADIUS_PX = 7f

    /** Box to ellipse factors: ML Kit boxes are tight, ears and chin need a little more. */
    private const val BOX_RX = 0.54f
    private const val BOX_RY = 0.58f

    /** Extra safety over the tight contour hull. */
    private const val CONTOUR_SAFETY = 1.08f

    fun fromBoundingBox(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Float = 0f,
    ): FaceRegion {
        val w = abs(right - left)
        val h = abs(bottom - top)
        return FaceRegion(
            centerX = (left + right) / 2f,
            centerY = (top + bottom) / 2f,
            radiusX = max(MIN_RADIUS_PX, w * BOX_RX),
            radiusY = max(MIN_RADIUS_PX, h * BOX_RY),
            rotationDegrees = rotationDegrees,
        )
    }

    /**
     * Fits a rotated ellipse around face contour points. Far tighter than the
     * bounding box for rotated or profile faces.
     */
    fun fromContour(points: List<PointF2>, rotationDegrees: Float = 0f): FaceRegion? {
        if (points.size < 5) return null
        val cx = points.sumOf { it.x.toDouble() }.toFloat() / points.size
        val cy = points.sumOf { it.y.toDouble() }.toFloat() / points.size
        val rad = radians(rotationDegrees)
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        var maxX = 0f
        var maxY = 0f
        for (p in points) {
            val dx = p.x - cx
            val dy = p.y - cy
            maxX = max(maxX, abs(dx * c + dy * s))
            maxY = max(maxY, abs(-dx * s + dy * c))
        }
        if (maxX <= 0f || maxY <= 0f) return null
        return FaceRegion(
            centerX = cx,
            centerY = cy,
            radiusX = max(MIN_RADIUS_PX, maxX * CONTOUR_SAFETY),
            radiusY = max(MIN_RADIUS_PX, maxY * CONTOUR_SAFETY),
            rotationDegrees = rotationDegrees,
        )
    }

    /**
     * Best available region: contour when present, bounding box otherwise. The
     * contour result is unioned with a floor derived from the box so a partial
     * contour can never shrink protection below the detected face.
     */
    fun bestRegion(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Float,
        contour: List<PointF2>,
    ): FaceRegion {
        val box = fromBoundingBox(left, top, right, bottom, rotationDegrees)
        val fitted = fromContour(contour, rotationDegrees) ?: return box
        val floorX = box.radiusX * 0.8f
        val floorY = box.radiusY * 0.8f
        return fitted.copy(
            radiusX = max(fitted.radiusX, floorX),
            radiusY = max(fitted.radiusY, floorY),
        )
    }
}
