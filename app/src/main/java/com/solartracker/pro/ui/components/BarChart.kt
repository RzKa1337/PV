package com.solartracker.pro.ui.components

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solartracker.pro.ui.Format

/** One series of a grouped bar chart. */
data class BarSeries(val name: String, val color: Color, val values: List<Double>)

/**
 * Grouped bar chart: one group per category label, one bar per series.
 */
@Composable
fun GroupedBarChart(
    categories: List<String>,
    series: List<BarSeries>,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.chart_bar),
) {
    if (categories.isEmpty() || series.isEmpty()) return
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val textMeasurer = rememberTextMeasurer()
    val maxValue = series.flatMap { it.values }.maxOrNull()?.coerceAtLeast(0.1) ?: 0.1

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .revealOnAppear()
            .semantics { this.contentDescription = contentDescription },
    ) {
        val leftPad = 40.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val topPad = 6.dp.toPx()
        val chartW = size.width - leftPad
        val chartH = size.height - bottomPad - topPad
        fun y(v: Double) = topPad + chartH * (1f - (v / maxValue).toFloat())

        for (i in 0..4) {
            val value = maxValue * i / 4
            val yy = y(value)
            drawLine(gridColor, Offset(leftPad, yy), Offset(size.width, yy), strokeWidth = 1f)
            val label = textMeasurer.measure(Format.decimal(value, 0), labelStyle)
            drawText(label, topLeft = Offset(leftPad - label.size.width - 4.dp.toPx(), yy - label.size.height / 2))
        }

        val groupW = chartW / categories.size
        val innerPad = groupW * 0.15f
        val barW = (groupW - 2 * innerPad) / series.size
        categories.forEachIndexed { ci, category ->
            val groupX = leftPad + ci * groupW
            series.forEachIndexed { si, s ->
                val v = s.values.getOrElse(ci) { 0.0 }
                val top = y(v)
                drawRoundRect(
                    color = s.color,
                    topLeft = Offset(groupX + innerPad + si * barW, top),
                    size = Size(barW * 0.9f, topPad + chartH - top),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            val label = textMeasurer.measure(category, labelStyle)
            drawText(
                label,
                topLeft = Offset(groupX + (groupW - label.size.width) / 2, topPad + chartH + 3.dp.toPx()),
            )
        }
    }
}
