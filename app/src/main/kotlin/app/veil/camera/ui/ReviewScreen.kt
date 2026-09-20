package app.veil.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.veil.camera.ProtectedPhoto
import app.veil.privacy.PrivacyEffect

@Composable
fun ReviewScreen(
    photo: ProtectedPhoto,
    effect: PrivacyEffect,
    onEffectChange: (PrivacyEffect) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onRetake: () -> Unit,
    onToggleFace: (Int) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.fillMaxSize().padding(bottom = 210.dp)) {
            Image(
                bitmap = photo.preview.asImageBitmap(),
                contentDescription = "Protected photo preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            FaceMarkers(photo = photo, onToggleFace = onToggleFace, modifier = Modifier.fillMaxSize())
        }

        ProtectionBanner(
            photo = photo,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 12.dp),
        )

        AnimatedVisibility(
            visible = true,
            enter = slideInVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                if (photo.faces.isNotEmpty()) {
                    Text(
                        text = if (photo.keptVisible.isEmpty()) {
                            "Tap a face to keep it visible"
                        } else {
                            "${photo.keptVisible.size} face(s) kept visible · tap again to protect"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                Text(
                    text = "Privacy effect",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrivacyEffect.entries.forEach { option ->
                        FilterChip(
                            selected = option == effect,
                            onClick = { onEffectChange(option) },
                            label = { Text(option.label()) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            ),
                            modifier = Modifier.semantics { contentDescription = "${option.label()} effect" },
                        )
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onRetake,
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = ActionPadding,
                    ) {
                        ActionContent(Icons.Filled.Refresh, "Retake")
                    }
                    OutlinedButton(
                        onClick = onShare,
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = ActionPadding,
                    ) {
                        ActionContent(Icons.Filled.Share, "Share")
                    }
                    Button(
                        onClick = onSave,
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = ActionPadding,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    ) {
                        ActionContent(Icons.Filled.Check, "Save")
                    }
                }
            }
        }
    }
}

/**
 * Draws a tappable marker over every detected face so the user can keep a
 * chosen face visible. Markers follow the same Fit mapping as the image.
 */
@Composable
private fun FaceMarkers(
    photo: ProtectedPhoto,
    onToggleFace: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (photo.faces.isEmpty()) return
    val accent = MaterialTheme.colorScheme.primary
    Canvas(
        modifier = modifier.pointerInput(photo) {
            detectTapGestures { tap ->
                val fit = fitTransform(size.width.toFloat(), size.height.toFloat(), photo)
                val x = (tap.x - fit.dx) / fit.scale
                val y = (tap.y - fit.dy) / fit.scale
                photo.faces
                    .withIndex()
                    .filter { (_, face) -> face.contains(x, y, margin = 1.3f) }
                    .minByOrNull { (_, face) -> face.area }
                    ?.let { (index, _) -> onToggleFace(index) }
            }
        },
    ) {
        val fit = fitTransform(size.width, size.height, photo)
        photo.faces.forEachIndexed { index, face ->
            val kept = index in photo.keptVisible
            val cx = face.centerX * fit.scale + fit.dx
            val cy = face.centerY * fit.scale + fit.dy
            val rx = face.radiusX * fit.scale * 1.1f
            val ry = face.radiusY * fit.scale * 1.1f
            rotate(degrees = face.rotationDegrees, pivot = Offset(cx, cy)) {
                drawOval(
                    color = if (kept) KeptFaceAccent else accent,
                    topLeft = Offset(cx - rx, cy - ry),
                    size = Size(rx * 2, ry * 2),
                    style = Stroke(width = if (kept) 4f else 2.5f, pathEffect = if (kept) KeptFaceDashes else null),
                )
            }
        }
    }
}

private data class FitTransform(val scale: Float, val dx: Float, val dy: Float)

private fun fitTransform(viewWidth: Float, viewHeight: Float, photo: ProtectedPhoto): FitTransform {
    val scale = minOf(viewWidth / photo.width, viewHeight / photo.height)
    return FitTransform(
        scale = scale,
        dx = (viewWidth - photo.width * scale) / 2f,
        dy = (viewHeight - photo.height * scale) / 2f,
    )
}

private val KeptFaceAccent = Color(0xFF7BE38B)
private val KeptFaceDashes = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))

private val ActionPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)

/** Icon plus a label that always stays on one line, even on narrow screens. */
@Composable
private fun ActionContent(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.size(6.dp))
    Text(
        text = label,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Visible,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
private fun ProtectionBanner(photo: ProtectedPhoto, modifier: Modifier = Modifier) {
    val headline = when (photo.facesProtected) {
        0 -> if (photo.faces.isEmpty()) "No faces found" else "No faces protected"
        1 -> "1 face protected"
        else -> "${photo.facesProtected} faces protected"
    } + if (photo.keptVisible.isNotEmpty()) " · ${photo.keptVisible.size} kept visible" else ""
    Row(
        modifier = modifier
            .background(Color(0x99000000), CircleShape)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.VerifiedUser,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.size(10.dp))
        Column {
            Text(headline, color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${photo.width} × ${photo.height} · background untouched",
                color = Color(0xFFBCC5D4),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
