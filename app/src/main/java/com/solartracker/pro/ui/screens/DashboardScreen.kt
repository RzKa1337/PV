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

@Composable
private fun WeatherCard(w: WeatherNow, onRefresh: () -> Unit) {
    val st = w.state
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val icon = when {
                    w.source != WeatherSource.FORECAST -> "📊"
                    (w.cloudCoverPercent ?: 0.0) >= 70 -> "☁️"
                    (w.cloudCoverPercent ?: 0.0) >= 30 -> "⛅"
                    else -> "☀️"
                }
                Text(stringResource(R.string.dash_weather, icon), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    when (w.source) {
                        WeatherSource.FORECAST -> listOfNotNull(
                            w.temperatureC?.let { "${Format.decimal(it, 0)}°C" },
                            w.cloudCoverPercent?.let { stringResource(R.string.dash_cloud_cover, Format.percent(it)) },
                        ).joinToString(" · ").ifEmpty { stringResource(R.string.forecast_lower) }
                        WeatherSource.CLIMATE -> stringResource(R.string.dash_climate_no_forecast)
                        WeatherSource.CLEAR_SKY -> if (st.loading) stringResource(R.string.dash_loading_data) else stringResource(R.string.dash_no_data_clear)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                w.hour?.takeIf { w.source == WeatherSource.FORECAST }?.let { h ->
                    val details = listOfNotNull(
                        h.windSpeedMs?.let { stringResource(R.string.dash_wind, Format.decimal(it, 1)) },
                        h.relativeHumidityPercent?.let { stringResource(R.string.dash_humidity, Format.percent(it)) },
                        h.precipitationMm?.takeIf { it > 0 }?.let { stringResource(R.string.dash_precip, Format.decimal(it, 1)) },
                        h.snowDepthM?.takeIf { it > 0 }?.let { stringResource(R.string.dash_snow, Format.decimal(it * 100, 0)) },
                        h.visibilityM?.takeIf { it < 5000 }?.let { stringResource(R.string.dash_visibility, Format.decimal(it / 1000, 1)) },
                    )
                    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                w.uv?.let { uv ->
                    val now = uv.now?.let { stringResource(R.string.dash_uv_now, Format.decimal(it, 1), UvLevel.of(it).uiLabel) }
                    val max = uv.todayMax?.let { m ->
                        stringResource(R.string.dash_uv_max, Format.decimal(m, 1), UvLevel.of(m).uiLabel) +
                            (uv.todayMaxAt?.let { stringResource(R.string.dash_uv_max_at, Format.time(it.minusSeconds(1800), java.time.ZoneId.systemDefault())) } ?: "")
                    }
                    val line = listOfNotNull(now, max).joinToString(" · ")
                    if (line.isNotEmpty()) {
                        Text("☀ $line", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                            color = if ((uv.todayMax ?: 0.0) >= 6) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        (uv.levelMax ?: uv.levelNow)?.takeIf { it != UvLevel.LOW }?.let { Text(it.uiAdvice, style = MaterialTheme.typography.bodySmall) }
                    }
                }
                w.hour?.takeIf { w.source == WeatherSource.FORECAST }?.let { h ->
                    if (h.cloudLowPercent != null || h.cloudMidPercent != null || h.cloudHighPercent != null) {
                        Text(stringResource(R.string.dash_cloud_layers, h.cloudLowPercent?.let { Format.percent(it) } ?: "—", h.cloudMidPercent?.let { Format.percent(it) } ?: "—",
                            h.cloudHighPercent?.let { Format.percent(it) } ?: "—"), style = MaterialTheme.typography.bodySmall)
                    }
                    w.clearSkyIndex?.let { csi ->
                        Text(stringResource(R.string.dash_clear_sky_index, Format.percent((csi * 100).coerceAtMost(100.0))),
                            style = MaterialTheme.typography.bodySmall)
                    }
                    WeatherEffects.cloudExplanation(h, w.clearSkyIndex)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                listOfNotNull(w.cloudToday?.let { stringResource(R.string.today) to it }, w.cloudTomorrow?.let { stringResource(R.string.tomorrow) to it }).forEach { (label, c) ->
                    val r = c.reductionPercent
                    if (r != null && c.source != WeatherSource.CLEAR_SKY) {
                        Text(
                            stringResource(
                                R.string.dash_cloud_impact,
                                label, Format.decimal(c.weatherKwh, 1), Format.decimal(c.clearSkyKwh, 1), Format.percent(r),
                                if (c.source == WeatherSource.CLIMATE) stringResource(R.string.dash_monthly_average) else "",
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (w.snowOnPanels) Text(stringResource(R.string.dash_snow_on_panels),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (st.loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.settings_weather_refresh)) }
            }
        }
        st.updatedAt?.let {
            Text(
                stringResource(R.string.dash_forecast_from, Format.time(it, java.time.ZoneId.systemDefault())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        st.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            OpenMeteo.ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
