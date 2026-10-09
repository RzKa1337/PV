package com.solartracker.pro.ui.screens

import com.solartracker.pro.i18n.tr
import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
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
import com.solartracker.pro.ui.components.SunnyScene
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
            stringResource(R.string.energy_title),
            state?.let {
                if (it.startDate == it.endDate) it.startDate.format(dateFormat)
                else "${it.startDate.format(dateFormat)} – ${it.endDate.format(dateFormat)}"
            },
            scene = SunnyScene.TUSCANY,
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
                    stringResource(R.string.energy_no_battery),
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
            stringResource(R.string.energy_footer, state.sourceDescription),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun backupLabel(source: BackupSource): String = when (source) {
    BackupSource.GRID -> tr("Sieć", "Grid")
    BackupSource.GENERATOR -> tr("Agregat", "Generator")
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
        CardHeader(stringResource(R.string.energy_balance))
        ValueRow(stringResource(R.string.energy_pv_production), Format.kwh(b.pvKwh), bold = true)
        ValueRow(stringResource(R.string.live_load), Format.kwh(b.consumptionKwh), bold = true)
        ValueRow(stringResource(R.string.energy_direct), Format.kwh(b.directUseKwh))
        ValueRow(stringResource(R.string.energy_to_battery), Format.kwh(b.toBatteryKwh))
        ValueRow(stringResource(R.string.energy_from_battery), Format.kwh(b.fromBatteryKwh))
        ValueRow(stringResource(R.string.energy_missing, backupLabel), Format.kwh(b.gridKwh))
        ValueRow(stringResource(R.string.energy_pv_surplus), Format.kwh(b.surplusKwh))
        ValueRow(stringResource(R.string.energy_battery_losses), Format.kwh(b.batteryLossKwh))
        val startSoc = b.startSocPercent
        val endSoc = b.endSocPercent
        if (startSoc != null && endSoc != null) {
            HorizontalDivider()
            ValueRow(stringResource(R.string.energy_soc_start_end), "${Format.percent(startSoc)} → ${Format.percent(endSoc)}", bold = true)
        }
        Text(
            stringResource(R.string.energy_profile, Format.kwh(dailyConsumption)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ComparisonCard(without: EnergyBalance, with: EnergyBalance, backupLabel: String) {
    SectionCard {
        CardHeader(stringResource(R.string.energy_without_vs_with))
        Row(Modifier.fillMaxWidth()) {
            Text("", modifier = Modifier.weight(1.4f))
            Text(stringResource(R.string.energy_without), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.energy_with), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        listOf(
            Triple("PV", without.pvKwh, with.pvKwh),
            Triple(stringResource(R.string.live_load), without.consumptionKwh, with.consumptionKwh),
            Triple(stringResource(R.string.energy_directly), without.directUseKwh, with.directUseKwh),
            Triple(stringResource(R.string.energy_to_battery), without.toBatteryKwh, with.toBatteryKwh),
            Triple(stringResource(R.string.energy_from_battery), without.fromBatteryKwh, with.fromBatteryKwh),
            Triple(stringResource(R.string.ins_surplus), without.surplusKwh, with.surplusKwh),
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
            ValueRow(stringResource(R.string.energy_soc_end), Format.percent(it), bold = true)
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
        add(LineSeries(stringResource(R.string.energy_pv_production), ChartColors.pv, values { pv, _, _, _, _ -> pv }, fill = true))
        add(LineSeries(stringResource(R.string.live_load), ChartColors.consumption, values { _, load, _, _, _ -> load }))
        if (r.hasBattery) {
            add(LineSeries(stringResource(R.string.energy_charging), ChartColors.charge, values { _, _, c, _, _ -> c }))
            add(LineSeries(stringResource(R.string.energy_discharging), ChartColors.discharge, values { _, _, _, d, _ -> d }))
        }
        add(LineSeries(backupLabel, ChartColors.grid, values { _, _, _, _, g -> g }))
    }
    SectionCard {
        CardHeader(if (hourly) stringResource(R.string.energy_flow_hourly) else stringResource(R.string.energy_flow_daily))
        LineChart(
            series = series,
            xLabels = xLabels(state),
            contentDescription = stringResource(R.string.energy_flow_chart_desc),
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
        CardHeader(if (useHourly(state)) stringResource(R.string.energy_soc_hourly) else stringResource(R.string.energy_soc_daily))
        LineChart(series, labels, fixedMax = 100.0, yUnit = "%", height = 160.dp, contentDescription = stringResource(R.string.energy_soc_chart_desc))
        Text(
            stringResource(R.string.energy_soc_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StatisticsCard(st: BatteryStatistics) {
    SectionCard {
        CardHeader(stringResource(R.string.energy_stats))
        ValueRow(stringResource(R.string.energy_cycles), Format.decimal(st.equivalentFullCycles, 2))
        ValueRow(stringResource(R.string.energy_avg_soc), Format.percent(st.averageSocPercent))
        ValueRow(stringResource(R.string.energy_min_soc), Format.percent(st.minSocPercent))
        ValueRow(stringResource(R.string.energy_max_soc), Format.percent(st.maxSocPercent))
        ValueRow(stringResource(R.string.energy_charged), Format.kwh(st.chargedKwh))
        ValueRow(stringResource(R.string.energy_discharged), Format.kwh(st.dischargedKwh))
        ValueRow(stringResource(R.string.energy_storage_losses), Format.kwh(st.lossesKwh))
        ValueRow(stringResource(R.string.energy_hours_empty), Format.decimal(st.hoursEmpty, 1) + " h")
        ValueRow(stringResource(R.string.energy_hours_full), Format.decimal(st.hoursFull, 1) + " h")
    }
}

@Composable
private fun AutonomyCard(battery: BatteryStorage?, defaultDailyKwh: Double) {
    SectionCard {
        CardHeader(stringResource(R.string.energy_autonomy), estimate = false)
        if (battery == null) {
            Text(stringResource(R.string.energy_autonomy_off), style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }
        var dailyText by rememberSaveable(defaultDailyKwh) { mutableStateOf(Format.decimal(defaultDailyKwh, 1)) }
        var constantW by rememberSaveable { mutableStateOf<Int?>(null) }
        val daily = Format.parseDecimal(dailyText)?.takeIf { it > 0.0 }

        Text(
            stringResource(R.string.energy_deliverable, Format.kwh(AutonomyCalculator.deliverableKwh(battery))),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = dailyText,
            onValueChange = { dailyText = it.take(8); constantW = null },
            label = { Text(stringResource(R.string.energy_consumption_per_day)) },
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
            val load = if (constantW != null) stringResource(R.string.energy_constant_load, Format.decimal(result.loadKw, 1)) else stringResource(R.string.energy_per_day, Format.kwh(daily ?: 0.0))
            Text(
                stringResource(R.string.energy_battery_load, Format.decimal(battery.nominalCapacityKwh, 1), load.toString()),
                style = MaterialTheme.typography.bodyMedium,
            )
            val hours = result.hours
            Text(
                if (hours == null) "—" else if (hours >= 48) stringResource(R.string.energy_autonomy_days, Format.decimal(hours / 24.0, 1))
                else stringResource(R.string.energy_autonomy_hours, Format.decimal(hours, 1)),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            if (result.limitedByDischargePower) {
                Text(
                    stringResource(R.string.energy_discharge_limit, Format.kw(battery.maxDischargePowerKw)),
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
        CardHeader(stringResource(R.string.energy_costs))
        if (costs == null) {
            Text(
                stringResource(R.string.energy_costs_need_prices),
                style = MaterialTheme.typography.bodyMedium,
            )
            return@SectionCard
        }
        val c = costs.comparison
        ValueRow(stringResource(R.string.energy_cost_without), Format.money(c.costWithoutBattery))
        if (costs.hasBattery) {
            ValueRow(stringResource(R.string.energy_cost_with), Format.money(c.costWithBattery))
            HorizontalDivider()
            ValueRow(stringResource(R.string.energy_savings_day), Format.money(c.dailySavings))
            ValueRow(stringResource(R.string.energy_savings_month), Format.money(c.monthlySavings))
            ValueRow(stringResource(R.string.energy_savings_year), Format.money(c.yearlySavings), bold = true)
            val payback = c.paybackYears
            ValueRow(
                stringResource(R.string.energy_payback),
                when {
                    c.batteryCost == null -> stringResource(R.string.energy_enter_cost)
                    payback == null -> stringResource(R.string.energy_no_payback)
                    else -> stringResource(R.string.energy_years, Format.decimal(payback, 1))
                },
                bold = true,
            )
        } else {
            Text(stringResource(R.string.energy_turn_on_battery), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.energy_cost_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
