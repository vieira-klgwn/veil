package app.veil.web

import app.veil.privacy.FaceRegion
import app.veil.privacy.FaceTracker
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyStrength
import app.veil.privacy.TrackedRegion
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.await
import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLCanvasElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLSelectElement
import org.w3c.dom.HTMLVideoElement
import org.w3c.dom.ImageData
import org.w3c.dom.events.MouseEvent
import org.w3c.dom.mediacapture.MediaStreamConstraints
import org.w3c.dom.url.URL
import org.w3c.files.Blob
import org.w3c.files.BlobPropertyBag
import org.w3c.files.File
import org.w3c.files.FilePropertyBag

private const val MODEL_PATH = "models/blaze_face_short_range.tflite"
private const val WASM_PATH = "wasm"
private const val MAX_PROCESSING_WIDTH = 960
private const val RECORDING_FPS = 30

/**
 * Detection is far heavier than protection, and faces do not move between
 * consecutive frames, so it runs on its own slower clock while every frame is
 * still protected with the latest regions.
 */
private const val DETECTION_INTERVAL_MS = 120.0

/**
 * The whole browser camera: preview, on-device detection, protection through
 * the shared engine, review, recording, download and share.
 *
 * Only protected pixels ever reach the output canvas, and the output canvas is
 * the only thing photos and recordings are made from — the raw frame stays in
 * memory for the length of one frame, or until a review is dismissed.
 */
class CameraApp {

    private val video = element<HTMLVideoElement>("camera")
    private val stage = element<HTMLCanvasElement>("stage")
    private val stageContext = stage.context2d()
    private val output = document.createElement("canvas") as HTMLCanvasElement
    private val outputContext = output.context2d()
    private val work = document.createElement("canvas") as HTMLCanvasElement
    private val workContext = work.context2d()

    private val shutter = element<HTMLButtonElement>("shutter")
    private val record = element<HTMLButtonElement>("record")
    private val save = element<HTMLButtonElement>("save")
    private val share = element<HTMLButtonElement>("share")
    private val retake = element<HTMLButtonElement>("retake")
    private val effectSelect = element<HTMLSelectElement>("effect")
    private val strengthSelect = element<HTMLSelectElement>("strength")
    private val status = element<HTMLElement>("status")
    private val reviewBar = element<HTMLElement>("review-bar")
    private val liveBar = element<HTMLElement>("live-bar")
    private val timer = element<HTMLElement>("timer")

    private val tracker = FaceTracker()
    private var detector: WebFaceDetector? = null
    private var detectionError: String? = null
    private var lastDetectionAt = 0.0
    private val keepVisible = mutableSetOf<Int>()
    private var tracked: List<TrackedRegion> = emptyList()

    private var reviewFrame: ImageData? = null
    private var photoBlob: Blob? = null
    private var recorder: MediaRecorder? = null
    private val recordedChunks = mutableListOf<Blob>()
    private var recordingStartedAt = 0.0
    private var videoBlob: Blob? = null
    private var lastSaveName = "veil.jpg"

    private val effect: PrivacyEffect get() = PrivacyEffect.valueOf(effectSelect.value)
    private val strength: PrivacyStrength get() = PrivacyStrength.valueOf(strengthSelect.value)
    private val inReview: Boolean get() = reviewFrame != null

    suspend fun start() {
        restoreSettings()
        wireControls()
        status.textContent = "Loading the on-device face detector…"
        try {
            detector = WebFaceDetector.create(WASM_PATH, MODEL_PATH)
        } catch (error: Throwable) {
            status.textContent = "Face detector failed to load: ${error.message}"
            return
        }
        status.textContent = "Asking for camera permission…"
        try {
            openCamera()
        } catch (error: Throwable) {
            status.textContent = "No camera: ${error.message}"
            return
        }
        status.textContent = "Tap a face to keep it visible."
        window.requestAnimationFrame { renderLoop() }
    }

    private suspend fun openCamera() {
        val videoConstraints: dynamic = js(
            "({ facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } })",
        )
        val constraints = MediaStreamConstraints(video = videoConstraints, audio = false)
        val stream = window.navigator.mediaDevices.getUserMedia(constraints).await()
        video.srcObject = stream
        video.play()
    }

    private fun wireControls() {
        shutter.addEventListener("click", { capturePhoto() })
        record.addEventListener("click", { toggleRecording() })
        retake.addEventListener("click", { leaveReview() })
        save.addEventListener("click", { downloadResult() })
        share.addEventListener("click", { shareResult() })
        stage.addEventListener("click", { event -> toggleFaceAt(event as MouseEvent) })
        effectSelect.addEventListener("change", {
            window.localStorage.setItem("veil.effect", effectSelect.value)
            if (inReview) renderReview()
        })
        strengthSelect.addEventListener("change", {
            window.localStorage.setItem("veil.strength", strengthSelect.value)
            if (inReview) renderReview()
        })
    }

    private fun restoreSettings() {
        window.localStorage.getItem("veil.effect")?.let { effectSelect.value = it }
        window.localStorage.getItem("veil.strength")?.let { strengthSelect.value = it }
    }

    private fun renderLoop() {
        // Scheduled before the work so one bad frame cannot kill the preview.
        window.requestAnimationFrame { renderLoop() }
        if (inReview) return
        try {
            renderLiveFrame()
        } catch (error: Throwable) {
            status.textContent = "Frame failed: ${error.message}"
        }
    }

    private fun renderLiveFrame() {
        val detector = this.detector ?: return
        if (video.readyState < 2 || video.videoWidth == 0) return

        val width = min(MAX_PROCESSING_WIDTH, video.videoWidth)
        val height = (video.videoHeight.toDouble() * width / video.videoWidth).roundToInt()
        resizeCanvases(width, height)

        workContext.drawImage(video, 0.0, 0.0, width.toDouble(), height.toDouble())
        val frame = workContext.getImageData(0.0, 0.0, width.toDouble(), height.toDouble())

        val now = window.performance.now()
        if (now - lastDetectionAt >= DETECTION_INTERVAL_MS) {
            lastDetectionAt = now
            tracked = tracker.update(detectFaces(detector, now))
        }

        protectInto(frame, tracked)
        drawStage()
        updateStatus(tracked.size)
    }

    /** A frame the detector chokes on is skipped, not shown unprotected. */
    private fun detectFaces(detector: WebFaceDetector, timestampMs: Double): List<FaceRegion> =
        try {
            detector.detect(work, timestampMs).also { detectionError = null }
        } catch (error: Throwable) {
            detectionError = error.message
            tracked.map { it.region }
        }

    /** Protects every tracked face except the ones the user chose to keep visible. */
    private fun protectInto(frame: ImageData, faces: List<TrackedRegion>) {
        val protectedFaces = faces.filter { it.id !in keepVisible }.map { it.region }
        CanvasPrivacy.protect(frame, protectedFaces, effect, strength)
        outputContext.putImageData(frame, 0.0, 0.0)
    }

    private fun drawStage() {
        stageContext.drawImage(output, 0.0, 0.0)
        for (face in tracked) drawMarker(face)
    }

    private fun drawMarker(face: TrackedRegion) {
        val kept = face.id in keepVisible
        stageContext.save()
        stageContext.translate(face.region.centerX.toDouble(), face.region.centerY.toDouble())
        stageContext.rotate(face.region.rotationDegrees.toDouble() * PI / 180.0)
        stageContext.beginPath()
        stageContext.ellipse(
            0.0,
            0.0,
            face.region.radiusX.toDouble() * 1.12,
            face.region.radiusY.toDouble() * 1.12,
            0.0,
            0.0,
            2 * PI,
        )
        stageContext.strokeStyle = if (kept) "#7ee787" else "#8ab4ff"
        stageContext.lineWidth = 2.0
        stageContext.setLineDash(if (kept) arrayOf(6.0, 5.0) else emptyArray())
        stageContext.stroke()
        stageContext.restore()
    }

    private fun toggleFaceAt(event: MouseEvent) {
        val rect = stage.getBoundingClientRect()
        val x = ((event.clientX - rect.left) * stage.width / rect.width).toFloat()
        val y = ((event.clientY - rect.top) * stage.height / rect.height).toFloat()
        val hit = tracked.firstOrNull { it.region.contains(x, y, margin = 1.25f) } ?: return
        if (!keepVisible.add(hit.id)) keepVisible.remove(hit.id)
        if (inReview) renderReview() else drawStage()
    }

    private fun capturePhoto() {
        if (video.readyState < 2) return
        val width = stage.width
        val height = stage.height
        workContext.drawImage(video, 0.0, 0.0, width.toDouble(), height.toDouble())
        reviewFrame = workContext.getImageData(0.0, 0.0, width.toDouble(), height.toDouble())
        renderReview()
        liveBar.hidden = true
        reviewBar.hidden = false
        status.textContent = "Review: tap a face to keep it visible, then save or share."
    }

    private fun renderReview() {
        val raw = reviewFrame ?: return
        // getImageData hands back a fresh buffer, so the protected pass never
        // touches the stored raw frame and the review can be redone from it.
        workContext.putImageData(raw, 0.0, 0.0)
        val copy = workContext.getImageData(0.0, 0.0, raw.width.toDouble(), raw.height.toDouble())
        protectInto(copy, tracked)
        drawStage()
        photoBlob = null
        output.toBlobAsync("image/jpeg", 0.96) { blob -> photoBlob = blob }
    }

    private fun leaveReview() {
        reviewFrame = null
        photoBlob = null
        videoBlob = null
        reviewBar.hidden = true
        liveBar.hidden = false
        status.textContent = "Tap a face to keep it visible."
    }

    private fun toggleRecording() {
        val active = recorder
        if (active != null && active.state == "recording") {
            active.stop()
            return
        }
        recordedChunks.clear()
        val mimeType = listOf(
            "video/webm;codecs=vp9",
            "video/webm;codecs=vp8",
            "video/webm",
        ).firstOrNull { MediaRecorder.isTypeSupported(it) }
        if (mimeType == null) {
            status.textContent = "This browser cannot record video."
            return
        }
        val options: dynamic = js("({})")
        options.mimeType = mimeType
        // The stream comes from the protected output canvas, so the raw camera
        // frames are never part of the recording.
        val recorder = MediaRecorder(output.captureStreamAt(RECORDING_FPS), options)
        recorder.ondataavailable = { event ->
            val data = event.data.unsafeCast<Blob>()
            if (data.size.toDouble() > 0.0) recordedChunks.add(data)
        }
        recorder.onstop = { finishRecording() }
        recorder.start(250)
        this.recorder = recorder
        recordingStartedAt = window.performance.now()
        record.textContent = "Stop"
        record.classList.add("recording")
        tickTimer()
    }

    private fun tickTimer() {
        val active = recorder
        if (active == null || active.state != "recording") {
            timer.textContent = ""
            return
        }
        val seconds = ((window.performance.now() - recordingStartedAt) / 1000.0).toInt()
        timer.textContent = "● ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        window.setTimeout({ tickTimer() }, 250)
    }

    private fun finishRecording() {
        recorder = null
        record.textContent = "Record"
        record.classList.remove("recording")
        timer.textContent = ""
        val options: dynamic = js("({ type: 'video/webm' })")
        videoBlob = Blob(recordedChunks.toTypedArray(), options.unsafeCast<BlobPropertyBag>())
        photoBlob = null
        liveBar.hidden = true
        reviewBar.hidden = false
        status.textContent = "Protected clip ready — save or share it."
    }

    private fun currentFile(): File? {
        val clip = videoBlob
        if (clip != null) {
            lastSaveName = "veil-${timestamp()}.webm"
            return File(arrayOf(clip), lastSaveName, fileOptions("video/webm"))
        }
        val photo = photoBlob ?: return null
        lastSaveName = "veil-${timestamp()}.jpg"
        return File(arrayOf(photo), lastSaveName, fileOptions("image/jpeg"))
    }

    private fun downloadResult() {
        val file = currentFile()
        if (file == null) {
            status.textContent = "Nothing to save yet."
            return
        }
        val url = URL.createObjectURL(file)
        val link = document.createElement("a").unsafeCast<HTMLAnchorElement>()
        link.href = url
        link.download = lastSaveName
        link.click()
        window.setTimeout({ URL.revokeObjectURL(url) }, 10_000)
        status.textContent = "Saved $lastSaveName to your downloads."
    }

    private fun shareResult() {
        val file = currentFile()
        if (file == null) {
            status.textContent = "Nothing to share yet."
            return
        }
        if (!canShareFile(file)) {
            status.textContent = "This browser cannot share files; use Save instead."
            return
        }
        shareFile(file, "Veil")
    }

    private fun updateStatus(faceCount: Int) {
        // The preview keeps running behind a finished clip; its status must not
        // overwrite the "clip ready" prompt.
        if (recorder != null || videoBlob != null) return
        val kept = tracked.count { it.id in keepVisible }
        detectionError?.let {
            status.textContent = "Face detection failed on this frame: $it"
            return
        }
        status.textContent = when {
            faceCount == 0 -> "No face in frame."
            kept == 0 -> "$faceCount face(s) protected. Tap one to keep it visible."
            else -> "${faceCount - kept} protected, $kept kept visible."
        }
    }

    private fun resizeCanvases(width: Int, height: Int) {
        if (stage.width == width && stage.height == height) return
        for (canvas in listOf(stage, output, work)) {
            canvas.width = width
            canvas.height = height
        }
    }

    private fun timestamp(): String = js("Date.now()").toString()

    private fun fileOptions(type: String): FilePropertyBag {
        val bag: dynamic = js("({})")
        bag.type = type
        return bag.unsafeCast<FilePropertyBag>()
    }

    private fun <T : HTMLElement> element(id: String): T =
        document.getElementById(id).unsafeCast<T>()

    private fun HTMLCanvasElement.context2d(): CanvasRenderingContext2D {
        val settings: dynamic = js("({ willReadFrequently: true })")
        return getContext("2d", settings).unsafeCast<CanvasRenderingContext2D>()
    }
}
