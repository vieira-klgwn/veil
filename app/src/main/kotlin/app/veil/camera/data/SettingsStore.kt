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

/**
 * Languages the app ships translations for. [SYSTEM] follows the device
 * setting; the others override it inside the app only.
 */
enum class AppLanguage(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    FRENCH("fr"),
    ARABIC("ar"),
}

data class VeilSettings(
    val effect: PrivacyEffect = PrivacyEffect.BLUR,
    val strength: PrivacyStrength = PrivacyStrength.BALANCED,
    val livePreviewEnabled: Boolean = true,
    val language: AppLanguage = AppLanguage.SYSTEM,
)

class SettingsStore(private val context: Context) {

    private val effectKey = stringPreferencesKey("effect")
    private val strengthKey = stringPreferencesKey("strength")
    private val liveKey = booleanPreferencesKey("live_preview")
    private val languageKey = stringPreferencesKey("language")

    val settings: Flow<VeilSettings> = context.dataStore.data.map { prefs ->
        VeilSettings(
            effect = prefs[effectKey]?.let { runCatching { PrivacyEffect.valueOf(it) }.getOrNull() }
                ?: PrivacyEffect.BLUR,
            strength = prefs[strengthKey]?.let { runCatching { PrivacyStrength.valueOf(it) }.getOrNull() }
                ?: PrivacyStrength.BALANCED,
            livePreviewEnabled = prefs[liveKey] ?: true,
            language = prefs[languageKey]?.let { runCatching { AppLanguage.valueOf(it) }.getOrNull() }
                ?: AppLanguage.SYSTEM,
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

    suspend fun setLanguage(language: AppLanguage) {
        context.dataStore.edit { it[languageKey] = language.name }
    }
}
