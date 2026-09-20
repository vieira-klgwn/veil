package app.veil.privacy

import kotlin.math.hypot

/**
 * Merges detections coming from several passes over the same photograph
 * (whole frame plus overlapping tiles). The larger region always wins so
 * that de-duplication can never shrink protection.
 */
object FaceRegions {

    /** Two detections are the same face when one centre sits inside the other. */
    private const val SAME_FACE_FACTOR = 0.75f

    fun deduplicate(regions: List<FaceRegion>): List<FaceRegion> {
        val kept = mutableListOf<FaceRegion>()
        for (candidate in regions.sortedByDescending { it.area }) {
            if (kept.none { isSameFace(it, candidate) }) kept += candidate
        }
        return kept
    }

    private fun isSameFace(a: FaceRegion, b: FaceRegion): Boolean {
        val distance = hypot(a.centerX - b.centerX, a.centerY - b.centerY)
        val reach = SAME_FACE_FACTOR * ((a.radiusX + a.radiusY) / 2f)
        return distance <= reach
    }
}
