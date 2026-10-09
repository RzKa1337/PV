package com.solartracker.pro.ui.energy

import com.solartracker.pro.ui.components.StatusLabel
import com.solartracker.pro.ui.components.StatusLevel
import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
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
import com.solartracker.pro.ui.uiLabel
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
        Text(stringResource(R.string.es_title), fontWeight = FontWeight.Bold)
        if (security == null) {
            Text(stringResource(R.string.ins_ems_computing), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Text(security.securityPercent?.let { "$it%" } ?: "—", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = riskColor(security.risk))
                Text(stringResource(R.string.es_risk, security.risk.uiLabel), color = riskColor(security.risk), fontWeight = FontWeight.SemiBold)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ec_soc_now), style = MaterialTheme.typography.labelMedium)
                Text(security.socNow?.let { "${it.toInt()}%" } ?: "—", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                security.minSocPercent?.let { Text(stringResource(R.string.es_minimum, it.toInt().toString()), style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (security.shortageExpected && !security.gridBackup) {
            StatusLabel(StatusLevel.CRITICAL, stringResource(R.string.es_shortage), fontWeight = FontWeight.Bold)
        }
        Text(security.explanation, style = MaterialTheme.typography.bodyMedium)
        security.timeToMinSoc?.let { Text(stringResource(R.string.es_to_min_soc, (hm(it)).toString()), fontWeight = FontWeight.SemiBold) }
        if (!compact && security.milestones.isNotEmpty()) {
            Text(stringResource(R.string.es_soc_range), style = MaterialTheme.typography.labelMedium)
            security.milestones.forEach { m ->
                Row(Modifier.fillMaxWidth()) {
                    Text(m.label, Modifier.weight(1f))
                    Text("${m.socPercent}%  (${m.lowPercent}–${m.highPercent}%)", fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Text(stringResource(R.string.ins_confidence, Fmt.conf(security.confidence)) + if (security.socKind != DataKind.MEASURED) " · SOC: ${security.socKind.uiLabel}" else "",
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
        Text(stringResource(R.string.es_outlook), fontWeight = FontWeight.Bold)
        outlook.forEach { d ->
            Text(d.label + if (d.partial) stringResource(R.string.es_from_now) else "", fontWeight = FontWeight.Bold, color = riskColor(d.risk))
            Text(stringResource(R.string.es_outlook_row, f(d.pvKwh), f(d.loadKwh), (if (d.balanceKwh >= 0) "+" else "").toString(), f(d.balanceKwh)),
                style = MaterialTheme.typography.bodyMedium)
            val soc = if (d.socMin != null) "SOC ${d.socMin}–${d.socMax}%" + (d.socMinPessimistic?.let { stringResource(R.string.es_worse_min, it.toString()) } ?: "") else null
            Text(listOfNotNull(
                soc,
                if (d.batteryChargeKwh > 0.05 || d.batteryDischargeKwh > 0.05) stringResource(R.string.es_battery_flow, f(d.batteryChargeKwh), f(d.batteryDischargeKwh)) else null,
                d.securityPercent?.let { stringResource(R.string.es_security, it.toString()) },
                stringResource(R.string.es_risk_lower, d.risk.uiLabel),
                stringResource(R.string.es_confidence_lower, Fmt.conf(d.confidence)),
            ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        KindBadge(DataKind.FORECAST)
    }
}
