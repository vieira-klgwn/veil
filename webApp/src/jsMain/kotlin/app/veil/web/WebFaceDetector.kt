package app.veil.web

import app.veil.privacy.FaceRegion
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlinx.browser.document
import kotlinx.coroutines.await
import org.w3c.dom.HTMLCanvasElement

/**
 * On-device face detection with the MediaPipe BlazeFace short-range model.
 * Detection only: the model reports where a face is, never who it is. The
 * model and its WebAssembly runtime are served by this app, so no frame and no
 * request ever leaves the browser.
 */
class WebFaceDetector private constructor(private val detector: MpFaceDetector) {

    /** Detects on the already downscaled frame, in that frame's pixel space. */
    fun detect(frame: HTMLCanvasElement, timestampMs: Double): List<FaceRegion> =
        detector.detectForVideo(frame, timestampMs).detections.mapNotNull { toRegion(it) }

    fun close() = detector.close()

    private fun toRegion(detection: MpDetection): FaceRegion? {
        val box = detection.boundingBox ?: return null
        if (box.width <= 0.0 || box.height <= 0.0) return null
        // BlazeFace boxes stop under the chin and cut off the forehead, so the
        // ellipse is grown to cover the whole head while still staying far
        // tighter than a rectangle.
        return FaceRegion(
            centerX = (box.originX + box.width / 2.0).toFloat(),
            centerY = (box.originY + box.height / 2.0).toFloat(),
            radiusX = max(4.0, box.width * 0.62).toFloat(),
            radiusY = max(4.0, box.height * 0.78).toFloat(),
            rotationDegrees = rotationDegrees(detection),
        )
    }

    private fun rotationDegrees(detection: MpDetection): Float {
        val points = detection.keypoints ?: return 0f
        if (points.size < 2) return 0f
        val right = points[0]
        val left = points[1]
        return (atan2(left.y - right.y, left.x - right.x) * 180.0 / PI).toFloat()
    }

    companion object {
        const val GPU = "GPU"
        const val CPU = "CPU"

        /**
         * MediaPipe converts every input frame through a WebGL 2 canvas, so a
         * browser without WebGL 2 cannot detect faces even on the CPU
         * delegate. Creation fails loudly there rather than letting the app
         * open a camera it could not protect.
         */
        fun isSupported(): Boolean {
            val canvas = document.createElement("canvas") as HTMLCanvasElement
            return canvas.getContext("webgl2") != null
        }

        suspend fun create(wasmPath: String, modelPath: String): WebFaceDetector {
            check(isSupported()) { "this browser has no WebGL 2, which MediaPipe needs to read frames" }
            val fileset = TasksVision.FilesetResolver.forVisionTasks(wasmPath).await()
            return try {
                WebFaceDetector(TasksVision.FaceDetector.createFromOptions(fileset, options(modelPath, GPU)).await())
            } catch (error: Throwable) {
                WebFaceDetector(TasksVision.FaceDetector.createFromOptions(fileset, options(modelPath, CPU)).await())
            }
        }

        private fun options(modelPath: String, delegate: String): dynamic {
            val baseOptions: dynamic = js("({})")
            baseOptions.modelAssetPath = modelPath
            baseOptions.delegate = delegate
            val options: dynamic = js("({})")
            options.baseOptions = baseOptions
            options.runningMode = "VIDEO"
            options.minDetectionConfidence = 0.4
            return options
        }
    }
}
