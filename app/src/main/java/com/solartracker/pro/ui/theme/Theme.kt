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
private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB5),
    onPrimaryContainer = Color(0xFF2C1700),
    secondary = Color(0xFF00639B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE5FF),
    onSecondaryContainer = Color(0xFF001D33),
    tertiary = Color(0xFFB45309),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2),
    onTertiaryContainer = Color(0xFF331200),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8F4),
    onBackground = Color(0xFF1C1B19),
    surface = Color(0xFFFBF8F4),
    onSurface = Color(0xFF1C1B19),
    surfaceVariant = Color(0xFFEFE4D8),
    onSurfaceVariant = Color(0xFF524A40),
    surfaceTint = Color(0xFF8A5100),
    surfaceBright = Color(0xFFFBF8F4),
    surfaceDim = Color(0xFFE2DCD4),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2EC),
    surfaceContainer = Color(0xFFF3EDE5),
    surfaceContainerHigh = Color(0xFFEDE6DD),
    surfaceContainerHighest = Color(0xFFE7DFD5),
    outline = Color(0xFF857A6D),
    outlineVariant = Color(0xFFD8CCBE),
    inverseSurface = Color(0xFF32302D),
    inverseOnSurface = Color(0xFFF5F0EA),
    inversePrimary = Color(0xFFFFB95C),
)

// Dark "energy" theme: graphite-navy surfaces, warm sun-amber accent, sky-blue secondary.
private val DarkColors = darkColorScheme(
    primary = SunAmber,
    onPrimary = Color(0xFF3F2500),
    primaryContainer = Color(0xFF5C3A00),
    onPrimaryContainer = Color(0xFFFFDDB5),
    secondary = Color(0xFF8FC8FF),
    onSecondary = Color(0xFF00325A),
    secondaryContainer = Color(0xFF0E4A7A),
    onSecondaryContainer = Color(0xFFD1E4FF),
    tertiary = Color(0xFFFFB77C),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF6A3B00),
    onTertiaryContainer = Color(0xFFFFDCC2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6E2DC),
    surface = Color(0xFF0E1116),
    onSurface = Color(0xFFE6E2DC),
    surfaceVariant = Color(0xFF2A2F37),
    onSurfaceVariant = Color(0xFFC3C7CF),
    surfaceTint = SunAmber,
    surfaceBright = Color(0xFF343942),
    surfaceDim = Color(0xFF0E1116),
    surfaceContainerLowest = Color(0xFF090B0F),
    surfaceContainerLow = Color(0xFF14181E),
    surfaceContainer = Color(0xFF181C23),
    surfaceContainerHigh = Color(0xFF20252D),
    surfaceContainerHighest = Color(0xFF2A3038),
    outline = Color(0xFF8D929B),
    outlineVariant = Color(0xFF3A4049),
    inverseSurface = Color(0xFFE6E2DC),
    inverseOnSurface = Color(0xFF2C3036),
    inversePrimary = Color(0xFF8A5100),
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
