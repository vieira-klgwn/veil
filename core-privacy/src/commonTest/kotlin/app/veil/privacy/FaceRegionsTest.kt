package app.veil.privacy

import kotlin.test.Test
import kotlin.test.assertEquals

class FaceRegionsTest {

    @Test
    fun `keeps the larger of two detections of the same face`() {
        val small = FaceRegion(100f, 100f, 20f, 24f)
        val large = FaceRegion(104f, 97f, 26f, 30f)
        val kept = FaceRegions.deduplicate(listOf(small, large))
        assertEquals(listOf(large), kept)
    }

    @Test
    fun `keeps distinct faces`() {
        val a = FaceRegion(100f, 100f, 20f, 24f)
        val b = FaceRegion(300f, 120f, 18f, 22f)
        val c = FaceRegion(140f, 100f, 15f, 18f)
        assertEquals(3, FaceRegions.deduplicate(listOf(a, b, c)).size)
    }

    @Test
    fun `empty input stays empty`() {
        assertEquals(emptyList<FaceRegion>(), FaceRegions.deduplicate(emptyList()))
    }
}
