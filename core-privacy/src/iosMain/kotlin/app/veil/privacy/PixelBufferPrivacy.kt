package app.veil.privacy

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.get
import kotlinx.cinterop.set
import kotlinx.cinterop.toCPointer

/**
 * Protects a locked `CVPixelBuffer` in place, so the iOS app never copies a
 * frame out of the capture pipeline just to anonymize it.
 *
 * The buffer must be `kCVPixelFormatType_32BGRA`. Its bytes are B, G, R, A,
 * which read back as a little endian 32 bit integer is 0xAARRGGBB — the same
 * packing `PixelImage` uses on Android, so the shared engine needs no colour
 * conversion.
 */
@OptIn(ExperimentalForeignApi::class)
object PixelBufferPrivacy {

    /**
     * @param baseAddress `CVPixelBufferGetBaseAddress` of a buffer locked for
     *   read and write.
     * @param bytesPerRow `CVPixelBufferGetBytesPerRow`, which is usually wider
     *   than `width * 4` because rows are padded for alignment.
     */
    fun protect(
        baseAddress: Long,
        width: Int,
        height: Int,
        bytesPerRow: Int,
        faces: List<FaceRegion>,
        effect: PrivacyEffect,
        strength: PrivacyStrength,
    ): ProtectionOutcome {
        val base = baseAddress.toCPointer<IntVar>()
        require(base != null) { "pixel buffer is not locked" }
        require(bytesPerRow >= width * 4) { "row stride smaller than the row" }
        val stride = bytesPerRow / 4

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * stride
            val out = y * width
            for (x in 0 until width) pixels[out + x] = base[row + x]
        }

        val outcome = PrivacyPipeline.protect(PixelImage(width, height, pixels), faces, effect, strength)

        for (y in 0 until height) {
            val row = y * stride
            val src = y * width
            for (x in 0 until width) base[row + x] = pixels[src + x]
        }
        return outcome
    }
}
