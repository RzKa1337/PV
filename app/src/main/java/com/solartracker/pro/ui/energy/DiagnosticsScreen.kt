package com.solartracker.pro.ui.energy

import com.solartracker.pro.ui.components.StatusLabel
import com.solartracker.pro.ui.components.StatusLevel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.R
import com.solartracker.pro.core.diagnostics.Diagnosis
import com.solartracker.pro.core.diagnostics.DiagnosisSeverity
import com.solartracker.pro.core.diagnostics.LossStep
import com.solartracker.pro.core.diagnostics.PvHealthStatus
import com.solartracker.pro.core.diagnostics.PvReality
import com.solartracker.pro.core.forecast.MissionGoal
import com.solartracker.pro.core.inverter.RegisterQuality
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.theme.ChartColors
import com.solartracker.pro.ui.uiLabel
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** Centrum → Diagnostyka PV: reality check, loss breakdown, PV Doctor, MPPT, radar, mission, long-term, registers. */
@Composable
fun DiagnosticsScreen(vm: EnergyCenterViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val d by vm.diagnostics.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    fun export(uri: android.net.Uri?, json: Boolean) {
        if (uri == null) return
        scope.launch {
            exportStatus = runCatching {
                val text = vm.exportRegisterLog(json)
                writeText(context, uri, text)
                context.getString(R.string.ins_export_saved, (text.length / 1024 + 1).toString())
            }.getOrElse { context.getString(R.string.ins_export_failed, it.message ?: it.javaClass.simpleName) }
        }
    }
    val csv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, false) }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, true) }
    val hm = remember { DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("diagnostics_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_energy_center)) }
        ScreenTitle(stringResource(R.string.dg_title), stringResource(R.string.dg_subtitle))

        // PV STATUS + RECOMMENDATION
        val diagnosis = d.diagnosis
        val r = d.reality
        SectionCard {
            Text(stringResource(R.string.dg_status), fontWeight = FontWeight.Bold)
            val status = diagnosis?.status ?: PvHealthStatus.UNKNOWN
            val level = when (status) { PvHealthStatus.OK -> StatusLevel.GOOD; PvHealthStatus.ATTENTION -> StatusLevel.WARNING; PvHealthStatus.PROBLEM -> StatusLevel.CRITICAL; PvHealthStatus.UNKNOWN -> StatusLevel.UNKNOWN }
            StatusLabel(level, status.uiLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textColor = MaterialTheme.colorScheme.onSurface)
            if (r == null) {
                Text(stringResource(R.string.dg_no_reality), style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(r.status.uiLabel, style = MaterialTheme.typography.bodyMedium)
                MetricRow(stringResource(R.string.dg_theoretical), Fmt.kw(r.theoreticalW), DataKind.ESTIMATED)
                MetricRow(stringResource(R.string.dg_expected), Fmt.kw(r.expectedW), DataKind.ESTIMATED)
                MetricRow(stringResource(R.string.dg_actual), Fmt.kw(r.actualW), r.actual?.kind ?: DataKind.UNAVAILABLE)
                MetricRow(stringResource(R.string.dg_deviation), Fmt.signedPct(r.deviationPercent), DataKind.CALCULATED)
                Text(stringResource(R.string.dg_basis, r.irradianceBasis.uiLabel, Fmt.pct(r.uncertainty * 100) ?: "—"), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            diagnosis?.let { Text(stringResource(R.string.dg_confidence, Fmt.conf(it.confidence)), style = MaterialTheme.typography.bodySmall) }
            if (live.info?.simulated == true) Text(stringResource(R.string.ec_simulator_warning), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        diagnosis?.primary?.let { p ->
            SectionCard(Modifier.testTag("dg_recommendation")) {
                Text(stringResource(R.string.dg_recommendation), fontWeight = FontWeight.Bold)
                Text(p.recommendation, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }

        // LOSS BREAKDOWN
        r?.takeIf { it.theoreticalW > 0 }?.let { LossBreakdownCard(it) }

        // DIAGNOSIS
        diagnosis?.let { dg ->
            SectionCard {
                Text(stringResource(R.string.dg_diagnosis), fontWeight = FontWeight.Bold)
                DiagnosisItem(dg.primary, primary = true)
                val others = dg.all.drop(1)
                if (others.isNotEmpty()) {
                    Text(stringResource(R.string.dg_other), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    others.forEach { DiagnosisItem(it, primary = false) }
                }
            }
        }

        // MPPT
        SectionCard {
            Text(stringResource(R.string.dg_mppt), fontWeight = FontWeight.Bold)
            val m = d.mppt
            if (m == null || !m.available) {
                Text(stringResource(R.string.dg_mppt_na), style = MaterialTheme.typography.bodyMedium)
                KindBadge(DataKind.UNAVAILABLE)
            } else {
                m.statuses.forEach { s ->
                    Text("${s.label}: ${Fmt.kw(s.powerW) ?: "N/A"} · ${Fmt.v(s.voltageV) ?: "N/A"} · ${Fmt.a(s.currentA) ?: "N/A"}", fontWeight = FontWeight.SemiBold)
                    Text("${stringResource(R.string.dg_expected)}: ${Fmt.kw(s.expectedW) ?: "—"} · ${Fmt.signedPct(s.deviationPercent) ?: "—"} · ${s.flag.uiLabel}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(m.note, style = MaterialTheme.typography.bodySmall)
                m.possibleCauses.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.dg_mppt_split), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // PV RADAR
        d.radar?.let { radar ->
            SectionCard {
                Text(stringResource(R.string.dg_radar), fontWeight = FontWeight.Bold)
                radar.points.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.horizon.label, Modifier.width(72.dp), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text("${Fmt.kwFromKw(p.expectedKw)} (${Fmt.kwFromKw(p.minKw)}–${Fmt.kwFromKw(p.maxKw)})", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Text(Fmt.conf(p.confidence), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(listOfNotNull(
                        p.cloudImpactPercent?.let { stringResource(R.string.dg_cloud_impact, Fmt.pct(it) ?: "") },
                        Fmt.kwh(p.energyKwh).takeIf { p.horizon.minutes > 0 },
                    ).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 72.dp))
                }
                radar.cloudEvent?.let { e ->
                    Text(stringResource(R.string.dg_cloud_event, e.minutesAhead.toString(), Fmt.kwFromKw(e.currentKw) ?: "", Fmt.kwFromKw(e.expectedMinKw) ?: "",
                        Fmt.pct(e.dropPercent) ?: ""), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.dg_resolution, radar.resolutionMinutes.toString(), (radar.resolutionMinutes / 2).toString()),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                KindBadge(DataKind.FORECAST)
            }
        }

        // BATTERY – MISSION
        SectionCard {
            Text(stringResource(R.string.dg_battery), fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MissionGoal.entries.forEach { g -> FilterChip(selected = d.missionGoal == g, onClick = { vm.setMissionGoal(g) }, label = { Text(g.uiLabel) }) }
            }
            d.mission?.let { m ->
                m.probabilityPercent?.let { Text(stringResource(R.string.dg_probability, "$it%"), fontWeight = FontWeight.SemiBold) }
                m.socNow?.let { MetricRow("SOC", Fmt.pct(it), m.socKind) }
                m.milestones.forEach { ms -> MetricRow("SOC ${ms.label}", "${ms.socPercent}% (${ms.lowPercent}–${ms.highPercent}%)", DataKind.FORECAST) }
                m.metrics.forEach { MetricRow(it.label, it.value, it.kind) }
                Text(m.recommendation, fontWeight = FontWeight.SemiBold)
            }
        }

        // LONG-TERM
        SectionCard {
            Text(stringResource(R.string.dg_longterm), fontWeight = FontWeight.Bold)
            d.soiling?.let { s ->
                Text(stringResource(R.string.dg_soiling, s.state.uiLabel), fontWeight = FontWeight.SemiBold)
                s.lossPercent?.let { Text(stringResource(R.string.dg_soiling_loss, Fmt.pct(it) ?: "", Fmt.conf(s.confidence)), style = MaterialTheme.typography.bodySmall) }
                s.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            } ?: Text("—")
            d.degradation?.let { g ->
                Text(stringResource(R.string.dg_degradation, g.trend.uiLabel), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                g.ratePercentPerYear?.let { Text(stringResource(R.string.dg_degradation_rate, String.format(java.util.Locale.ROOT, "%.2f%%", it), Fmt.conf(g.confidence)), style = MaterialTheme.typography.bodySmall) }
                g.projection.forEach { p -> Text(stringResource(R.string.dg_capacity, "${p.year} → ${String.format(java.util.Locale.ROOT, "%.2f kWp", p.capacityKwp)}"), style = MaterialTheme.typography.bodySmall) }
                g.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
            KindBadge(DataKind.ESTIMATED)
        }

        // REGISTER LOG
        SectionCard(Modifier.testTag("dg_registers")) {
            Text(stringResource(R.string.dg_registers), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.dg_registers_hint), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.dg_record), Modifier.weight(1f))
                Switch(checked = d.registerRecording, onCheckedChange = vm::setRegisterRecording)
            }
            Text(stringResource(R.string.dg_records, d.registerRecords.toString()), style = MaterialTheme.typography.bodySmall)
            if (d.registerSummary.isNotEmpty()) {
                Text(RegisterQuality.entries.mapNotNull { q -> d.registerSummary[q]?.let { "${q.uiLabel}: $it" } }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
            val last = d.lastRegisters
            when {
                last == null -> Text(stringResource(R.string.dg_no_registers), style = MaterialTheme.typography.bodySmall)
                last.simulated -> Text(stringResource(R.string.dg_registers_sim), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                !last.communicationOk -> Text("${hm.format(last.timestamp)} · ${last.communication}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                last.samples.isEmpty() -> Text(stringResource(R.string.dg_no_registers), style = MaterialTheme.typography.bodySmall)
                else -> last.samples.forEach { s ->
                    Text("${s.address} ${s.name}: ${s.raw} → ${s.decoded?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "N/A"} ${s.unit} · ${s.quality.uiLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (s.quality == RegisterQuality.INVALID || s.quality == RegisterQuality.SUSPECTED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { csv.launch("anenji-registers.csv") }, enabled = d.registerRecords > 0) { Text("CSV") }
                OutlinedButton(onClick = { json.launch("anenji-registers.json") }, enabled = d.registerRecords > 0) { Text("JSON") }
                TextButton(onClick = vm::clearRegisterLog, enabled = d.registerRecords > 0) { Text(stringResource(R.string.dg_clear)) }
            }
            exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** Waterfall as horizontal bars: each loss as a share of the theoretical power (one hue, values as text). */
@Composable
private fun LossBreakdownCard(r: PvReality) {
    SectionCard {
        Text(stringResource(R.string.dg_losses), fontWeight = FontWeight.Bold)
        val max = r.losses.maxOf { abs(it.watts) }.coerceAtLeast(1.0)
        r.losses.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.step.uiLabel, Modifier.width(132.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    if (l.modelled && l.watts > 0) {
                        val color = if (l.step == LossStep.UNEXPLAINED) MaterialTheme.colorScheme.error else ChartColors.pv
                        Box(Modifier.fillMaxWidth((l.watts / max).toFloat().coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if (!l.modelled) stringResource(R.string.dg_not_modelled)
                    else "${String.format(java.util.Locale.ROOT, "%+.2f", -l.watts / 1000)} kW",
                    Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                )
            }
        }
        MetricRow(stringResource(R.string.dg_expected), Fmt.kw(r.expectedW), DataKind.ESTIMATED)
        r.actualW?.let { MetricRow(stringResource(R.string.dg_actual), Fmt.kw(it), r.actual?.kind ?: DataKind.UNKNOWN) }
    }
}

@Composable
private fun DiagnosisItem(d: Diagnosis, primary: Boolean) {
    val color = when (d.severity) {
        DiagnosisSeverity.CRITICAL -> MaterialTheme.colorScheme.error
        DiagnosisSeverity.WARNING -> MaterialTheme.colorScheme.tertiary
        DiagnosisSeverity.INFO -> MaterialTheme.colorScheme.onSurface
    }
    val level = when (d.severity) { DiagnosisSeverity.CRITICAL -> StatusLevel.CRITICAL; DiagnosisSeverity.WARNING -> StatusLevel.WARNING; DiagnosisSeverity.INFO -> StatusLevel.INFO }
    Column(Modifier.padding(top = 4.dp)) {
        StatusLabel(level, d.type.uiLabel, fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold, textColor = color,
            style = if (primary) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.dg_confidence, Fmt.conf(d.confidence)) + (d.impactW?.let { " · " + stringResource(R.string.dg_impact, Fmt.kw(it) ?: "") } ?: ""),
            style = MaterialTheme.typography.bodySmall)
        if (primary || d.severity != DiagnosisSeverity.INFO) d.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        if (!primary) Text("→ ${d.recommendation}", style = MaterialTheme.typography.bodySmall)
    }
}
