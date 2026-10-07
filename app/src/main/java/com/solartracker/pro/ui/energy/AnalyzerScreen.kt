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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.R
import com.solartracker.pro.core.anenji.AnenjiSettingsDiff
import com.solartracker.pro.core.anenji.Finding
import com.solartracker.pro.core.anenji.FindingSeverity
import com.solartracker.pro.core.anenji.SettingStatus
import com.solartracker.pro.core.anenji.TrendWindow
import com.solartracker.pro.core.anenji.WhyQuestion
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Centrum → Analiza Anenji: deep, read-only analysis of the stored history or an imported log. */
@Composable
fun AnalyzerScreen(vm: EnergyCenterViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val a by vm.analyzer.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val fmt = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.loadAnalyzer() }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
            val name = uri.lastPathSegment?.substringAfterLast('/')
            if (text == null) status = context.getString(R.string.ins_cannot_open) else vm.importLog(text, name)
        }
    }
    @Composable
    fun exporter(format: String, mime: String) = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(mime)) { uri ->
        if (uri != null) scope.launch {
            status = runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { vm.exportAnalyzerReport(format, it) } ?: error(context.getString(R.string.ins_cannot_open)) }
                context.getString(R.string.ins_pdf_saved)
            }.getOrElse { context.getString(R.string.ins_export_failed, it.message ?: it.javaClass.simpleName) }
        }
    }
    val json = exporter("json", "application/json")
    val csv = exporter("csv", "text/csv")
    val pdf = exporter("pdf", "application/pdf")

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("analyzer_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_energy_center)) }
        ScreenTitle(stringResource(R.string.an_title), stringResource(R.string.an_subtitle))
        Text(stringResource(R.string.an_read_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runAnalysis(useImport = false) }, enabled = !a.running) { Text(stringResource(R.string.an_analyze_history)) }
            OutlinedButton(onClick = { importer.launch(arrayOf("text/*", "application/json", "application/octet-stream")) }, enabled = !a.running) {
                Text(stringResource(R.string.an_import))
            }
        }
        if (a.running) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { CircularProgressIndicator(); Text(stringResource(R.string.an_running)) }
        a.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        a.importSummary?.let { s ->
            SectionCard {
                Text(s, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                if (a.importIssues.isNotEmpty()) {
                    Text(stringResource(R.string.an_import_issues), fontWeight = FontWeight.SemiBold)
                    a.importIssues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        val r = a.report
        if (r != null) {
            SectionCard(Modifier.testTag("an_health")) {
                Text(stringResource(R.string.an_source, r.origin.label, r.from?.let(fmt::format) ?: "—", r.to?.let(fmt::format) ?: "—"), style = MaterialTheme.typography.bodySmall)
                if (r.simulated) Text(stringResource(R.string.an_simulated), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                if (r.excludedSimulatorSamples > 0 && !r.simulated) Text(stringResource(R.string.an_excluded, r.excludedSimulatorSamples.toString()), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.an_health), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.an_overall, r.health.overall?.toString() ?: "N/A"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                r.health.categories.forEach { c ->
                    MetricRow(c.category.label, c.score?.let { "$it" } ?: "N/A", if (c.score == null) DataKind.UNAVAILABLE else DataKind.CALCULATED)
                    val why = if (c.deductions.isEmpty()) c.basis else c.deductions.joinToString { "${it.reason} −${it.points}" }
                    Text(why, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(r.health.explanation, style = MaterialTheme.typography.labelSmall)
            }
            val all = r.anomalies + r.findings
            FindingsCard(stringResource(R.string.an_critical), all.filter { it.severity == FindingSeverity.CRITICAL })
            FindingsCard(stringResource(R.string.an_warnings), all.filter { it.severity == FindingSeverity.WARNING })
            FindingsCard(stringResource(R.string.an_info), all.filter { it.severity == FindingSeverity.INFO })

            SectionCard {
                Text(stringResource(R.string.an_events), fontWeight = FontWeight.Bold)
                if (r.patterns.isEmpty() && r.events.isEmpty()) Text(stringResource(R.string.an_none))
                r.patterns.forEach { p ->
                    Text("${p.description}: ${p.occurrences}×" + (p.dominantWindow?.let { w -> " · ${(p.windowShare * 100).toInt()}% w $w" } ?: ""), fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(p.precedingSocMedian?.let { "SOC przed ~${it.toInt()}%" }, p.averageDuration?.let { "średnio ${it.toMinutes()} min" }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                    Text("→ ${p.likelyCause} (${stringResource(R.string.an_confidence, Fmt.conf(p.confidence))})", style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard {
                Text(stringResource(R.string.an_comm), fontWeight = FontWeight.Bold)
                val c = r.communication
                MetricRow(stringResource(R.string.an_comm), c.score?.let { "$it / 100" }, DataKind.CALCULATED)
                Text(stringResource(R.string.an_comm_line, c.timeouts.toString(), c.crcErrors.toString(), c.invalidFrames.toString(), c.missingSamples.toString(),
                    c.longestOutage.seconds.toString()), style = MaterialTheme.typography.bodySmall)
            }

            SectionCard {
                Text(stringResource(R.string.an_trends), fontWeight = FontWeight.Bold)
                val key = r.trends.filter { it.window == TrendWindow.D7 || it.window == TrendWindow.D30 }
                if (key.isEmpty()) Text(stringResource(R.string.an_none))
                key.forEach { t ->
                    Text("${t.channel.label} (${t.window.label}): śr. ${"%.1f".format(t.average)} · P95 ${"%.1f".format(t.p95)} · min ${"%.1f".format(t.min)} · max ${"%.1f".format(t.max)} ${t.channel.unit} · ${t.direction.label}" +
                        if (t.anomalies.isNotEmpty()) " · anomalie ${t.anomalies.size}" else "", style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.an_energy, Fmt.kwh(r.energy.pvKwh) ?: "—", Fmt.kwh(r.energy.loadKwh) ?: "—", Fmt.kwh(r.energy.gridImportKwh) ?: "—"),
                    style = MaterialTheme.typography.bodySmall)
            }

            // WHAT HAPPENED?
            SectionCard(Modifier.testTag("an_incident")) {
                Text(stringResource(R.string.an_incident), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.an_incident_hint), style = MaterialTheme.typography.bodySmall)
                var time by rememberSaveable { mutableStateOf("") }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.events.filter { it.severity.name != "INFO" }.takeLast(8).reversed().forEach { e ->
                        FilterChip(selected = false, onClick = { time = fmt.format(e.start); vm.analyzeIncident(e.start) }, label = { Text("${fmt.format(e.start).substring(5)} ${e.description.take(24)}") })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(time, { time = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("2026-10-07 06:42") })
                    FilledTonalButton(onClick = {
                        runCatching { LocalDateTime.parse(time.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")).atZone(zone).toInstant() }.getOrNull()?.let(vm::analyzeIncident)
                    }) { Text(stringResource(R.string.an_analyze)) }
                }
                a.incident?.let { i ->
                    i.timeline.forEach { Text(it.text, style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.an_conclusion, i.conclusion), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.an_confidence, Fmt.conf(i.confidence)), style = MaterialTheme.typography.bodySmall)
                }
            }

            // WHY?
            SectionCard(Modifier.testTag("an_why")) {
                Text(stringResource(R.string.an_why), fontWeight = FontWeight.Bold)
                var q by rememberSaveable { mutableStateOf("") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text(stringResource(R.string.an_why_hint)) })
                    FilledTonalButton(onClick = { vm.ask(q) }) { Text(stringResource(R.string.an_ask)) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    WhyQuestion.entries.forEach { w -> FilterChip(selected = a.why?.question == w, onClick = { vm.ask(null, w) }, label = { Text(w.label) }) }
                }
                a.why?.let { w ->
                    Text("${w.question.label} (${w.date})", fontWeight = FontWeight.SemiBold)
                    w.findings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    w.incident?.timeline?.forEach { Text(it.text, style = MaterialTheme.typography.labelSmall) }
                    Text(stringResource(R.string.an_conclusion, w.conclusion), fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.an_confidence, Fmt.conf(w.confidence)), style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard {
                Text(stringResource(R.string.an_quality), fontWeight = FontWeight.Bold)
                val dq = r.dataQuality
                Text(stringResource(R.string.an_quality_line, dq.samples.toString(), Fmt.pct(dq.coverage * 100) ?: "—", String.format(java.util.Locale.ROOT, "%.1f%%", dq.invalidShare * 100)),
                    style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.an_unresolved), fontWeight = FontWeight.SemiBold)
                r.unresolved.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }

            SectionCard {
                Text(stringResource(R.string.an_export), fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { json.launch("ANENJI_FULL_DIAGNOSTIC_REPORT.json") }) { Text("JSON") }
                    OutlinedButton(onClick = { csv.launch("ANENJI_FULL_DIAGNOSTIC_REPORT.csv") }) { Text("CSV") }
                    OutlinedButton(onClick = { pdf.launch("ANENJI_FULL_DIAGNOSTIC_REPORT.pdf") }) { Text("PDF") }
                }
            }
        }

        // CONFIGURATION SNAPSHOTS
        SectionCard(Modifier.testTag("an_config")) {
            Text(stringResource(R.string.an_config), fontWeight = FontWeight.Bold)
            FilledTonalButton(onClick = vm::takeSnapshot) { Text(stringResource(R.string.an_snapshot)) }
            val snaps = a.snapshots
            if (snaps.isEmpty()) Text(stringResource(R.string.an_no_snapshots))
            snaps.lastOrNull()?.let { s ->
                Text(s.format(zone).lines().filterNot { it.endsWith("NOT_AVAILABLE") }.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                val na = s.values.count { it.status == SettingStatus.NOT_AVAILABLE }
                if (na > 0) Text("NOT_AVAILABLE: $na / ${s.values.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (snaps.size >= 2) Text(AnenjiSettingsDiff.timeline(snaps).describe(zone), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FindingsCard(title: String, list: List<Finding>) {
    SectionCard {
        Text(title, fontWeight = FontWeight.Bold)
        if (list.isEmpty()) Text(stringResource(R.string.an_none))
        list.forEach { f ->
            var open by rememberSaveable(f.title) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp)) {
                Text(f.title, fontWeight = FontWeight.SemiBold, color = if (f.severity == FindingSeverity.INFO) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                Text("${f.reason} · ${stringResource(R.string.an_confidence, Fmt.conf(f.confidence))}", style = MaterialTheme.typography.bodySmall)
                if (open) {
                    f.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Wpływ: ${f.impact}", style = MaterialTheme.typography.bodySmall)
                    if (f.possibleCauses.isNotEmpty()) Text("Możliwe przyczyny: ${f.possibleCauses.joinToString()}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
