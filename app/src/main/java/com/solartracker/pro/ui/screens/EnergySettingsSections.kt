package com.solartracker.pro.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.energy.BackupSource
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.BatteryType
import com.solartracker.pro.core.energy.BatteryValidationError
import com.solartracker.pro.core.energy.ConsumptionPeriod
import com.solartracker.pro.core.energy.EnergyPrices
import com.solartracker.pro.data.ConsumptionMode
import com.solartracker.pro.data.ConsumptionSettings
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.components.SectionCard
import kotlinx.coroutines.launch

/** Callbacks for the energy-related settings, implemented by the ViewModel. */
interface EnergySettingsActions {
    fun setBatteryEnabled(enabled: Boolean)
    suspend fun saveBattery(enabled: Boolean, battery: BatteryStorage): Boolean
    suspend fun saveConsumption(consumption: ConsumptionSettings): Boolean
    suspend fun savePrices(prices: EnergyPrices): Boolean
}

fun BatteryType.label(): String = when (this) {
    BatteryType.LIFEPO4 -> "LiFePO4"
    BatteryType.LI_ION -> "Li-ion"
    BatteryType.AGM -> "AGM"
    BatteryType.GEL -> "GEL"
    BatteryType.LEAD_ACID -> "Kwasowo-ołowiowy"
    BatteryType.OTHER -> "Inne"
}

fun BatteryValidationError.message(): String = when (this) {
    BatteryValidationError.CAPACITY_NOT_POSITIVE -> "Pojemność musi być większa od 0"
    BatteryValidationError.USABLE_CAPACITY_OUT_OF_RANGE -> "Użyteczna pojemność: 1–100%"
    BatteryValidationError.SOC_OUT_OF_RANGE -> "SOC musi mieścić się w zakresie 0–100%"
    BatteryValidationError.MIN_SOC_NOT_BELOW_MAX -> "Minimalny SOC musi być mniejszy niż maksymalny"
    BatteryValidationError.CHARGE_POWER_NOT_POSITIVE -> "Moc ładowania musi być większa od 0"
    BatteryValidationError.DISCHARGE_POWER_NOT_POSITIVE -> "Moc rozładowania musi być większa od 0"
    BatteryValidationError.EFFICIENCY_OUT_OF_RANGE -> "Sprawność musi mieścić się w zakresie 1–100%"
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(10)) },
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
private fun SavedMessage(message: String?) {
    if (message != null) {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (message.startsWith("Zapisano")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
fun BatterySection(enabled: Boolean, current: BatteryStorage, actions: EnergySettingsActions) {
    val scope = rememberCoroutineScope()
    var capacity by rememberSaveable(current) { mutableStateOf(Format.decimal(current.nominalCapacityKwh, 1)) }
    var usable by rememberSaveable(current) { mutableStateOf(Format.decimal(current.usableCapacityPercent, 0)) }
    var initial by rememberSaveable(current) { mutableStateOf(Format.decimal(current.initialSocPercent, 0)) }
    var minSoc by rememberSaveable(current) { mutableStateOf(Format.decimal(current.minSocPercent, 0)) }
    var maxSoc by rememberSaveable(current) { mutableStateOf(Format.decimal(current.maxSocPercent, 0)) }
    var chargeKw by rememberSaveable(current) { mutableStateOf(Format.decimal(current.maxChargePowerKw, 1)) }
    var dischargeKw by rememberSaveable(current) { mutableStateOf(Format.decimal(current.maxDischargePowerKw, 1)) }
    var chargeEff by rememberSaveable(current) { mutableStateOf(Format.decimal(current.chargeEfficiencyPercent, 0)) }
    var dischargeEff by rememberSaveable(current) { mutableStateOf(Format.decimal(current.dischargeEfficiencyPercent, 0)) }
    var type by rememberSaveable(current) { mutableStateOf(current.type) }
    var message by remember { mutableStateOf<String?>(null) }

    val fields = listOf(capacity, usable, initial, minSoc, maxSoc, chargeKw, dischargeKw, chargeEff, dischargeEff)
    val parsed = fields.map { Format.parseDecimal(it) }
    val candidate = if (parsed.all { it != null }) {
        val v = parsed.map { it!! }
        BatteryStorage(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], type)
    } else {
        null
    }
    val errors = candidate?.validate().orEmpty()
    fun has(e: BatteryValidationError) = e in errors
    val canSave = candidate != null && errors.isEmpty()

    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Magazyn energii",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Mam magazyn energii", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { actions.setBatteryEnabled(it) })
        }
        if (!enabled) return@SectionCard

        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BatteryType.entries.forEach { t ->
                FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label()) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Pojemność [kWh]", capacity, { capacity = it }, Modifier.weight(1f),
                isError = parsed[0] == null || has(BatteryValidationError.CAPACITY_NOT_POSITIVE))
            NumberField("Użyteczna [%]", usable, { usable = it }, Modifier.weight(1f),
                isError = parsed[1] == null || has(BatteryValidationError.USABLE_CAPACITY_OUT_OF_RANGE))
        }
        val socError = has(BatteryValidationError.SOC_OUT_OF_RANGE)
        val orderError = has(BatteryValidationError.MIN_SOC_NOT_BELOW_MAX)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("SOC start [%]", initial, { initial = it }, Modifier.weight(1f), isError = parsed[2] == null || socError)
            NumberField("SOC min [%]", minSoc, { minSoc = it }, Modifier.weight(1f), isError = parsed[3] == null || socError || orderError)
            NumberField("SOC maks [%]", maxSoc, { maxSoc = it }, Modifier.weight(1f), isError = parsed[4] == null || socError || orderError)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Ładowanie [kW]", chargeKw, { chargeKw = it }, Modifier.weight(1f),
                isError = parsed[5] == null || has(BatteryValidationError.CHARGE_POWER_NOT_POSITIVE))
            NumberField("Rozładowanie [kW]", dischargeKw, { dischargeKw = it }, Modifier.weight(1f),
                isError = parsed[6] == null || has(BatteryValidationError.DISCHARGE_POWER_NOT_POSITIVE))
        }
        val effError = has(BatteryValidationError.EFFICIENCY_OUT_OF_RANGE)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Sprawn. ładow. [%]", chargeEff, { chargeEff = it }, Modifier.weight(1f), isError = parsed[7] == null || effError)
            NumberField("Sprawn. rozład. [%]", dischargeEff, { dischargeEff = it }, Modifier.weight(1f), isError = parsed[8] == null || effError)
        }
        if (candidate == null) {
            Text("Wpisz liczby we wszystkich polach.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        errors.forEach {
            Text("• ${it.message()}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (candidate != null && errors.isEmpty()) {
            Text(
                "Użyteczna energia: ${Format.kwh(candidate.usableCapacityKwh)} (100% SOC), " +
                    "praca w oknie ${Format.percent(candidate.minSocPercent)}–${Format.percent(candidate.maxSocPercent)} = " +
                    Format.kwh(candidate.operatingWindowKwh),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = canSave,
                onClick = {
                    val battery = candidate ?: return@Button
                    scope.launch {
                        message = if (actions.saveBattery(true, battery)) "Zapisano magazyn energii" else "Błędna konfiguracja – nie zapisano"
                    }
                },
            ) { Text("Zapisz magazyn") }
            OutlinedButton(onClick = {
                val d = BatteryStorage()
                capacity = Format.decimal(d.nominalCapacityKwh, 1); usable = Format.decimal(d.usableCapacityPercent, 0)
                initial = Format.decimal(d.initialSocPercent, 0); minSoc = Format.decimal(d.minSocPercent, 0)
                maxSoc = Format.decimal(d.maxSocPercent, 0); chargeKw = Format.decimal(d.maxChargePowerKw, 1)
                dischargeKw = Format.decimal(d.maxDischargePowerKw, 1); chargeEff = Format.decimal(d.chargeEfficiencyPercent, 0)
                dischargeEff = Format.decimal(d.dischargeEfficiencyPercent, 0); type = d.type
                message = null
            }) { Text("Domyślne") }
        }
        SavedMessage(message)
    }
}

/** Editable row of the hourly consumption profile. */
private data class PeriodInput(val start: String, val end: String, val kw: String) {
    fun toPeriod(): ConsumptionPeriod? {
        val s = start.trim().toIntOrNull() ?: return null
        val e = end.trim().toIntOrNull() ?: return null
        val p = Format.parseDecimal(kw) ?: return null
        return ConsumptionPeriod(s, e, p)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsumptionSection(current: ConsumptionSettings, actions: EnergySettingsActions) {
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable(current) { mutableStateOf(current.mode) }
    var constantText by rememberSaveable(current) { mutableStateOf(Format.decimal(current.constantKw * 1000, 0)) }
    val rows = remember(current) {
        mutableStateListOf<PeriodInput>().apply {
            current.periods.forEach { add(PeriodInput(it.startHour.toString(), it.endHour.toString(), Format.decimal(it.powerKw, 2))) }
        }
    }
    var message by remember { mutableStateOf<String?>(null) }

    val constantW = Format.parseDecimal(constantText)
    val periods = rows.map { it.toPeriod() }
    val candidate = when (mode) {
        ConsumptionMode.CONSTANT -> constantW?.let { current.copy(mode = mode, constantKw = it / 1000.0) }
        ConsumptionMode.HOURLY -> if (periods.all { it != null }) current.copy(mode = mode, periods = periods.map { it!! }) else null
    }
    val errors = candidate?.validate() ?: listOf("Wpisz liczby we wszystkich polach")

    SectionCard {
        Text("Profil zużycia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(ConsumptionMode.CONSTANT to "Stałe zużycie", ConsumptionMode.HOURLY to "Godzinowe").forEachIndexed { i, (m, label) ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { mode = m },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                ) { Text(label) }
            }
        }
        when (mode) {
            ConsumptionMode.CONSTANT -> {
                NumberField("Stały pobór [W] przez 24 h", constantText, { constantText = it }, Modifier.fillMaxWidth(),
                    isError = errors.isNotEmpty())
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(200, 500, 1000, 2000).forEach { w ->
                        FilterChip(
                            selected = constantW == w.toDouble(),
                            onClick = { constantText = w.toString() },
                            label = { Text(if (w < 1000) "$w W" else "${w / 1000} kW") },
                        )
                    }
                }
            }
            ConsumptionMode.HOURLY -> {
                Text("Godziny 0–24, moc w kW. Godziny poza przedziałami = 0 kW.", style = MaterialTheme.typography.bodySmall)
                rows.forEachIndexed { i, row ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = row.start, onValueChange = { rows[i] = row.copy(start = it.take(2)) },
                            label = { Text("Od") }, singleLine = true, modifier = Modifier.width(72.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = row.end, onValueChange = { rows[i] = row.copy(end = it.take(2)) },
                            label = { Text("Do") }, singleLine = true, modifier = Modifier.width(72.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = row.kw, onValueChange = { rows[i] = row.copy(kw = it.take(8)) },
                            label = { Text("kW") }, singleLine = true, modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        IconButton(onClick = { rows.removeAt(i) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "Usuń przedział")
                        }
                    }
                }
                OutlinedButton(onClick = {
                    val lastEnd = rows.lastOrNull()?.end?.toIntOrNull() ?: 0
                    rows.add(PeriodInput(lastEnd.coerceAtMost(23).toString(), "24", "0,5"))
                }) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Dodaj przedział")
                }
            }
        }
        if (errors.isEmpty() && candidate != null) {
            Text(
                "Zużycie dobowe: ${Format.kwh(candidate.profile().dailyKwh)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
        errors.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(
            enabled = errors.isEmpty() && candidate != null,
            onClick = {
                val c = candidate ?: return@Button
                scope.launch { message = if (actions.saveConsumption(c)) "Zapisano profil zużycia" else "Błędny profil – nie zapisano" }
            },
        ) { Text("Zapisz profil zużycia") }
        SavedMessage(message)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PricesSection(current: EnergyPrices, actions: EnergySettingsActions) {
    val scope = rememberCoroutineScope()
    fun text(v: Double?) = v?.let { Format.decimal(it, 2) }.orEmpty()
    var grid by rememberSaveable(current) { mutableStateOf(text(current.gridPricePerKwh)) }
    var generator by rememberSaveable(current) { mutableStateOf(text(current.generatorPricePerKwh)) }
    var feedIn by rememberSaveable(current) { mutableStateOf(text(current.feedInPricePerKwh)) }
    var batteryCost by rememberSaveable(current) { mutableStateOf(text(current.batteryCost)) }
    var source by rememberSaveable(current) { mutableStateOf(current.backupSource) }
    var message by remember { mutableStateOf<String?>(null) }

    /** Empty = not set; otherwise must be a number >= 0. */
    fun parse(t: String): Result<Double?> =
        if (t.isBlank()) Result.success(null)
        else Format.parseDecimal(t)?.takeIf { it >= 0.0 }?.let { Result.success(it) } ?: Result.failure(IllegalArgumentException())
    val values = listOf(grid, generator, feedIn, batteryCost).map(::parse)
    val valid = values.all { it.isSuccess }

    SectionCard {
        Text("Ceny energii (opcjonalnie)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Brakującą energię pokrywa:", style = MaterialTheme.typography.bodyMedium)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf(BackupSource.GRID to "Sieć", BackupSource.GENERATOR to "Agregat").forEachIndexed { i, (s, label) ->
                SegmentedButton(
                    selected = source == s,
                    onClick = { source = s },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                ) { Text(label) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Sieć [zł/kWh]", grid, { grid = it }, Modifier.weight(1f), isError = values[0].isFailure)
            NumberField("Agregat [zł/kWh]", generator, { generator = it }, Modifier.weight(1f), isError = values[1].isFailure)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Sprzedaż [zł/kWh]", feedIn, { feedIn = it }, Modifier.weight(1f), isError = values[2].isFailure)
            NumberField("Koszt magazynu [zł]", batteryCost, { batteryCost = it }, Modifier.weight(1f), isError = values[3].isFailure)
        }
        if (!valid) Text("Ceny muszą być liczbami ≥ 0 (puste pole = brak).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Button(
            enabled = valid,
            onClick = {
                val prices = EnergyPrices(
                    gridPricePerKwh = values[0].getOrNull(),
                    generatorPricePerKwh = values[1].getOrNull(),
                    feedInPricePerKwh = values[2].getOrNull(),
                    batteryCost = values[3].getOrNull(),
                    backupSource = source,
                )
                scope.launch { message = if (actions.savePrices(prices)) "Zapisano ceny" else "Błędne ceny – nie zapisano" }
            },
        ) { Text("Zapisz ceny") }
        SavedMessage(message)
    }
}
