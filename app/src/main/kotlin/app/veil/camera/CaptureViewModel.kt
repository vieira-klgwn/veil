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

    private var lastCapture: ByteArray? = null

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
        process(jpeg, settings.value.effect, settings.value.strength)
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
        val faces: List<FaceRegion> = try {
            captureDetector.detect(bitmap)
        } catch (t: Throwable) {
            Log.w(TAG, "face detection failed, saving photo unmodified is not allowed", t)
            throw t
        }
        val detectMs = (System.nanoTime() - detectStart) / 1_000_000
        val outcome = BitmapPrivacy.protect(bitmap, faces, effect, strength)
        return ProtectedPhoto(
            bitmap = bitmap,
            preview = BitmapPrivacy.previewCopy(bitmap),
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
        _uiState.value = CaptureUiState.Camera
    }

    fun consumeMessage() {
        _message.value = null
    }

    override fun onCleared() {
        captureDetector.close()
        super.onCleared()
    }

    private companion object {
        const val TAG = "Veil"
    }
}
