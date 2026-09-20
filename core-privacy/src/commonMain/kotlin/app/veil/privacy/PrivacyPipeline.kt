package app.veil.privacy

import kotlin.time.TimeSource

data class ProtectionOutcome(
    val facesProtected: Int,
    val escalatedFaces: Int,
    val modifiedFraction: Float,
    val elapsedMillis: Long,
    val audits: List<FaceAudit>,
) {
    val allFacesVerified: Boolean get() = audits.all { it.isProtected }
}

/**
 * Detect -> protect -> verify. Each face is checked after processing and, if
 * the effect did not obscure it enough (very flat or very small faces), the
 * face is re-processed with a stronger effect. Memory use stays low because
 * only the region of interest of each face is ever copied.
 */
object PrivacyPipeline {

    fun protect(
        image: PixelImage,
        faces: List<FaceRegion>,
        effect: PrivacyEffect,
        strength: PrivacyStrength = PrivacyStrength.BALANCED,
    ): ProtectionOutcome {
        val start = TimeSource.Monotonic.markNow()
        var protectedCount = 0
        var escalated = 0
        var modified = 0
        val audits = ArrayList<FaceAudit>(faces.size)

        for (face in faces) {
            val roi = face.scaled(strength.marginFactor).bounds(pad = 2f).clampTo(image.width, image.height)
            if (roi.isEmpty()) continue
            val before = cropped(image, roi)

            var result = FaceAnonymizer.anonymize(image, listOf(face), effect, strength)
            var audit = auditFace(before, image, roi, face.scaled(strength.marginFactor))

            if (!audit.isProtected) {
                result = FaceAnonymizer.anonymize(image, listOf(face), PrivacyEffect.MASK, PrivacyStrength.MAXIMUM)
                audit = auditFace(before, image, roi, face.scaled(PrivacyStrength.MAXIMUM.marginFactor))
                escalated++
            }
            if (result.modifiedPixels > 0) protectedCount++
            modified += result.modifiedPixels
            audits += audit
        }

        return ProtectionOutcome(
            facesProtected = protectedCount,
            escalatedFaces = escalated,
            modifiedFraction = modified.toFloat() / (image.width * image.height),
            elapsedMillis = start.elapsedNow().inWholeMilliseconds,
            audits = audits,
        )
    }

    private fun cropped(image: PixelImage, roi: IntRect): PixelImage {
        val out = IntArray(roi.width * roi.height)
        for (y in 0 until roi.height) {
            val from = (roi.top + y) * image.width + roi.left
            image.pixels.copyInto(out, y * roi.width, from, from + roi.width)
        }
        return PixelImage(roi.width, roi.height, out)
    }

    private fun auditFace(before: PixelImage, image: PixelImage, roi: IntRect, face: FaceRegion): FaceAudit {
        val after = cropped(image, roi)
        val local = face.copy(centerX = face.centerX - roi.left, centerY = face.centerY - roi.top)
        return PrivacyAudit.audit(before, after, listOf(local)).faces.first()
    }
}
