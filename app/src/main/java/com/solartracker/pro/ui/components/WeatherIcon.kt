package com.solartracker.pro.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AcUnit
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Dehaze
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.NightsStay
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.WbCloudy
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** One consistent set of weather symbols (Material icons, no emoji) with fixed, recognisable tints. */
enum class WeatherGlyph(val icon: ImageVector, val tint: Color) {
    SUN(Icons.Outlined.WbSunny, Color(0xFFFFB300)),
    PARTLY(Icons.Outlined.WbCloudy, Color(0xFFF2A93B)),
    CLOUD(Icons.Outlined.Cloud, Color(0xFF8C9BAB)),
    RAIN(Icons.Outlined.Opacity, Color(0xFF3D8BEA)),
    SNOW(Icons.Outlined.AcUnit, Color(0xFF7CC4F2)),
    STORM(Icons.Outlined.FlashOn, Color(0xFF9C6ADE)),
    FOG(Icons.Outlined.Dehaze, Color(0xFF9AA4AE)),
    NIGHT(Icons.Outlined.NightsStay, Color(0xFF8E9BE0)),
    MODEL(Icons.Outlined.BarChart, Color(0xFF8C9BAB)),
    UNKNOWN(Icons.Outlined.HelpOutline, Color(0xFF8C9BAB)),
    ;

    companion object {
        /**
         * Symbol from the WMO weather code, precipitation and the share of sunlight actually blocked
         * (not total cloud cover – thin cirrus is "100 %" but sunny).
         */
        fun of(weatherCode: Int?, precipitationMm: Double?, snowfallCm: Double?, blockedPercent: Double?, night: Boolean): WeatherGlyph {
            val code = weatherCode ?: -1
            return when {
                code >= 95 -> STORM
                (snowfallCm ?: 0.0) > 0.05 || code in 71..77 || code in 85..86 -> SNOW
                (precipitationMm ?: 0.0) >= 0.1 || code in 51..67 || code in 80..82 -> RAIN
                code in 45..48 -> FOG
                night -> if ((blockedPercent ?: 0.0) >= 70) CLOUD else NIGHT
                (blockedPercent ?: 0.0) >= 70 -> CLOUD
                (blockedPercent ?: 0.0) >= 30 -> PARTLY
                else -> SUN
            }
        }
    }
}

@Composable
fun WeatherIcon(glyph: WeatherGlyph, size: Dp = 24.dp, modifier: Modifier = Modifier, contentDescription: String? = null) {
    Icon(glyph.icon, contentDescription = contentDescription, tint = glyph.tint, modifier = modifier.size(size))
}
