package app.veil.web

import kotlin.js.Promise
import org.w3c.dom.HTMLCanvasElement

/**
 * Minimal external bindings for the parts of @mediapipe/tasks-vision this app
 * uses. The detector runs as WebAssembly inside the page: frames are handed to
 * it in memory and never uploaded anywhere.
 */
@JsModule("@mediapipe/tasks-vision")
@JsNonModule
external object TasksVision {
    object FilesetResolver {
        fun forVisionTasks(wasmPath: String): Promise<WasmFileset>
    }

    object FaceDetector {
        fun createFromOptions(fileset: WasmFileset, options: dynamic): Promise<MpFaceDetector>
    }
}

external interface WasmFileset

external interface MpFaceDetector {
    fun detectForVideo(image: HTMLCanvasElement, timestampMs: Double): MpDetectionResult
    fun close()
}

external interface MpDetectionResult {
    val detections: Array<MpDetection>
}

external interface MpDetection {
    val boundingBox: MpBoundingBox?
    val keypoints: Array<MpKeypoint>?
}

external interface MpBoundingBox {
    val originX: Double
    val originY: Double
    val width: Double
    val height: Double
}

/** Keypoint coordinates are normalized to the input image, in 0..1. */
external interface MpKeypoint {
    val x: Double
    val y: Double
}
