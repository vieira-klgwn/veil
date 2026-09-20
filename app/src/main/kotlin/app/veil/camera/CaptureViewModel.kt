package app.veil.camera

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.veil.camera.data.SettingsStore
import app.veil.camera.data.VeilSettings
import app.veil.camera.privacy.BitmapPrivacy
import app.veil.camera.privacy.FaceRegionDetector
import app.veil.camera.privacy.PhotoStore
import app.veil.camera.video.VideoRecorder
import app.veil.privacy.FaceRegion
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyStrength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ProtectedPhoto(
    /** Full resolution result, used for saving and sharing. */
    val bitmap: Bitmap,
    /** Screen sized copy, so review never uploads a 12MP texture per frame. */
    val preview: Bitmap,
    /** Every face found, protected or not, so the user can tap to keep one. */
    val faces: List<FaceRegion>,
    /** Indices into [faces] the user chose to leave visible. */
    val keptVisible: Set<Int>,
    val facesProtected: Int,
    val escalatedFaces: Int,
    val allFacesVerified: Boolean,
    val detectionMillis: Long,
    val processingMillis: Long,
    val width: Int,
    val height: Int,
)

sealed interface CaptureUiState {
    data object Camera : CaptureUiState
    data object Working : CaptureUiState
    data class Review(val photo: ProtectedPhoto) : CaptureUiState
}

data class VideoState(
    val recording: Boolean = false,
    val startedAtMillis: Long = 0L,
    val saving: Boolean = false,
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val captureDetector by lazy { FaceRegionDetector.forCapture() }

    val settings: StateFlow<VeilSettings> = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, VeilSettings())

    private val _uiState = MutableStateFlow<CaptureUiState>(CaptureUiState.Camera)
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    private val _liveFaces = MutableStateFlow<LivePreviewFaces>(LivePreviewFaces())
    val liveFaces: StateFlow<LivePreviewFaces> = _liveFaces.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _savedUri = MutableStateFlow<Uri?>(null)
    val savedUri: StateFlow<Uri?> = _savedUri.asStateFlow()

    private val _video = MutableStateFlow(VideoState())
    val video: StateFlow<VideoState> = _video.asStateFlow()

    /** Faces the user tapped in the viewfinder to keep visible. */
    private val _keepVisible = MutableStateFlow<Set<Int>>(emptySet())
    val keepVisible: StateFlow<Set<Int>> = _keepVisible.asStateFlow()

    val recorder = VideoRecorder(app.cacheDir)

    private var lastCapture: ByteArray? = null
    private var lastFaces: List<FaceRegion> = emptyList()
    private var keptInPhoto: Set<Int> = emptySet()

    fun onLiveFaces(faces: LivePreviewFaces) {
        _liveFaces.value = faces
    }

    fun onCaptureFailed(error: Throwable) {
        Log.w(TAG, "capture failed", error)
        _uiState.value = CaptureUiState.Camera
        _message.value = "Could not take the photo. Please try again."
    }

    fun onPhotoCaptured(jpeg: ByteArray) {
        lastCapture = jpeg
        lastFaces = emptyList()
        keptInPhoto = emptySet()
        process(jpeg, settings.value.effect, settings.value.strength)
    }

    /** Protection settings the live/video frame pipeline should apply. */
    fun frameProtection(): FrameProtection = FrameProtection(
        effect = settings.value.effect,
        strength = settings.value.strength,
        keepVisible = _keepVisible.value,
    )

    /** Tap a face in the viewfinder to keep it visible, tap again to protect it. */
    fun toggleLiveFace(trackingId: Int) {
        _keepVisible.value = _keepVisible.value.toMutableSet().apply {
            if (!add(trackingId)) remove(trackingId)
        }
    }

    /** Tap a face on the review screen to keep it visible in the photo. */
    fun togglePhotoFace(index: Int) {
        val jpeg = lastCapture ?: return
        if (_uiState.value !is CaptureUiState.Review) return
        keptInPhoto = keptInPhoto.toMutableSet().apply { if (!add(index)) remove(index) }
        process(jpeg, settings.value.effect, settings.value.strength)
    }

    fun startRecording(width: Int, height: Int) {
        if (_video.value.recording) return
        if (recorder.start(width, height)) {
            _video.value = VideoState(recording = true, startedAtMillis = System.currentTimeMillis())
        } else {
            _message.value = "Video recording is not available on this device."
        }
    }

    fun stopRecording() {
        if (!_video.value.recording) return
        _video.value = _video.value.copy(recording = false, saving = true)
        viewModelScope.launch {
            val file = withContext(Dispatchers.Default) { recorder.finish() }
            if (file == null) {
                _message.value = "The recording was too short to save."
            } else {
                try {
                    _savedUri.value = PhotoStore.saveVideoToGallery(getApplication(), file)
                    _message.value = "Saved to Movies/Veil"
                } catch (t: Throwable) {
                    Log.e(TAG, "video save failed", t)
                    file.delete()
                    _message.value = "Saving the video failed: ${t.message ?: "unknown error"}"
                }
            }
            _video.value = VideoState()
        }
    }

    fun shareVideo(onIntent: (Intent) -> Unit) {
        val uri = _savedUri.value ?: return
        onIntent(PhotoStore.shareVideoIntent(uri))
    }

    fun changeEffect(effect: PrivacyEffect) {
        viewModelScope.launch { settingsStore.setEffect(effect) }
        val jpeg = lastCapture ?: return
        if (_uiState.value is CaptureUiState.Review || _uiState.value is CaptureUiState.Working) {
            process(jpeg, effect, settings.value.strength)
        }
    }

    fun changeStrength(strength: PrivacyStrength) {
        viewModelScope.launch { settingsStore.setStrength(strength) }
        val jpeg = lastCapture ?: return
        if (_uiState.value is CaptureUiState.Review) {
            process(jpeg, settings.value.effect, strength)
        }
    }

    fun setLivePreview(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setLivePreview(enabled) }
    }

    private fun process(jpeg: ByteArray, effect: PrivacyEffect, strength: PrivacyStrength) {
        _uiState.value = CaptureUiState.Working
        viewModelScope.launch {
            try {
                val photo = withContext(Dispatchers.Default) { protect(jpeg, effect, strength) }
                lastFaces = photo.faces
                _uiState.value = CaptureUiState.Review(photo)
                if (!photo.allFacesVerified) {
                    _message.value = "Some faces needed a stronger effect and were fully masked."
                }
            } catch (oom: OutOfMemoryError) {
                Log.e(TAG, "out of memory while protecting photo", oom)
                _uiState.value = CaptureUiState.Camera
                _message.value = "This photo is too large for the available memory."
            } catch (t: Throwable) {
                Log.e(TAG, "processing failed", t)
                _uiState.value = CaptureUiState.Camera
                _message.value = "Face protection failed, the photo was not saved."
            }
        }
    }

    private suspend fun protect(
        jpeg: ByteArray,
        effect: PrivacyEffect,
        strength: PrivacyStrength,
    ): ProtectedPhoto {
        val bitmap = BitmapPrivacy.decodeUpright(jpeg)
        val detectStart = System.nanoTime()
        // Detection is the slow half, so a re-run for a different effect or a
        // face the user wants kept visible reuses the faces already found.
        val faces: List<FaceRegion> = lastFaces.ifEmpty {
            try {
                captureDetector.detect(bitmap)
            } catch (t: Throwable) {
                Log.w(TAG, "face detection failed, saving photo unmodified is not allowed", t)
                throw t
            }
        }
        val detectMs = (System.nanoTime() - detectStart) / 1_000_000
        val protectedFaces = faces.filterIndexed { index, _ -> index !in keptInPhoto }
        val outcome = BitmapPrivacy.protect(bitmap, protectedFaces, effect, strength)
        return ProtectedPhoto(
            bitmap = bitmap,
            preview = BitmapPrivacy.previewCopy(bitmap),
            faces = faces,
            keptVisible = keptInPhoto,
            facesProtected = outcome.facesProtected,
            escalatedFaces = outcome.escalatedFaces,
            allFacesVerified = outcome.allFacesVerified,
            detectionMillis = detectMs,
            processingMillis = outcome.elapsedMillis,
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    fun save() {
        val review = _uiState.value as? CaptureUiState.Review ?: return
        viewModelScope.launch {
            try {
                val uri = PhotoStore.saveToGallery(getApplication(), review.photo.bitmap)
                _savedUri.value = uri
                _message.value = "Saved to Pictures/Veil"
                retake()
            } catch (t: Throwable) {
                Log.e(TAG, "save failed", t)
                _message.value = "Saving failed: ${t.message ?: "unknown error"}"
            }
        }
    }

    fun share(onIntent: (Intent) -> Unit) {
        val review = _uiState.value as? CaptureUiState.Review ?: return
        viewModelScope.launch {
            try {
                onIntent(PhotoStore.shareIntent(getApplication(), review.photo.bitmap))
            } catch (t: Throwable) {
                Log.e(TAG, "share failed", t)
                _message.value = "Sharing is not available right now."
            }
        }
    }

    fun retake() {
        // The bitmap may still be on screen during the transition, so it is left
        // to the garbage collector instead of being recycled here.
        lastCapture = null
        lastFaces = emptyList()
        keptInPhoto = emptySet()
        _uiState.value = CaptureUiState.Camera
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        recorder.cancel()
        captureDetector.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Veil"
    }
}
