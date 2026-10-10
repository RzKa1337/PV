package com.solartracker.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.solartracker.pro.data.ThemeMode

private val SunAmber = Color(0xFFFFB300)
private val SunOrange = Color(0xFFF57C00)
private val SkyBlue = Color(0xFF1E88E5)

// Every container tone is set explicitly: unset ones fall back to Material's purple baseline and tint cards unevenly.
// Premium palette: deep navy ink, petrol/turquoise accents and a warm sun-gold highlight (nav indicator, badges).
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B4F6C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3A6),
    onPrimaryContainer = Color(0xFF2A1C00),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFB8EFE6),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Color(0xFF8C5A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDA8),
    onTertiaryContainer = Color(0xFF2C1800),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF3F6FA),
    onBackground = Color(0xFF0F1A2A),
    surface = Color(0xFFF3F6FA),
    onSurface = Color(0xFF0F1A2A),
    surfaceVariant = Color(0xFFDDE4EC),
    onSurfaceVariant = Color(0xFF3F4A57),
    surfaceTint = Color(0xFF0B4F6C),
    surfaceBright = Color(0xFFF3F6FA),
    surfaceDim = Color(0xFFD5DCE4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFEEF2F7),
    surfaceContainer = Color(0xFFE8EDF3),
    surfaceContainerHigh = Color(0xFFE1E7EE),
    surfaceContainerHighest = Color(0xFFDAE1E9),
    outline = Color(0xFF73808E),
    outlineVariant = Color(0xFFC3CCD6),
    inverseSurface = Color(0xFF223042),
    inverseOnSurface = Color(0xFFEDF1F6),
    inversePrimary = Color(0xFF8FCDE6),
)

// Dark "night sky" theme: deep navy surfaces, sun-gold primary, turquoise secondary.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFF2C14E),
    onPrimary = Color(0xFF3A2A00),
    primaryContainer = Color(0xFF5A4300),
    onPrimaryContainer = Color(0xFFFFE3A6),
    secondary = Color(0xFF4FD8C4),
    onSecondary = Color(0xFF00382F),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFB8EFE6),
    tertiary = Color(0xFFFFB86B),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3B00),
    onTertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0A1220),
    onBackground = Color(0xFFE4E9F0),
    surface = Color(0xFF0A1220),
    onSurface = Color(0xFFE4E9F0),
    surfaceVariant = Color(0xFF26313F),
    onSurfaceVariant = Color(0xFFBFC8D3),
    surfaceTint = Color(0xFFF2C14E),
    surfaceBright = Color(0xFF2A3548),
    surfaceDim = Color(0xFF0A1220),
    surfaceContainerLowest = Color(0xFF060B14),
    surfaceContainerLow = Color(0xFF0F1828),
    surfaceContainer = Color(0xFF131D2E),
    surfaceContainerHigh = Color(0xFF1A2537),
    surfaceContainerHighest = Color(0xFF223044),
    outline = Color(0xFF8A95A3),
    outlineVariant = Color(0xFF334052),
    inverseSurface = Color(0xFFE4E9F0),
    inverseOnSurface = Color(0xFF1E2836),
    inversePrimary = Color(0xFF7A5C00),
)

/** Type scale: bold, compact headlines and large readable values; body text unchanged for long descriptions. */
private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
        labelMedium = t.labelMedium.copy(letterSpacing = 0.4.sp),
    )
}

/** Colors used for charts, independent of light/dark scheme (mid tones readable on both). */
object ChartColors {
    val pv = Color(0xFFFFB300)
    val consumption = Color(0xFF42A5F5)
    val charge = Color(0xFF66BB6A)
    val discharge = Color(0xFFAB47BC)
    val grid = Color(0xFFEF5350)
    val surplus = Color(0xFF26A69A)
    val soc = Color(0xFF43A047)

    val tiltSeries = listOf(
        SkyBlue,
        SunOrange,
        Color(0xFF43A047),
        Color(0xFF8E24AA),
        Color(0xFFE53935),
    )
}

@Composable
fun SolarTrackerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content,
    )
}
