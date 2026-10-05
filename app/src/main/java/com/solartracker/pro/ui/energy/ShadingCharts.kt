package com.solartracker.pro.ui.energy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.shading.DayShading
import com.solartracker.pro.core.shading.SolarHorizonProfile
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Polar sky chart: centre = zenith, edge = horizon, north up. Grey area = horizon profile (terrain +
 * obstacles); orange = sun path today, dashed = solstices; dot = sun at the selected time.
 */
@Composable
fun HorizonChart(
    profile: SolarHorizonProfile,
    location: GeoLocation,
    date: LocalDate,
    zone: ZoneId,
    sunAzimuth: Double?,
    sunElevation: Double?,
    modifier: Modifier = Modifier,
) {
    val c = MaterialTheme.colorScheme
    Canvas(modifier.fillMaxWidth().height(280.dp)) {
        val r = min(size.width, size.height) / 2 * 0.95f
        val center = Offset(size.width / 2, size.height / 2)
        fun pos(az: Double, el: Double): Offset {
            val rr = r * (1 - (el.coerceIn(0.0, 90.0) / 90.0)).toFloat()
            val a = Math.toRadians(az)
            return Offset(center.x + rr * sin(a).toFloat(), center.y - rr * cos(a).toFloat())
        }
        listOf(0.0, 30.0, 60.0).forEach { el ->
            drawCircle(c.outlineVariant, radius = r * (1 - el.toFloat() / 90f), center = center, style = Stroke(1f))
        }
        for (az in 0 until 360 step 45) drawLine(c.outlineVariant, center, pos(az.toDouble(), 0.0), 1f)
        val horizon = Path().apply {
            moveTo(pos(0.0, 0.0).x, pos(0.0, 0.0).y)
            for (az in 0..360) { val p = pos(az.toDouble(), profile.elevationAt(az.toDouble())); lineTo(p.x, p.y) }
            for (az in 360 downTo 0) { val p = pos(az.toDouble(), 0.0); lineTo(p.x, p.y) }
            close()
        }
        drawPath(horizon, c.onSurface.copy(alpha = 0.35f))
        fun sunPath(d: LocalDate, color: androidx.compose.ui.graphics.Color, width: Float) {
            var prev: Offset? = null
            val start = d.atStartOfDay(zone).toInstant()
            for (m in 0..(24 * 60) step 10) {
                val s = SolarCalculator.position(location, start.plusSeconds(m * 60L))
                if (s.elevationDeg <= 0) { prev = null; continue }
                val p = pos(s.azimuthDeg, s.elevationDeg)
                prev?.let { drawLine(color, it, p, width) }
                prev = p
            }
        }
        sunPath(LocalDate.of(date.year, 6, 21), c.tertiary.copy(alpha = 0.5f), 2f)
        sunPath(LocalDate.of(date.year, 12, 21), c.tertiary.copy(alpha = 0.5f), 2f)
        sunPath(date, c.tertiary, 4f)
        if (sunAzimuth != null && sunElevation != null && sunElevation > 0) drawCircle(c.error, 8f, pos(sunAzimuth, sunElevation))
    }
}

/** Hourly bars: full height = PV without shading, coloured part = with shading. */
@Composable
fun HourlyShadingChart(day: DayShading, zone: ZoneId, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    val hours = (0..23).map { h ->
        val steps = day.steps.filter { it.time.atZone(zone).hour == h }
        val f = if (steps.isEmpty()) 0.0 else 1.0 / steps.size
        steps.sumOf { it.unshadedKw } * f to steps.sumOf { it.shadedKw } * f
    }
    val max = hours.maxOf { it.first }.coerceAtLeast(0.01)
    Canvas(modifier.fillMaxWidth().height(140.dp)) {
        val w = size.width / 24f
        hours.forEachIndexed { i, (unshaded, shaded) ->
            val hU = (unshaded / max * size.height).toFloat()
            val hS = (shaded / max * size.height).toFloat()
            drawRect(c.error.copy(alpha = 0.35f), Offset(i * w + 1, size.height - hU), Size(w - 2, hU))
            drawRect(c.primary, Offset(i * w + 1, size.height - hS), Size(w - 2, hS))
        }
        for (h in listOf(6, 12, 18)) drawLine(c.outlineVariant, Offset(h * w, 0f), Offset(h * w, size.height), 1f)
    }
}
