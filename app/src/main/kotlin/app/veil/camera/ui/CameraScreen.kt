package app.veil.camera.ui

import android.content.Context
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.veil.camera.FrameProtection
import app.veil.camera.LiveFaceAnalyzer
import app.veil.camera.LivePreviewFaces
import app.veil.camera.R
import app.veil.camera.VideoState
import app.veil.camera.video.VideoRecorder
import app.veil.privacy.PrivacyEffect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    live: LivePreviewFaces,
    effect: PrivacyEffect,
    livePreviewEnabled: Boolean,
    busy: Boolean,
    video: VideoState,
    keepVisible: Set<Int>,
    recorder: VideoRecorder,
    frameProtection: () -> FrameProtection,
    onFaces: (LivePreviewFaces) -> Unit,
    onToggleFace: (Int) -> Unit,
    onStartRecording: (Int, Int) -> Unit,
    onStopRecording: () -> Unit,
    onPhoto: (ByteArray) -> Unit,
    onCaptureError: (Throwable) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var videoMode by remember { mutableStateOf(false) }

    val executor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val imageCapture = remember(lensFacing) {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(98)
            .build()
    }
    val analyzer = remember(lensFacing) {
        LiveFaceAnalyzer(
            scope = scope,
            isMirrored = { lensFacing == CameraSelector.LENS_FACING_FRONT },
            overlayEnabled = { livePreviewEnabled },
            protection = frameProtection,
            recorder = recorder,
            onFaces = onFaces,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.close()
            executor.shutdown()
        }
    }

    // Keeps captured photos upright even though the UI stays in portrait.
    DisposableEffect(imageCapture) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        if (listener.canDetectOrientation()) listener.enable()
        onDispose { listener.disable() }
    }

    // Analysis stays bound even with the overlay off: video recording is fed
    // from these frames, and they are the only frames the app can anonymize
    // before they reach the encoder.
    LaunchedEffect(lensFacing) {
        try {
            val provider = context.awaitCameraProvider()
            provider.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(VIDEO_WIDTH, VIDEO_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build(),
                )
                .build()
                .also { it.setAnalyzer(executor, analyzer) }
            val useCases = listOf(preview, imageCapture, analysis)
            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                *useCases.toTypedArray(),
            )
            cameraError = null
        } catch (t: Throwable) {
            cameraError = "Camera unavailable: ${t.message ?: "unknown error"}"
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize().semantics { contentDescription = "Camera viewfinder" },
        )
        if (livePreviewEnabled) {
            FaceShieldOverlay(
                live = live,
                keepVisible = keepVisible,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(live) {
                        detectTapGestures { tap ->
                            live.faceAt(tap, size.width.toFloat(), size.height.toFloat())
                                ?.let(onToggleFace)
                        }
                    },
            )
        }

        Column(
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrivacyChip(
                faceCount = live.faces.count { it.trackingId == null || it.trackingId !in keepVisible },
                keptCount = live.faces.count { it.trackingId != null && it.trackingId in keepVisible },
                effect = effect,
            )
            if (video.recording) {
                Spacer(Modifier.size(8.dp))
                RecordingChip(startedAtMillis = video.startedAtMillis)
            }
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(8.dp)
                .background(ControlScrim, CircleShape),
        ) {
            Icon(
                Icons.Filled.Tune,
                contentDescription = stringResource(R.string.cd_settings),
                tint = Color.White,
            )
        }

        cameraError?.let { error ->
            Text(
                text = error,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xCC1B0F14), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (livePreviewEnabled && live.faces.isNotEmpty()) {
                Text(
                    text = if (keepVisible.isEmpty()) {
                        "Tap a face to keep it visible"
                    } else {
                        "${keepVisible.size} face(s) kept visible · tap again to protect"
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .background(ControlScrim, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Spacer(Modifier.size(10.dp))
            }
            ModeSwitch(
                videoMode = videoMode,
                enabled = !video.recording && !video.saving,
                onChange = { videoMode = it },
            )
            Spacer(Modifier.size(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
            IconButton(
                onClick = onOpenGallery,
                modifier = Modifier.size(52.dp).background(ControlScrim, CircleShape),
            ) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = stringResource(R.string.cd_gallery),
                    tint = Color.White,
                )
            }
            if (videoMode) {
                RecordButton(recording = video.recording, saving = video.saving) {
                    if (video.recording) {
                        onStopRecording()
                    } else {
                        val width = live.sourceWidth.takeIf { it > 0 } ?: VIDEO_WIDTH
                        val height = live.sourceHeight.takeIf { it > 0 } ?: VIDEO_HEIGHT
                        onStartRecording(width, height)
                    }
                }
            } else {
            ShutterButton(busy = busy) {
                imageCapture.takePicture(
                    executor,
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            try {
                                val buffer = image.planes[0].buffer
                                val bytes = ByteArray(buffer.remaining())
                                buffer.get(bytes)
                                onPhoto(bytes)
                            } catch (t: Throwable) {
                                onCaptureError(t)
                            } finally {
                                image.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) = onCaptureError(exception)
                    },
                )
            }
            }
            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                // Switching cameras rebinds the stream the recorder is fed from.
                enabled = !video.recording,
                modifier = Modifier.size(52.dp).background(ControlScrim, CircleShape),
            ) {
                Icon(
                    Icons.Filled.Cameraswitch,
                    contentDescription = stringResource(R.string.cd_switch_camera),
                    tint = if (video.recording) Color.White.copy(alpha = 0.4f) else Color.White,
                )
            }
            }
        }
    }
}

/** Maps a tap on the viewfinder back to the tracking id of the face under it. */
private fun LivePreviewFaces.faceAt(tap: Offset, viewWidth: Float, viewHeight: Float): Int? {
    if (sourceWidth <= 0 || sourceHeight <= 0) return null
    val scale = maxOf(viewWidth / sourceWidth, viewHeight / sourceHeight)
    val dx = (viewWidth - sourceWidth * scale) / 2f
    val dy = (viewHeight - sourceHeight * scale) / 2f
    val x = (if (mirrored) viewWidth - tap.x else tap.x - 0f).let { (it - dx) / scale }
    val y = (tap.y - dy) / scale
    return faces
        .filter { it.trackingId != null && it.region.contains(x, y, margin = 1.3f) }
        .minByOrNull { it.region.area }
        ?.trackingId
}

@Composable
private fun PrivacyChip(
    faceCount: Int,
    keptCount: Int,
    effect: PrivacyEffect,
    modifier: Modifier = Modifier,
) {
    val label = when {
        faceCount == 0 && keptCount == 0 -> "Privacy on"
        faceCount == 0 -> "$keptCount kept visible"
        faceCount == 1 -> "1 face will be protected"
        else -> "$faceCount faces will be protected"
    } + if (faceCount > 0 && keptCount > 0) " · $keptCount kept visible" else ""
    Row(
        modifier = modifier
            .background(Color(0x66000000), CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = "$label · ${effect.label()}",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ShutterButton(busy: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(78.dp)
            .background(Color.White.copy(alpha = 0.18f), CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .clickable(enabled = !busy, onClick = onClick)
            .semantics { contentDescription = "Take a protected photo" },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(visible = !busy, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .size(60.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        if (busy) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(38.dp),
            )
        }
    }
}

/** Elapsed recording time, driven by a one second tick. */
@Composable
private fun RecordingChip(startedAtMillis: Long) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMillis) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(500)
        }
    }
    val seconds = ((now - startedAtMillis) / 1000).coerceAtLeast(0)
    Row(
        modifier = Modifier
            .background(Color(0xCC8E1111), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { contentDescription = "Recording a protected video" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(9.dp).background(Color.White, CircleShape))
        Spacer(Modifier.size(8.dp))
        Text(
            text = "REC %02d:%02d".format(seconds / 60, seconds % 60),
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun ModeSwitch(videoMode: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .background(ControlScrim, CircleShape)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModeChip("Photo", selected = !videoMode, enabled = enabled) { onChange(false) }
        ModeChip("Video", selected = videoMode, enabled = enabled) { onChange(true) }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val content = when {
        selected -> MaterialTheme.colorScheme.onPrimary
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.4f)
    }
    Text(
        text = label,
        color = content,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .background(background, CircleShape)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 8.dp)
            .semantics { contentDescription = "$label mode" },
    )
}

@Composable
private fun RecordButton(recording: Boolean, saving: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(78.dp)
            .background(Color.White.copy(alpha = 0.18f), CircleShape)
            .border(3.dp, Color.White, CircleShape)
            .clickable(enabled = !saving, onClick = onClick)
            .semantics {
                contentDescription = if (recording) {
                    "Stop recording"
                } else {
                    "Record a protected video"
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            saving -> CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 3.dp,
                modifier = Modifier.size(38.dp),
            )
            recording -> Box(
                Modifier.size(30.dp).background(Color(0xFFE53935), RoundedCornerShape(6.dp)),
            )
            else -> Box(Modifier.size(60.dp).background(Color(0xFFE53935), CircleShape))
        }
    }
}

fun PrivacyEffect.label(): String = when (this) {
    PrivacyEffect.BLUR -> "Blur"
    PrivacyEffect.PIXELATE -> "Pixelate"
    PrivacyEffect.MASK -> "Mask"
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider {
    val future = ProcessCameraProvider.getInstance(this)
    return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { cont.resumeWith(Result.success(it)) }
                    .onFailure { cont.cancel(it) }
            },
            ContextCompat.getMainExecutor(this),
        )
    }
}

/** Keeps white controls legible over bright previews. */
private val ControlScrim = Color(0x66000000)

/** Requested analysis/recording resolution; the camera picks the closest. */
private const val VIDEO_WIDTH = 720
private const val VIDEO_HEIGHT = 1280
