package app.veil.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.veil.camera.privacy.BitmapPrivacy
import app.veil.camera.privacy.FaceRegionDetector
import app.veil.camera.privacy.PhotoStore
import app.veil.camera.video.VideoRecorder
import app.veil.privacy.FaceRegion
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyStrength
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Exercises the real video path: protected frames -> H.264 -> MP4 -> MediaStore,
 * and the per face exclusion that keeps one chosen person visible.
 */
@RunWith(AndroidJUnit4::class)
class ProtectedVideoDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun recordsAPlayableMp4FromProtectedFrames() {
        val recorder = VideoRecorder(context.cacheDir)
        assertTrue("encoder did not start", recorder.start(WIDTH, HEIGHT))

        val frames = 24
        repeat(frames) { index ->
            val frame = syntheticFrame(index)
            recorder.offer(frame, index * FRAME_NANOS)
            frame.recycle()
        }
        val file = recorder.finish()
        assertNotNull("no video file produced", file)
        requireNotNull(file)
        assertTrue("empty video file", file.length() > 0)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            assertEquals(
                WIDTH,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt(),
            )
            assertEquals(
                HEIGHT,
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt(),
            )
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
            assertTrue("duration was $durationMs ms", durationMs > 200)
            assertNotNull("first frame could not be decoded", retriever.getFrameAtTime(0))
        } finally {
            retriever.release()
        }

        val uri = runBlocking { PhotoStore.saveVideoToGallery(context, file) }
        context.contentResolver.openInputStream(uri).use { stream ->
            assertTrue("saved video is empty", (stream?.available() ?: 0) > 0)
        }
        android.util.Log.i(TAG, "video=$uri frames=$frames dropped=${recorder.droppedFrames}")
    }

    /** A recording of a scene with faces: everyone protected, in every frame. */
    @Test
    fun everyRecordedFrameHasFacesProtected(): Unit = runBlocking {
        val full = loadScene() ?: return@runBlocking
        // Recording resolution, not photo resolution: the encoder works on the
        // camera's analysis stream.
        val scale = 720f / maxOf(full.width, full.height)
        val scene = Bitmap.createScaledBitmap(
            full,
            (full.width * scale).toInt() and 1.inv(),
            (full.height * scale).toInt() and 1.inv(),
            true,
        )
        val detector = FaceRegionDetector.forCapture()
        val faces = try {
            detector.detect(scene)
        } finally {
            detector.close()
        }
        assumeTrue("ML Kit found no faces in the reference photo", faces.isNotEmpty())

        val recorder = VideoRecorder(context.cacheDir)
        assertTrue(recorder.start(scene.width, scene.height))
        var protectedFrames = 0
        repeat(6) { index ->
            val frame = scene.copy(Bitmap.Config.ARGB_8888, true)
            val outcome = BitmapPrivacy.protect(frame, faces, PrivacyEffect.BLUR, PrivacyStrength.BALANCED)
            if (outcome.facesProtected == faces.size) protectedFrames++
            recorder.offer(frame, index * FRAME_NANOS)
            frame.recycle()
        }
        val file = recorder.finish()
        assertEquals("a frame went to the encoder unprotected", 6, protectedFrames)
        assertNotNull(file)
        requireNotNull(file).delete()
    }

    /** The excluded face keeps its original pixels; everyone else is protected. */
    @Test
    fun excludedFaceStaysVisibleWhileOthersAreProtected(): Unit = runBlocking {
        val scene = loadScene() ?: return@runBlocking
        val detector = FaceRegionDetector.forCapture()
        val faces = try {
            detector.detect(scene)
        } finally {
            detector.close()
        }
        assumeTrue("needs at least two faces", faces.size >= 2)

        val kept = faces.indices.maxByOrNull { faces[it].area } ?: 0
        val working = scene.copy(Bitmap.Config.ARGB_8888, true)
        val outcome = BitmapPrivacy.protect(
            working,
            faces.filterIndexed { index, _ -> index != kept },
            PrivacyEffect.BLUR,
            PrivacyStrength.BALANCED,
        )

        assertEquals(faces.size - 1, outcome.facesProtected)
        assertEquals(
            "the face kept visible was modified",
            0,
            changedPixelsInside(scene, working, faces[kept]),
        )
        faces.forEachIndexed { index, face ->
            if (index == kept) return@forEachIndexed
            assertTrue(
                "face $index was not protected",
                changedPixelsInside(scene, working, face) > 0,
            )
        }
        working.recycle()
    }

    private fun loadScene(): Bitmap? {
        val file = File(context.getExternalFilesDir(null), "veil_scene.jpg")
        if (!file.exists()) {
            android.util.Log.w(TAG, "no veil_scene.jpg pushed; skipping")
            return null
        }
        return BitmapPrivacy.decodeUpright(file.readBytes())
    }

    /** Counts pixels inside the ellipse that the protection pass changed. */
    private fun changedPixelsInside(original: Bitmap, processed: Bitmap, face: FaceRegion): Int {
        val bounds = face.bounds().clampTo(original.width, original.height)
        var changed = 0
        for (y in bounds.top until bounds.bottom) {
            for (x in bounds.left until bounds.right) {
                if (!face.contains(x.toFloat(), y.toFloat())) continue
                if (original.getPixel(x, y) != processed.getPixel(x, y)) changed++
            }
        }
        return changed
    }

    private fun syntheticFrame(index: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(20, 40 + index * 4, 90))
        canvas.drawCircle(
            WIDTH / 2f,
            HEIGHT / 2f + index * 3f,
            60f,
            Paint().apply { color = Color.rgb(230, 180, 60) },
        )
        return bitmap
    }

    private companion object {
        const val TAG = "VeilVideoTest"
        const val WIDTH = 320
        const val HEIGHT = 240
        const val FRAME_NANOS = 41_666_667L
    }
}
