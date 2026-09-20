package app.veil.camera.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.veil.privacy.PrivacyEffect
import app.veil.privacy.PrivacyStrength
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "veil_settings")

data class VeilSettings(
    val effect: PrivacyEffect = PrivacyEffect.BLUR,
    val strength: PrivacyStrength = PrivacyStrength.BALANCED,
    val livePreviewEnabled: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private val effectKey = stringPreferencesKey("effect")
    private val strengthKey = stringPreferencesKey("strength")
    private val liveKey = booleanPreferencesKey("live_preview")

    val settings: Flow<VeilSettings> = context.dataStore.data.map { prefs ->
        VeilSettings(
            effect = prefs[effectKey]?.let { runCatching { PrivacyEffect.valueOf(it) }.getOrNull() }
                ?: PrivacyEffect.BLUR,
            strength = prefs[strengthKey]?.let { runCatching { PrivacyStrength.valueOf(it) }.getOrNull() }
                ?: PrivacyStrength.BALANCED,
            livePreviewEnabled = prefs[liveKey] ?: true,
        )
    }

    suspend fun setEffect(effect: PrivacyEffect) {
        context.dataStore.edit { it[effectKey] = effect.name }
    }

    suspend fun setStrength(strength: PrivacyStrength) {
        context.dataStore.edit { it[strengthKey] = strength.name }
    }

    suspend fun setLivePreview(enabled: Boolean) {
        context.dataStore.edit { it[liveKey] = enabled }
    }
}
