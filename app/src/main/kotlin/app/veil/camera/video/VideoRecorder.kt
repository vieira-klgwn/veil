package app.veil.camera.video

import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * Owns the encoder for one recording. Only frames that already went through
 * the privacy pipeline are handed to it, so the MP4 on disk can never contain
 * an unprotected face.
 */
class VideoRecorder(private val cacheDir: File) {

    private val lock = Any()
    private var encoder: ProtectedVideoEncoder? = null
    private var dropped = 0

    @Volatile
    var isRecording: Boolean = false
        private set

    /** Frames written so far, for the on screen duration readout. */
    val frameCount: Int get() = synchronized(lock) { encoder?.frameCount ?: 0 }

    val droppedFrames: Int get() = synchronized(lock) { dropped }

    fun start(width: Int, height: Int): Boolean = synchronized(lock) {
        if (encoder != null) return false
        val directory = File(cacheDir, "video").apply { mkdirs() }
        val file = File(directory, "veil-${System.currentTimeMillis()}.mp4")
        return try {
            encoder = ProtectedVideoEncoder(file, even(width), even(height))
            dropped = 0
            isRecording = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not start the encoder", t)
            file.delete()
            encoder = null
            isRecording = false
            false
        }
    }

    /**
     * Offers one protected frame. Frames that arrive while the encoder is
     * saturated are dropped rather than queued, which keeps the preview
     * responsive; timestamps are wall clock so playback speed stays correct.
     */
    fun offer(bitmap: Bitmap, timestampNanos: Long): Unit = synchronized(lock) {
        val active = encoder ?: return
        val sized = if (bitmap.width == active.width && bitmap.height == active.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, active.width, active.height, true)
        }
        try {
            if (!active.encode(sized, timestampNanos)) dropped++
        } catch (t: Throwable) {
            Log.e(TAG, "frame encoding failed", t)
            dropped++
        } finally {
            if (sized !== bitmap) sized.recycle()
        }
    }

    /** Finishes the MP4. Returns null when nothing usable was recorded. */
    fun finish(): File? = synchronized(lock) {
        val active = encoder ?: return null
        encoder = null
        isRecording = false
        return try {
            val file = active.finish()
            if (active.frameCount > 0 && file.length() > 0) {
                file
            } else {
                file.delete()
                null
            }
        } catch (t: Throwable) {
            Log.e(TAG, "could not finish the recording", t)
            active.file.delete()
            null
        }
    }

    fun cancel(): Unit = synchronized(lock) {
        val active = encoder ?: return
        encoder = null
        isRecording = false
        active.close()
        active.file.delete()
    }

    private fun even(value: Int) = if (value % 2 == 0) value else value - 1

    private companion object {
        const val TAG = "VeilVideo"
    }
}
