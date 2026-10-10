package com.solartracker.pro.ui.components

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solartracker.pro.core.pv.PowerPoint
import com.solartracker.pro.ui.Format
import java.time.Instant
import kotlin.math.max

/**
 * Area chart: hour of day → estimated PV power [kW].
 * The x axis spans from the first to the last point; [now] is drawn as a marker.
 */
@Composable
fun PowerChart(
    points: List<PowerPoint>,
    now: Instant?,
    peakPowerKw: Double,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) return
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val nowColor = MaterialTheme.colorScheme.secondary
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    val start = points.first().time.toEpochMilli()
    val end = points.last().time.toEpochMilli()
    val maxPower = max(points.maxOf { it.powerKw }, peakPowerKw * 0.1).coerceAtLeast(0.1)
    val description = stringResource(R.string.chart_power_desc, Format.kw(points.maxOf { it.powerKw }))

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
            .revealOnAppear()
            .semantics { contentDescription = description },
    ) {
        val leftPad = 44.dp.toPx()
        val bottomPad = 20.dp.toPx()
        val topPad = 8.dp.toPx()
        val chartW = size.width - leftPad
        val chartH = size.height - bottomPad - topPad

        fun x(t: Long) = leftPad + chartW * (t - start).toFloat() / (end - start).toFloat()
        fun y(p: Double) = topPad + chartH * (1f - (p / maxPower).toFloat())

        // Horizontal grid with kW labels.
        for (i in 0..4) {
            val value = maxPower * i / 4
            val yy = y(value)
            drawLine(gridColor, Offset(leftPad, yy), Offset(size.width, yy), strokeWidth = 1f)
            val label = textMeasurer.measure(Format.decimal(value, if (maxPower < 1) 2 else 1), labelStyle)
            drawText(label, topLeft = Offset(leftPad - label.size.width - 6.dp.toPx(), yy - label.size.height / 2))
        }

        // Hour labels every 6 hours.
        val hourMillis = 3_600_000L
        for (h in 0..24 step 6) {
            val t = start + h * hourMillis
            if (t > end) break
            val xx = x(t)
            drawLine(gridColor, Offset(xx, topPad), Offset(xx, topPad + chartH), strokeWidth = 1f)
            val label = textMeasurer.measure("${h.toString().padStart(2, '0')}:00", labelStyle)
            val lx = (xx - label.size.width / 2).coerceIn(leftPad, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, topPad + chartH + 4.dp.toPx()))
        }

        val line = Path()
        points.forEachIndexed { i, p ->
            val px = x(p.time.toEpochMilli())
            val py = y(p.powerKw)
            if (i == 0) line.moveTo(px, py) else line.lineTo(px, py)
        }
        val area = Path().apply {
            moveTo(x(start), y(0.0))
            points.forEach { p -> lineTo(x(p.time.toEpochMilli()), y(p.powerKw)) }
            lineTo(x(end), y(0.0))
            lineTo(x(start), y(0.0))
            close()
        }
        drawPath(
            area,
            brush = Brush.verticalGradient(
                listOf(lineColor.copy(alpha = 0.45f), lineColor.copy(alpha = 0.05f)),
                startY = topPad,
                endY = topPad + chartH,
            ),
        )
        drawPath(line, color = lineColor, style = Stroke(width = 2.5.dp.toPx()))

        if (now != null) {
            val t = now.toEpochMilli()
            if (t in start..end) {
                val nx = x(t)
                drawLine(
                    nowColor,
                    Offset(nx, topPad),
                    Offset(nx, topPad + chartH),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)),
                )
                val nearest = points.minBy { kotlin.math.abs(it.time.toEpochMilli() - t) }
                drawCircle(nowColor, radius = 5.dp.toPx(), center = Offset(nx, y(nearest.powerKw)))
            }
        }
    }
}
