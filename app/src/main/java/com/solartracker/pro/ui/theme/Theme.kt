package com.solartracker.pro.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.solartracker.pro.data.ThemeMode

private val SunAmber = Color(0xFFFFB300)
private val SunOrange = Color(0xFFF57C00)
private val SkyBlue = Color(0xFF1E88E5)

private val LightColors = lightColorScheme(
    primary = Color(0xFF8A5100),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB5),
    onPrimaryContainer = Color(0xFF2C1700),
    secondary = Color(0xFF00639B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE5FF),
    onSecondaryContainer = Color(0xFF001D33),
    tertiary = SunOrange,
    background = Color(0xFFFFFBF5),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF1E0D0),
    onSurfaceVariant = Color(0xFF50453A),
    surfaceContainer = Color(0xFFF8EEE4),
    surfaceContainerHigh = Color(0xFFF3E7DC),
    outline = Color(0xFF827568),
)

private val DarkColors = darkColorScheme(
    primary = SunAmber,
    onPrimary = Color(0xFF4A2800),
    primaryContainer = Color(0xFF693C00),
    onPrimaryContainer = Color(0xFFFFDDB5),
    secondary = Color(0xFF96CCFF),
    onSecondary = Color(0xFF003353),
    secondaryContainer = Color(0xFF004A76),
    onSecondaryContainer = Color(0xFFCEE5FF),
    tertiary = Color(0xFFFFB77C),
    background = Color(0xFF121212),
    onBackground = Color(0xFFEBE1D9),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFEBE1D9),
    surfaceVariant = Color(0xFF50453A),
    onSurfaceVariant = Color(0xFFD5C4B5),
    surfaceContainer = Color(0xFF1E1B18),
    surfaceContainerHigh = Color(0xFF29251F),
    outline = Color(0xFF9E8E81),
)

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
        content = content,
    )
}
