package com.solartracker.pro.ui.energy

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solartracker.pro.core.analytics.AlertSeverity
import com.solartracker.pro.core.analytics.ForecastComparison
import com.solartracker.pro.core.forecast.AdvisorAnswer
import com.solartracker.pro.core.forecast.AdvisorQuestion
import com.solartracker.pro.core.forecast.SolarAdvisor
import com.solartracker.pro.core.inverter.BatteryFlowState
import com.solartracker.pro.core.inverter.Freshness
import com.solartracker.pro.core.inverter.LinkStatus
import com.solartracker.pro.core.inverter.TelemetryField
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.ui.WeatherState
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.uiLabel
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val hms = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

/**
 * Feeds the weather to the Energy Center model and polls the inverter only while the calling screen is
 * visible and the app is in the foreground (battery friendly).
 */
@Composable
fun MonitorWhileVisible(vm: EnergyCenterViewModel, weather: WeatherState) {
    LaunchedEffect(weather.forecast, weather.climate) { vm.setWeather(weather.forecast, weather.climate) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> vm.setMonitoring(true)
                Lifecycle.Event.ON_STOP -> vm.setMonitoring(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.setMonitoring(true)
        onDispose {
            lifecycle.removeObserver(observer)
            vm.setMonitoring(false)
        }
    }
}

@Composable
fun EnergyCenterScreen(weather: WeatherState, modifier: Modifier = Modifier, vm: EnergyCenterViewModel = viewModel()) {
    MonitorWhileVisible(vm, weather)
    var page by rememberSaveable { mutableStateOf("main") }
    // System Back closes a sub-page first (instead of leaving the tab or the app).
    BackHandler(enabled = page != "main") { page = "main" }
    when (page) {
        "shading" -> ShadingScreen(vm, onBack = { page = "main" }, modifier = modifier)
        "config" -> EnergyConfigScreen(vm, onBack = { page = "main" }, modifier = modifier)
        "insights" -> InsightsScreen(vm, onBack = { page = "main" }, modifier = modifier)
        else -> EnergyCenterMain(vm, onShading = { page = "shading" }, onConfig = { page = "config" }, onInsights = { page = "insights" }, modifier = modifier)
    }
}

@Composable
private fun EnergyCenterMain(vm: EnergyCenterViewModel, onShading: () -> Unit, onConfig: () -> Unit, onInsights: () -> Unit, modifier: Modifier) {
    val live by vm.live.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val forecast by vm.forecast.collectAsStateWithLifecycle()
    val shading by vm.shading.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val config by vm.inverterConfig.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(stringResource(R.string.ec_title), stringResource(R.string.ec_subtitle))
        message?.let {
            SectionCard {
                Text(it)
                TextButton(onClick = vm::dismissMessage) { Text("OK") }
            }
        }

        // CONNECTION
        SectionCard {
            Text(stringResource(R.string.ec_connection), fontWeight = FontWeight.Bold)
            val enabled = config?.enabled == true
            if (!enabled) {
                Text(stringResource(R.string.ec_not_configured))
            } else {
                val status = when {
                    live.connection.status == LinkStatus.OFFLINE -> "OFFLINE"
                    live.freshness == Freshness.STALE -> "STALE DATA"
                    live.connection.status == LinkStatus.ONLINE -> "ONLINE"
                    live.connection.status == LinkStatus.CONNECTING -> stringResource(R.string.ec_connecting)
                    live.connection.status == LinkStatus.DEGRADED -> stringResource(R.string.ec_read_problem)
                    else -> stringResource(R.string.ec_disconnected)
                }
                Text(stringResource(R.string.ec_device_status, live.info?.manufacturer ?: stringResource(R.string.inverter), status), style = MaterialTheme.typography.titleMedium,
                    color = if (status == "ONLINE") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (live.info?.simulated == true) Text(stringResource(R.string.ec_simulator_warning), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                live.info?.let { Text("${it.model} · ${it.protocol} · ${it.interfaceDescription}", style = MaterialTheme.typography.bodySmall) }
                live.telemetry?.let { t ->
                    val age = Duration.between(t.timestamp, live.now).seconds.coerceAtLeast(0)
                    Text(stringResource(R.string.ec_measured_ago, hms.format(t.timestamp), age.toString()), style = MaterialTheme.typography.bodySmall)
                }
                Text(stringResource(R.string.ec_link_quality, Fmt.conf(live.connection.quality)) + (live.connection.lastLatencyMs?.let { stringResource(R.string.ec_latency, it.toString()) } ?: ""), style = MaterialTheme.typography.bodySmall)
                live.connection.lastError?.let { Text(stringResource(R.string.ec_last_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                val c = live.connection
                if (c.readsOk + c.readsFailed > 0) {
                    Text(stringResource(R.string.ec_reads, c.readsOk.toString(), c.readsFailed.toString(), c.reconnects.toString()) +
                        c.errorCounts.entries.joinToString("") { " · ${it.key.label}: ${it.value}" }, style = MaterialTheme.typography.bodySmall)
                }
                live.validation?.issues?.takeIf { it.isNotEmpty() }?.let { issues ->
                    Text(stringResource(R.string.ec_data_quality), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    issues.forEach { Text("• ${it.type.label}: ${it.detail}", style = MaterialTheme.typography.bodySmall) }
                }
                live.diagnostics.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.ec_register_map), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (enabled) FilledTonalButton(onClick = vm::refreshNow) { Text(stringResource(R.string.widget_refresh)) }
                OutlinedButton(onClick = onConfig) { Text(stringResource(R.string.ec_configuration)) }
            }
            OutlinedButton(onClick = onInsights, modifier = Modifier.fillMaxWidth().testTag("open_insights")) { Text(stringResource(R.string.ec_open_insights)) }
        }

        LiveSection(vm)
        FlowSection(vm)
        EnergySecurityCard(forecast.security)

        // PRODUCTION
        SectionCard {
            Text(stringResource(R.string.ec_production), fontWeight = FontWeight.Bold)
            MetricRow(stringResource(R.string.ec_produced_today), Fmt.kwh(forecast.producedTodayKwh), DataKind.CALCULATED, stringResource(R.string.ec_no_history_today))
            MetricRow(stringResource(R.string.ec_expected_today), Fmt.kwh(forecast.today?.expectedKwh), DataKind.FORECAST)
            MetricRow(stringResource(R.string.ec_shading_loss_today), Fmt.kwh(shading.today?.lossKwh), DataKind.CALCULATED, shading.reason)
            MetricRow(stringResource(R.string.ec_model_now), Fmt.kwFromKw(model.modelKw), DataKind.ESTIMATED)
            MetricRow(stringResource(R.string.ec_real_now), Fmt.kw(live.telemetry?.pv?.powerW?.takeIf { live.freshness == Freshness.LIVE }), DataKind.MEASURED, stringResource(R.string.ec_no_current_reading))
            MetricRow(stringResource(R.string.ec_difference), Fmt.signedPct(model.comparison?.differencePercent?.takeIf { live.freshness == Freshness.LIVE }), DataKind.CALCULATED, "—")
            val actualKw = live.telemetry?.pv?.powerW?.takeIf { live.freshness == Freshness.LIVE }?.div(1000.0)
            forecast.nowForecastKw?.let { fk ->
                MetricRow(stringResource(R.string.ec_forecast_now), Fmt.kwFromKw(fk), DataKind.FORECAST)
                MetricRow(stringResource(R.string.ec_forecast_error), actualKw?.let { a -> ForecastComparison.relativeError(fk, a) }?.let { Fmt.signedPct(it * 100) },
                    DataKind.CALCULATED, stringResource(R.string.ec_no_reading_or_zero))
            }
            model.comparison?.takeIf { live.freshness == Freshness.LIVE }?.causes?.take(3)?.forEach {
                Text("• ${it.cause.uiLabel}: ${it.explanation}", style = MaterialTheme.typography.bodySmall)
            }
            model.calibration?.let { Text(stringResource(R.string.ec_calibration, it.reason) + if (it.ready) stringResource(R.string.ec_calibration_factor, "%.2f".format(it.factor), Fmt.conf(it.confidence)) else "", style = MaterialTheme.typography.bodySmall) }
            model.calibrationModel?.let { Text(stringResource(R.string.ec_calibration3, it.describe()), style = MaterialTheme.typography.bodySmall) }
        }

        PerformanceCard(model.performance)

        // FORECAST
        SectionCard {
            Text(stringResource(R.string.ec_pv_forecast), fontWeight = FontWeight.Bold)
            forecast.shortTerm.forEach { s ->
                Row(Modifier.fillMaxWidth()) {
                    Text(s.horizon.label, Modifier.weight(1f))
                    Text("${Fmt.kwFromKw(s.point.expectedKw, s.decimals)} (${Fmt.kwFromKw(s.point.minKw, 1)}–${Fmt.kwFromKw(s.point.maxKw, 1)})")
                }
            }
            forecast.shortTerm.firstOrNull()?.let { Text(stringResource(R.string.ec_basis, it.point.basis), style = MaterialTheme.typography.bodySmall) }
            listOf(stringResource(R.string.today_upper) to forecast.today, stringResource(R.string.tomorrow_upper) to forecast.tomorrow).forEach { (label, d) ->
                if (d != null) {
                    Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    MetricRow(stringResource(R.string.ec_expected), Fmt.kwh(d.expectedKwh), DataKind.FORECAST)
                    d.producedKwh?.let { MetricRow(stringResource(R.string.ec_so_far), Fmt.kwh(it), DataKind.CALCULATED) }
                    if (d.producedKwh != null) MetricRow(stringResource(R.string.ec_remaining), Fmt.kwh(d.remainingKwh), DataKind.FORECAST)
                    MetricRow(stringResource(R.string.ec_min_max), "${Fmt.kwh(d.minKwh)} / ${Fmt.kwh(d.maxKwh)}", DataKind.FORECAST)
                    MetricRow(stringResource(R.string.ec_shading_loss), Fmt.kwh(d.shadingLossKwh), DataKind.CALCULATED)
                    Text(stringResource(R.string.ec_confidence, Fmt.conf(d.confidence)), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (forecast.timeline.isNotEmpty()) {
                Text(stringResource(R.string.ec_next_hours), fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                forecast.timeline.take(13).forEach { r ->
                    Text(stringResource(R.string.ec_timeline_row, hm.format(r.time), "%.2f".format(r.pvKw), "%.2f".format(r.shadingLossKw), "%.2f".format(r.loadKw)),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // BATTERY
        SectionCard {
            Text(stringResource(R.string.ec_battery), fontWeight = FontWeight.Bold)
            val b = forecast.battery
            if (b == null) {
                Text(stringResource(R.string.ec_soc_forecast_needs))
            } else {
                MetricRow(stringResource(R.string.ec_soc_now), Fmt.pct(b.startSoc), b.startKind)
                b.milestones.forEach { MetricRow("SOC ${it.label}", Fmt.pct(it.socPercent), DataKind.FORECAST) }
                MetricRow(stringResource(R.string.ec_energy_available), Fmt.kwh(b.energyAvailableKwh), DataKind.CALCULATED)
                b.chargingStarts?.let { Text(stringResource(R.string.ec_charging_from, hm.format(it)) + (b.chargingEnds?.let { e -> stringResource(R.string.ec_until, hm.format(e)) } ?: "")) }
                b.emptyAt?.let { Text(stringResource(R.string.ec_min_soc_at, hm.format(it)), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.ec_confidence, Fmt.conf(b.confidence)), style = MaterialTheme.typography.bodySmall)
            }
        }

        // SHADING
        SectionCard {
            Text(stringResource(R.string.ec_shading), fontWeight = FontWeight.Bold)
            if (!shading.ready) {
                Text(shading.reason ?: if (shading.computing) stringResource(R.string.ec_computing) else stringResource(R.string.ec_no_shading_model))
            } else {
                shading.reason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                MetricRow(stringResource(R.string.ec_shaded_now), Fmt.pct(shading.current?.shadedAreaFraction?.times(100)), shading.confidence?.kind ?: DataKind.ESTIMATED)
                val next = shading.next
                MetricRow(stringResource(R.string.ec_next_shade), next?.let { "${hm.format(it.event.start)}–${hm.format(it.event.end)} · ${it.obstacleName}" }, DataKind.FORECAST, stringResource(R.string.ec_none_next_days))
                MetricRow(stringResource(R.string.ec_loss_today), Fmt.kwh(shading.today?.lossKwh), shading.confidence?.kind ?: DataKind.ESTIMATED)
                val annual = shading.year.sumOf { it.lossKwh }
                val annualTotal = shading.year.sumOf { it.unshadedKwh }
                if (shading.year.isNotEmpty()) MetricRow(stringResource(R.string.ec_loss_year), "${Fmt.kwh(annual)} (${"%.1f".format(if (annualTotal > 0) annual / annualTotal * 100 else 0.0)}%)", shading.confidence?.kind ?: DataKind.ESTIMATED)
                Text(stringResource(R.string.ec_confidence, Fmt.conf(shading.confidence?.score)), style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onShading) { Text(stringResource(R.string.ec_shading_analysis)) }
        }

        // ALERTS
        SectionCard {
            Text(stringResource(R.string.ec_alerts), fontWeight = FontWeight.Bold)
            if (alerts.isEmpty()) Text(stringResource(R.string.ec_no_alerts))
            alerts.forEach { a ->
                Text("${a.type.title}${if (a.occurrences > 1) " (×${a.occurrences})" else ""}",
                    color = if (a.type.severity == AlertSeverity.INFO) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.ec_alert_since, a.detail, hm.format(a.firstSeen)), style = MaterialTheme.typography.bodySmall)
            }
        }

        AdvisorSection(vm)
    }
}

@Composable
private fun LiveSection(vm: EnergyCenterViewModel) {
    val live by vm.live.collectAsStateWithLifecycle()
    val t = live.telemetry
    val kind = freshnessKind(live.freshness)
    val caps = live.capabilities
    val checked = live.validation?.values.orEmpty()
    fun k(field: TelemetryField) = if (checked[field]?.kind == DataKind.INVALID) DataKind.INVALID else kind
    val context = LocalContext.current
    fun na(field: TelemetryField) = checked[field]?.takeIf { it.kind == DataKind.INVALID }?.let { context.getString(R.string.ec_rejected, it.reason ?: it.validity.label) }
        ?: if (field !in caps && caps.isNotEmpty()) context.getString(R.string.ec_not_in_protocol) else context.getString(R.string.no_data)
    SectionCard {
        Text(if (live.freshness == Freshness.LIVE) "LIVE" else "LIVE – ${kind.uiLabel}", fontWeight = FontWeight.Bold,
            color = if (live.freshness == Freshness.LIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        if (t == null) {
            Text(stringResource(R.string.ec_no_inverter_reading))
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigMetric("PV", Fmt.kw(t.pv.powerW) ?: "N/A", k(TelemetryField.PV_POWER))
            BigMetric("LOAD", Fmt.kw(t.load.powerW) ?: "N/A", k(TelemetryField.LOAD_POWER))
            BigMetric("BATTERY", Fmt.v(t.battery.voltageV) ?: "N/A", k(TelemetryField.BATTERY_VOLTAGE))
            val socEst = live.soc?.takeIf { t.battery.socPercent == null && it.kind == DataKind.ESTIMATED }
            if (socEst != null) BigMetric(stringResource(R.string.ec_soc_from_voltage), "~${Fmt.pct(socEst.value)} ±${socEst.uncertainty?.toInt() ?: "?"}", DataKind.ESTIMATED)
            else BigMetric("SOC", Fmt.pct(t.battery.socPercent) ?: "N/A", k(TelemetryField.BATTERY_SOC))
            BigMetric("BATTERY FLOW", Fmt.signedKw(t.battery.powerW) ?: "N/A", k(TelemetryField.BATTERY_POWER))
            BigMetric("GRID", Fmt.signedKw(t.grid.powerW) ?: "N/A", k(TelemetryField.GRID_POWER))
        }
        Text(stringResource(R.string.ec_inverter_mode, t.inverter.mode.name) + (t.inverter.rawMode?.let { stringResource(R.string.ec_code, it.toString()) } ?: ""), fontWeight = FontWeight.SemiBold)
        Text(
            when (t.battery.state) {
                BatteryFlowState.CHARGING -> stringResource(R.string.ec_bat_charging)
                BatteryFlowState.DISCHARGING -> stringResource(R.string.ec_bat_discharging)
                BatteryFlowState.IDLE -> stringResource(R.string.ec_bat_idle)
                BatteryFlowState.DISCONNECTED -> stringResource(R.string.ec_bat_disconnected)
                BatteryFlowState.UNKNOWN -> stringResource(R.string.ec_bat_unknown)
            },
        )
        var details by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { details = !details }) { Text(if (details) stringResource(R.string.ec_hide_details) else stringResource(R.string.ec_all_params)) }
        if (details) {
            MetricRow(stringResource(R.string.ec_pv_voltage), Fmt.v(t.pv.voltageV), k(TelemetryField.PV_VOLTAGE), na(TelemetryField.PV_VOLTAGE))
            MetricRow(stringResource(R.string.ec_pv_current), Fmt.a(t.pv.currentA), k(TelemetryField.PV_CURRENT), na(TelemetryField.PV_CURRENT))
            MetricRow(stringResource(R.string.ec_pv_charging_power), Fmt.kw(t.pv.chargingPowerW), k(TelemetryField.PV_CHARGING_POWER), na(TelemetryField.PV_CHARGING_POWER))
            MetricRow(stringResource(R.string.ec_pv_energy_today), Fmt.kwh(t.pv.energyTodayKwh), k(TelemetryField.PV_ENERGY_TODAY), na(TelemetryField.PV_ENERGY_TODAY))
            MetricRow(stringResource(R.string.ec_pv_energy_total), Fmt.kwh(t.pv.energyTotalKwh), k(TelemetryField.PV_ENERGY_TOTAL), na(TelemetryField.PV_ENERGY_TOTAL))
            MetricRow(stringResource(R.string.ec_bat_current), Fmt.a(t.battery.currentA), k(TelemetryField.BATTERY_CURRENT), na(TelemetryField.BATTERY_CURRENT))
            MetricRow(stringResource(R.string.ec_bat_temp), Fmt.c(t.battery.temperatureC), k(TelemetryField.BATTERY_TEMPERATURE), na(TelemetryField.BATTERY_TEMPERATURE))
            MetricRow(stringResource(R.string.ec_charge_power), Fmt.kw(t.battery.chargePowerW), k(TelemetryField.BATTERY_POWER), na(TelemetryField.BATTERY_POWER))
            MetricRow(stringResource(R.string.ec_discharge_power), Fmt.kw(t.battery.dischargePowerW), k(TelemetryField.BATTERY_POWER), na(TelemetryField.BATTERY_POWER))
            MetricRow(stringResource(R.string.ec_grid_voltage), Fmt.v(t.grid.voltageV), k(TelemetryField.GRID_VOLTAGE), na(TelemetryField.GRID_VOLTAGE))
            MetricRow(stringResource(R.string.ec_grid_current), Fmt.a(t.grid.currentA), k(TelemetryField.GRID_CURRENT), na(TelemetryField.GRID_CURRENT))
            MetricRow(stringResource(R.string.ec_grid_freq), Fmt.hz(t.grid.frequencyHz), k(TelemetryField.GRID_FREQUENCY), na(TelemetryField.GRID_FREQUENCY))
            MetricRow(stringResource(R.string.ec_grid_import), Fmt.kw(t.grid.importPowerW), k(TelemetryField.GRID_POWER), na(TelemetryField.GRID_POWER))
            MetricRow(stringResource(R.string.ec_grid_export), Fmt.kw(t.grid.exportPowerW), k(TelemetryField.GRID_POWER), na(TelemetryField.GRID_POWER))
            MetricRow(stringResource(R.string.ec_energy_import_export), t.grid.importEnergyKwh?.let { "${Fmt.kwh(it)} / ${Fmt.kwh(t.grid.exportEnergyKwh)}" }, k(TelemetryField.GRID_IMPORT_ENERGY), na(TelemetryField.GRID_IMPORT_ENERGY))
            MetricRow(stringResource(R.string.ec_load_percent), Fmt.pct(t.load.percent), k(TelemetryField.LOAD_PERCENT), na(TelemetryField.LOAD_PERCENT))
            MetricRow(stringResource(R.string.ec_apparent_power), t.load.apparentPowerVa?.let { "%.0f VA".format(it) }, k(TelemetryField.LOAD_APPARENT_POWER), na(TelemetryField.LOAD_APPARENT_POWER))
            MetricRow(stringResource(R.string.ec_load_energy), Fmt.kwh(t.load.energyTodayKwh), k(TelemetryField.LOAD_ENERGY), na(TelemetryField.LOAD_ENERGY))
            MetricRow(stringResource(R.string.ec_inverter_power), Fmt.kw(t.inverter.powerW), k(TelemetryField.INVERTER_POWER), na(TelemetryField.INVERTER_POWER))
            MetricRow(stringResource(R.string.ec_output_voltage), Fmt.v(t.inverter.outputVoltageV), k(TelemetryField.OUTPUT_VOLTAGE), na(TelemetryField.OUTPUT_VOLTAGE))
            MetricRow(stringResource(R.string.ec_output_freq), Fmt.hz(t.inverter.outputFrequencyHz), k(TelemetryField.OUTPUT_FREQUENCY), na(TelemetryField.OUTPUT_FREQUENCY))
            MetricRow(stringResource(R.string.ec_inverter_temp), Fmt.c(t.inverter.temperatureC), k(TelemetryField.INVERTER_TEMPERATURE), na(TelemetryField.INVERTER_TEMPERATURE))
            MetricRow(stringResource(R.string.ec_dcdc_temp), Fmt.c(t.inverter.auxTemperatureC), k(TelemetryField.INVERTER_TEMPERATURE), na(TelemetryField.INVERTER_TEMPERATURE))
            if (t.mppts.isEmpty()) MetricRow(stringResource(R.string.ec_mppt_strings), null, k(TelemetryField.MPPT_DETAILS), na(TelemetryField.MPPT_DETAILS))
            t.mppts.forEach { m -> MetricRow("MPPT${m.index}", "${Fmt.v(m.voltageV)} ${Fmt.a(m.currentA)} ${Fmt.kw(m.powerW)}", kind) }
            Text(stringResource(R.string.ec_warnings, t.inverter.warnings.joinToString { it.description }.ifEmpty { stringResource(R.string.none) }), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.ec_faults, t.inverter.faults.joinToString { "${it.code}: ${it.description}" }.ifEmpty { stringResource(R.string.none) }), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FlowSection(vm: EnergyCenterViewModel) {
    val live by vm.live.collectAsStateWithLifecycle()
    val flow = live.flow ?: return
    SectionCard {
        Text(stringResource(R.string.ec_energy_flow), fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.ec_flow_hint), style = MaterialTheme.typography.bodySmall)
        if (flow.active.isEmpty()) Text(stringResource(R.string.ec_no_flows))
        flow.active.forEach { (name, w) -> MetricRow(name, Fmt.kw(w), if (live.freshness == Freshness.LIVE) DataKind.CALCULATED else freshnessKind(live.freshness)) }
        if (!flow.consistent) {
            Text(
                if (flow.missing.isNotEmpty()) stringResource(R.string.ec_flow_missing, flow.missing.joinToString())
                else stringResource(R.string.ec_flow_residual, "%.0f".format(flow.residualW)),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AdvisorSection(vm: EnergyCenterViewModel) {
    var answer by rememberSaveable { mutableStateOf<String?>(null) }
    var question by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    fun show(a: AdvisorAnswer) {
        answer = "${a.question.text}\n\n${a.text}" + if (a.usedData.isNotEmpty()) "\n\n" + context.getString(R.string.ec_advisor_sources, a.usedData.joinToString()) else ""
    }
    SectionCard {
        Text("SOLAR ADVISOR", fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.ec_advisor_hint), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AdvisorQuestion.entries.forEach { q -> AssistChip(onClick = { show(vm.ask(q)) }, label = { Text(q.text) }) }
        }
        OutlinedTextField(question, { question = it.take(200) }, label = { Text(stringResource(R.string.ec_ask_question)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FilledTonalButton(onClick = {
            val q = SolarAdvisor.match(question)
            answer = if (q == null) context.getString(R.string.ec_advisor_unknown) else null
            if (q != null) show(vm.ask(q))
        }, enabled = question.isNotBlank()) { Text(stringResource(R.string.ec_ask)) }
        answer?.let { Text(it) }
    }
}
