package app.matterpair.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Fallback palette (used when dynamic color is unavailable, e.g. some custom ROMs). */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B4FD8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE1FF),
    onPrimaryContainer = Color(0xFF001551),
    secondary = Color(0xFF595D72),
    secondaryContainer = Color(0xFFDEE1F9),
    tertiary = Color(0xFF006C4C),
    tertiaryContainer = Color(0xFF89F8C7),
    background = Color(0xFFF8F9FF),
    surface = Color(0xFFF8F9FF),
    surfaceContainer = Color(0xFFECEEF7),
    surfaceContainerHigh = Color(0xFFE6E8F1),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB6C4FF),
    onPrimary = Color(0xFF002780),
    primaryContainer = Color(0xFF0039B3),
    onPrimaryContainer = Color(0xFFDCE1FF),
    secondary = Color(0xFFC2C5DD),
    secondaryContainer = Color(0xFF424659),
    tertiary = Color(0xFF6CDBAC),
    tertiaryContainer = Color(0xFF005138),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2026),
    surfaceContainerHigh = Color(0xFF282A30),
)

/** Traffic-light colors that stay readable on both light and dark surfaces. */
@Immutable
data class StatusColors(val ok: Color, val warn: Color, val fail: Color, val okContainer: Color, val warnContainer: Color, val failContainer: Color)

private val LightStatus = StatusColors(
    ok = Color(0xFF1E8E3E), warn = Color(0xFFB06000), fail = Color(0xFFC5221F),
    okContainer = Color(0xFFE6F4EA), warnContainer = Color(0xFFFEF7E0), failContainer = Color(0xFFFCE8E6),
)
private val DarkStatus = StatusColors(
    ok = Color(0xFF81C995), warn = Color(0xFFFDD663), fail = Color(0xFFF28B82),
    okContainer = Color(0xFF0D3B1E), warnContainer = Color(0xFF3D2E00), failContainer = Color(0xFF410E0B),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

private val AppTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun MatterPairTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    // Dynamic color ("Material You") is available on every supported SDK (minSdk 31).
    val base = runCatching { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
        .getOrElse { if (dark) DarkColors else LightColors }
    val colors = if (dark) withTintedSurfaces(base) else base
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, shapes = AppShapes, content = content)
    }
}

/**
 * Some ROMs (seen on LineageOS) deliver a dynamic dark scheme whose surface containers are pure
 * black, so cards vanish into the background. We therefore always derive the container tones
 * ourselves: the background with an increasing tint of the primary color, like Material 3's
 * tonal elevation. This keeps cards visibly separated on every ROM.
 */
private fun withTintedSurfaces(c: androidx.compose.material3.ColorScheme): androidx.compose.material3.ColorScheme {
    fun tone(alpha: Float) = c.primary.copy(alpha = alpha).compositeOver(c.surface)
    return c.copy(
        surfaceContainerLowest = tone(0.02f),
        surfaceContainerLow = tone(0.05f),
        surfaceContainer = tone(0.08f),
        surfaceContainerHigh = tone(0.11f),
        surfaceContainerHighest = tone(0.14f),
        surfaceVariant = tone(0.14f),
    )
}
