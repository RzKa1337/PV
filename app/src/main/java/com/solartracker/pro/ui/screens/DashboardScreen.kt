package com.solartracker.pro.ui.screens

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solartracker.pro.core.solar.DayType
import com.solartracker.pro.data.LocationSource
import com.solartracker.pro.ui.BatteryNow
import com.solartracker.pro.ui.DashboardState
import com.solartracker.pro.ui.WeatherNow
import com.solartracker.pro.ui.describeSources
import com.solartracker.pro.core.weather.OpenMeteo
import com.solartracker.pro.core.weather.UvLevel
import com.solartracker.pro.core.weather.WeatherEffects
import com.solartracker.pro.core.weather.WeatherSource
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.IconButton
import com.solartracker.pro.ui.theme.ChartColors
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.uiAdvice
import com.solartracker.pro.ui.uiLabel
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.PowerChart
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.components.StatTile

@Composable
fun DashboardScreen(
    state: DashboardState?,
    onRefreshWeather: () -> Unit,
    modifier: Modifier = Modifier,
    energyOverview: @Composable () -> Unit = {},
) {
    if (state == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val s = state.settings
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LocationHeader(state)
        energyOverview()
        state.weather?.let { WeatherCard(it, onRefreshWeather) }
        SunCard(state)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "PV",
                value = "${Format.decimal(s.system.peakPowerKw)} kWp",
                footnote = stringResource(R.string.dash_pv_footnote, Format.degrees(s.system.tiltDeg), Format.degrees(s.system.azimuthDeg), Format.compass(s.system.azimuthDeg)),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.dash_current_power),
                value = Format.kw(state.currentPowerKw),
                estimate = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = stringResource(R.string.dash_today),
                value = Format.kwh(state.energySoFarKwh),
                footnote = stringResource(R.string.dash_since_midnight),
                estimate = true,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.dash_day_forecast),
                value = Format.kwh(state.energyTodayKwh),
                footnote = stringResource(R.string.dash_whole_day),
                estimate = true,
                modifier = Modifier.weight(1f),
            )
        }

        state.battery?.let { BatteryCard(it) }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.dash_power_chart),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                EstimateBadge()
            }
            PowerChart(points = state.profile, now = state.now, peakPowerKw = s.system.peakPowerKw)
        }

        Text(
            stringResource(
                R.string.dash_disclaimer,
                state.weather?.state?.let(::describeSources) ?: stringResource(R.string.clear_sky_weather_off),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationHeader(state: DashboardState) {
    val s = state.settings
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(6.dp))
        Column {
            val source = if (s.locationSource == LocationSource.GPS) "GPS" else stringResource(R.string.settings_location_manual)
            Text(
                text = s.locationName.ifBlank { stringResource(R.string.location) } + " · $source",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = Format.coordinates(s.location.latitude, s.location.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SunCard(state: DashboardState) {
    SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            stringResource(R.string.dash_sun),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Format.degrees(state.sun.elevationDeg),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    stringResource(R.string.dash_elevation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    stringResource(R.string.dash_azimuth, Format.degrees(state.sun.azimuthDeg), Format.compass(state.sun.azimuthDeg)),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (!state.sun.isAboveHorizon) {
            Text(
                stringResource(R.string.sun_below_horizon),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        val times = state.sunTimes
        when (times.dayType) {
            DayType.NORMAL -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SunEvent(stringResource(R.string.sunrise), Format.time(times.sunrise, state.zone))
                SunEvent(stringResource(R.string.solar_noon), Format.time(times.solarNoon, state.zone))
                SunEvent(stringResource(R.string.sunset), Format.time(times.sunset, state.zone))
            }
            DayType.POLAR_DAY -> SunEvent(stringResource(R.string.polar_day), stringResource(R.string.polar_day_hint))
            DayType.POLAR_NIGHT -> SunEvent(stringResource(R.string.polar_night), stringResource(R.string.polar_night_hint))
        }
        Text(
            stringResource(R.string.day_length, Format.duration(times.dayLength)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SunEvent(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun BatteryCard(b: BatteryNow) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.dash_battery),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            EstimateBadge()
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("SOC ${Format.percent(b.socPercent)}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(12.dp))
            Text(
                "${Format.decimal(b.storedKwh, 1)} / ${Format.decimal(b.usableKwh, 1)} kWh",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        LinearProgressIndicator(
            progress = { (b.socPercent / 100.0).toFloat().coerceIn(0f, 1f) },
            color = ChartColors.soc,
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(R.string.charging_value, Format.kw(b.chargeKw)),
                color = if (b.chargeKw > 0.0) ChartColors.charge else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.discharging_value, Format.kw(b.dischargeKw)),
                color = if (b.dischargeKw > 0.0) ChartColors.discharge else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            stringResource(R.string.dash_battery_sim),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WeatherCard(w: WeatherNow, onRefresh: () -> Unit) {
    val st = w.state
    val zone = java.time.ZoneId.systemDefault()
    val forecastHour = w.hour?.takeIf { w.source == WeatherSource.FORECAST }
    SectionCard {
        // Header: icon, temperature and condition; refresh aligned to the header only.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val icon = when {
                w.source != WeatherSource.FORECAST -> "📊"
                (w.cloudCoverPercent ?: 0.0) >= 70 -> "☁️"
                (w.cloudCoverPercent ?: 0.0) >= 30 -> "⛅"
                else -> "☀️"
            }
            Text(icon, fontSize = 34.sp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.dash_weather, "").trim(), style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.Bottom) {
                    if (w.source == WeatherSource.FORECAST && w.temperatureC != null) {
                        Text("${Format.decimal(w.temperatureC, 0)}°C", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        when (w.source) {
                            WeatherSource.FORECAST -> w.cloudCoverPercent?.let { stringResource(R.string.dash_cloud_cover, Format.percent(it)) }
                                ?: stringResource(R.string.forecast_lower)
                            WeatherSource.CLIMATE -> stringResource(R.string.dash_climate_no_forecast)
                            WeatherSource.CLEAR_SKY -> if (st.loading) stringResource(R.string.dash_loading_data) else stringResource(R.string.dash_no_data_clear)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            if (st.loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.settings_weather_refresh)) }
            }
        }

        // Compact info pills.
        val uvNow = w.uv?.now
        val uvMax = w.uv?.todayMax
        val pills = listOfNotNull(
            forecastHour?.windSpeedMs?.let { Triple(stringResource(R.string.w_wind), "${Format.decimal(it, 1)} m/s", null) },
            forecastHour?.relativeHumidityPercent?.let { Triple(stringResource(R.string.w_humidity), Format.percent(it), null) },
            forecastHour?.precipitationMm?.takeIf { it > 0 }?.let { Triple("💧", stringResource(R.string.dash_precip, Format.decimal(it, 1)), null) },
            forecastHour?.snowDepthM?.takeIf { it > 0 }?.let { Triple("❄", stringResource(R.string.dash_snow, Format.decimal(it * 100, 0)), null) },
            forecastHour?.visibilityM?.takeIf { it < 5000 }?.let { Triple("👁", stringResource(R.string.dash_visibility, Format.decimal(it / 1000, 1)), null) },
            uvNow?.let { Triple(stringResource(R.string.w_uv), "${Format.decimal(it, 1)} · ${UvLevel.of(it).uiLabel}", uvColor(UvLevel.of(it))) },
        )
        if (pills.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                pills.forEach { (label, value, accent) -> InfoPill(label, value, accent) }
            }
        }
        uvMax?.let { m ->
            val level = UvLevel.of(m)
            val at = w.uv?.todayMaxAt?.let { stringResource(R.string.dash_uv_max_at, Format.time(it.minusSeconds(1800), zone)) } ?: ""
            Text("☀ " + stringResource(R.string.w_uv_max, Format.decimal(m, 1), level.uiLabel) + at,
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            (w.uv?.levelMax ?: w.uv?.levelNow)?.takeIf { it != UvLevel.LOW }?.let {
                Text(it.uiAdvice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Sunshine actually reaching the panels (forecast irradiance vs clear sky).
        if (forecastHour != null) {
            w.clearSkyIndex?.let { csi ->
                val v = csi.coerceIn(0.0, 1.0)
                SubHeader(stringResource(R.string.w_sunshine))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MeterBar(v.toFloat(), ChartColors.pv, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.w_sunshine_value, Format.percent(v * 100)), style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold)
                }
            }
            if (forecastHour.cloudLowPercent != null || forecastHour.cloudMidPercent != null || forecastHour.cloudHighPercent != null) {
                Text(
                    stringResource(R.string.w_clouds) + ": " + listOf(
                        stringResource(R.string.w_cloud_low) to forecastHour.cloudLowPercent,
                        stringResource(R.string.w_cloud_mid) to forecastHour.cloudMidPercent,
                        stringResource(R.string.w_cloud_high) to forecastHour.cloudHighPercent,
                    ).joinToString(" · ") { (label, v) -> "$label ${v?.let { Format.percent(it) } ?: "—"}" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            WeatherEffects.cloudExplanation(forecastHour, w.clearSkyIndex)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // Production today / tomorrow versus a cloudless sky.
        val impacts = listOfNotNull(w.cloudToday?.let { stringResource(R.string.today) to it }, w.cloudTomorrow?.let { stringResource(R.string.tomorrow) to it })
            .filter { (_, c) -> c.reductionPercent != null && c.source != WeatherSource.CLEAR_SKY }
        if (impacts.isNotEmpty()) {
            SubHeader(stringResource(R.string.w_production))
            impacts.forEach { (label, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(64.dp))
                    MeterBar((c.weatherKwh / c.clearSkyKwh).coerceIn(0.0, 1.0).toFloat(), ChartColors.pv, Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.w_production_row, Format.decimal(c.weatherKwh, 1), Format.decimal(c.clearSkyKwh, 1)) +
                            (if (c.source == WeatherSource.CLIMATE) " " + stringResource(R.string.dash_monthly_average) else ""),
                        style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        if (w.snowOnPanels) Text(stringResource(R.string.dash_snow_on_panels),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
        st.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

        // Footer: forecast time and attribution in one muted line.
        Text(
            listOfNotNull(st.updatedAt?.let { stringResource(R.string.dash_forecast_from, Format.time(it, zone)) }, OpenMeteo.ATTRIBUTION).joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun MeterBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
    }
}

/** Small rounded chip: muted label + value; [accent] tints the background (the text always names the level). */
@Composable
private fun InfoPill(label: String, value: String, accent: Color? = null) {
    val bg = accent?.copy(alpha = 0.22f) ?: MaterialTheme.colorScheme.surfaceVariant
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(4.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** WHO UV colour scale (always shown together with the level name). */
private fun uvColor(level: UvLevel): Color = when (level) {
    UvLevel.LOW -> Color(0xFF4CAF50)
    UvLevel.MODERATE -> Color(0xFFFFC107)
    UvLevel.HIGH -> Color(0xFFFF7043)
    UvLevel.VERY_HIGH -> Color(0xFFE53935)
    UvLevel.EXTREME -> Color(0xFF8E24AA)
}
