package app.veil.privacy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FaceTrackerTest {

    private fun face(cx: Float, cy: Float) = FaceRegion(cx, cy, 40f, 50f)

    @Test
    fun `a face drifting between frames keeps its id`() {
        val tracker = FaceTracker()
        val first = tracker.update(listOf(face(100f, 100f))).single()
        val second = tracker.update(listOf(face(112f, 94f))).single()
        assertEquals(first.id, second.id)
        assertEquals(112f, second.region.centerX)
    }

    @Test
    fun `a face that jumps across the frame is treated as a new face`() {
        val tracker = FaceTracker()
        val first = tracker.update(listOf(face(100f, 100f))).single()
        val second = tracker.update(listOf(face(600f, 400f))).single()
        assertTrue(second.id != first.id)
    }

    @Test
    fun `two faces keep their own ids and never share one`() {
        val tracker = FaceTracker()
        val frame1 = tracker.update(listOf(face(100f, 100f), face(300f, 120f)))
        val frame2 = tracker.update(listOf(face(310f, 126f), face(106f, 104f)))
        assertEquals(frame1[0].id, frame2[1].id)
        assertEquals(frame1[1].id, frame2[0].id)
        assertEquals(2, frame2.map { it.id }.toSet().size)
    }

    @Test
    fun `a face leaving and returning does not reuse the old id`() {
        val tracker = FaceTracker()
        val first = tracker.update(listOf(face(100f, 100f))).single()
        tracker.update(emptyList())
        val back = tracker.update(listOf(face(100f, 100f))).single()
        assertTrue(back.id != first.id)
    }

    @Test
    fun `reset forgets every id`() {
        val tracker = FaceTracker()
        val first = tracker.update(listOf(face(100f, 100f))).single()
        tracker.reset()
        val after = tracker.update(listOf(face(100f, 100f))).single()
        assertTrue(after.id != first.id)
    }
}
