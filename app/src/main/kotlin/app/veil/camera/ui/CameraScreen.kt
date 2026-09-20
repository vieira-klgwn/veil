package app.veil.camera.ui

import android.content.Context
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.veil.camera.LiveFaceAnalyzer
import app.veil.camera.LivePreviewFaces
import app.veil.camera.R
import app.veil.privacy.PrivacyEffect
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Composable
fun CameraScreen(
    live: LivePreviewFaces,
    effect: PrivacyEffect,
    livePreviewEnabled: Boolean,
    busy: Boolean,
    onFaces: (LivePreviewFaces) -> Unit,
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

    LaunchedEffect(lensFacing, livePreviewEnabled) {
        try {
            val provider = context.awaitCameraProvider()
            provider.unbindAll()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val useCases = buildList {
                add(preview)
                add(imageCapture)
                if (livePreviewEnabled) {
                    add(
                        ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) },
                    )
                }
            }
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
            FaceShieldOverlay(live = live, accent = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize())
        }

        PrivacyChip(
            faceCount = live.faces.size,
            effect = effect,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
        )

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
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

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onOpenGallery, modifier = Modifier.size(52.dp)) {
                Icon(
                    Icons.Filled.PhotoLibrary,
                    contentDescription = stringResource(R.string.cd_gallery),
                    tint = Color.White,
                )
            }
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
            IconButton(
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                },
                modifier = Modifier.size(52.dp),
            ) {
                Icon(
                    Icons.Filled.Cameraswitch,
                    contentDescription = stringResource(R.string.cd_switch_camera),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun PrivacyChip(faceCount: Int, effect: PrivacyEffect, modifier: Modifier = Modifier) {
    val label = when {
        faceCount == 0 -> "Privacy on"
        faceCount == 1 -> "1 face will be protected"
        else -> "$faceCount faces will be protected"
    }
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
