package com.solartracker.pro.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.solartracker.pro.ui.components.LocalReducedMotion
import com.solartracker.pro.ui.components.SceneryWindow
import com.solartracker.pro.ui.components.gentleEnter
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.solartracker.pro.ui.components.SectionHeader
import com.solartracker.pro.ui.components.WeatherGlyph
import com.solartracker.pro.ui.components.WeatherIcon
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
        // Most important first: what the panels produce now and today, then the conditions behind it.
        ProductionCard(state)
        state.weather?.let { WeatherCard(it, onRefreshWeather) }
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
        SunCard(state)

        state.battery?.let { BatteryCard(it) }

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

/**
 * Hero: current production (model estimate, marked as such) on the scenery photo – value in W, a gauge of the share
 * of the installed peak power with a glow that grows with the power (none at night), today so far, the day forecast
 * and the next hours.
 */
@Composable
private fun ProductionCard(state: DashboardState) {
    val s = state.settings
    val peak = s.system.peakPowerKw
    val share = if (peak > 0) (state.currentPowerKw / peak).coerceIn(0.0, 1.0) else 0.0
    val reduced = LocalReducedMotion.current
    val shownKw by animateFloatAsState(state.currentPowerKw.toFloat(), if (reduced) snap() else tween(700), label = "power")
    val shownShare by animateFloatAsState(share.toFloat(), if (reduced) snap() else tween(900), label = "share")
    val onImage = Color.White
    val dim = Color.White.copy(alpha = 0.82f)
    SceneryWindow(Modifier.fillMaxWidth().gentleEnter().testTag("dash_production"), veil = 0.5f) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionHeaderOnImage(stringResource(R.string.dash_current_power), Modifier.weight(1f))
                EstimateBadge(onImage = true)
            }
            Box(Modifier.fillMaxWidth().height(196.dp), contentAlignment = Alignment.Center) {
                PowerGauge(shownShare, Modifier.size(196.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        Format.watts(shownKw.toDouble()),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = onImage,
                        modifier = Modifier.semantics { contentDescription = Format.watts(state.currentPowerKw) },
                    )
                    Text(
                        stringResource(R.string.dash_share_of_peak, Format.percent(share * 100), Format.decimal(peak)),
                        style = MaterialTheme.typography.bodySmall,
                        color = dim,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 150.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroMetric(stringResource(R.string.dash_today), Format.kwh(state.energySoFarKwh), stringResource(R.string.dash_since_midnight), Modifier.weight(1f))
                HeroMetric(stringResource(R.string.dash_day_forecast), Format.kwh(state.energyTodayKwh), stringResource(R.string.dash_whole_day), Modifier.weight(1f))
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.25f))
            Text(stringResource(R.string.dash_next_hours), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = dim)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                nextHours(state).forEach { (hours, kw) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(stringResource(R.string.dash_in_hours, hours), style = MaterialTheme.typography.labelSmall, color = dim)
                        Text(kw?.let { Format.watts(it) } ?: "—", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = onImage)
                    }
                }
            }
            Text(
                stringResource(R.string.dash_pv_footnote, Format.degrees(s.system.tiltDeg), Format.degrees(s.system.azimuthDeg), Format.compass(s.system.azimuthDeg)),
                style = MaterialTheme.typography.bodySmall,
                color = dim,
            )
        }
    }
}

/** Model power 1, 2 and 3 hours from now (from today's profile; "—" past the end of the day). */
private fun nextHours(state: DashboardState): List<Pair<Int, Double?>> = (1..3).map { h ->
    val at = state.now.plusSeconds(h * 3600L)
    val point = state.profile.minByOrNull { kotlin.math.abs(java.time.Duration.between(it.time, at).seconds) }
    h to point?.takeIf { kotlin.math.abs(java.time.Duration.between(it.time, at).toMinutes()) <= 30 }?.powerKw
}

/** Arc gauge (240°) of the share of peak power with a warm glow proportional to it; nothing lit at zero power. */
@Composable
private fun PowerGauge(share: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 12.dp.toPx()
        val r = size.minDimension / 2 - stroke
        val center = Offset(size.width / 2, size.height / 2)
        if (share > 0.001f) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFC857).copy(alpha = 0.55f * share), Color.Transparent), center, r * 1.1f), r * 1.1f, center)
        }
        val topLeft = Offset(center.x - r, center.y - r)
        val arcSize = Size(r * 2, r * 2)
        drawArc(Color.White.copy(alpha = 0.22f), 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (share > 0.001f) {
            drawArc(
                Brush.sweepGradient(listOf(Color(0xFFFFE08A), Color(0xFFFFB300), Color(0xFFFF8A00), Color(0xFFFFE08A)), center),
                150f, 240f * share, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
    }
}

@Composable
private fun SectionHeaderOnImage(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Outlined.WbSunny, contentDescription = null, tint = Color(0xFFFFC857), modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

@Composable
private fun HeroMetric(label: String, value: String, footnote: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Color.White.copy(alpha = 0.82f))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White)
        Text(footnote, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.82f))
    }
}

@Composable
private fun MetricColumn(label: String, value: String, footnote: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(footnote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                text = listOf(s.locationDetail, Format.coordinates(s.location.latitude, s.location.longitude)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SunCard(state: DashboardState) {
    SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        SectionHeader(stringResource(R.string.dash_sun), Icons.Outlined.WbSunny, MaterialTheme.colorScheme.onPrimaryContainer)
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
            SectionHeader(stringResource(R.string.dash_battery), Icons.Outlined.BatteryChargingFull, ChartColors.charge, Modifier.weight(1f))
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
            // Headline from the sunlight that gets through (forecast irradiance), not from total cloud cover:
            // a sky of thin cirrus counts as 100 % cloud cover while being practically sunny.
            val blocked = com.solartracker.pro.core.analytics.SkyClassifier.effectiveCloudPercent(w.clearSkyIndex, w.cloudCoverPercent)
            val glyph = if (w.source != WeatherSource.FORECAST) WeatherGlyph.MODEL
                else WeatherGlyph.of(null, forecastHour?.precipitationMm, null, blocked, night = false)
            WeatherIcon(glyph, size = 40.dp)
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
                            WeatherSource.FORECAST -> w.clearSkyIndex?.let { csi ->
                                val sky = com.solartracker.pro.core.analytics.SkyClassifier.classify(w.cloudCoverPercent, forecastHour?.precipitationMm,
                                    forecastHour?.snowDepthM, w.temperatureC, csi)
                                if (sky == com.solartracker.pro.core.analytics.SkyCondition.CLEAR && (w.cloudCoverPercent ?: 0.0) >= 50) stringResource(R.string.dash_sunny_thin_clouds)
                                else sky.uiLabel
                            } ?: w.cloudCoverPercent?.let { stringResource(R.string.dash_cloud_cover, Format.percent(it)) }
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
            forecastHour?.precipitationMm?.takeIf { it > 0 }?.let { Triple(stringResource(R.string.w_precip_label), "${Format.decimal(it, 1)} mm", null) },
            forecastHour?.snowDepthM?.takeIf { it > 0 }?.let { Triple(stringResource(R.string.w_snow_label), "${Format.decimal(it * 100, 0)} cm", null) },
            forecastHour?.visibilityM?.takeIf { it < 5000 }?.let { Triple(stringResource(R.string.w_visibility_label), "${Format.decimal(it / 1000, 1)} km", null) },
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
            Text(stringResource(R.string.w_uv_max, Format.decimal(m, 1), level.uiLabel) + at,
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
                    stringResource(R.string.w_clouds) + ": " + (w.cloudCoverPercent?.let { stringResource(R.string.w_cloud_total, Format.percent(it)) + " · " } ?: "") + listOf(
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
