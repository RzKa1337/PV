package com.solartracker.pro.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solartracker.pro.core.forecast.DayOutlook
import com.solartracker.pro.core.forecast.EnergyRisk
import com.solartracker.pro.core.forecast.EnergySecurity
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.ui.components.SectionCard
import java.time.Duration
import java.util.Locale

@Composable
private fun riskColor(risk: EnergyRisk): Color = when (risk) {
    EnergyRisk.LOW -> MaterialTheme.colorScheme.primary
    EnergyRisk.MEDIUM -> MaterialTheme.colorScheme.tertiary
    EnergyRisk.HIGH -> MaterialTheme.colorScheme.error
    EnergyRisk.UNKNOWN -> MaterialTheme.colorScheme.outline
}

private fun hm(d: Duration): String = "${d.toHours()} h ${d.toMinutes() % 60} min"

/** "Will there be enough energy?" – large, phone-readable summary with SOC milestones and uncertainty. */
@Composable
fun EnergySecurityCard(security: EnergySecurity?, modifier: Modifier = Modifier, compact: Boolean = false) {
    SectionCard(modifier.testTag("energy_security_card")) {
        Text("BEZPIECZEŃSTWO ENERGETYCZNE", fontWeight = FontWeight.Bold)
        if (security == null) {
            Text("Prognoza jeszcze się liczy…", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(security.securityPercent?.let { "$it%" } ?: "—", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = riskColor(security.risk))
                Text("Ryzyko: ${security.risk.label}", color = riskColor(security.risk), fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text("SOC teraz", style = MaterialTheme.typography.labelMedium)
                Text(security.socNow?.let { "${it.toInt()}%" } ?: "—", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                security.minSocPercent?.let { Text("Minimum: ${it.toInt()}%", style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (security.shortageExpected && !security.gridBackup) {
            Text("⚠️ PRZEWIDYWANY BRAK ENERGII", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        Text(security.explanation, style = MaterialTheme.typography.bodyMedium)
        security.timeToMinSoc?.let { Text("Do minimum SOC: ${hm(it)}", fontWeight = FontWeight.SemiBold) }
        if (!compact && security.milestones.isNotEmpty()) {
            Text("Przewidywany SOC (zakres: gorszy–lepszy scenariusz)", style = MaterialTheme.typography.labelMedium)
            security.milestones.forEach { m ->
                Row(Modifier.fillMaxWidth()) {
                    Text(m.label, Modifier.weight(1f))
                    Text("${m.socPercent}%  (${m.lowPercent}–${m.highPercent}%)", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text("Pewność ${Fmt.conf(security.confidence)}" + if (security.socKind != DataKind.MEASURED) " · SOC: ${security.socKind.label}" else "",
            style = MaterialTheme.typography.bodySmall)
        KindBadge(DataKind.FORECAST)
    }
}

/** Today / tomorrow / +2 / +3 days. */
@Composable
fun OutlookCard(outlook: List<DayOutlook>, modifier: Modifier = Modifier) {
    if (outlook.isEmpty()) return
    fun f(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    SectionCard(modifier.testTag("outlook_card")) {
        Text("PROGNOZA ENERGII 72 H", fontWeight = FontWeight.Bold)
        outlook.forEach { d ->
            Text(d.label + if (d.partial) " (od teraz)" else "", fontWeight = FontWeight.Bold, color = riskColor(d.risk))
            Text("PV ${f(d.pvKwh)} kWh · zużycie ${f(d.loadKwh)} kWh · bilans ${if (d.balanceKwh >= 0) "+" else ""}${f(d.balanceKwh)} kWh",
                style = MaterialTheme.typography.bodyMedium)
            val soc = if (d.socMin != null) "SOC ${d.socMin}–${d.socMax}%" + (d.socMinPessimistic?.let { " (gorzej: min $it%)" } ?: "") else null
            Text(listOfNotNull(
                soc,
                if (d.batteryChargeKwh > 0.05 || d.batteryDischargeKwh > 0.05) "bateria +${f(d.batteryChargeKwh)}/−${f(d.batteryDischargeKwh)} kWh" else null,
                d.securityPercent?.let { "bezpieczeństwo $it%" },
                "ryzyko ${d.risk.label}",
                "pewność ${Fmt.conf(d.confidence)}",
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        KindBadge(DataKind.FORECAST)
    }
}
