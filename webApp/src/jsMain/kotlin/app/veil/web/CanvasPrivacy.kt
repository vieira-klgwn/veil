package app.veil.web

import app.veil.privacy.FaceRegion
import app.veil.privacy.PixelImage
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyPipeline
import app.veil.privacy.PrivacyStrength
import app.veil.privacy.ProtectionOutcome
import org.khronos.webgl.Uint8ClampedArray
import org.khronos.webgl.get
import org.w3c.dom.ImageData

/**
 * Bridges canvas pixels to the shared Kotlin privacy engine: canvas RGBA in,
 * protected RGBA back into the same buffer. Everything between those two
 * points is the same code the Android and iOS apps run.
 */
object CanvasPrivacy {

    fun protect(
        imageData: ImageData,
        faces: List<FaceRegion>,
        effect: PrivacyEffect,
        strength: PrivacyStrength,
    ): ProtectionOutcome {
        val image = toPixelImage(imageData)
        val outcome = PrivacyPipeline.protect(image, faces, effect, strength)
        writeBack(image, imageData.data)
        return outcome
    }

    private fun toPixelImage(imageData: ImageData): PixelImage {
        val data = imageData.data
        val pixels = IntArray(imageData.width * imageData.height)
        var i = 0
        while (i < pixels.size) {
            val o = i * 4
            pixels[i] = (data[o + 3].toInt() and 0xFF shl 24) or
                (data[o].toInt() and 0xFF shl 16) or
                (data[o + 1].toInt() and 0xFF shl 8) or
                (data[o + 2].toInt() and 0xFF)
            i++
        }
        return PixelImage(imageData.width, imageData.height, pixels)
    }

    /**
     * Written through the raw array: a clamped byte array treats the signed
     * Byte a Kotlin [Uint8ClampedArray] setter takes as a negative number and
     * clamps every channel above 127 to zero.
     */
    private fun writeBack(image: PixelImage, data: Uint8ClampedArray) {
        val raw = data.asDynamic()
        val pixels = image.pixels
        var i = 0
        while (i < pixels.size) {
            val argb = pixels[i]
            val o = i * 4
            raw[o] = argb shr 16 and 0xFF
            raw[o + 1] = argb shr 8 and 0xFF
            raw[o + 2] = argb and 0xFF
            raw[o + 3] = argb shr 24 and 0xFF
            i++
        }
    }
}
