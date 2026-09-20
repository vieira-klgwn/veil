package app.veil.privacy

import kotlin.math.hypot

/** A face region carrying an id that is stable across frames of one session. */
data class TrackedRegion(val id: Int, val region: FaceRegion)

/**
 * Follows faces across frames by proximity, so a face the user chose to keep
 * visible stays chosen while the camera moves. It matches positions only: no
 * appearance is compared, nothing is stored between sessions, and an id means
 * nothing outside the recording it was issued in.
 *
 * Android gets these ids from ML Kit's own tracker; platforms whose detector
 * has none (Vision on iOS) use this instead.
 */
class FaceTracker(private val reachFactor: Float = 0.9f) {

    private var nextId = 1
    private var previous: List<TrackedRegion> = emptyList()

    fun update(regions: List<FaceRegion>): List<TrackedRegion> {
        val free = previous.toMutableList()
        val matched = arrayOfNulls<TrackedRegion>(regions.size)

        // Closest pair first, so two faces crossing paths do not swap ids.
        val pairs = ArrayList<Triple<Float, Int, TrackedRegion>>()
        for ((index, region) in regions.withIndex()) {
            for (candidate in previous) {
                val distance = hypot(
                    region.centerX - candidate.region.centerX,
                    region.centerY - candidate.region.centerY,
                )
                val reach = reachFactor * (region.radiusX + region.radiusY) / 2f
                if (distance <= reach) pairs += Triple(distance, index, candidate)
            }
        }
        for ((_, index, candidate) in pairs.sortedBy { it.first }) {
            if (matched[index] != null || candidate !in free) continue
            matched[index] = TrackedRegion(candidate.id, regions[index])
            free -= candidate
        }

        val current = regions.mapIndexed { index, region ->
            matched[index] ?: TrackedRegion(nextId++, region)
        }
        previous = current
        return current
    }

    fun reset() {
        previous = emptyList()
    }
}
