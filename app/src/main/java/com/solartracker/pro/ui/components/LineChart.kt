package com.solartracker.pro.ui.components

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solartracker.pro.ui.Format

/** One line of a [LineChart]; [values] are aligned with the chart's x positions. */
data class LineSeries(val name: String, val color: Color, val values: List<Double>, val fill: Boolean = false)

/**
 * Multi-series line chart over evenly spaced x positions.
 *
 * @param xLabels label for selected x indices (index → text)
 * @param fixedMax fixed top of the y axis (e.g. 100 for SOC), otherwise the data maximum
 */
@Composable
fun LineChart(
    series: List<LineSeries>,
    xLabels: Map<Int, String>,
    modifier: Modifier = Modifier,
    fixedMax: Double? = null,
    yUnit: String = "",
    height: Dp = 200.dp,
    contentDescription: String = stringResource(R.string.chart),
) {
    val count = series.maxOfOrNull { it.values.size } ?: 0
    if (count < 2) return
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()
    val maxValue = fixedMax ?: (series.flatMap { it.values }.maxOrNull() ?: 0.0).coerceAtLeast(0.1)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .revealOnAppear()
            .semantics { this.contentDescription = contentDescription },
    ) {
        val leftPad = 40.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val topPad = 6.dp.toPx()
        val chartW = size.width - leftPad
        val chartH = size.height - bottomPad - topPad
        fun x(i: Int) = leftPad + chartW * i / (count - 1).toFloat()
        fun y(v: Double) = topPad + chartH * (1f - (v / maxValue).toFloat().coerceIn(0f, 1f))

        for (i in 0..4) {
            val value = maxValue * i / 4
            val yy = y(value)
            drawLine(gridColor, Offset(leftPad, yy), Offset(size.width, yy), strokeWidth = 1f)
            val text = Format.decimal(value, if (maxValue < 2) 2 else if (maxValue < 20) 1 else 0) + yUnit
            val label = textMeasurer.measure(text, labelStyle)
            drawText(label, topLeft = Offset(leftPad - label.size.width - 4.dp.toPx(), yy - label.size.height / 2))
        }
        xLabels.forEach { (index, text) ->
            if (index !in 0 until count) return@forEach
            val xx = x(index)
            drawLine(gridColor, Offset(xx, topPad), Offset(xx, topPad + chartH), strokeWidth = 1f)
            val label = textMeasurer.measure(text, labelStyle)
            val lx = (xx - label.size.width / 2).coerceIn(leftPad, size.width - label.size.width)
            drawText(label, topLeft = Offset(lx, topPad + chartH + 3.dp.toPx()))
        }

        series.forEach { s ->
            if (s.values.size < 2) return@forEach
            val line = Path()
            s.values.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
            if (s.fill) {
                val area = Path().apply {
                    moveTo(x(0), y(0.0))
                    s.values.forEachIndexed { i, v -> lineTo(x(i), y(v)) }
                    lineTo(x(s.values.size - 1), y(0.0))
                    close()
                }
                drawPath(area, color = s.color.copy(alpha = 0.18f))
            }
            drawPath(line, color = s.color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(series: List<LineSeries>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(
                    Modifier
                        .size(10.dp)
                        .background(s.color, CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                Text(s.name, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
