package app.veil.camera.privacy

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import app.veil.privacy.FaceRegion
import app.veil.privacy.PixelImage
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyPipeline
import app.veil.privacy.PrivacyStrength
import app.veil.privacy.ProtectionOutcome
import java.io.ByteArrayInputStream

/** Bridges Android bitmaps to the platform independent privacy core. */
object BitmapPrivacy {

    private const val PREVIEW_MAX_DIMENSION = 1440

    /**
     * Decodes a captured JPEG at full resolution and applies EXIF rotation so
     * that the saved photograph is upright everywhere, including gallery apps
     * that ignore EXIF.
     */
    fun decodeUpright(jpeg: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IllegalArgumentException("capture could not be decoded")
        val orientation = runCatching {
            ExifInterface(ByteArrayInputStream(jpeg))
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        return applyOrientation(decoded, orientation)
    }

    fun applyOrientation(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return source
        }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated != source) source.recycle()
        return rotated
    }

    /**
     * Screen sized copy of a protected photograph. Review renders this instead
     * of the full resolution result, which keeps frames cheap on large images.
     */
    fun previewCopy(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= PREVIEW_MAX_DIMENSION) return bitmap
        val scale = PREVIEW_MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            maxOf(1, (bitmap.width * scale).toInt()),
            maxOf(1, (bitmap.height * scale).toInt()),
            true,
        )
    }

    /**
     * Anonymizes [bitmap] in place. Resolution, aspect ratio and every pixel
     * outside a face ellipse are left untouched.
     */
    fun protect(
        bitmap: Bitmap,
        faces: List<FaceRegion>,
        effect: PrivacyEffect,
        strength: PrivacyStrength,
    ): ProtectionOutcome {
        if (faces.isEmpty()) {
            return ProtectionOutcome(0, 0, 0f, 0, emptyList())
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val image = PixelImage(bitmap.width, bitmap.height, pixels)
        val outcome = PrivacyPipeline.protect(image, faces, effect, strength)
        bitmap.setPixels(image.pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return outcome
    }
}
