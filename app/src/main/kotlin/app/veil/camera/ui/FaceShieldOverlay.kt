package app.veil.camera.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import app.veil.camera.LivePreviewFaces
import androidx.compose.ui.graphics.PathEffect
import kotlin.math.max

/** Faces the user chose to keep visible are outlined, never filled. */
private val KeptAccent = Color(0xFF7BE38B)
private val KeptDashes = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))

/**
 * Draws a soft shield over every face the viewfinder currently sees. The
 * mapping matches PreviewView's FILL_CENTER scaling.
 */
@Composable
fun FaceShieldOverlay(
    live: LivePreviewFaces,
    keepVisible: Set<Int>,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val pulse by animateFloatAsState(
        targetValue = if (live.faces.isEmpty()) 0f else 1f,
        label = "shieldPulse",
    )
    Canvas(modifier = modifier) {
        if (live.faces.isEmpty() || live.sourceWidth <= 0 || live.sourceHeight <= 0) return@Canvas
        val scale = max(size.width / live.sourceWidth, size.height / live.sourceHeight)
        val dx = (size.width - live.sourceWidth * scale) / 2f
        val dy = (size.height - live.sourceHeight * scale) / 2f
        for (tracked in live.faces) {
            val face = tracked.region
            val kept = tracked.trackingId != null && tracked.trackingId in keepVisible
            val color = if (kept) KeptAccent else accent
            val cxRaw = face.centerX * scale + dx
            val cx = if (live.mirrored) size.width - cxRaw else cxRaw
            val cy = face.centerY * scale + dy
            val rx = face.radiusX * scale * 1.06f
            val ry = face.radiusY * scale * 1.06f
            val rotation = if (live.mirrored) -face.rotationDegrees else face.rotationDegrees
            rotate(degrees = rotation, pivot = Offset(cx, cy)) {
                if (!kept) {
                    drawOval(
                        color = color.copy(alpha = 0.18f * pulse),
                        topLeft = Offset(cx - rx, cy - ry),
                        size = Size(rx * 2, ry * 2),
                    )
                }
                drawOval(
                    color = color.copy(alpha = 0.85f * pulse),
                    topLeft = Offset(cx - rx, cy - ry),
                    size = Size(rx * 2, ry * 2),
                    style = Stroke(
                        width = if (kept) 3.5f else 2.5f,
                        pathEffect = if (kept) KeptDashes else null,
                    ),
                )
            }
        }
    }
}
