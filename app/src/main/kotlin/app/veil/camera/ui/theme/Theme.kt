package app.veil.camera.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val VeilMint = Color(0xFF2FD8B3)
val VeilMintSoft = Color(0xFF7FE9D3)
val VeilNight = Color(0xFF0B0E14)
val VeilSurfaceDark = Color(0xFF151A23)
val VeilInk = Color(0xFF10131A)
val VeilCloud = Color(0xFFF5F7FA)

private val DarkColors = darkColorScheme(
    primary = VeilMint,
    onPrimary = VeilNight,
    primaryContainer = Color(0xFF17322C),
    onPrimaryContainer = VeilMintSoft,
    secondary = Color(0xFF8FA0C8),
    background = VeilNight,
    onBackground = Color(0xFFE8ECF3),
    surface = VeilSurfaceDark,
    onSurface = Color(0xFFE8ECF3),
    surfaceVariant = Color(0xFF232A36),
    onSurfaceVariant = Color(0xFFBCC5D4),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF00806A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB9F2E4),
    onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF3F5B8C),
    background = VeilCloud,
    onBackground = VeilInk,
    surface = Color.White,
    onSurface = VeilInk,
    surfaceVariant = Color(0xFFE3E8F0),
    onSurfaceVariant = Color(0xFF424A57),
    error = Color(0xFFBA1A1A),
)

private val VeilTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Light,
        fontSize = 34.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        letterSpacing = 0.4.sp,
    ),
)

@Composable
fun VeilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        val window = (LocalContext.current as? Activity)?.window
        SideEffect {
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = VeilTypography, content = content)
}
