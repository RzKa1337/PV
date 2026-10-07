package com.solartracker.pro.ui.energy

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.analytics.HistoryPeriod
import com.solartracker.pro.core.ems.WindowKind
import com.solartracker.pro.core.health.FaultLevel
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.uiLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val hmFmt = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private fun dayFmt() = DateTimeFormatter.ofPattern("EEE d.MM", Format.locale)

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
                context.getString(R.string.ins_export_saved, (text.length / 1024 + 1).toString())
            }.getOrElse { context.getString(R.string.ins_export_failed, it.message ?: it.javaClass.simpleName) }
        }
    }
    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { export(it, false) }
    val jsonLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { export(it, true) }
    val pdfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch {
            exportStatus = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { vm.exportPdf(it) } ?: error(context.getString(R.string.ins_cannot_open))
                }
                context.getString(R.string.ins_pdf_saved)
            }.getOrElse { context.getString(R.string.ins_export_failed, it.message ?: it.javaClass.simpleName) }
        }
    }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_energy_center)) }
        ScreenTitle(stringResource(R.string.ins_title), stringResource(R.string.ins_subtitle))

        // HEALTH
        SectionCard(Modifier.testTag("health_card")) {
            Text(stringResource(R.string.ins_health), fontWeight = FontWeight.Bold)
            val h = insights.health
            if (h == null) {
                Text(insights.healthReason ?: stringResource(R.string.ins_no_data), style = MaterialTheme.typography.bodyMedium)
                KindBadge(DataKind.UNAVAILABLE)
            } else {
                Text(h.score?.let { "$it / 100" } ?: "—", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(h.explanation(), style = MaterialTheme.typography.bodyMedium)
                h.deductions.forEach { d -> Text("−${d.points} ${d.category}: ${d.reason} (${d.evidence})", style = MaterialTheme.typography.bodySmall) }
                if (h.unknowns.isNotEmpty()) Text(stringResource(R.string.ins_not_rated, h.unknowns.joinToString()), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ins_confidence, Fmt.conf(h.confidence)), style = MaterialTheme.typography.bodySmall)
                KindBadge(h.kind)
            }
        }

        val forecastState by vm.forecast.collectAsStateWithLifecycle()
        DailyReportCard(insights.dailyToday, insights.dailyYesterday)
        TwinCard(insights.twin)
        OutlookCard(forecastState.outlook)

        // FORECAST ACCURACY
        SectionCard(Modifier.testTag("accuracy_card")) {
            Text(stringResource(R.string.ins_accuracy_title), fontWeight = FontWeight.Bold)
            insights.todayAccuracy?.accuracyPercent?.let { a ->
                Text(stringResource(R.string.ins_today_accuracy, f(a, 0)), fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
            val reports = insights.accuracy.filterValues { it.count > 0 }
            if (reports.isEmpty()) {
                Text(stringResource(R.string.ins_accuracy_no_data), style = MaterialTheme.typography.bodyMedium)
                KindBadge(DataKind.UNAVAILABLE)
            }
            reports.forEach { (horizon, r) ->
                Text(stringResource(R.string.ins_forecast_horizon, horizon.label, r.count.toString()), fontWeight = FontWeight.SemiBold)
                Text(r.describe(), style = MaterialTheme.typography.bodyMedium)
                Text("MAE ${f(r.mae, 2)} kWh · RMSE ${f(r.rmse, 2)} kWh" + (r.mapePercent?.let { " · MAPE ${f(it, 0)}%" } ?: "") +
                    (r.biasPercent?.let { stringResource(R.string.ins_bias_long, (if (it >= 0) "+" else "") + f(it)) } ?: "") +
                    (r.r2?.let { " · R² ${f(it, 2)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            insights.periodAccuracy.filterValues { it.count > 0 }.forEach { (period, r) ->
                Text(stringResource(R.string.ins_period_accuracy, period.uiLabel, r.count.toString(), r.accuracyPercent?.let { f(it, 0) + "%" } ?: "—") +
                    (r.biasPercent?.let { stringResource(R.string.ins_bias_short, (if (it >= 0) "+" else "") + f(it)) } ?: ""), style = MaterialTheme.typography.bodySmall)
            }
            if (reports.isNotEmpty()) KindBadge(DataKind.CALCULATED)
        }

        // CALIBRATION 3.0
        val modelState by vm.model.collectAsStateWithLifecycle()
        modelState.calibrationModel?.let { cm ->
            SectionCard(Modifier.testTag("calibration_card")) {
                Text(stringResource(R.string.ins_calibration), fontWeight = FontWeight.Bold)
                Text(cm.describe(), style = MaterialTheme.typography.bodyMedium)
                cm.validation?.let { v ->
                    Text(stringResource(R.string.ins_validation, v.samples.toString(), f(v.maeBefore, 2), f(v.maeAfter, 2), f(v.rmseBefore, 2), f(v.rmseAfter, 2), f(v.biasBefore, 2), f(v.biasAfter, 2)),
                        style = MaterialTheme.typography.bodySmall)
                }
                cm.buckets.filter { it.dimension == "condition" }.forEach { b ->
                    val label = com.solartracker.pro.core.analytics.SkyCondition.entries.firstOrNull { it.name == b.key }?.uiLabel ?: b.key
                    Text(stringResource(R.string.ins_bucket, label, f(b.factor, 2), b.samples.toString()), style = MaterialTheme.typography.bodySmall)
                }
                if (cm.excluded.isNotEmpty()) {
                    Text(stringResource(R.string.ins_excluded) + cm.excluded.entries.joinToString { "${it.key} (${it.value})" }, style = MaterialTheme.typography.bodySmall)
                }
                KindBadge(DataKind.CALCULATED)
            }
        }

        // EARLY WARNINGS
        SectionCard {
            Text(stringResource(R.string.ins_warnings), fontWeight = FontWeight.Bold)
            if (insights.faults.isEmpty()) Text(stringResource(R.string.ins_no_warnings), style = MaterialTheme.typography.bodyMedium)
            insights.faults.forEach { w ->
                Text("${w.level.label}: ${w.title}", fontWeight = FontWeight.SemiBold,
                    color = if (w.level == FaultLevel.FAULT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(w.reason, style = MaterialTheme.typography.bodySmall)
                w.evidence.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.ins_recommendation, w.recommendation, Fmt.conf(w.confidence)), style = MaterialTheme.typography.bodySmall)
            }
        }

        // EMS
        SectionCard(Modifier.testTag("ems_card")) {
            Text(stringResource(R.string.ins_ems), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.ins_ems_hint), style = MaterialTheme.typography.bodySmall)
            val ems = insights.ems
            if (ems == null) Text(stringResource(R.string.ins_ems_computing)) else {
                ems.decisions.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
                ems.windows.take(6).forEach { w ->
                    val label = if (w.kind == WindowKind.SURPLUS) stringResource(R.string.ins_surplus) else stringResource(R.string.ins_deficit)
                    Text(stringResource(R.string.ins_window, label, hmFmt.format(w.start), hmFmt.format(w.end), f(w.energyKwh), f(w.peakKw)), style = MaterialTheme.typography.bodySmall)
                }
                ems.minSocPercent?.let { Text(stringResource(R.string.ins_min_soc, f(it, 0), ems.minSocAt?.let(hmFmt::format) ?: "—"), style = MaterialTheme.typography.bodySmall) }
                if (ems.recommendations.isEmpty()) Text(stringResource(R.string.ins_add_loads), style = MaterialTheme.typography.bodySmall)
                ems.generator?.let { g ->
                    Text(stringResource(R.string.ins_generator, hmFmt.format(g.start), f(g.hours), f(g.energyKwh)) + (g.fuelLiters?.let { stringResource(R.string.ins_fuel, f(it)) } ?: ""), style = MaterialTheme.typography.bodySmall)
                }
                KindBadge(DataKind.FORECAST)
            }
            if (insights.periods.isNotEmpty()) {
                Text(stringResource(R.string.ins_rest_of_day), fontWeight = FontWeight.SemiBold)
                insights.periods.forEach { p ->
                    Text(stringResource(R.string.ins_period_balance, p.label, f(p.pvKwh), f(p.loadKwh), if (p.balanceKwh >= 0) "+" else "", f(p.balanceKwh)), style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // 7 DAYS
        if (insights.week.isNotEmpty()) SectionCard {
            Text(stringResource(R.string.ins_week), fontWeight = FontWeight.Bold)
            insights.week.forEach { d ->
                Text(stringResource(R.string.ins_week_row, dayFmt().format(d.date), f(d.pvKwh), f(d.loadKwh), stringResource(if (d.surplus) R.string.ins_surplus_lower else R.string.ins_deficit_lower), f(kotlin.math.abs(d.balanceKwh)), Fmt.conf(d.confidence)),
                    style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.ins_week_hint), style = MaterialTheme.typography.bodySmall)
            KindBadge(DataKind.FORECAST)
        }

        // HISTORY
        SectionCard(Modifier.testTag("history_card")) {
            Text(stringResource(R.string.ins_history), fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryPeriod.entries.forEach { p -> FilterChip(selected = p == period, onClick = { period = p }, label = { Text(p.uiLabel) }) }
            }
            val rows = insights.totals[period].orEmpty().takeLast(14).reversed()
            if (rows.isEmpty()) Text(stringResource(R.string.ins_no_history), style = MaterialTheme.typography.bodyMedium)
            rows.forEach { t ->
                Text(t.periodStart.toString(), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.ins_history_row, f(t.pvKwh, 2), f(t.loadKwh, 2), f(t.gridImportKwh, 2), f(t.gridExportKwh, 2)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.ins_history_ratios, t.selfConsumption?.let { "${f(it * 100, 0)}%" } ?: "—", t.autarky?.let { "${f(it * 100, 0)}%" } ?: "—", f(t.coverage * 100, 0)),
                    style = MaterialTheme.typography.bodySmall)
            }
            KindBadge(DataKind.CALCULATED)
        }

        // EXPORT
        SectionCard {
            Text(stringResource(R.string.ins_export), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.ins_export_hint), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { csvLauncher.launch("solar-history.csv") }) { Text("CSV") }
                OutlinedButton(onClick = { jsonLauncher.launch("solar-history.json") }) { Text("JSON") }
                OutlinedButton(onClick = { pdfLauncher.launch("solar-report.pdf") }) { Text("PDF") }
            }
            exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal suspend fun writeText(context: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        ?: error(context.getString(R.string.ins_cannot_open))
}
