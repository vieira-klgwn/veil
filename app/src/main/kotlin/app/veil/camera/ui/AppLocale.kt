package app.veil.camera.ui

import android.content.res.Configuration
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import app.veil.camera.data.AppLanguage
import java.util.Locale

/**
 * Applies the in-app language to everything composed inside it, so switching
 * language takes effect immediately without restarting the activity. Arabic
 * also flips the layout direction, which mirrors the whole camera UI.
 */
@Composable
fun AppLocale(language: AppLanguage, content: @Composable () -> Unit) {
    val tag = language.tag
    if (tag == null) {
        content()
        return
    }
    val context = LocalContext.current
    val locale = remember(tag) { Locale.forLanguageTag(tag) }
    val configuration = remember(tag, LocalConfiguration.current) {
        Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
    }
    // A ContextThemeWrapper keeps the activity as its base context, so the
    // activity result registry, lifecycle and theme owners composables look up
    // through LocalContext are still reachable; createConfigurationContext
    // returns a detached context and breaks them.
    val localizedContext = remember(context, configuration) {
        ContextThemeWrapper(context, 0).apply { applyOverrideConfiguration(configuration) }
    }
    val direction = remember(locale) {
        if (TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL) {
            LayoutDirection.Rtl
        } else {
            LayoutDirection.Ltr
        }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides configuration,
        LocalLayoutDirection provides direction,
        content = content,
    )
}
