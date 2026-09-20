package app.veil.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.veil.camera.data.VeilSettings
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyStrength

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: VeilSettings,
    onEffect: (PrivacyEffect) -> Unit,
    onStrength: (PrivacyStrength) -> Unit,
    onLivePreview: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Text("Privacy", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Faces are found and protected on this device. Photos never leave your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(22.dp))
            Text("Effect", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrivacyEffect.entries.forEach { option ->
                    FilterChip(
                        selected = option == settings.effect,
                        onClick = { onEffect(option) },
                        label = { Text(option.label()) },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Text("Protection level", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrivacyStrength.entries.forEach { option ->
                    FilterChip(
                        selected = option == settings.strength,
                        onClick = { onStrength(option) },
                        label = { Text(if (option == PrivacyStrength.BALANCED) "Balanced" else "Maximum") },
                    )
                }
            }

            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Live face shields", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Show which faces will be protected in the viewfinder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.livePreviewEnabled, onCheckedChange = onLivePreview)
            }
        }
    }
}
