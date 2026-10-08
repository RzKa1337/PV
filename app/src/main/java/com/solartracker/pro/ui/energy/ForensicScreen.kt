package com.solartracker.pro.ui.energy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.R
import com.solartracker.pro.core.anenji.Certainty
import com.solartracker.pro.core.anenji.Channel
import com.solartracker.pro.core.anenji.DataStatus
import com.solartracker.pro.core.anenji.DiagnosisSeverityLevel
import com.solartracker.pro.core.anenji.EvidenceRole
import com.solartracker.pro.core.anenji.ForensicDiagnosis
import com.solartracker.pro.core.anenji.ForensicPeriod
import com.solartracker.pro.core.anenji.RankedIssue
import com.solartracker.pro.core.inverter.SmgRegisters
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.uiLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Centrum → "CO SIĘ STAŁO?": evidence-first forensic view. Conclusion first, then why (evidence), impact, when and what to
 * check; every diagnosis drills down Diagnosis → Evidence → Normalised data → Raw → Register → Source. Read-only.
 */
@Composable
fun ForensicScreen(vm: EnergyCenterViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val a by vm.analyzer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadAnalyzer() }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch {
            status = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { vm.exportForensicPackage(it) } ?: error(context.getString(R.string.ins_cannot_open)) }
                context.getString(R.string.fo_exported)
            }.getOrElse { context.getString(R.string.ins_export_failed, it.message ?: it.javaClass.simpleName) }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("forensic_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_energy_center)) }
        ScreenTitle(stringResource(R.string.fo_title), stringResource(R.string.fo_subtitle))
        Text(stringResource(R.string.an_read_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (a.report == null) {
            Button(onClick = { vm.runAnalysis(useImport = false) }, enabled = !a.running) { Text(stringResource(R.string.an_analyze_history)) }
            Text(stringResource(R.string.fo_import_hint), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ForensicPeriod.entries.filter { it != ForensicPeriod.CUSTOM }.forEach { p ->
                FilterChip(selected = a.forensicPeriod == p, onClick = { vm.runForensics(p) }, enabled = a.report != null, label = { Text(p.uiLabel) },
                    modifier = Modifier.testTag("fo_period_${p.name}"))
            }
        }
        if (a.report != null) {
            var from by rememberSaveable { mutableStateOf("") }
            var to by rememberSaveable { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(from, { from = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("2026-10-01") })
                OutlinedTextField(to, { to = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("2026-10-07") })
                FilledTonalButton(onClick = {
                    val f = runCatching { LocalDate.parse(from.trim()) }.getOrNull()
                    val t = runCatching { LocalDate.parse(to.trim()) }.getOrNull()
                    if (f != null && t != null && !t.isBefore(f)) vm.runForensics(ForensicPeriod.CUSTOM, f.atStartOfDay(zone).toInstant() to t.plusDays(1).atStartOfDay(zone).toInstant())
                    else status = context.getString(R.string.fo_bad_range)
                }) { Text(ForensicPeriod.CUSTOM.uiLabel) }
            }
        }
        if (a.running || a.forensicRunning) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(); Text(stringResource(R.string.an_running)) }
        a.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        val r = a.forensic
        if (r != null) {
            // 1. Summary: health, most important issue, confidence, why, impact, when, what to check.
            SectionCard(Modifier.testTag("fo_summary")) {
                if (r.simulated) Text(stringResource(R.string.an_simulated), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.fo_period, fmt.format(r.from), fmt.format(r.to)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.fo_data_status, r.status.status.uiLabel, r.status.note),
                    color = if (r.status.status == DataStatus.OK) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                a.report?.health?.overall?.let { Text(stringResource(R.string.fo_health, it.toString()), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                val top = r.mostImportant
                if (top == null) {
                    Text(if (r.status.status == DataStatus.NO_DATA) stringResource(R.string.fo_no_data) else stringResource(R.string.fo_no_issues), fontWeight = FontWeight.SemiBold)
                } else {
                    val d = top.diagnoses.minBy { it.certainty.ordinal }
                    Text(stringResource(R.string.fo_most_important), style = MaterialTheme.typography.labelMedium)
                    Text(top.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = severityColor(top.severity))
                    Text(stringResource(R.string.fo_certainty_line, d.observation.uiLabel, d.certainty.uiLabel, Fmt.conf(top.confidence)), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.fo_why), fontWeight = FontWeight.SemiBold)
                    d.rootCause.possible.filter { it.status == "wspierana" }.forEach { Text("✓ ${it.cause}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                    d.rootCause.eliminated.forEach { Text("✗ ${it.cause}: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.fo_impact), fontWeight = FontWeight.SemiBold)
                    Text(impactText(top), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.fo_when, fmt.format(top.firstSeen), fmt.format(top.lastSeen), top.occurrences.toString()), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.fo_check), fontWeight = FontWeight.SemiBold)
                    Text(d.recommendation, style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.fo_counts, (r.bySeverity[DiagnosisSeverityLevel.CRITICAL] ?: 0).toString(), (r.bySeverity[DiagnosisSeverityLevel.WARNING] ?: 0).toString(),
                    (r.bySeverity[DiagnosisSeverityLevel.INFO] ?: 0).toString()), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.fo_lost, Fmt.kwh(r.lostKwh) ?: "N/A", r.cost?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "N/A"), style = MaterialTheme.typography.bodySmall)
            }

            // 2. Ranking with drill-down.
            SectionCard(Modifier.testTag("fo_ranking")) {
                Text(stringResource(R.string.fo_ranking), fontWeight = FontWeight.Bold)
                if (r.ranking.isEmpty()) Text(stringResource(R.string.an_none))
                r.ranking.forEach { issue -> IssueRow(issue, fmt, onShowMoment = { vm.reconstructIncident(it) }) }
            }

            // 3. Moment reconstruction −60…+60 min.
            a.reconstruction?.let { rec ->
                SectionCard(Modifier.testTag("fo_moment")) {
                    Text(stringResource(R.string.fo_moment, fmt.format(rec.report.at)), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.fo_data_status, rec.status.status.uiLabel, rec.status.note), style = MaterialTheme.typography.labelSmall)
                    rec.snapshots.forEach { s ->
                        val x = s.sample
                        Text(String.format(Locale.ROOT, "%+4d min  ", s.offsetMinutes) + (x?.let {
                            listOfNotNull(it[Channel.PV_POWER]?.let { v -> "PV ${v.toInt()} W" }, it[Channel.LOAD_POWER]?.let { v -> "obc. ${v.toInt()} W" },
                                it[Channel.SOC]?.let { v -> "SOC ${v.toInt()}%" }, it[Channel.GRID_POWER]?.let { v -> "sieć ${v.toInt()} W" },
                                it[Channel.BATTERY_VOLTAGE]?.let { v -> String.format(Locale.ROOT, "%.1f V", v) }).joinToString(" · ")
                        } ?: s.status.uiLabel), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    rec.report.timeline.forEach { Text(it.text, style = MaterialTheme.typography.labelSmall) }
                    Text(stringResource(R.string.an_conclusion, rec.report.conclusion), fontWeight = FontWeight.SemiBold)
                    rec.diagnoses.take(5).forEach { Text("• ${it.title} (${it.observation.uiLabel})", style = MaterialTheme.typography.bodySmall) }
                }
            }

            // 4. 90-day trends.
            SectionCard(Modifier.testTag("fo_trends")) {
                Text(stringResource(R.string.fo_trends), fontWeight = FontWeight.Bold)
                a.trends90.forEach { t ->
                    Text("${t.metric}: " + if (t.sufficient) "${t.direction.label}" + (t.changePerMonth?.let { String.format(Locale.ROOT, " (%+.2f %s/mies.)", it, t.unit) } ?: "") +
                        " · ${t.weekly.size} tyg." else t.note, style = MaterialTheme.typography.bodySmall)
                }
            }

            // 5. Configuration forensics.
            if (a.configImpacts.isNotEmpty()) SectionCard(Modifier.testTag("fo_config")) {
                Text(stringResource(R.string.fo_config), fontWeight = FontWeight.Bold)
                a.configImpacts.forEach { c ->
                    Text("${c.change.key.label}: ${c.change.old.display} → ${c.change.new.display} (${fmt.format(c.change.seenAt)})", fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.fo_config_after, c.incidentsAfter.size.toString()) + c.incidentsAfter.take(3).joinToString(prefix = " ") { it.title },
                        style = MaterialTheme.typography.bodySmall)
                    c.before.forEach { (k, v) -> Text("$k: ${v?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"} → ${c.after[k]?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A"}",
                        style = MaterialTheme.typography.labelSmall) }
                    Text(c.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionCard {
                Text(stringResource(R.string.an_export), fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { export.launch("ANENJI_FORENSIC_PACKAGE.zip") }, modifier = Modifier.testTag("fo_export")) { Text(stringResource(R.string.fo_export)) }
            }
        }

        ValidationCard(vm, fmt)
    }
}

@Composable
private fun severityColor(s: DiagnosisSeverityLevel): Color = when (s) {
    DiagnosisSeverityLevel.CRITICAL -> MaterialTheme.colorScheme.error
    DiagnosisSeverityLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    DiagnosisSeverityLevel.INFO -> MaterialTheme.colorScheme.onSurface
}

private fun impactText(i: RankedIssue): String = listOfNotNull(
    i.energyKwh?.let { String.format(Locale.ROOT, "%.2f kWh", it) },
    i.cost?.let { String.format(Locale.ROOT, "%.2f zł", it) },
    i.downtime.takeIf { !it.isZero }?.let { "${it.toMinutes()} min" },
    i.diagnoses.firstNotNullOfOrNull { it.impact.batteryImpact },
).joinToString(" · ").ifEmpty { "N/A – " + (i.diagnoses.firstOrNull()?.impact?.basis ?: "") }

@Composable
private fun IssueRow(issue: RankedIssue, fmt: DateTimeFormatter, onShowMoment: (java.time.Instant) -> Unit) {
    var open by rememberSaveable(issue.type.name) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp).testTag("fo_issue_${issue.type.name}")) {
        Text("${issue.severity.uiLabel} · ${issue.title}", fontWeight = FontWeight.SemiBold, color = severityColor(issue.severity))
        Text("×${issue.occurrences} · ${issue.certainty.uiLabel} · ${Fmt.conf(issue.confidence)} · " + (issue.energyKwh?.let { String.format(Locale.ROOT, "%.2f kWh", it) } ?: "N/A"),
            style = MaterialTheme.typography.bodySmall)
        if (open) issue.diagnoses.take(10).forEach { d -> DiagnosisDrillDown(d, fmt, onShowMoment) }
    }
}

/** Diagnosis → Evidence → Normalised → Raw → Register → Source. */
@Composable
private fun DiagnosisDrillDown(d: ForensicDiagnosis, fmt: DateTimeFormatter, onShowMoment: (java.time.Instant) -> Unit) {
    var level by rememberSaveable(d.id + d.anomaly.start) { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${fmt.format(d.anomaly.start)} – ${fmt.format(d.anomaly.end)} · ${d.anomaly.detail}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.fo_certainty_line, d.observation.uiLabel, d.certainty.uiLabel, Fmt.conf(d.confidence.value)), style = MaterialTheme.typography.labelSmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(R.string.fo_l_evidence, R.string.fo_l_normalized, R.string.fo_l_raw, R.string.fo_l_register, R.string.fo_l_source).forEachIndexed { i, label ->
                FilterChip(selected = level == i + 1, onClick = { level = if (level == i + 1) 0 else i + 1 }, label = { Text(stringResource(label)) })
            }
            TextButton(onClick = { onShowMoment(d.anomaly.start) }) { Text(stringResource(R.string.fo_show_moment)) }
        }
        val small = MaterialTheme.typography.labelSmall
        when (level) {
            1 -> {
                d.evidence.nodes.forEach { n ->
                    val mark = when (n.role) { EvidenceRole.SUPPORTS -> "✓"; EvidenceRole.CONTRADICTS -> "✗"; EvidenceRole.CONTEXT -> "·"; EvidenceRole.UNKNOWN -> "?" }
                    Text("$mark ${n.label}: ${n.valueText}" + (n.expectedValue?.let { " (oczek. ${it.toInt()})" } ?: "") + " [${n.status}] · ${n.source.name}", style = small)
                }
                d.confidence.describe().forEach { Text("  $it", style = small) }
                if (d.rootCause.certainty == Certainty.INSUFFICIENT_DATA) Text(stringResource(R.string.fo_cause_unknown), style = small)
            }
            2 -> d.anomaly.samples.take(12).forEach { s ->
                Text("${fmt.format(s.time)} " + s.values.entries.take(8).joinToString { "${it.key.name}=${String.format(Locale.ROOT, "%.1f", it.value)}" } + " · ${s.origin.name}",
                    style = small, fontFamily = FontFamily.Monospace)
            }
            3 -> d.anomaly.samples.take(12).forEach { s ->
                val raw = s.raw
                Text(if (raw == null || raw.fields.isEmpty()) "${fmt.format(s.time)} — ${stringResource(R.string.fo_no_raw)}" else
                    "${raw.rawTimestamp ?: fmt.format(s.time)} " + raw.fields.entries.take(10).joinToString { "${it.key}=${it.value}" },
                    style = small, fontFamily = FontFamily.Monospace)
            }
            4 -> d.anomaly.samples.take(12).forEach { s ->
                val regs = s.raw?.registers.orEmpty()
                Text("${fmt.format(s.time)} " + if (regs.isEmpty()) stringResource(R.string.fo_no_registers) else
                    regs.entries.take(12).joinToString { "0x${it.key.toString(16).uppercase()}(${it.key})=${it.value}" }, style = small, fontFamily = FontFamily.Monospace)
            }
            5 -> d.anomaly.samples.mapNotNull { it.raw }.distinctBy { it.source to it.locator }.take(12).ifEmpty { null }?.forEach { raw ->
                Text("${raw.source} · ${raw.locator}", style = small, fontFamily = FontFamily.Monospace)
            } ?: Text(stringResource(R.string.fo_no_raw), style = small)
        }
    }
}

/** Validation mode: enter what the inverter display shows; never VERIFIED from documentation or the simulator. */
@Composable
private fun ValidationCard(vm: EnergyCenterViewModel, fmt: DateTimeFormatter) {
    val a by vm.analyzer.collectAsStateWithLifecycle()
    val specs = remember { SmgRegisters.LIVE }
    var selected by rememberSaveable { mutableStateOf(specs.firstOrNull()?.address ?: 0) }
    var value by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val spec = specs.firstOrNull { it.address == selected }
    var unit by rememberSaveable(selected) { mutableStateOf(spec?.unit ?: "") }
    SectionCard(Modifier.testTag("fo_validation")) {
        Text(stringResource(R.string.fo_validation), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.fo_validation_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            specs.forEach { s -> FilterChip(selected = s.address == selected, onClick = { selected = s.address }, label = { Text(s.name) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value, { value = it }, Modifier.weight(1f), singleLine = true, label = { Text(stringResource(R.string.fo_display_value)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(unit, { unit = it }, Modifier.weight(0.5f), singleLine = true, label = { Text(stringResource(R.string.fo_unit)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                val v = value.replace(',', '.').trim().toDoubleOrNull()
                message = if (v == null) "?" else vm.addReference(selected, v, unit)
                if (message == null) value = ""
            }) { Text(stringResource(R.string.fo_save_reference)) }
            if (a.references.isNotEmpty()) OutlinedButton(onClick = vm::clearReferences) { Text(stringResource(R.string.fo_clear_references)) }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        a.validation.forEach { e ->
            val name = specs.firstOrNull { it.address == e.address }?.name ?: e.address.toString()
            Text("0x${e.address.toString(16).uppercase()} $name: ${e.status.uiLabel}", fontWeight = FontWeight.SemiBold)
            Text(e.reason, style = MaterialTheme.typography.labelSmall)
            e.comparisons.takeLast(5).forEach { c ->
                Text("${fmt.format(c.reference.manualReferenceTimestamp)} raw ${c.reference.rawValue} → ${String.format(Locale.ROOT, "%.2f", c.reference.decodedValue)} vs " +
                    "${c.reference.manualReferenceValue} ${c.reference.manualReferenceUnit}: ${c.verdict.name}" + (c.hint?.let { " · $it" } ?: "") +
                    if (!c.reference.fromRealDevice) " · SIMULATED" else "", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
