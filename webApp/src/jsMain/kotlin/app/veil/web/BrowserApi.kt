package app.veil.web

import kotlinx.browser.window
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.mediacapture.MediaStream
import org.w3c.files.Blob
import org.w3c.files.File

/**
 * Browser APIs the Kotlin/JS standard library does not declare yet.
 */
external class MediaRecorder(stream: MediaStream, options: dynamic) {
    val state: String
    var ondataavailable: ((dynamic) -> Unit)?
    var onstop: ((dynamic) -> Unit)?

    fun start(timesliceMs: Int)
    fun stop()

    companion object {
        fun isTypeSupported(type: String): Boolean
    }
}

fun HTMLCanvasElement.captureStreamAt(fps: Int): MediaStream =
    asDynamic().captureStream(fps).unsafeCast<MediaStream>()

fun HTMLCanvasElement.toBlobAsync(type: String, quality: Double, onBlob: (Blob?) -> Unit) {
    asDynamic().toBlob({ blob: Blob? -> onBlob(blob) }, type, quality)
}

fun canShareFile(file: File): Boolean {
    val navigator = window.navigator.asDynamic()
    if (navigator.canShare == undefined || navigator.share == undefined) return false
    val payload: dynamic = js("({})")
    payload.files = arrayOf(file)
    return navigator.canShare(payload) as Boolean
}

fun shareFile(file: File, title: String) {
    val payload: dynamic = js("({})")
    payload.files = arrayOf(file)
    payload.title = title
    window.navigator.asDynamic().share(payload)
}
