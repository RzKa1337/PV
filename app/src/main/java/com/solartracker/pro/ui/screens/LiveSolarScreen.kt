package com.solartracker.pro.ui.screens

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
        PowerCard(state.pv, state.sun.isDay)
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
            "Pozycja Słońca jest liczona lokalnie co sekundę. Irradiancja pochodzi z modelu/prognozy " +
                "(bez zapytań do internetu co sekundę), dlatego moc PV to wartość modelowana, a nie pomiar.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LiveStatusRow(clock: String?, active: Boolean, paused: Boolean, onPausedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("☀️ LIVE SOLAR", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            val running = active && !paused
            Text(
                if (running) "🟢 LIVE" else "🔴 LIVE PAUSED",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (running) ChartColors.charge else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(LiveTags.STATUS),
            )
            if (clock != null) {
                Text("Updated $clock", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        FilledTonalButton(onClick = { onPausedChange(!paused) }) {
            Text(if (paused) "▶ LIVE" else "⏸ Pauza")
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
                if (sun.isDay) "☀️ Dzień" else "🌙 Noc",
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
                    "Wschód ${sun.sunriseText}",
                    "Zachód ${sun.sunsetText}",
                ).joinToString("  ·  "),
                style = MaterialTheme.typography.bodyMedium,
                color = onColor,
            )
            DayType.POLAR_DAY -> Text("Dzień polarny – Słońce nie zachodzi", color = onColor)
            DayType.POLAR_NIGHT -> Text("Noc polarna – Słońce nie wschodzi", color = onColor)
        }
        val countdown = if (sun.isDay) sun.timeToSunsetText?.let { "Do zachodu: $it" } else sun.timeToSunriseText?.let { "Do wschodu: $it" }
        if (countdown != null) {
            Text(countdown, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = onColor)
        }
    }
}

@Composable
private fun PowerCard(pv: LivePvUi, isDay: Boolean) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (pv.measuredPowerKw != null) "Moc PV" else "Szacowana moc PV",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            EstimateBadge(text = "MODEL")
        }
        if (pv.measuredPowerKw != null) {
            Text("Rzeczywista moc PV: ${Format.kw(pv.measuredPowerKw)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("Model: ${Format.kw(pv.modeledPowerKw)}", style = MaterialTheme.typography.titleMedium)
        } else {
            Text(
                if (isDay) Format.kw(pv.modeledPowerKw) else "0 W",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag(LiveTags.POWER),
            )
        }
        Text(
            "${Format.decimal(pv.poa, 0)} W/m² na panelu (POA)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Irradiancja: ${pv.sourceLabel}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (pv.measuredPowerKw == null) {
            Text(
                "Brak danych z falownika – pokazywana jest wartość modelowana, nie pomiar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            Text("co 1 s", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LiveRow("Czas", state.clockText)
        LiveRow("Azymut", deg2(sun.azimuthDeg))
        LiveRow("Wysokość (elevation)", deg2(sun.elevationDeg))
        LiveRow("Zenit (odległość zenitalna)", deg2(sun.zenithDeg))
        LiveRow("Kąt godzinowy", deg2(sun.hourAngleDeg))
        LiveRow("Deklinacja", deg2(sun.declinationDeg))
        LiveRow("Air mass", sun.airMass?.let { Format.decimal(it, 3) } ?: "— (pod horyzontem)")
        HorizontalDivider()
        LiveRow("POA", "${Format.decimal(pv.poa, 0)} W/m²")
        LiveRow("GHI / DNI / DHI", "${Format.decimal(pv.ghi, 0)} / ${Format.decimal(pv.dni, 0)} / ${Format.decimal(pv.dhi, 0)}")
        LiveRow("PV (model)", Format.kw(pv.modeledPowerKw))
        LiveRow("Panel", "${Format.degrees(pv.tiltDeg)} / ${Format.degrees(pv.panelAzimuthDeg)}")
        LiveRow("Kąt padania", Format.decimal(pv.angleOfIncidenceDeg, 1) + "°")
        LiveRow("Wykorzystanie geometrii", Format.percent(pv.geometricUtilization * 100, 1))
        LiveRow("Temperatura powietrza", pv.ambientTemperatureC?.let { Format.decimal(it, 1) + "°C" } ?: "brak danych")
        LiveRow("Temperatura ogniwa", pv.cellTemperatureC?.let { Format.decimal(it, 1) + "°C" } ?: "—")
        HorizontalDivider()
        LiveRow("Wschód / zachód", "${sun.sunriseText} / ${sun.sunsetText}")
        LiveRow("Górowanie", sun.solarNoonText)
        sun.timeToSunriseText?.let { LiveRow("Do wschodu", it) }
        sun.timeToSunsetText?.let { LiveRow("Do zachodu", it) }
    }
}

@Composable
private fun CompassCard(path: SunPathUi, sun: LiveSunUi, panelAzimuthDeg: Double) {
    val textMeasurer = rememberTextMeasurer()
    val outline = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurface
    val pathColor = ChartColors.pv.copy(alpha = 0.6f)
    val panelColor = ChartColors.consumption
    SectionCard {
        Text("Kompas – pozycja Słońca", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .semantics { contentDescription = "Kompas: Słońce na azymucie ${deg2(sun.azimuthDeg)}, wysokość ${deg2(sun.elevationDeg)}" },
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
            "Środek = zenit, okrąg = horyzont. Niebieska linia: kierunek paneli. Przerywana: dzisiejsza droga Słońca.",
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
    SectionCard {
        Text("Dzisiejsza droga Słońca (${path.dateText})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = "Droga Słońca: najwyżej ${deg2(path.highestElevationDeg)} o ${path.highestText}" },
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
            "Wschód ${path.sunriseText} · najwyżej ${Format.decimal(path.highestElevationDeg, 1)}° o ${path.highestText} " +
                "(az ${Format.decimal(path.highestAzimuthDeg, 1)}°) · zachód ${path.sunsetText}",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Teraz: az ${deg2(sun.azimuthDeg)}, wys. ${deg2(sun.elevationDeg)}" + if (!sun.isDay) " (pod horyzontem)" else "",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EnergyNowCard(e: LiveEnergyUi) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Energia teraz", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            EstimateBadge(text = "MODEL")
        }
        LiveRow("PV", Format.kw(e.pvKw))
        LiveRow("Zużycie", Format.kw(e.loadKw))
        LiveRow(if (e.netKw >= 0) "Nadwyżka" else "Deficyt", Format.kw(abs(e.netKw)))
        if (e.hasBattery) {
            val sign = if (e.batteryKw > 0) "+" else if (e.batteryKw < 0) "−" else ""
            LiveRow("Bateria", "$sign${Format.kw(abs(e.batteryKw))}")
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
                LiveRow("Zgromadzone", "${Format.decimal(e.storedKwh, 2)} / ${Format.decimal(e.usableKwh, 1)} kWh")
            }
        }
        if (e.gridImportKw > 0.0005) LiveRow("${e.backupLabel} → odbiorniki", Format.kw(e.gridImportKw))
        if (e.exportKw > 0.0005) LiveRow("Nadwyżka do ${e.backupLabel.lowercase()} / utracona", Format.kw(e.exportKw))
        EnergyFlowDiagram(e)
        Text(flowSummary(e), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

private const val FLOW_EPS = 0.0005

fun flowSummary(e: LiveEnergyUi): String = buildList {
    if (e.directKw > FLOW_EPS) add("PV → Dom")
    if (e.chargeKw > FLOW_EPS) add("PV → Bateria")
    if (e.dischargeKw > FLOW_EPS) add("Bateria → Dom")
    if (e.exportKw > FLOW_EPS) add("PV → ${e.backupLabel}")
    if (e.gridImportKw > FLOW_EPS) add("${e.backupLabel} → Dom")
}.ifEmpty { listOf("Brak przepływu energii") }.joinToString("   ")

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
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(220.dp)
                .semantics { contentDescription = "Przepływ energii: ${flowSummary(e)}" },
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

            val emoji = TextStyle(fontSize = 26.sp)
            val small = TextStyle(fontSize = 11.sp, color = labelColor, fontWeight = FontWeight.SemiBold)
            fun node(center: Offset, icon: String, label: String) {
                drawCircle(Color.White.copy(alpha = 0.12f), 30f * density / 2, center)
                val m = textMeasurer.measure(icon, emoji)
                drawText(m, topLeft = Offset(center.x - m.size.width / 2, center.y - m.size.height / 2))
                val l = textMeasurer.measure(label, small)
                drawText(l, topLeft = Offset(center.x - l.size.width / 2, center.y + m.size.height / 2))
            }
            node(pv, "☀️", "PV ${Format.kw(e.pvKw)}")
            node(home, "🏠", "Zużycie ${Format.kw(e.loadKw)}")
            node(hub, "⚡", "")
            if (e.hasBattery) node(battery, "🔋", e.socPercent?.let { Format.percent(it) } ?: "")
            node(grid, "🌐", e.backupLabel)
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
