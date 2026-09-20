package app.veil.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.veil.camera.ui.CameraScreen
import app.veil.camera.ui.ReviewScreen
import app.veil.camera.ui.SettingsSheet
import app.veil.camera.ui.theme.VeilTheme

class MainActivity : ComponentActivity() {

    private val viewModel: CaptureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            VeilTheme {
                VeilApp(viewModel)
            }
        }
    }
}

@Composable
private fun VeilApp(viewModel: CaptureViewModel) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val live by viewModel.liveFaces.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val video by viewModel.video.collectAsStateWithLifecycle()
    val keepVisible by viewModel.keepVisible.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }
    var hasCamera by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCamera = granted }

    LaunchedEffect(Unit) {
        if (!hasCamera) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        val savedVideo = text.startsWith("Saved to Movies")
        val result = snackbarHost.showSnackbar(text, actionLabel = if (savedVideo) "Share" else null)
        if (savedVideo && result == SnackbarResult.ActionPerformed) {
            viewModel.shareVideo { intent ->
                context.startActivity(Intent.createChooser(intent, "Share protected video"))
            }
        }
        viewModel.consumeMessage()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
        // The screens are full bleed and apply their own status/navigation bar
        // padding, so the scaffold neither applies nor consumes system insets.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            if (!hasCamera) {
                PermissionPrompt { permissionLauncher.launch(Manifest.permission.CAMERA) }
            } else {
                when (val state = uiState) {
                    is CaptureUiState.Review -> ReviewScreen(
                        photo = state.photo,
                        effect = settings.effect,
                        onEffectChange = viewModel::changeEffect,
                        onSave = viewModel::save,
                        onShare = { viewModel.share { intent -> context.startActivity(Intent.createChooser(intent, "Share protected photo")) } },
                        onRetake = viewModel::retake,
                        onToggleFace = viewModel::togglePhotoFace,
                    )

                    else -> CameraScreen(
                        live = live,
                        effect = settings.effect,
                        livePreviewEnabled = settings.livePreviewEnabled,
                        busy = uiState is CaptureUiState.Working,
                        video = video,
                        keepVisible = keepVisible,
                        recorder = viewModel.recorder,
                        frameProtection = viewModel::frameProtection,
                        onFaces = viewModel::onLiveFaces,
                        onToggleFace = viewModel::toggleLiveFace,
                        onStartRecording = viewModel::startRecording,
                        onStopRecording = viewModel::stopRecording,
                        onPhoto = viewModel::onPhotoCaptured,
                        onCaptureError = viewModel::onCaptureFailed,
                        onOpenSettings = { showSettings = true },
                        onOpenGallery = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI),
                                )
                            }
                        },
                    )
                }
            }

            if (showSettings) {
                SettingsSheet(
                    settings = settings,
                    onEffect = viewModel::changeEffect,
                    onStrength = viewModel::changeStrength,
                    onLivePreview = viewModel::setLivePreview,
                    onDismiss = { showSettings = false },
                )
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Veil needs the camera",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Photos are analysed and protected on this device. Nothing is uploaded.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRequest) { Text("Allow camera") }
    }
}
