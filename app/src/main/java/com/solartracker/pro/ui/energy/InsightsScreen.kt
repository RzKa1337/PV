package com.solartracker.pro.ui.energy

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.analytics.HistoryPeriod
import com.solartracker.pro.core.ems.WindowKind
import com.solartracker.pro.core.health.FaultLevel
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val hmFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val dayFmt = DateTimeFormatter.ofPattern("EEE d.MM", Locale.forLanguageTag("pl"))

private fun f(v: Double, d: Int = 1) = String.format(Locale.ROOT, "%.${d}f", v)

@Composable
fun InsightsScreen(vm: EnergyCenterViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val insights by vm.insights.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    var period by rememberSaveable { mutableStateOf(HistoryPeriod.DAY) }

    fun export(uri: Uri?, json: Boolean) {
        if (uri == null) return
        scope.launch {
            exportStatus = runCatching {
                val text = vm.exportHistory(json)
                writeText(context, uri, text)
                "Zapisano eksport (${text.length / 1024 + 1} kB)"
            }.getOrElse { "Eksport nieudany: ${it.message ?: it.javaClass.simpleName}" }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, false) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, true) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            exportStatus = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { vm.exportPdf(it) } ?: error("Nie można otworzyć pliku")
                }
                "Zapisano raport PDF"
            }.getOrElse { "Eksport nieudany: ${it.message ?: it.javaClass.simpleName}" }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← Centrum energii") }
        ScreenTitle("Analizy", "Zdrowie instalacji, ostrzeżenia, EMS, historia")

        // HEALTH
        SectionCard(Modifier.testTag("health_card")) {
            Text("ZDROWIE INSTALACJI", fontWeight = FontWeight.Bold)
            val h = insights.health
            if (h == null) {
                Text(insights.healthReason ?: "Brak danych", style = MaterialTheme.typography.bodyMedium)
                KindBadge(DataKind.UNAVAILABLE)
            } else {
                Text(h.score?.let { "$it / 100" } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(h.explanation(), style = MaterialTheme.typography.bodyMedium)
                h.deductions.forEach { d -> Text("−${d.points} ${d.category}: ${d.reason} (${d.evidence})", style = MaterialTheme.typography.bodySmall) }
                if (h.unknowns.isNotEmpty()) Text("Nie oceniono: ${h.unknowns.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("Pewność ${Fmt.conf(h.confidence)}", style = MaterialTheme.typography.bodySmall)
                KindBadge(h.kind)
            }
        }

        // FORECAST ACCURACY
        SectionCard(Modifier.testTag("accuracy_card")) {
            Text("DOKŁADNOŚĆ PROGNOZ (30 DNI)", fontWeight = FontWeight.Bold)
            val reports = insights.accuracy.filterValues { it.count > 0 }
            if (reports.isEmpty()) {
                Text("Brak danych: potrzebne są zapisane prognozy i pełne godziny pomiarów z falownika.", style = MaterialTheme.typography.bodyMedium)
                KindBadge(DataKind.UNAVAILABLE)
            }
            reports.forEach { (horizon, r) ->
                Text("Prognoza ${horizon.label} · ${r.count} h", fontWeight = FontWeight.SemiBold)
                Text(r.describe(), style = MaterialTheme.typography.bodyMedium)
                Text("MAE ${f(r.mae, 2)} kWh · RMSE ${f(r.rmse, 2)} kWh" + (r.mapePercent?.let { " · MAPE ${f(it, 0)}%" } ?: "") +
                    (r.biasPercent?.let { " · błąd systematyczny ${if (it >= 0) "+" else ""}${f(it)}%" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            if (reports.isNotEmpty()) KindBadge(DataKind.CALCULATED)
        }

        // CALIBRATION 3.0
        val modelState by vm.model.collectAsStateWithLifecycle()
        modelState.calibrationModel?.let { cm ->
            SectionCard(Modifier.testTag("calibration_card")) {
                Text("KALIBRACJA PROGNOZ", fontWeight = FontWeight.Bold)
                Text(cm.describe(), style = MaterialTheme.typography.bodyMedium)
                cm.validation?.let { v ->
                    Text("Sprawdzenie na ostatnich dniach (${v.samples} próbek): MAE ${f(v.maeBefore, 2)} → ${f(v.maeAfter, 2)} kW · " +
                        "RMSE ${f(v.rmseBefore, 2)} → ${f(v.rmseAfter, 2)} kW · błąd syst. ${f(v.biasBefore, 2)} → ${f(v.biasAfter, 2)} kW",
                        style = MaterialTheme.typography.bodySmall)
                }
                cm.buckets.filter { it.dimension == "condition" }.forEach { b ->
                    val label = com.solartracker.pro.core.analytics.SkyCondition.entries.firstOrNull { it.name == b.key }?.label ?: b.key
                    Text("$label: ×${f(b.factor, 2)} (${b.samples} próbek)", style = MaterialTheme.typography.bodySmall)
                }
                if (cm.excluded.isNotEmpty()) {
                    Text("Pominięte w nauce: " + cm.excluded.entries.joinToString { "${it.key} (${it.value})" }, style = MaterialTheme.typography.bodySmall)
                }
                KindBadge(DataKind.CALCULATED)
            }
        }

        // EARLY WARNINGS
        SectionCard {
            Text("OSTRZEŻENIA PREDYKCYJNE", fontWeight = FontWeight.Bold)
            if (insights.faults.isEmpty()) Text("Brak sygnałów ostrzegawczych w danych z ostatnich dni.", style = MaterialTheme.typography.bodyMedium)
            insights.faults.forEach { w ->
                Text("${w.level.label}: ${w.title}", fontWeight = FontWeight.SemiBold,
                    color = if (w.level == FaultLevel.FAULT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(w.reason, style = MaterialTheme.typography.bodySmall)
                w.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text("Zalecenie: ${w.recommendation} · pewność ${Fmt.conf(w.confidence)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        // EMS
        SectionCard(Modifier.testTag("ems_card")) {
            Text("ZARZĄDZANIE ENERGIĄ (EMS)", fontWeight = FontWeight.Bold)
            Text("Tylko zalecenia — aplikacja niczego nie przełącza.", style = MaterialTheme.typography.bodySmall)
            val ems = insights.ems
            if (ems == null) Text("Prognoza jeszcze się liczy…") else {
                ems.decisions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                ems.windows.take(6).forEach { w ->
                    val label = if (w.kind == WindowKind.SURPLUS) "Nadwyżka" else "Niedobór"
                    Text("$label ${hmFmt.format(w.start)}–${hmFmt.format(w.end)}: ${f(w.energyKwh)} kWh (szczyt ${f(w.peakKw)} kW)", style = MaterialTheme.typography.bodySmall)
                }
                ems.minSocPercent?.let { Text("Najniższy SOC: ${f(it, 0)}% ok. ${ems.minSocAt?.let(hmFmt::format) ?: "—"}", style = MaterialTheme.typography.bodySmall) }
                if (ems.recommendations.isEmpty()) Text("Dodaj odbiorniki (pralka, bojler…) w Konfiguracji, aby dostać godziny uruchomienia.", style = MaterialTheme.typography.bodySmall)
                ems.generator?.let { g ->
                    Text("Agregat: start ok. ${hmFmt.format(g.start)}, ${f(g.hours)} h, ${f(g.energyKwh)} kWh" + (g.fuelLiters?.let { " · ok. ${f(it)} l paliwa" } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                KindBadge(DataKind.FORECAST)
            }
            if (insights.periods.isNotEmpty()) {
                Text("Reszta dnia", fontWeight = FontWeight.SemiBold)
                insights.periods.forEach { p ->
                    Text("${p.label}: PV ${f(p.pvKwh)} kWh, zużycie ${f(p.loadKwh)} kWh → ${if (p.balanceKwh >= 0) "+" else ""}${f(p.balanceKwh)} kWh", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 7 DAYS
        if (insights.week.isNotEmpty()) SectionCard {
            Text("BILANS 7 DNI", fontWeight = FontWeight.Bold)
            insights.week.forEach { d ->
                Text("${dayFmt.format(d.date)}: PV ${f(d.pvKwh)} / zużycie ${f(d.loadKwh)} kWh → ${if (d.surplus) "nadwyżka" else "deficyt"} ${f(kotlin.math.abs(d.balanceKwh))} kWh · pewność ${Fmt.conf(d.confidence)}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("Dalsze dni bez prognozy pogody liczone są z klimatu — niższa pewność.", style = MaterialTheme.typography.bodySmall)
            KindBadge(DataKind.FORECAST)
        }

        // HISTORY
        SectionCard(Modifier.testTag("history_card")) {
            Text("HISTORIA (POMIARY)", fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryPeriod.entries.forEach { p -> FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.label) }) }
            }
            val rows = insights.totals[period].orEmpty().takeLast(14).reversed()
            if (rows.isEmpty()) Text("Brak zapisanej historii z falownika.", style = MaterialTheme.typography.bodyMedium)
            rows.forEach { t ->
                Text(t.periodStart.toString(), fontWeight = FontWeight.SemiBold)
                Text("PV ${f(t.pvKwh, 2)} kWh · zużycie ${f(t.loadKwh, 2)} kWh · sieć +${f(t.gridImportKwh, 2)}/−${f(t.gridExportKwh, 2)} kWh", style = MaterialTheme.typography.bodySmall)
                Text("Autokonsumpcja ${t.selfConsumption?.let { "${f(it * 100, 0)}%" } ?: "—"} · autarkia ${t.autarky?.let { "${f(it * 100, 0)}%" } ?: "—"} · kompletność danych ${f(t.coverage * 100, 0)}%",
                    style = MaterialTheme.typography.bodySmall)
            }
            KindBadge(DataKind.CALCULATED)
        }

        // EXPORT
        SectionCard {
            Text("EKSPORT", fontWeight = FontWeight.Bold)
            Text("Zapis do wybranego pliku (bez wysyłania do sieci). Brakujące wartości zostają puste.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { csvLauncher.launch("solar-history.csv") }) { Text("CSV") }
                OutlinedButton(onClick = { jsonLauncher.launch("solar-history.json") }) { Text("JSON") }
                OutlinedButton(onClick = { pdfLauncher.launch("solar-report.pdf") }) { Text("PDF") }
            }
            exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        ?: error("Nie można otworzyć pliku")
}
