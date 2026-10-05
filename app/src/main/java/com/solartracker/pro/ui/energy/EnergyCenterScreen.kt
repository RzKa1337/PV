package com.solartracker.pro.ui.energy

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
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.solartracker.pro.core.analytics.AlertSeverity
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
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
private val hms = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

@Composable
fun EnergyCenterScreen(weather: WeatherState, modifier: Modifier = Modifier, vm: EnergyCenterViewModel = viewModel()) {
    LaunchedEffect(weather.forecast, weather.climate) { vm.setWeather(weather.forecast, weather.climate) }
    // Monitoring only while this screen is visible and the app is in the foreground.
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
    var page by rememberSaveable { mutableStateOf("main") }
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
        ScreenTitle("Centrum energii", "Falownik, prognozy, zacienienie")
        message?.let {
            SectionCard {
                Text(it)
                TextButton(onClick = vm::dismissMessage) { Text("OK") }
            }
        }

        // CONNECTION
        SectionCard {
            Text("POŁĄCZENIE", fontWeight = FontWeight.Bold)
            val enabled = config?.enabled == true
            if (!enabled) {
                Text("Falownik nie jest skonfigurowany. Dane LIVE niedostępne – prognozy korzystają tylko z modelu.")
            } else {
                val status = when {
                    live.connection.status == LinkStatus.OFFLINE -> "OFFLINE"
                    live.freshness == Freshness.STALE -> "STALE DATA"
                    live.connection.status == LinkStatus.ONLINE -> "ONLINE"
                    live.connection.status == LinkStatus.CONNECTING -> "ŁĄCZENIE…"
                    live.connection.status == LinkStatus.DEGRADED -> "PROBLEM Z ODCZYTEM"
                    else -> "ROZŁĄCZONY"
                }
                Text("${live.info?.manufacturer ?: "Falownik"}: $status", style = MaterialTheme.typography.titleMedium,
                    color = if (status == "ONLINE") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (live.info?.simulated == true) Text("SYMULATOR – dane testowe, to nie są pomiary.", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                live.info?.let { Text("${it.model} · ${it.protocol} · ${it.interfaceDescription}", style = MaterialTheme.typography.bodySmall) }
                live.telemetry?.let { t ->
                    val age = Duration.between(t.timestamp, live.now).seconds.coerceAtLeast(0)
                    Text("Pomiar: ${hms.format(t.timestamp)} · ${age} s temu", style = MaterialTheme.typography.bodySmall)
                }
                Text("Jakość łącza: ${Fmt.conf(live.connection.quality)}" + (live.connection.lastLatencyMs?.let { " · odpowiedź $it ms" } ?: ""), style = MaterialTheme.typography.bodySmall)
                live.connection.lastError?.let { Text("Ostatni błąd: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (enabled) FilledTonalButton(onClick = vm::refreshNow) { Text("Odśwież") }
                OutlinedButton(onClick = onConfig) { Text("Konfiguracja") }
            }
            OutlinedButton(onClick = onInsights, modifier = Modifier.fillMaxWidth().testTag("open_insights")) { Text("Analizy: zdrowie, EMS, historia, eksport") }
        }

        LiveSection(vm)
        FlowSection(vm)

        // PRODUCTION
        SectionCard {
            Text("PRODUKCJA", fontWeight = FontWeight.Bold)
            MetricRow("Dziś wyprodukowano", Fmt.kwh(forecast.producedTodayKwh), DataKind.CALCULATED, "brak historii pomiarów z dziś")
            MetricRow("Dziś oczekiwane", Fmt.kwh(forecast.today?.expectedKwh), DataKind.FORECAST)
            MetricRow("Strata przez zacienienie (dziś)", Fmt.kwh(shading.today?.lossKwh), DataKind.CALCULATED, shading.reason)
            MetricRow("MODEL teraz (z zacienieniem)", Fmt.kwFromKw(model.modelKw), DataKind.ESTIMATED)
            MetricRow("REAL teraz (Anenji)", Fmt.kw(live.telemetry?.pv?.powerW?.takeIf { live.freshness == Freshness.LIVE }), DataKind.MEASURED, "brak bieżącego pomiaru")
            MetricRow("Różnica", Fmt.signedPct(model.comparison?.differencePercent?.takeIf { live.freshness == Freshness.LIVE }), DataKind.CALCULATED, "—")
            model.comparison?.takeIf { live.freshness == Freshness.LIVE }?.causes?.take(3)?.forEach {
                Text("• ${it.cause.label}: ${it.explanation}", style = MaterialTheme.typography.bodySmall)
            }
            model.calibration?.let { Text("Kalibracja modelu: ${it.reason}" + if (it.ready) " (×${"%.2f".format(it.factor)}, pewność ${Fmt.conf(it.confidence)})" else "", style = MaterialTheme.typography.bodySmall) }
        }

        // FORECAST
        SectionCard {
            Text("PROGNOZA PV", fontWeight = FontWeight.Bold)
            forecast.shortTerm.forEach { s ->
                Row(Modifier.fillMaxWidth()) {
                    Text(s.horizon.label, Modifier.weight(1f))
                    Text("${Fmt.kwFromKw(s.point.expectedKw, s.decimals)} (${Fmt.kwFromKw(s.point.minKw, 1)}–${Fmt.kwFromKw(s.point.maxKw, 1)})")
                }
            }
            forecast.shortTerm.firstOrNull()?.let { Text("Podstawa: ${it.point.basis}", style = MaterialTheme.typography.bodySmall) }
            listOf("DZIŚ" to forecast.today, "JUTRO" to forecast.tomorrow).forEach { (label, d) ->
                if (d != null) {
                    Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                    MetricRow("Oczekiwane", Fmt.kwh(d.expectedKwh), DataKind.FORECAST)
                    d.producedKwh?.let { MetricRow("Dotychczas", Fmt.kwh(it), DataKind.CALCULATED) }
                    if (d.producedKwh != null) MetricRow("Pozostało", Fmt.kwh(d.remainingKwh), DataKind.FORECAST)
                    MetricRow("Minimum / maksimum", "${Fmt.kwh(d.minKwh)} / ${Fmt.kwh(d.maxKwh)}", DataKind.FORECAST)
                    MetricRow("Strata przez zacienienie", Fmt.kwh(d.shadingLossKwh), DataKind.CALCULATED)
                    Text("Pewność: ${Fmt.conf(d.confidence)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (forecast.timeline.isNotEmpty()) {
                Text("Najbliższe godziny (PV / cień / zużycie)", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                forecast.timeline.take(13).forEach { r ->
                    Text("${hm.format(r.time)}  PV ${"%.2f".format(r.pvKw)} kW · cień −${"%.2f".format(r.shadingLossKw)} · zużycie ${"%.2f".format(r.loadKw)} kW",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // BATTERY
        SectionCard {
            Text("BATERIA", fontWeight = FontWeight.Bold)
            val b = forecast.battery
            if (b == null) {
                Text("Prognoza SOC wymaga odczytu SOC z falownika i włączonej baterii w ustawieniach.")
            } else {
                MetricRow("SOC teraz", Fmt.pct(b.startSoc), b.startKind)
                b.milestones.forEach { MetricRow("SOC ${it.label}", Fmt.pct(it.socPercent), DataKind.FORECAST) }
                MetricRow("Energia dostępna", Fmt.kwh(b.energyAvailableKwh), DataKind.CALCULATED)
                b.chargingStarts?.let { Text("Ładowanie od: ${hm.format(it)}" + (b.chargingEnds?.let { e -> " do ${hm.format(e)}" } ?: "")) }
                b.emptyAt?.let { Text("Minimalny SOC osiągnięty ok. ${hm.format(it)}", color = MaterialTheme.colorScheme.error) }
                Text("Pewność: ${Fmt.conf(b.confidence)}", style = MaterialTheme.typography.bodySmall)
            }
        }

        // SHADING
        SectionCard {
            Text("ZACIENIENIE", fontWeight = FontWeight.Bold)
            if (!shading.ready) {
                Text(shading.reason ?: if (shading.computing) "Obliczanie…" else "Brak modelu zacienienia")
            } else {
                shading.reason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                MetricRow("Teraz zacienione", Fmt.pct(shading.current?.shadedAreaFraction?.times(100)), shading.confidence?.kind ?: DataKind.ESTIMATED)
                val next = shading.next
                MetricRow("Następny cień", next?.let { "${hm.format(it.event.start)}–${hm.format(it.event.end)} · ${it.obstacleName}" }, DataKind.FORECAST, "brak w najbliższych dniach")
                MetricRow("Strata dziś", Fmt.kwh(shading.today?.lossKwh), shading.confidence?.kind ?: DataKind.ESTIMATED)
                val annual = shading.year.sumOf { it.lossKwh }
                val annualTotal = shading.year.sumOf { it.unshadedKwh }
                if (shading.year.isNotEmpty()) MetricRow("Strata roczna", "${Fmt.kwh(annual)} (${"%.1f".format(if (annualTotal > 0) annual / annualTotal * 100 else 0.0)}%)", shading.confidence?.kind ?: DataKind.ESTIMATED)
                Text("Pewność: ${Fmt.conf(shading.confidence?.score)}", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = onShading) { Text("Analiza zacienienia") }
        }

        // ALERTS
        SectionCard {
            Text("ALERTY", fontWeight = FontWeight.Bold)
            if (alerts.isEmpty()) Text("Brak aktywnych ostrzeżeń.")
            alerts.forEach { a ->
                Text("${a.type.title}${if (a.occurrences > 1) " (×${a.occurrences})" else ""}",
                    color = if (a.type.severity == AlertSeverity.INFO) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                Text("${a.detail} · od ${hm.format(a.firstSeen)}", style = MaterialTheme.typography.bodySmall)
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
    fun na(field: TelemetryField) = if (field !in caps && caps.isNotEmpty()) "niedostępne w tym protokole" else "brak danych"
    SectionCard {
        Text(if (live.freshness == Freshness.LIVE) "LIVE" else "LIVE – ${kind.label}", fontWeight = FontWeight.Bold,
            color = if (live.freshness == Freshness.LIVE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        if (t == null) {
            Text("Brak odczytu z falownika.")
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            BigMetric("PV", Fmt.kw(t.pv.powerW) ?: "N/A", kind)
            BigMetric("LOAD", Fmt.kw(t.load.powerW) ?: "N/A", kind)
            BigMetric("BATTERY", Fmt.v(t.battery.voltageV) ?: "N/A", kind)
            BigMetric("SOC", Fmt.pct(t.battery.socPercent) ?: "N/A", kind)
            BigMetric("BATTERY FLOW", Fmt.signedKw(t.battery.powerW) ?: "N/A", kind)
            BigMetric("GRID", Fmt.signedKw(t.grid.powerW) ?: "N/A", kind)
        }
        Text("Falownik: ${t.inverter.mode.name}" + (t.inverter.rawMode?.let { " (kod $it)" } ?: ""), fontWeight = FontWeight.SemiBold)
        Text(
            when (t.battery.state) {
                BatteryFlowState.CHARGING -> "Bateria: ładowanie"
                BatteryFlowState.DISCHARGING -> "Bateria: rozładowanie"
                BatteryFlowState.IDLE -> "Bateria: bez przepływu"
                BatteryFlowState.DISCONNECTED -> "Bateria: niepodłączona"
                BatteryFlowState.UNKNOWN -> "Bateria: stan nieznany"
            },
        )
        var details by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { details = !details }) { Text(if (details) "Ukryj szczegóły" else "Wszystkie parametry") }
        if (details) {
            MetricRow("PV napięcie", Fmt.v(t.pv.voltageV), kind, na(TelemetryField.PV_VOLTAGE))
            MetricRow("PV prąd", Fmt.a(t.pv.currentA), kind, na(TelemetryField.PV_CURRENT))
            MetricRow("PV moc ładowania", Fmt.kw(t.pv.chargingPowerW), kind, na(TelemetryField.PV_CHARGING_POWER))
            MetricRow("PV energia dziś", Fmt.kwh(t.pv.energyTodayKwh), kind, na(TelemetryField.PV_ENERGY_TODAY))
            MetricRow("PV energia całkowita", Fmt.kwh(t.pv.energyTotalKwh), kind, na(TelemetryField.PV_ENERGY_TOTAL))
            MetricRow("Bateria prąd", Fmt.a(t.battery.currentA), kind, na(TelemetryField.BATTERY_CURRENT))
            MetricRow("Bateria temperatura", Fmt.c(t.battery.temperatureC), kind, na(TelemetryField.BATTERY_TEMPERATURE))
            MetricRow("Moc ładowania", Fmt.kw(t.battery.chargePowerW), kind, na(TelemetryField.BATTERY_POWER))
            MetricRow("Moc rozładowania", Fmt.kw(t.battery.dischargePowerW), kind, na(TelemetryField.BATTERY_POWER))
            MetricRow("Sieć napięcie", Fmt.v(t.grid.voltageV), kind, na(TelemetryField.GRID_VOLTAGE))
            MetricRow("Sieć prąd", Fmt.a(t.grid.currentA), kind, na(TelemetryField.GRID_CURRENT))
            MetricRow("Sieć częstotliwość", Fmt.hz(t.grid.frequencyHz), kind, na(TelemetryField.GRID_FREQUENCY))
            MetricRow("Import z sieci", Fmt.kw(t.grid.importPowerW), kind, na(TelemetryField.GRID_POWER))
            MetricRow("Eksport do sieci", Fmt.kw(t.grid.exportPowerW), kind, na(TelemetryField.GRID_POWER))
            MetricRow("Energia import/eksport", t.grid.importEnergyKwh?.let { "${Fmt.kwh(it)} / ${Fmt.kwh(t.grid.exportEnergyKwh)}" }, kind, na(TelemetryField.GRID_IMPORT_ENERGY))
            MetricRow("Obciążenie", Fmt.pct(t.load.percent), kind, na(TelemetryField.LOAD_PERCENT))
            MetricRow("Moc pozorna", t.load.apparentPowerVa?.let { "%.0f VA".format(it) }, kind, na(TelemetryField.LOAD_APPARENT_POWER))
            MetricRow("Energia odbiorów", Fmt.kwh(t.load.energyTodayKwh), kind, na(TelemetryField.LOAD_ENERGY))
            MetricRow("Moc falownika", Fmt.kw(t.inverter.powerW), kind, na(TelemetryField.INVERTER_POWER))
            MetricRow("Napięcie wyjścia", Fmt.v(t.inverter.outputVoltageV), kind, na(TelemetryField.OUTPUT_VOLTAGE))
            MetricRow("Częstotliwość wyjścia", Fmt.hz(t.inverter.outputFrequencyHz), kind, na(TelemetryField.OUTPUT_FREQUENCY))
            MetricRow("Temperatura falownika", Fmt.c(t.inverter.temperatureC), kind, na(TelemetryField.INVERTER_TEMPERATURE))
            MetricRow("Temperatura DC/DC", Fmt.c(t.inverter.auxTemperatureC), kind, na(TelemetryField.INVERTER_TEMPERATURE))
            if (t.mppts.isEmpty()) MetricRow("MPPT / stringi", null, kind, na(TelemetryField.MPPT_DETAILS))
            t.mppts.forEach { m -> MetricRow("MPPT${m.index}", "${Fmt.v(m.voltageV)} ${Fmt.a(m.currentA)} ${Fmt.kw(m.powerW)}", kind) }
            Text("Ostrzeżenia: ${t.inverter.warnings.joinToString { it.description }.ifEmpty { "brak" }}", style = MaterialTheme.typography.bodySmall)
            Text("Błędy: ${t.inverter.faults.joinToString { "${it.code}: ${it.description}" }.ifEmpty { "brak" }}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FlowSection(vm: EnergyCenterViewModel) {
    val live by vm.live.collectAsStateWithLifecycle()
    val flow = live.flow ?: return
    SectionCard {
        Text("PRZEPŁYW ENERGII", fontWeight = FontWeight.Bold)
        Text("Kierunki obliczone z pomiarów (priorytet: PV → odbiory → bateria → sieć)", style = MaterialTheme.typography.bodySmall)
        if (flow.active.isEmpty()) Text("Brak przepływów powyżej 20 W.")
        flow.active.forEach { (name, w) -> MetricRow(name, Fmt.kw(w), if (live.freshness == Freshness.LIVE) DataKind.CALCULATED else freshnessKind(live.freshness)) }
        if (!flow.consistent) {
            Text(
                if (flow.missing.isNotEmpty()) "Brak danych: ${flow.missing.joinToString()} – bilans niepełny."
                else "Bilans nie zamyka się (różnica ${"%.0f".format(flow.residualW)} W – straty przetwarzania lub niespójne odczyty).",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun AdvisorSection(vm: EnergyCenterViewModel) {
    var answer by rememberSaveable { mutableStateOf<String?>(null) }
    var question by rememberSaveable { mutableStateOf("") }
    fun show(a: AdvisorAnswer) {
        answer = "${a.question.text}\n\n${a.text}" + if (a.usedData.isNotEmpty()) "\n\nŹródła: ${a.usedData.joinToString()}" else ""
    }
    SectionCard {
        Text("SOLAR ADVISOR", fontWeight = FontWeight.Bold)
        Text("Odpowiada wyłącznie na podstawie danych z falownika, historii, prognoz i modelu zacienienia.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AdvisorQuestion.entries.forEach { q -> AssistChip(onClick = { show(vm.ask(q)) }, label = { Text(q.text) }) }
        }
        OutlinedTextField(question, { question = it.take(200) }, label = { Text("Zadaj pytanie") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FilledTonalButton(onClick = {
            val q = SolarAdvisor.match(question)
            answer = if (q == null) "Nie rozpoznaję tego pytania. Wybierz jedno z pytań powyżej – odpowiadam tylko na podstawie danych instalacji." else null
            if (q != null) show(vm.ask(q))
        }, enabled = question.isNotBlank()) { Text("Zapytaj") }
        answer?.let { Text(it) }
    }
}
