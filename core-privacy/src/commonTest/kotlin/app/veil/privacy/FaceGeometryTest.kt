package app.veil.privacy

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FaceGeometryTest {

    @Test
    fun `bounding box becomes an ellipse smaller than the box`() {
        val region = FaceGeometry.fromBoundingBox(400f, 200f, 500f, 320f)
        assertEquals(450f, region.centerX, 0.01f)
        assertEquals(260f, region.centerY, 0.01f)
        val boxArea = 100f * 120f
        assertTrue(region.area < boxArea, "ellipse must be smaller than the box")
    }

    @Test
    fun `small faces keep a usable minimum radius`() {
        val region = FaceGeometry.fromBoundingBox(10f, 10f, 16f, 18f)
        assertTrue(region.radiusX >= 7f && region.radiusY >= 7f)
    }

    @Test
    fun `contour fit is tighter than the bounding box for a rotated face`() {
        val rotation = 35f
        val rad = rotation.toDouble() * PI / 180.0
        val points = (0 until 24).map { i ->
            val t = 2 * PI * i / 24
            val lx = 40 * cos(t)
            val ly = 55 * sin(t)
            PointF2(
                (300 + lx * cos(rad) - ly * sin(rad)).toFloat(),
                (300 + lx * sin(rad) + ly * cos(rad)).toFloat(),
            )
        }
        val box = points.fold(
            floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE),
        ) { acc, p ->
            floatArrayOf(minOf(acc[0], p.x), minOf(acc[1], p.y), maxOf(acc[2], p.x), maxOf(acc[3], p.y))
        }
        val fromBox = FaceGeometry.fromBoundingBox(box[0], box[1], box[2], box[3], rotation)
        val fitted = FaceGeometry.bestRegion(box[0], box[1], box[2], box[3], rotation, points)
        assertTrue(fitted.area < fromBox.area, "contour ellipse should cover less area")
        assertTrue(points.all { PrivacyAudit.contains(fitted, it.x, it.y) })
    }

    @Test
    fun `contour fit needs enough points`() {
        assertNull(FaceGeometry.fromContour(listOf(PointF2(1f, 1f), PointF2(2f, 2f))))
    }

    @Test
    fun `contour result never shrinks below the detected face`() {
        val tinyContour = listOf(
            PointF2(300f, 300f), PointF2(305f, 300f), PointF2(305f, 305f),
            PointF2(300f, 305f), PointF2(302f, 307f),
        )
        val region = FaceGeometry.bestRegion(260f, 250f, 340f, 350f, 0f, tinyContour)
        val box = FaceGeometry.fromBoundingBox(260f, 250f, 340f, 350f)
        assertTrue(region.radiusX >= box.radiusX * 0.8f)
        assertTrue(region.radiusY >= box.radiusY * 0.8f)
    }
}
