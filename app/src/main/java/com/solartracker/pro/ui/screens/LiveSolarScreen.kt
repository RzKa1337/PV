package com.solartracker.pro.ui.screens

import com.solartracker.pro.ui.LiveOutlookUi
import com.solartracker.pro.ui.components.StatusLabel
import com.solartracker.pro.ui.components.StatusLevel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.solartracker.pro.i18n.tr
import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solartracker.pro.core.solar.DayType
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.LiveEnergyUi
import com.solartracker.pro.ui.LivePvUi
import com.solartracker.pro.ui.LiveSunUi
import com.solartracker.pro.ui.LiveUiState
import com.solartracker.pro.ui.SunPathUi
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.theme.ChartColors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Test tags used by the instrumented test that watches the live screen. */
object LiveTags {
    const val CLOCK = "live_clock"
    const val AZIMUTH = "live_azimuth"
    const val ELEVATION = "live_elevation"
    const val POWER = "live_power"
    const val STATUS = "live_status"
    const val SOC = "live_soc"
}

private fun deg2(v: Double) = Format.decimal(v, 2) + "°"

@Composable
fun LiveSolarScreen(
    state: LiveUiState?,
    sunPath: SunPathUi?,
    active: Boolean,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LiveStatusRow(state?.clockText, active, paused, onPausedChange)
        if (state == null) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        SunHeader(state.clockText, state.dateText, state.sun)
        PowerCard(state.pv, state.sun.isDay, state.weatherAgeText, state.weatherStale)
        state.outlook?.let { OutlookCard(it) }
        if (sunPath != null) {
            CompassCard(sunPath, state.sun, state.pv.panelAzimuthDeg)
            SunPathCard(sunPath, state.sun)
        }
        LiveCard(state)
        EnergyNowCard(state.energy)
        Text(
            state.locationText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(R.string.live_footer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveStatusRow(clock: String?, active: Boolean, paused: Boolean, onPausedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.WbSunny, contentDescription = null, tint = ChartColors.pv, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(8.dp))
                Text("LIVE SOLAR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            val running = active && !paused
            val statusColor = if (running) ChartColors.charge else MaterialTheme.colorScheme.error
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (running) "LIVE" else "LIVE PAUSED",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier.testTag(LiveTags.STATUS),
                )
            }
            if (clock != null) {
                Text(stringResource(R.string.live_updated, clock.toString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(onClick = { onPausedChange(!paused) }) {
            Text(if (paused) "▶ LIVE" else stringResource(R.string.live_pause))
        }
    }
}

@Composable
private fun SunHeader(clock: String, date: String, sun: LiveSunUi) {
    SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        val onColor = MaterialTheme.colorScheme.onPrimaryContainer
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                clock,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = onColor,
                modifier = Modifier
                    .weight(1f)
                    .testTag(LiveTags.CLOCK),
            )
            Text(
                if (sun.isDay) stringResource(R.string.live_day) else stringResource(R.string.live_night),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onColor,
            )
        }
        Text(date, style = MaterialTheme.typography.bodySmall, color = onColor)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                "AZ ${deg2(sun.azimuthDeg)}",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = onColor,
                modifier = Modifier.testTag(LiveTags.AZIMUTH),
            )
            Text(
                "EL ${deg2(sun.elevationDeg)}",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                color = onColor,
                modifier = Modifier.testTag(LiveTags.ELEVATION),
            )
        }
        when (sun.dayType) {
            DayType.NORMAL -> Text(
                listOfNotNull(
                    stringResource(R.string.live_sunrise, sun.sunriseText.toString()),
                    stringResource(R.string.live_sunset, sun.sunsetText.toString()),
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = onColor,
            )
            DayType.POLAR_DAY -> Text(stringResource(R.string.live_polar_day), color = onColor)
            DayType.POLAR_NIGHT -> Text(stringResource(R.string.live_polar_night), color = onColor)
        }
        val countdown = if (sun.isDay) sun.timeToSunsetText?.let { stringResource(R.string.live_to_sunset, it) } else sun.timeToSunriseText?.let { stringResource(R.string.live_to_sunrise, it) }
        if (countdown != null) {
            Text(countdown, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = onColor)
        }
    }
}

@Composable
private fun PowerCard(pv: LivePvUi, isDay: Boolean, weatherAge: String?, weatherStale: Boolean) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pv.measuredPowerKw != null) stringResource(R.string.live_pv_power) else stringResource(R.string.live_pv_estimated),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            EstimateBadge(text = "MODEL")
        }
        if (pv.measuredPowerKw != null) {
            Text(stringResource(R.string.live_real_power, Format.kw(pv.measuredPowerKw)), fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.live_model_power, Format.kw(pv.modeledPowerKw)), style = MaterialTheme.typography.titleMedium)
        } else {
            Text(
                if (isDay) Format.kw(pv.modeledPowerKw) else "0 W",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(LiveTags.POWER),
            )
        }
        Text(
            stringResource(R.string.live_poa, Format.decimal(pv.poa, 0)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(R.string.live_irradiance, pv.sourceLabel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isDay) {
            Text(
                listOfNotNull(
                    pv.cellTemperatureC?.let { stringResource(R.string.live_cell_temp_short, Format.decimal(it, 0)) },
                    pv.ambientTemperatureC?.let { stringResource(R.string.live_air_temp_short, Format.decimal(it, 0)) },
                    stringResource(R.string.live_ghi_dni_dhi, Format.decimal(pv.ghi, 0), Format.decimal(pv.dni, 0), Format.decimal(pv.dhi, 0)),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        weatherAge?.let {
            if (weatherStale) StatusLabel(StatusLevel.WARNING, stringResource(R.string.live_weather_stale, it), style = MaterialTheme.typography.bodySmall, fontWeight = null)
            else Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (pv.measuredPowerKw == null) {
            Text(
                stringResource(R.string.live_no_inverter),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The same model one step ahead: +5…+60 min, today's maximum and the day's energy. Refreshed once a minute. */
@Composable
private fun OutlookCard(o: LiveOutlookUi) {
    SectionCard(Modifier.testTag("live_outlook")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_outlook_title), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            EstimateBadge(text = stringResource(R.string.live_forecast_badge))
        }
        Row(Modifier.fillMaxWidth()) {
            o.ahead.forEach { (label, kw) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Format.kw(kw), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        HorizontalDivider()
        LiveRow(stringResource(R.string.live_today_max), Format.kw(o.todayMaxKw) + " · " + o.todayMaxText)
        LiveRow(stringResource(R.string.live_energy_so_far), Format.kwh(o.soFarKwh))
        LiveRow(stringResource(R.string.live_energy_remaining), Format.kwh(o.remainingKwh))
        LiveRow(stringResource(R.string.live_energy_day), Format.kwh(o.dayKwh))
        Text(stringResource(R.string.live_outlook_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LiveRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LiveCard(state: LiveUiState) {
    val sun = state.sun
    val pv = state.pv
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("LIVE", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.live_every_second), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LiveRow(stringResource(R.string.live_time), state.clockText)
        LiveRow(stringResource(R.string.live_azimuth), deg2(sun.azimuthDeg))
        LiveRow(stringResource(R.string.live_elevation), deg2(sun.elevationDeg))
        LiveRow(stringResource(R.string.live_zenith), deg2(sun.zenithDeg))
        LiveRow(stringResource(R.string.live_hour_angle), deg2(sun.hourAngleDeg))
        LiveRow(stringResource(R.string.live_declination), deg2(sun.declinationDeg))
        LiveRow("Air mass", sun.airMass?.let { Format.decimal(it, 3) } ?: stringResource(R.string.live_below_horizon))
        HorizontalDivider()
        LiveRow("POA", "${Format.decimal(pv.poa, 0)} W/m²")
        LiveRow("GHI / DNI / DHI", "${Format.decimal(pv.ghi, 0)} / ${Format.decimal(pv.dni, 0)} / ${Format.decimal(pv.dhi, 0)}")
        LiveRow("PV (model)", Format.kw(pv.modeledPowerKw))
        LiveRow("Panel", "${Format.degrees(pv.tiltDeg)} / ${Format.degrees(pv.panelAzimuthDeg)}")
        LiveRow(stringResource(R.string.live_incidence), Format.decimal(pv.angleOfIncidenceDeg, 1) + "°")
        LiveRow(stringResource(R.string.live_geometry), Format.percent(pv.geometricUtilization * 100, 1))
        LiveRow(stringResource(R.string.live_air_temp), pv.ambientTemperatureC?.let { Format.decimal(it, 1) + "°C" } ?: stringResource(R.string.no_data))
        LiveRow(stringResource(R.string.live_cell_temp), pv.cellTemperatureC?.let { Format.decimal(it, 1) + "°C" } ?: "—")
        HorizontalDivider()
        LiveRow(stringResource(R.string.live_sunrise_sunset), "${sun.sunriseText} / ${sun.sunsetText}")
        LiveRow(stringResource(R.string.live_solar_noon), sun.solarNoonText)
        sun.timeToSunriseText?.let { LiveRow(stringResource(R.string.live_until_sunrise), it) }
        sun.timeToSunsetText?.let { LiveRow(stringResource(R.string.live_until_sunset), it) }
    }
}

@Composable
private fun CompassCard(path: SunPathUi, sun: LiveSunUi, panelAzimuthDeg: Double) {
    val textMeasurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurface
    val pathColor = ChartColors.pv.copy(alpha = 0.6f)
    val panelColor = ChartColors.consumption
    val compassDescription = stringResource(R.string.live_compass_desc, deg2(sun.azimuthDeg), deg2(sun.elevationDeg))
    SectionCard {
        Text(stringResource(R.string.live_compass), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics { contentDescription = compassDescription },
        ) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 * 0.82f
            fun point(az: Double, el: Double): Offset {
                val rr = r * ((90.0 - el.coerceIn(0.0, 90.0)) / 90.0).toFloat()
                val a = Math.toRadians(az)
                return Offset(c.x + rr * sin(a).toFloat(), c.y - rr * cos(a).toFloat())
            }
            // Elevation rings 0°, 30°, 60° and cardinal axes.
            for (el in listOf(0.0, 30.0, 60.0)) {
                drawCircle(outline.copy(alpha = if (el == 0.0) 0.8f else 0.3f), radius = r * ((90 - el) / 90).toFloat(), center = c, style = Stroke(1.5f))
            }
            drawLine(outline.copy(alpha = 0.3f), Offset(c.x, c.y - r), Offset(c.x, c.y + r))
            drawLine(outline.copy(alpha = 0.3f), Offset(c.x - r, c.y), Offset(c.x + r, c.y))
            val style = TextStyle(fontSize = 13.sp, color = labelColor, fontWeight = FontWeight.Bold)
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, az) ->
                val p = point(az, -8.0).let { Offset(c.x + (it.x - c.x) * 1.1f, c.y + (it.y - c.y) * 1.1f) }
                val m = textMeasurer.measure(label, style)
                drawText(m, topLeft = Offset(p.x - m.size.width / 2, p.y - m.size.height / 2))
            }
            // Today's path (above the horizon).
            val arc = Path()
            var started = false
            path.azimuths.indices.forEach { i ->
                if (path.elevations[i] > 0) {
                    val p = point(path.azimuths[i], path.elevations[i])
                    if (!started) arc.moveTo(p.x, p.y) else arc.lineTo(p.x, p.y)
                    started = true
                } else {
                    started = false
                }
            }
            drawPath(arc, pathColor, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            // Panel direction.
            drawLine(panelColor, c, point(panelAzimuthDeg, 45.0), strokeWidth = 5f)
            // The sun – on the rim (grey) when below the horizon.
            val sunPoint = point(sun.azimuthDeg, sun.elevationDeg)
            if (sun.isDay) {
                drawCircle(ChartColors.pv, radius = 16f, center = sunPoint)
                drawCircle(Color.White.copy(alpha = 0.6f), radius = 16f, center = sunPoint, style = Stroke(3f))
            } else {
                drawCircle(Color.Gray, radius = 10f, center = sunPoint)
            }
        }
        Text(
            stringResource(R.string.live_compass_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SunPathCard(path: SunPathUi, sun: LiveSunUi) {
    val textMeasurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val pathDescription = stringResource(R.string.live_path_desc, deg2(path.highestElevationDeg), path.highestText)
    SectionCard {
        Text(stringResource(R.string.live_path_title, path.dateText.toString()), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = pathDescription },
        ) {
            val left = 34.dp.toPx()
            val bottom = 18.dp.toPx()
            val top = 8.dp.toPx()
            val w = size.width - left
            val h = size.height - bottom - top
            val maxEl = (path.highestElevationDeg + 5).coerceIn(10.0, 95.0)
            // x: azimuth relative to the azimuth of the highest point, −180..180.
            fun rel(az: Double) = ((az - path.highestAzimuthDeg + 540.0) % 360.0) - 180.0
            fun x(az: Double) = left + w * ((rel(az) + 180.0) / 360.0).toFloat()
            fun y(el: Double) = top + h * (1f - (el.coerceAtLeast(0.0) / maxEl).toFloat())
            val small = TextStyle(fontSize = 10.sp, color = labelColor)
            drawLine(outline, Offset(left, y(0.0)), Offset(size.width, y(0.0)), strokeWidth = 2f)
            for (el in listOf(15.0, 30.0, 45.0, 60.0, 75.0).filter { it < maxEl }) {
                drawLine(outline.copy(alpha = 0.25f), Offset(left, y(el)), Offset(size.width, y(el)))
                val m = textMeasurer.measure("${el.toInt()}°", small)
                drawText(m, topLeft = Offset(left - m.size.width - 4, y(el) - m.size.height / 2))
            }
            listOf("N" to 0.0, "E" to 90.0, "S" to 180.0, "W" to 270.0).forEach { (label, az) ->
                val m = textMeasurer.measure(label, small)
                drawText(m, topLeft = Offset((x(az) - m.size.width / 2).coerceIn(left, size.width - m.size.width), y(0.0) + 2))
            }
            val line = Path()
            var started = false
            var previousRel = 0.0
            path.azimuths.indices.forEach { i ->
                val el = path.elevations[i]
                val az = path.azimuths[i]
                val jump = started && abs(rel(az) - previousRel) > 90.0 // wrap-around (polar day)
                if (el > -1.0 && !jump) {
                    if (!started) line.moveTo(x(az), y(el)) else line.lineTo(x(az), y(el))
                    started = true
                } else {
                    started = false
                }
                previousRel = rel(az)
            }
            drawPath(line, ChartColors.pv, style = Stroke(3f))
            path.sunriseAzimuthDeg?.let { drawCircle(ChartColors.discharge, 7f, Offset(x(it), y(0.0))) }
            path.sunsetAzimuthDeg?.let { drawCircle(ChartColors.discharge, 7f, Offset(x(it), y(0.0))) }
            drawCircle(ChartColors.grid, 7f, Offset(x(path.highestAzimuthDeg), y(path.highestElevationDeg)))
            // Current position.
            if (sun.elevationDeg > 0) {
                val p = Offset(x(sun.azimuthDeg), y(sun.elevationDeg))
                drawCircle(ChartColors.pv, 14f, p)
                drawCircle(Color.White, 14f, p, style = Stroke(3f))
            }
        }
        Text(
            stringResource(R.string.live_path_summary, path.sunriseText, Format.decimal(path.highestElevationDeg, 1), path.highestText, Format.decimal(path.highestAzimuthDeg, 1), path.sunsetText),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(R.string.live_now_position, deg2(sun.azimuthDeg), deg2(sun.elevationDeg)) + if (!sun.isDay) stringResource(R.string.live_below_horizon_suffix) else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EnergyNowCard(e: LiveEnergyUi) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.live_energy_now), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            EstimateBadge(text = "MODEL")
        }
        LiveRow("PV", Format.kw(e.pvKw))
        LiveRow(stringResource(R.string.live_load), Format.kw(e.loadKw))
        LiveRow(if (e.netKw >= 0) stringResource(R.string.ins_surplus) else stringResource(R.string.live_deficit), Format.kw(abs(e.netKw)))
        if (e.hasBattery) {
            val sign = if (e.batteryKw > 0) "+" else if (e.batteryKw < 0) "−" else ""
            LiveRow(stringResource(R.string.live_battery), "$sign${Format.kw(abs(e.batteryKw))}")
            Row(Modifier.fillMaxWidth()) {
                Text("SOC", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(
                    e.socPercent?.let { Format.percent(it, 1) } ?: "—",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag(LiveTags.SOC),
                )
            }
            if (e.storedKwh != null && e.usableKwh != null) {
                LiveRow(stringResource(R.string.live_stored), "${Format.decimal(e.storedKwh, 2)} / ${Format.decimal(e.usableKwh, 1)} kWh")
            }
        }
        if (e.gridImportKw > 0.0005) LiveRow(stringResource(R.string.live_to_loads, e.backupLabel), Format.kw(e.gridImportKw))
        if (e.exportKw > 0.0005) LiveRow(stringResource(R.string.live_export, e.backupLabel.lowercase().toString()), Format.kw(e.exportKw))
        EnergyFlowDiagram(e)
        Text(flowSummary(e), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private const val FLOW_EPS = 0.0005

fun flowSummary(e: LiveEnergyUi): String = buildList {
    if (e.directKw > FLOW_EPS) add(tr("PV → Dom", "PV → Home"))
    if (e.chargeKw > FLOW_EPS) add(tr("PV → Bateria", "PV → Battery"))
    if (e.dischargeKw > FLOW_EPS) add(tr("Bateria → Dom", "Battery → Home"))
    if (e.exportKw > FLOW_EPS) add("PV → ${e.backupLabel}")
    if (e.gridImportKw > FLOW_EPS) add(tr("${e.backupLabel} → Dom", "${e.backupLabel} → Home"))
}.ifEmpty { listOf(tr("Brak przepływu energii", "No energy flow")) }.joinToString("   ")

@Composable
private fun EnergyFlowDiagram(e: LiveEnergyUi) {
    val transition = rememberInfiniteTransition(label = "flow")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val textMeasurer = rememberTextMeasurer()
    val idle = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.colorScheme.onSurface
    val loadLabel = stringResource(R.string.live_load_value, Format.kw(e.loadKw))
    val sunIcon = rememberVectorPainter(Icons.Outlined.WbSunny)
    val homeIcon = rememberVectorPainter(Icons.Outlined.Home)
    val hubIcon = rememberVectorPainter(Icons.Outlined.FlashOn)
    val batteryIcon = rememberVectorPainter(Icons.Outlined.BatteryChargingFull)
    val gridIcon = rememberVectorPainter(Icons.Outlined.Public)
    val nodeFill = MaterialTheme.colorScheme.surfaceContainerHighest
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = tr("Przepływ energii: ", "Energy flow: ") + flowSummary(e) },
        ) {
            val hub = Offset(size.width / 2, size.height / 2)
            val pv = Offset(size.width / 2, size.height * 0.13f)
            val home = Offset(size.width / 2, size.height * 0.87f)
            val battery = Offset(size.width * 0.14f, size.height / 2)
            val grid = Offset(size.width * 0.86f, size.height / 2)
            // Edge: from → to when the power is positive.
            flowEdge(pv, hub, e.pvKw, ChartColors.pv, idle, phase)
            flowEdge(hub, home, e.loadKw, ChartColors.consumption, idle, phase)
            if (e.hasBattery) {
                if (e.dischargeKw > FLOW_EPS) flowEdge(battery, hub, e.dischargeKw, ChartColors.discharge, idle, phase)
                else flowEdge(hub, battery, e.chargeKw, ChartColors.charge, idle, phase)
            }
            if (e.gridImportKw > FLOW_EPS) flowEdge(grid, hub, e.gridImportKw, ChartColors.grid, idle, phase)
            else flowEdge(hub, grid, e.exportKw, ChartColors.surplus, idle, phase)

            val small = TextStyle(fontSize = 11.sp, color = labelColor, fontWeight = FontWeight.SemiBold)
            val iconPx = 24.dp.toPx()
            val radius = 21.dp.toPx()
            fun node(center: Offset, icon: VectorPainter, tint: Color, label: String) {
                drawCircle(nodeFill, radius, center)
                drawCircle(tint.copy(alpha = 0.6f), radius, center, style = Stroke(width = 1.5.dp.toPx()))
                translate(center.x - iconPx / 2, center.y - iconPx / 2) {
                    with(icon) { draw(Size(iconPx, iconPx), colorFilter = ColorFilter.tint(tint)) }
                }
                if (label.isNotEmpty()) {
                    val l = textMeasurer.measure(label, small)
                    drawText(l, topLeft = Offset(center.x - l.size.width / 2, center.y + radius + 2.dp.toPx()))
                }
            }
            node(pv, sunIcon, ChartColors.pv, "PV ${Format.kw(e.pvKw)}")
            node(home, homeIcon, ChartColors.consumption, loadLabel)
            node(hub, hubIcon, labelColor, "")
            if (e.hasBattery) node(battery, batteryIcon, ChartColors.charge, e.socPercent?.let { Format.percent(it) } ?: "")
            node(grid, gridIcon, ChartColors.grid, e.backupLabel)
        }
    }
}

/** Draws an edge; when [kw] > 0 moving dots show the direction of the flow. */
private fun DrawScope.flowEdge(from: Offset, to: Offset, kw: Double, color: Color, idle: Color, phase: Float) {
    val activeEdge = kw > FLOW_EPS
    drawLine(if (activeEdge) color.copy(alpha = 0.55f) else idle, from, to, strokeWidth = if (activeEdge) 6f else 3f)
    if (!activeEdge) return
    val dots = 4
    for (i in 0 until dots) {
        val t = (phase + i.toFloat() / dots) % 1f
        drawCircle(color, radius = 7f, center = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t))
    }
}
