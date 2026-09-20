package app.veil.camera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.veil.camera.R
import app.veil.camera.data.AppLanguage
import app.veil.camera.data.VeilSettings
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyStrength

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    settings: VeilSettings,
    onEffect: (PrivacyEffect) -> Unit,
    onStrength: (PrivacyStrength) -> Unit,
    onLivePreview: (Boolean) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        // The sheet lives in its own window, which re-provides the activity
        // context and configuration, so the in-app language has to be applied
        // again inside it.
        AppLocale(settings.language) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 28.dp),
            ) {
                Text(
                    stringResource(R.string.settings_privacy),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_privacy_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(22.dp))
                Text(stringResource(R.string.settings_effect), style = MaterialTheme.typography.titleMedium)
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
                Text(stringResource(R.string.settings_strength), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrivacyStrength.entries.forEach { option ->
                        FilterChip(
                            selected = option == settings.strength,
                            onClick = { onStrength(option) },
                            label = { Text(stringResource(option.labelRes())) },
                        )
                    }
                }

                Spacer(Modifier.height(22.dp))
                Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppLanguage.entries.forEach { option ->
                        FilterChip(
                            selected = option == settings.language,
                            onClick = { onLanguage(option) },
                            label = { Text(stringResource(option.labelRes())) },
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
                        Text(
                            stringResource(R.string.settings_shields),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.settings_shields_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = settings.livePreviewEnabled, onCheckedChange = onLivePreview)
                }
            }
        }
    }
}

private fun PrivacyStrength.labelRes(): Int = when (this) {
    PrivacyStrength.BALANCED -> R.string.strength_balanced
    PrivacyStrength.MAXIMUM -> R.string.strength_maximum
}

/** Language names stay in their own language, so they read natively. */
private fun AppLanguage.labelRes(): Int = when (this) {
    AppLanguage.SYSTEM -> R.string.language_system
    AppLanguage.ENGLISH -> R.string.language_english
    AppLanguage.FRENCH -> R.string.language_french
    AppLanguage.ARABIC -> R.string.language_arabic
}
