package app.veil.camera.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Image(
            bitmap = photo.bitmap.asImageBitmap(),
            contentDescription = "Protected photo preview",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(bottom = 210.dp),
        )

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
        0 -> "No faces found"
        1 -> "1 face protected"
        else -> "${photo.facesProtected} faces protected"
    }
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
