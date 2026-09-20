package app.veil.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.veil.camera.privacy.BitmapPrivacy
import app.veil.camera.privacy.FaceRegionDetector
import app.veil.camera.privacy.TrackedFace
import app.veil.camera.video.VideoRecorder
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyStrength
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

data class LivePreviewFaces(
    val faces: List<TrackedFace> = emptyList(),
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val mirrored: Boolean = false,
)

/** What the frame pipeline should do with the faces it finds. */
data class FrameProtection(
    val effect: PrivacyEffect = PrivacyEffect.BLUR,
    val strength: PrivacyStrength = PrivacyStrength.BALANCED,
    /** Tracking ids the user chose to keep visible, e.g. their own face. */
    val keepVisible: Set<Int> = emptySet(),
)

/**
 * Turns preview frames into "who is in shot" for the viewfinder and, while a
 * recording is running, into anonymized video frames. Frames are dropped
 * while one is in flight so the camera never stalls; the captured photograph
 * is always re-detected at full quality regardless of what the preview saw.
 */
class LiveFaceAnalyzer(
    private val scope: CoroutineScope,
    private val isMirrored: () -> Boolean,
    private val overlayEnabled: () -> Boolean,
    private val protection: () -> FrameProtection,
    private val recorder: VideoRecorder,
    private val onFaces: (LivePreviewFaces) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val detector = FaceRegionDetector.forPreview()
    private val busy = AtomicBoolean(false)

    override fun analyze(proxy: ImageProxy) {
        val recording = recorder.isRecording
        if ((!overlayEnabled() && !recording) || !busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val timestamp = proxy.imageInfo.timestamp
        val frame = try {
            proxy.toUprightBitmap()
        } catch (t: Throwable) {
            Log.d(TAG, "frame conversion skipped: ${t.message}")
            null
        } finally {
            proxy.close()
        }
        if (frame == null) {
            busy.set(false)
            return
        }
        scope.launch {
            try {
                withContext(Dispatchers.Default) { processFrame(frame, timestamp, recording) }
            } catch (t: Throwable) {
                Log.d(TAG, "frame skipped: ${t.message}")
            } finally {
                frame.recycle()
                busy.set(false)
            }
        }
    }

    private suspend fun processFrame(frame: Bitmap, timestampNanos: Long, recording: Boolean) {
        val faces = try {
            detector.detectTracked(frame)
        } catch (t: Throwable) {
            Log.d(TAG, "preview detection skipped: ${t.message}")
            emptyList()
        }
        onFaces(LivePreviewFaces(faces, frame.width, frame.height, isMirrored()))
        if (!recording) return
        val config = protection()
        val protectedFaces = faces
            .filter { it.trackingId == null || it.trackingId !in config.keepVisible }
            .map { it.region }
        BitmapPrivacy.protect(frame, protectedFaces, config.effect, config.strength)
        recorder.offer(frame, timestampNanos)
    }

    override fun close() = detector.close()

    private companion object {
        const val TAG = "VeilPreview"
    }
}

/**
 * Copies an RGBA_8888 analysis frame into an upright bitmap. The row stride
 * can exceed the frame width, so the padded columns are cropped away.
 */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val plane = planes[0]
    val rowStride = plane.rowStride
    val paddedWidth = rowStride / plane.pixelStride
    val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
    plane.buffer.rewind()
    padded.copyPixelsFromBuffer(plane.buffer)
    val rotation = imageInfo.rotationDegrees
    val matrix = Matrix().apply { if (rotation != 0) postRotate(rotation.toFloat()) }
    val upright = Bitmap.createBitmap(padded, 0, 0, width, height, matrix, true)
    if (upright !== padded) padded.recycle()
    return upright
}
