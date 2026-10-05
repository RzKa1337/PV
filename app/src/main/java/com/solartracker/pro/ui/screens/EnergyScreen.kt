package com.solartracker.pro.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.energy.AutonomyCalculator
import com.solartracker.pro.core.energy.AutonomyResult
import com.solartracker.pro.core.energy.BackupSource
import com.solartracker.pro.core.energy.BatteryStatistics
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.EnergyBalance
import com.solartracker.pro.ui.CostState
import com.solartracker.pro.ui.EnergyPeriod
import com.solartracker.pro.ui.EnergyState
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.components.ChartLegend
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.LineChart
import com.solartracker.pro.ui.components.LineSeries
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.theme.ChartColors
import java.time.format.DateTimeFormatter

@Composable
fun EnergyScreen(
    state: EnergyState?,
    costs: CostState?,
    period: EnergyPeriod,
    onPeriodChange: (EnergyPeriod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Format.locale)
        ScreenTitle(
            "Bilans energii",
            state?.let {
                if (it.startDate == it.endDate) it.startDate.format(dateFormat)
                else "${it.startDate.format(dateFormat)} – ${it.endDate.format(dateFormat)}"
            },
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EnergyPeriod.entries.forEach { p ->
                FilterChip(selected = p == period, onClick = { onPeriodChange(p) }, label = { Text(p.label) })
            }
        }

        if (state == null || state.period != period) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Column
        }

        val backupLabel = backupLabel(state.settings.prices.backupSource)
        if (!state.result.hasBattery) {
            SectionCard(containerColor = MaterialTheme.colorScheme.secondaryContainer) {
                Text(
                    "Brak magazynu energii. Włącz go w Ustawieniach → Magazyn energii. " +
                        "Poniżej bilans samej instalacji PV.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }

        BalanceCard(state.result.balance, backupLabel, state.settings.consumption.profile().dailyKwh)
        state.withoutBattery?.let { ComparisonCard(it.balance, state.result.balance, backupLabel) }
        FlowChartCard(state, backupLabel)
        if (state.result.hasBattery) SocChartCard(state)
        state.result.statistics?.let { StatisticsCard(it) }
        AutonomyCard(state.settings.activeBattery, state.settings.consumption.profile().dailyKwh)
        CostsCard(costs)

        Text(
            "Symulacja co 15 minut (SZACUNEK). Źródło PV: ${state.sourceDescription}. " +
                "Każdy dzień zaczyna się z SOC, z jakim zakończył się poprzedni. " +
                "Dla dni bez prognozy użyto średnich klimatycznych – to wartości typowe, nie konkretna pogoda.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun backupLabel(source: BackupSource): String = when (source) {
    BackupSource.GRID -> "Sieć"
    BackupSource.GENERATOR -> "Agregat"
}

@Composable
private fun CardHeader(title: String, estimate: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (estimate) EstimateBadge()
    }
}

@Composable
private fun ValueRow(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun BalanceCard(b: EnergyBalance, backupLabel: String, dailyConsumption: Double) {
    SectionCard {
        CardHeader("Bilans")
        ValueRow("Produkcja PV", Format.kwh(b.pvKwh), bold = true)
        ValueRow("Zużycie", Format.kwh(b.consumptionKwh), bold = true)
        ValueRow("Wykorzystane bezpośrednio", Format.kwh(b.directUseKwh))
        ValueRow("Do baterii", Format.kwh(b.toBatteryKwh))
        ValueRow("Z baterii", Format.kwh(b.fromBatteryKwh))
        ValueRow("$backupLabel (brakująca energia)", Format.kwh(b.gridKwh))
        ValueRow("Nadwyżka PV", Format.kwh(b.surplusKwh))
        ValueRow("Straty baterii", Format.kwh(b.batteryLossKwh))
        val startSoc = b.startSocPercent
        val endSoc = b.endSocPercent
        if (startSoc != null && endSoc != null) {
            HorizontalDivider()
            ValueRow("SOC początkowy → końcowy", "${Format.percent(startSoc)} → ${Format.percent(endSoc)}", bold = true)
        }
        Text(
            "Profil zużycia: ${Format.kwh(dailyConsumption)} na dobę",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComparisonCard(without: EnergyBalance, with: EnergyBalance, backupLabel: String) {
    SectionCard {
        CardHeader("Bez baterii vs z baterią")
        Row(Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.weight(1.4f))
            Text("Bez", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("Z baterią", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        listOf(
            Triple("PV", without.pvKwh, with.pvKwh),
            Triple("Zużycie", without.consumptionKwh, with.consumptionKwh),
            Triple("Bezpośrednio", without.directUseKwh, with.directUseKwh),
            Triple("Do baterii", without.toBatteryKwh, with.toBatteryKwh),
            Triple("Z baterii", without.fromBatteryKwh, with.fromBatteryKwh),
            Triple("Nadwyżka", without.surplusKwh, with.surplusKwh),
            Triple(backupLabel, without.gridKwh, with.gridKwh),
        ).forEach { (label, a, b) ->
            Row(Modifier.fillMaxWidth()) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1.4f))
                Text(Format.kwhShort(a), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(Format.kwhShort(b), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
        with.endSocPercent?.let {
            HorizontalDivider()
            ValueRow("SOC końcowy", Format.percent(it), bold = true)
        }
    }
}

/** Hourly points for up to 7 days, daily totals for longer periods. */
private fun useHourly(state: EnergyState) = state.period.shownDays <= 7

private fun xLabels(state: EnergyState): Map<Int, String> {
    val r = state.result
    return if (useHourly(state)) {
        val hourly = r.hourly
        if (state.period.shownDays == 1) {
            hourly.indices.filter { it % 6 == 0 }.associateWith {
                hourly[it].start.atZone(state.zone).toLocalTime().toString().take(5)
            }
        } else {
            val f = DateTimeFormatter.ofPattern("EEE", Format.locale)
            hourly.indices.filter { hourly[it].start.atZone(state.zone).hour == 0 }.associateWith {
                hourly[it].start.atZone(state.zone).format(f)
            }
        }
    } else {
        val days = r.days
        val step = if (days.size > 60) 61 else 7
        val f = DateTimeFormatter.ofPattern(if (days.size > 60) "MMM" else "d.MM", Format.locale)
        days.indices.filter { it % step == 0 }.associateWith { days[it].date.format(f) }
    }
}

@Composable
private fun FlowChartCard(state: EnergyState, backupLabel: String) {
    val r = state.result
    val hourly = useHourly(state)
    fun values(pick: (pv: Double, load: Double, charge: Double, discharge: Double, grid: Double) -> Double): List<Double> =
        if (hourly) {
            r.hourly.map { pick(it.pvKwh, it.consumptionKwh, it.toBatteryKwh, it.fromBatteryKwh, it.gridKwh) }
        } else {
            r.days.map { d -> d.balance.let { pick(it.pvKwh, it.consumptionKwh, it.toBatteryKwh, it.fromBatteryKwh, it.gridKwh) } }
        }
    val series = buildList {
        add(LineSeries("Produkcja PV", ChartColors.pv, values { pv, _, _, _, _ -> pv }, fill = true))
        add(LineSeries("Zużycie", ChartColors.consumption, values { _, load, _, _, _ -> load }))
        if (r.hasBattery) {
            add(LineSeries("Ładowanie", ChartColors.charge, values { _, _, c, _, _ -> c }))
            add(LineSeries("Rozładowanie", ChartColors.discharge, values { _, _, _, d, _ -> d }))
        }
        add(LineSeries(backupLabel, ChartColors.grid, values { _, _, _, _, g -> g }))
    }
    SectionCard {
        CardHeader(if (hourly) "Przepływ energii [kW, średnio w godzinie]" else "Przepływ energii [kWh na dzień]")
        LineChart(
            series = series,
            xLabels = xLabels(state),
            contentDescription = "Wykres produkcji PV, zużycia, ładowania, rozładowania i poboru z sieci",
        )
        ChartLegend(series)
    }
}

@Composable
private fun SocChartCard(state: EnergyState) {
    val r = state.result
    val values = if (useHourly(state)) {
        listOf(r.startSocPercent ?: 0.0) + r.hourly.map { it.socPercent }
    } else {
        listOf(r.startSocPercent ?: 0.0) + r.days.map { it.balance.endSocPercent ?: 0.0 }
    }
    // Shift labels by one: index 0 is the starting SOC.
    val labels = xLabels(state).mapKeys { it.key + 1 }
    val series = listOf(LineSeries("SOC", ChartColors.soc, values, fill = true))
    SectionCard {
        CardHeader(if (useHourly(state)) "SOC baterii [%]" else "SOC baterii na koniec dnia [%]")
        LineChart(series, labels, fixedMax = 100.0, yUnit = "%", height = 160.dp, contentDescription = "Wykres SOC baterii")
        Text(
            "Linia rośnie – bateria się ładuje, opada – rozładowuje.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatisticsCard(st: BatteryStatistics) {
    SectionCard {
        CardHeader("Statystyki magazynu")
        ValueRow("Pełne cykle (ekwiwalent)", Format.decimal(st.equivalentFullCycles, 2))
        ValueRow("Średni SOC", Format.percent(st.averageSocPercent))
        ValueRow("Minimalny SOC", Format.percent(st.minSocPercent))
        ValueRow("Maksymalny SOC", Format.percent(st.maxSocPercent))
        ValueRow("Energia naładowana", Format.kwh(st.chargedKwh))
        ValueRow("Energia rozładowana", Format.kwh(st.dischargedKwh))
        ValueRow("Straty magazynu", Format.kwh(st.lossesKwh))
        ValueRow("Godziny z pustą baterią", Format.decimal(st.hoursEmpty, 1) + " h")
        ValueRow("Godziny z pełną baterią", Format.decimal(st.hoursFull, 1) + " h")
    }
}

@Composable
private fun AutonomyCard(battery: BatteryStorage?, defaultDailyKwh: Double) {
    SectionCard {
        CardHeader("Jak długo wytrzyma bateria?", estimate = false)
        if (battery == null) {
            Text("Włącz magazyn energii w Ustawieniach, aby policzyć autonomię.", style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        var dailyText by rememberSaveable(defaultDailyKwh) { mutableStateOf(Format.decimal(defaultDailyKwh, 1)) }
        var constantW by rememberSaveable { mutableStateOf<Int?>(null) }
        val daily = Format.parseDecimal(dailyText)?.takeIf { it > 0.0 }

        Text(
            "Bez ładowania, od SOC maks. do min., z uwzględnieniem użytecznej pojemności i sprawności rozładowania: " +
                "${Format.kwh(AutonomyCalculator.deliverableKwh(battery))} do dyspozycji.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = dailyText,
            onValueChange = { dailyText = it.take(8); constantW = null },
            label = { Text("Zużycie [kWh/dzień]") },
            singleLine = true,
            isError = daily == null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(200, 500, 1000, 2000).forEach { w ->
                AssistChip(
                    onClick = { constantW = w },
                    label = { Text(if (w < 1000) "$w W" else "${w / 1000} kW") },
                )
            }
        }
        val result: AutonomyResult? = when {
            constantW != null -> AutonomyCalculator.forConstantLoad(battery, constantW!! / 1000.0)
            daily != null -> AutonomyCalculator.forDailyConsumption(battery, daily)
            else -> null
        }
        if (result != null) {
            val load = if (constantW != null) "stały pobór ${Format.decimal(result.loadKw, 1)} kW" else "${Format.kwh(daily ?: 0.0)}/dzień"
            Text(
                "${Format.decimal(battery.nominalCapacityKwh, 1)} kWh bateria, $load →",
                style = MaterialTheme.typography.bodyMedium,
            )
            val hours = result.hours
            Text(
                if (hours == null) "—" else if (hours >= 48) "około ${Format.decimal(hours / 24.0, 1)} dni autonomii"
                else "około ${Format.decimal(hours, 1)} h autonomii",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (result.limitedByDischargePower) {
                Text(
                    "Uwaga: pobór przekracza maks. moc rozładowania (${Format.kw(battery.maxDischargePowerKw)}). " +
                        "Bateria pokryje tylko tę moc, resztę musi dać sieć/agregat.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun CostsCard(costs: CostState?) {
    SectionCard {
        CardHeader("Koszt energii (prognoza roczna)")
        if (costs == null) {
            Text(
                "Wpisz cenę energii z sieci lub agregatu w Ustawieniach → Ceny energii, aby zobaczyć koszty i okres zwrotu.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        val c = costs.comparison
        ValueRow("Koszt bez baterii / rok", Format.money(c.costWithoutBattery))
        if (costs.hasBattery) {
            ValueRow("Koszt z baterią / rok", Format.money(c.costWithBattery))
            HorizontalDivider()
            ValueRow("Oszczędność dzienna", Format.money(c.dailySavings))
            ValueRow("Oszczędność miesięczna", Format.money(c.monthlySavings))
            ValueRow("Oszczędność roczna", Format.money(c.yearlySavings), bold = true)
            val payback = c.paybackYears
            ValueRow(
                "Okres zwrotu magazynu",
                when {
                    c.batteryCost == null -> "podaj koszt magazynu"
                    payback == null -> "brak zwrotu"
                    else -> "${Format.decimal(payback, 1)} lat"
                },
                bold = true,
            )
        } else {
            Text("Włącz magazyn energii, aby zobaczyć oszczędności.", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Koszt = energia z sieci/agregatu × cena − nadwyżka × cena sprzedaży. Prognoza roczna " +
                "opiera się na prognozie pogody i średnich klimatycznych – rzeczywiste oszczędności mogą się różnić.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
