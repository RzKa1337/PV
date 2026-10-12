package com.solartracker.pro.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.solartracker.pro.R
import com.solartracker.pro.core.analytics.DayEstimate
import com.solartracker.pro.core.analytics.EntryComparison
import com.solartracker.pro.core.analytics.EstimateBasis
import com.solartracker.pro.core.analytics.ManualComparison
import com.solartracker.pro.core.analytics.ManualEntryProblem
import com.solartracker.pro.core.analytics.ManualProduction
import com.solartracker.pro.core.analytics.ManualProductionAnalyzer
import com.solartracker.pro.core.analytics.ManualProductionEntry
import com.solartracker.pro.core.analytics.PastDayEstimator
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.data.AppSettings
import com.solartracker.pro.data.ProductionRepository
import com.solartracker.pro.data.WeatherRepository
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.components.StatusLabel
import com.solartracker.pro.ui.components.StatusLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId

private data class ProductionResult(val comparison: ManualComparison, val weatherMissing: Boolean)

/**
 * Readings the user typed in (inverter display, meter) compared with the model for the same days, using the
 * hourly weather of those days where the provider has it. The readings are measurements by the user; the model
 * side stays an estimate and says what it was based on.
 */
@Composable
internal fun ProductionTool(settings: AppSettings) {
    val context = LocalContext.current
    val repository = remember { ProductionRepository(context) }
    val scope = rememberCoroutineScope()
    val yesterday = remember { LocalDate.now().minusDays(1).toString() }
    var from by rememberSaveable { mutableStateOf(yesterday) }
    var to by rememberSaveable { mutableStateOf(yesterday) }
    var energy by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Int?>(null) }
    var entries by remember { mutableStateOf<List<ManualProductionEntry>?>(null) }
    var result by remember { mutableStateOf<ProductionResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    val system = settings.system

    LaunchedEffect(Unit) { entries = repository.entries() }

    LaunchedEffect(entries, system, settings.location) {
        val list = entries
        if (list.isNullOrEmpty()) { result = null; return@LaunchedEffect }
        busy = true
        val weather = repository.weather(settings.location, list.minOf { it.from }, list.maxOf { it.to })
        result = withContext(Dispatchers.Default) {
            // Days the archive does not cover fall back to the cached climate (typical month), stated per row.
            val climate = WeatherRepository(context).cachedOnly(settings.location).climate
            val estimator = PastDayEstimator(WeatherAwareIrradianceModel(settings.location, weather, climate), system, settings.location, weather?.zone ?: ZoneId.systemDefault())
            val days = HashMap<LocalDate, DayEstimate>()
            ProductionResult(ManualProductionAnalyzer.compare(list, system.peakPowerKw) { d -> days.getOrPut(d) { estimator.estimate(d) } }, weather == null)
        }
        busy = false
    }

    SectionCard {
        Text(stringResource(R.string.mp_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.mp_hint, f(system.peakPowerKw, 2), settings.locationName, f(system.tiltDeg, 0), f(system.azimuthDeg, 0)),
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField(stringResource(R.string.mp_from), from, { from = it }, Modifier.weight(1f).testTag("mp_from"))
            DateField(stringResource(R.string.mp_to), to, { to = it }, Modifier.weight(1f).testTag("mp_to"))
        }
        Num(stringResource(R.string.mp_energy), energy, { energy = it }, Modifier.fillMaxWidth().testTag("mp_energy"))
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("mp_error")) }
        Button(
            enabled = entries != null,
            onClick = {
                val a = ManualProduction.parseDate(from)
                val b = ManualProduction.parseDate(to)
                val kwh = energy.num()
                val existing = entries.orEmpty()
                error = when {
                    a == null || b == null -> R.string.mp_err_date
                    kwh == null -> R.string.mp_err_energy
                    else -> {
                        val entry = ManualProductionEntry(a, b, kwh)
                        when (ManualProduction.validate(entry, LocalDate.now(), existing, system.peakPowerKw)) {
                            ManualEntryProblem.INVALID_ENERGY -> R.string.mp_err_energy
                            ManualEntryProblem.END_BEFORE_START -> R.string.mp_err_order
                            ManualEntryProblem.NOT_FINISHED -> R.string.mp_err_not_finished
                            ManualEntryProblem.TOO_LONG -> R.string.mp_err_too_long
                            ManualEntryProblem.OVERLAP -> R.string.mp_err_overlap
                            ManualEntryProblem.IMPLAUSIBLE -> R.string.mp_err_implausible
                            null -> {
                                val updated = ManualProduction.add(existing, entry)
                                entries = updated
                                energy = ""
                                scope.launch { repository.save(updated) }
                                null
                            }
                        }
                    }
                }
            },
        ) { Text(stringResource(R.string.mp_add)) }
    }

    val list = entries
    if (list != null && list.isEmpty()) {
        SectionCard { Text(stringResource(R.string.mp_empty), style = MaterialTheme.typography.bodyMedium) }
        return
    }
    if (busy && result == null) SectionCard { Text(stringResource(R.string.computing_short)) }

    result?.let { r ->
        val c = r.comparison
        SectionCard {
            Text(stringResource(R.string.mp_summary), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            c.weatherBased?.let { s ->
                Line(stringResource(R.string.mp_weather_days), s.days.toString())
                Line(stringResource(R.string.mp_actual), Format.kwh(s.actualKwh))
                Line(stringResource(R.string.mp_model), Format.kwh(s.estimatedKwh))
                s.ratio?.let { Line(stringResource(R.string.mp_ratio), Format.percent(it * 100)) }
            }
            val factor = c.suggestedFactor
            when {
                factor != null -> {
                    Line(stringResource(R.string.mp_factor), Format.decimal(factor, 2))
                    Text(stringResource(R.string.mp_factor_note, (c.weatherBased?.days ?: 0).toString()), style = MaterialTheme.typography.bodySmall)
                }
                c.ratioImplausible -> StatusLabel(StatusLevel.WARNING, stringResource(R.string.mp_factor_implausible))
                else -> Text(stringResource(R.string.mp_factor_missing, c.daysMissingForFactor.toString(), ManualProductionAnalyzer.MIN_DAYS_FOR_FACTOR.toString()),
                    style = MaterialTheme.typography.bodySmall)
            }
            c.other?.let { o ->
                Text(stringResource(R.string.mp_other, o.days.toString(), Format.kwh(o.actualKwh), Format.kwh(o.estimatedKwh)), style = MaterialTheme.typography.bodySmall)
            }
            if (r.weatherMissing) StatusLabel(StatusLevel.WARNING, stringResource(R.string.mp_weather_error), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.mp_model_limits), style = MaterialTheme.typography.bodySmall)
            EstimateBadge()
        }
        SectionCard {
            EstimateBadge(text = stringResource(R.string.badge_user_reading))
            c.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider()
                EntryRow(row) {
                    val updated = list.orEmpty() - row.entry
                    entries = updated
                    scope.launch { repository.save(updated) }
                }
            }
        }
    }
}

@Composable
private fun DateField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier) {
    OutlinedTextField(value, { onChange(it.take(10)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), modifier = modifier)
}

@Composable
private fun EntryRow(row: EntryComparison, onDelete: () -> Unit) {
    val e = row.entry
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (e.days == 1) e.from.toString() else stringResource(R.string.mp_row_days, e.from.toString(), e.to.toString(), e.days.toString()),
                style = MaterialTheme.typography.bodySmall)
            Text(Format.kwh(e.kwh) + (row.differencePercent?.let { "  (${if (it >= 0) "+" else "−"}${Format.percent(kotlin.math.abs(it))})" } ?: ""),
                fontWeight = FontWeight.SemiBold)
            val estimated = row.estimatedKwh
            Text(
                when {
                    estimated == null -> stringResource(R.string.mp_row_no_model)
                    row.ratio == null -> stringResource(R.string.mp_row_night)
                    else -> stringResource(R.string.mp_row_model, Format.kwh(estimated), stringResource(when (row.basis) {
                        EstimateBasis.WEATHER -> R.string.mp_basis_weather
                        EstimateBasis.CLIMATE -> R.string.mp_basis_climate
                        else -> R.string.mp_basis_clear
                    }))
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.mp_delete)) }
    }
}
