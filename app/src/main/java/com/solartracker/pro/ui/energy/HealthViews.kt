package com.solartracker.pro.ui.energy

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
import java.util.Locale

private fun f(v: Double, d: Int = 1) = String.format(Locale.ROOT, "%.${d}f", v)

/** Expected vs actual PV with estimated loss shares (never presented as measurements). */
@Composable
fun PerformanceCard(p: PerformanceReport?, modifier: Modifier = Modifier) {
    p ?: return
    SectionCard(modifier.testTag("performance_card")) {
        Text("WYDAJNOŚĆ PV", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth()) { Text("Oczekiwana (bez strat)", Modifier.weight(1f)); Text("${f(p.expectedW, 0)} W", fontWeight = FontWeight.SemiBold) }
        Row(Modifier.fillMaxWidth()) { Text("Rzeczywista", Modifier.weight(1f)); Text(p.actualW?.let { "${f(it, 0)} W" } ?: "—", fontWeight = FontWeight.SemiBold) }
        p.performancePercent?.let { Row(Modifier.fillMaxWidth()) { Text("Wydajność", Modifier.weight(1f)); Text("${f(it)}%", fontWeight = FontWeight.Bold) } }
        Text(p.status, style = MaterialTheme.typography.bodySmall)
        if (p.losses.isNotEmpty()) {
            Text("Szacowane straty:", style = MaterialTheme.typography.labelMedium)
            p.losses.forEach { l ->
                Row(Modifier.fillMaxWidth()) {
                    Text(if (l.cause == LossCause.UNKNOWN) "Nieznane (pomiar vs model)" else l.cause.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Text("${f(l.percent)}%", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("Straty są szacunkiem modelu (zabrudzenie i mismatch – z profilu strat), nie pomiarem.", style = MaterialTheme.typography.bodySmall)
        }
        p.weatherVsClearSkyPercent?.let { Text("Pogoda: ${f(it, 0)}% mniej światła niż przy bezchmurnym niebie", style = MaterialTheme.typography.bodySmall) }
        KindBadge(DataKind.ESTIMATED)
    }
}

@Composable
fun DailyReportCard(today: DailyEnergyReport?, yesterday: DailyEnergyReport?, modifier: Modifier = Modifier) {
    if (today == null && yesterday == null) return
    SectionCard(modifier.testTag("daily_report_card")) {
        Text("RAPORT DZIENNY", fontWeight = FontWeight.Bold)
        today?.let {
            Text(it.text(), style = MaterialTheme.typography.bodyMedium)
            if (it.vs7DayAverage.isNotEmpty()) {
                Text("vs średnia 7 dni: " + it.vs7DayAverage.joinToString(" · ") { d -> "${d.metric} ${d.percent?.let { p -> (if (p >= 0) "+" else "") + f(p, 0) + "%" } ?: "—"}" },
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        yesterday?.let { Text("Wczoraj: PV ${f(it.pvKwh)} kWh · zużycie ${f(it.loadKwh)} kWh", style = MaterialTheme.typography.bodySmall) }
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
        Text("MODEL INSTALACJI", fontWeight = FontWeight.Bold)
        twin.nodes.forEach { n ->
            Row(Modifier.fillMaxWidth()) {
                Text(n.element.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Text(n.health.label, color = healthColor(n.health), fontWeight = FontWeight.SemiBold)
            }
            val values = (n.measured + n.calculated + n.forecast).entries.joinToString(" · ") { (k, q) ->
                "$k ${q.value?.let { f(it, if (q.unit == "W" || q.unit == "W/m²" || q.unit == "%" || q.unit == "°") 0 else 1) } ?: "—"} ${q.unit} [${q.kind.label}]"
            }
            if (values.isNotEmpty()) Text(values, style = MaterialTheme.typography.bodySmall)
            n.healthNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
