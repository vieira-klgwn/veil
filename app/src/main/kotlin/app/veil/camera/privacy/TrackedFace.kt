package app.veil.camera.privacy

import app.veil.privacy.FaceRegion

/**
 * A face seen in the viewfinder. [trackingId] is ML Kit's transient id for
 * "the same face as the previous frame", which lets the user keep one chosen
 * face visible while the camera keeps protecting everyone else. It carries no
 * identity and is discarded when the camera stops.
 */
data class TrackedFace(
    val region: FaceRegion,
    val trackingId: Int?,
)
