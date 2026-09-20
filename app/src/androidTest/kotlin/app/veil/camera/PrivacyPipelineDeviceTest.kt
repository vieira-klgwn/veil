package app.veil.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.veil.camera.privacy.BitmapPrivacy
import app.veil.camera.privacy.FaceRegionDetector
import app.veil.camera.privacy.PhotoStore
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyStrength
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the real capture pipeline (ML Kit detection -> anonymization -> gallery
 * write) against a photograph pushed to the device. The photograph is not
 * committed to the repository; push one with:
 *
 *   adb push scene.jpg /sdcard/Android/data/app.veil.camera.debug/files/veil_scene.jpg
 */
@RunWith(AndroidJUnit4::class)
class PrivacyPipelineDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sourceFile(): File = File(context.getExternalFilesDir(null), "veil_scene.jpg")

    private fun loadScene(): Bitmap {
        val file = sourceFile()
        assumeTrue("no veil_scene.jpg pushed to the app files directory", file.exists())
        return BitmapPrivacy.decodeUpright(file.readBytes())
    }

    @Test
    fun detectsRealFacesAndProtectsOnlyThem(): Unit = runBlocking {
        val original = loadScene()
        val working = original.copy(Bitmap.Config.ARGB_8888, true)

        val detector = FaceRegionDetector.forCapture()
        val started = System.currentTimeMillis()
        val faces = try {
            detector.detect(working)
        } finally {
            detector.close()
        }
        val detectMs = System.currentTimeMillis() - started

        assertTrue("ML Kit found no faces in the reference photo", faces.isNotEmpty())

        val outcome = BitmapPrivacy.protect(working, faces, PrivacyEffect.BLUR, PrivacyStrength.BALANCED)

        assertEquals(original.width, working.width)
        assertEquals(original.height, working.height)
        assertEquals(faces.size, outcome.facesProtected)
        assertTrue(
            "protection touched ${outcome.modifiedFraction} of the frame",
            outcome.modifiedFraction < 0.25f,
        )

        val changedOutside = changedPixelsOutsideFaces(original, working, faces)
        assertEquals("pixels outside the face ellipses were modified", 0, changedOutside)

        android.util.Log.i(
            "VeilDeviceTest",
            "faces=${faces.size} detect=${detectMs}ms protect=${outcome.elapsedMillis}ms " +
                "modified=${outcome.modifiedFraction} size=${working.width}x${working.height}",
        )

        val uri = PhotoStore.saveToGallery(context, working)
        context.contentResolver.openInputStream(uri).use { stream ->
            val saved = BitmapFactory.decodeStream(stream)
            assertEquals(working.width, saved.width)
            assertEquals(working.height, saved.height)
        }
        android.util.Log.i("VeilDeviceTest", "saved=$uri")

        File(context.getExternalFilesDir(null), "protected_reference.jpg").outputStream().use {
            working.compress(Bitmap.CompressFormat.JPEG, 96, it)
        }
    }

    @Test
    fun pixelateAndMaskAlsoProtectEveryFace(): Unit = runBlocking {
        val original = loadScene()
        val detector = FaceRegionDetector.forCapture()
        val faces = try {
            detector.detect(original)
        } finally {
            detector.close()
        }
        assertTrue(faces.isNotEmpty())

        listOf(PrivacyEffect.PIXELATE, PrivacyEffect.MASK).forEach { effect ->
            val working = original.copy(Bitmap.Config.ARGB_8888, true)
            val outcome = BitmapPrivacy.protect(working, faces, effect, PrivacyStrength.BALANCED)
            assertEquals(faces.size, outcome.facesProtected)
            assertEquals(0, changedPixelsOutsideFaces(original, working, faces))
            File(context.getExternalFilesDir(null), "protected_${effect.name.lowercase()}.jpg")
                .outputStream().use { working.compress(Bitmap.CompressFormat.JPEG, 96, it) }
        }
    }

    private fun changedPixelsOutsideFaces(
        original: Bitmap,
        processed: Bitmap,
        faces: List<app.veil.privacy.FaceRegion>,
    ): Int {
        val w = original.width
        val h = original.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        original.getPixels(a, 0, w, 0, 0, w, h)
        processed.getPixels(b, 0, w, 0, 0, w, h)
        // A generous rectangle around every face: anything changed outside all of
        // them would mean the effect leaked into the photograph.
        val boxes = faces.map { face ->
            val bounds = face.bounds()
            intArrayOf(
                (bounds.left - 0.35f * face.radiusX).toInt(),
                (bounds.top - 0.35f * face.radiusY).toInt(),
                (bounds.right + 0.35f * face.radiusX).toInt(),
                (bounds.bottom + 0.35f * face.radiusY).toInt(),
            )
        }
        var changed = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (a[i] == b[i]) continue
                val inside = boxes.any { x >= it[0] && x <= it[2] && y >= it[1] && y <= it[3] }
                if (!inside) changed++
            }
        }
        return changed
    }
}
