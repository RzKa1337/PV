package com.solartracker.pro.ui.energy

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.solartracker.pro.core.analytics.DailyEnergyReport
import com.solartracker.pro.core.health.LossCause
import com.solartracker.pro.core.health.PerformanceReport
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.twin.DigitalTwin
import com.solartracker.pro.core.twin.TwinHealth
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.uiLabel
import java.util.Locale

private fun f(v: Double, d: Int = 1) = String.format(Locale.ROOT, "%.${d}f", v)

/** Expected vs actual PV with estimated loss shares (never presented as measurements). */
@Composable
fun PerformanceCard(p: PerformanceReport?, modifier: Modifier = Modifier) {
    p ?: return
    SectionCard(modifier.testTag("performance_card")) {
        Text(stringResource(R.string.hv_performance), fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth()) { Text(stringResource(R.string.hv_expected), Modifier.weight(1f)); Text("${f(p.expectedW, 0)} W", fontWeight = FontWeight.SemiBold) }
        Row(Modifier.fillMaxWidth()) { Text(stringResource(R.string.hv_actual), Modifier.weight(1f)); Text(p.actualW?.let { "${f(it, 0)} W" } ?: "—", fontWeight = FontWeight.SemiBold) }
        p.performancePercent?.let { Row(Modifier.fillMaxWidth()) { Text(stringResource(R.string.hv_performance_value), Modifier.weight(1f)); Text("${f(it)}%", fontWeight = FontWeight.Bold) } }
        Text(p.status, style = MaterialTheme.typography.bodySmall)
        if (p.losses.isNotEmpty()) {
            Text(stringResource(R.string.hv_losses), style = MaterialTheme.typography.labelMedium)
            p.losses.forEach { l ->
                Row(Modifier.fillMaxWidth()) {
                    Text(if (l.cause == LossCause.UNKNOWN) stringResource(R.string.hv_unknown_loss) else l.cause.uiLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${f(l.percent)}%", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(stringResource(R.string.hv_losses_hint), style = MaterialTheme.typography.bodySmall)
        }
        p.weatherVsClearSkyPercent?.let { Text(stringResource(R.string.hv_weather_loss, f(it, 0)), style = MaterialTheme.typography.bodySmall) }
        KindBadge(DataKind.ESTIMATED)
    }
}

@Composable
fun DailyReportCard(today: DailyEnergyReport?, yesterday: DailyEnergyReport?, modifier: Modifier = Modifier) {
    if (today == null && yesterday == null) return
    SectionCard(modifier.testTag("daily_report_card")) {
        Text(stringResource(R.string.hv_daily_report), fontWeight = FontWeight.Bold)
        today?.let {
            Text(it.text(), style = MaterialTheme.typography.bodyMedium)
            if (it.vs7DayAverage.isNotEmpty()) {
                Text(stringResource(R.string.hv_vs_7day) + it.vs7DayAverage.joinToString(" · ") { d -> "${d.metric} ${d.percent?.let { p -> (if (p >= 0) "+" else "") + f(p, 0) + "%" } ?: "—"}" },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        yesterday?.let { Text(stringResource(R.string.hv_yesterday, f(it.pvKwh), f(it.loadKwh)), style = MaterialTheme.typography.bodySmall) }
        KindBadge(DataKind.CALCULATED)
    }
}

@Composable
private fun healthColor(h: TwinHealth): Color = when (h) {
    TwinHealth.OK -> MaterialTheme.colorScheme.primary
    TwinHealth.WARNING -> MaterialTheme.colorScheme.tertiary
    TwinHealth.FAULT -> MaterialTheme.colorScheme.error
    TwinHealth.UNKNOWN -> MaterialTheme.colorScheme.outline
}

/** Digital twin: one line per element, values with their provenance. */
@Composable
fun TwinCard(twin: DigitalTwin?, modifier: Modifier = Modifier) {
    twin ?: return
    SectionCard(modifier.testTag("twin_card")) {
        Text(stringResource(R.string.hv_twin), fontWeight = FontWeight.Bold)
        twin.nodes.forEach { n ->
            Row(Modifier.fillMaxWidth()) {
                Text(n.element.uiLabel, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(n.health.uiLabel, color = healthColor(n.health), fontWeight = FontWeight.SemiBold)
            }
            val values = (n.measured + n.calculated + n.forecast).entries.joinToString(" · ") { (k, q) ->
                "$k ${q.value?.let { f(it, if (q.unit == "W" || q.unit == "W/m²" || q.unit == "%" || q.unit == "°") 0 else 1) } ?: "—"} ${q.unit} [${q.kind.uiLabel}]"
            }
            if (values.isNotEmpty()) Text(values, style = MaterialTheme.typography.bodySmall)
            n.healthNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
