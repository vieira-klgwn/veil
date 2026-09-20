package app.veil.privacy

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

data class FaceAudit(
    val region: FaceRegion,
    /** Mean per-channel difference inside the face ellipse (0..255). */
    val meanDifference: Float,
    /** Detail energy remaining inside the face, relative to the original (0..1). */
    val detailRetained: Float,
) {
    val isProtected: Boolean get() = meanDifference >= 6f && detailRetained <= 0.45f
}

data class AuditReport(
    val faces: List<FaceAudit>,
    /** Fraction of pixels outside every face ellipse that changed at all. */
    val backgroundChangedFraction: Float,
    /** Fraction of the whole image that changed. */
    val imageChangedFraction: Float,
) {
    val allFacesProtected: Boolean get() = faces.all { it.isProtected }
    val backgroundPreserved: Boolean get() = backgroundChangedFraction <= 0.0005f
}

/**
 * Compares the original and protected images to prove that faces really were
 * anonymized and that nothing else was touched. The app runs this after every
 * capture and escalates the effect if a face is not sufficiently protected.
 */
object PrivacyAudit {

    fun audit(original: PixelImage, protectedImage: PixelImage, faces: List<FaceRegion>): AuditReport {
        require(original.width == protectedImage.width && original.height == protectedImage.height) {
            "audit requires identical dimensions"
        }
        val inFace = BooleanArray(original.pixels.size)
        val audits = faces.map { face ->
            val roi = face.bounds(pad = 1f).clampTo(original.width, original.height)
            var sum = 0.0
            var count = 0
            for (y in roi.top until roi.bottom) {
                for (x in roi.left until roi.right) {
                    if (!contains(face, x + 0.5f, y + 0.5f)) continue
                    val i = y * original.width + x
                    inFace[i] = true
                    sum += FaceAnonymizer.channelDistance(original.pixels[i], protectedImage.pixels[i]).toDouble()
                    count++
                }
            }
            FaceAudit(
                region = face,
                meanDifference = if (count == 0) 0f else (sum / count).toFloat(),
                detailRetained = detailRatio(original, protectedImage, face),
            )
        }

        var bgChanged = 0
        var bgTotal = 0
        var changed = 0
        for (i in original.pixels.indices) {
            val diff = FaceAnonymizer.channelDistance(original.pixels[i], protectedImage.pixels[i])
            if (diff > 2) changed++
            if (!inFace[i]) {
                bgTotal++
                if (diff > 2) bgChanged++
            }
        }
        return AuditReport(
            faces = audits,
            backgroundChangedFraction = if (bgTotal == 0) 0f else bgChanged.toFloat() / bgTotal,
            imageChangedFraction = changed.toFloat() / original.pixels.size,
        )
    }

    fun contains(face: FaceRegion, x: Float, y: Float): Boolean {
        val rad = Math.toRadians(face.rotationDegrees.toDouble())
        val c = kotlin.math.cos(rad).toFloat()
        val s = kotlin.math.sin(rad).toFloat()
        val px = x - face.centerX
        val py = y - face.centerY
        val lx = (px * c + py * s) / face.radiusX
        val ly = (-px * s + py * c) / face.radiusY
        return lx * lx + ly * ly <= 1f
    }

    /** Ratio of local gradient energy inside the face ellipse, after versus before. */
    private fun detailRatio(original: PixelImage, protectedImage: PixelImage, face: FaceRegion): Float {
        val before = gradientEnergyInFace(original, face)
        val after = gradientEnergyInFace(protectedImage, face)
        if (before <= 0.0001) return 0f
        return min(1f, (after / before).toFloat())
    }

    /** Gradient energy restricted to the face ellipse, ignoring the sharp surroundings. */
    fun gradientEnergyInFace(image: PixelImage, face: FaceRegion): Double {
        val inner = face.scaled(0.8f)
        val roi = inner.bounds().clampTo(image.width, image.height)
        if (roi.width < 3 || roi.height < 3) return 0.0
        var sum = 0.0
        var n = 0
        for (y in max(1, roi.top) until min(image.height - 1, roi.bottom)) {
            for (x in max(1, roi.left) until min(image.width - 1, roi.right)) {
                if (!contains(inner, x + 0.5f, y + 0.5f)) continue
                val i = y * image.width + x
                val l = luma(image.pixels[i])
                sum += abs(l - luma(image.pixels[i + 1])) + abs(l - luma(image.pixels[i + image.width]))
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    fun gradientEnergy(image: PixelImage, roi: IntRect): Double {
        val r = roi.clampTo(image.width, image.height)
        if (r.width < 3 || r.height < 3) return 0.0
        var sum = 0.0
        var n = 0
        for (y in r.top + 1 until r.bottom - 1) {
            for (x in r.left + 1 until r.right - 1) {
                val i = y * image.width + x
                val l = luma(image.pixels[i])
                sum += abs(l - luma(image.pixels[i + 1])) + abs(l - luma(image.pixels[i + image.width]))
                n++
            }
        }
        return if (n == 0) 0.0 else sum / n
    }

    fun luma(p: Int): Double =
        0.299 * (p shr 16 and 0xFF) + 0.587 * (p shr 8 and 0xFF) + 0.114 * (p and 0xFF)

    fun maxChannelDistance(a: PixelImage, b: PixelImage): Int {
        var m = 0
        for (i in a.pixels.indices) m = max(m, FaceAnonymizer.channelDistance(a.pixels[i], b.pixels[i]))
        return m
    }
}
