package app.veil.camera

import android.annotation.SuppressLint
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import app.veil.camera.privacy.FaceRegionDetector
import app.veil.privacy.FaceRegion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class LivePreviewFaces(
    val faces: List<FaceRegion> = emptyList(),
    val sourceWidth: Int = 0,
    val sourceHeight: Int = 0,
    val mirrored: Boolean = false,
)

/**
 * Feeds preview frames to a lightweight detector so the viewfinder can show
 * which faces will be protected. Frames are dropped while a detection is in
 * flight, so the preview never stalls; the captured photograph is always
 * re-detected at full quality regardless of what the preview showed.
 */
class LiveFaceAnalyzer(
    private val scope: CoroutineScope,
    private val isMirrored: () -> Boolean,
    private val onFaces: (LivePreviewFaces) -> Unit,
) : ImageAnalysis.Analyzer, AutoCloseable {

    private val detector = FaceRegionDetector.forPreview()
    private val busy = AtomicBoolean(false)

    @SuppressLint("UnsafeOptInUsageError")
    override fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || !busy.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val rotated = rotation == 90 || rotation == 270
        val width = if (rotated) proxy.height else proxy.width
        val height = if (rotated) proxy.width else proxy.height
        scope.launch {
            try {
                val faces = detector.detect(media, rotation)
                onFaces(LivePreviewFaces(faces, width, height, isMirrored()))
            } catch (t: Throwable) {
                Log.d(TAG, "preview detection skipped: ${t.message}")
                onFaces(LivePreviewFaces(emptyList(), width, height, isMirrored()))
            } finally {
                proxy.close()
                busy.set(false)
            }
        }
    }

    override fun close() = detector.close()

    private companion object {
        const val TAG = "VeilPreview"
    }
}
