package com.solartracker.pro.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.access.Feature
import com.solartracker.pro.core.access.FeatureAccessManager
import com.solartracker.pro.core.access.SubscriptionState
import com.solartracker.pro.core.design.AnnualYield
import com.solartracker.pro.core.design.DesignInput
import com.solartracker.pro.core.design.DesignResult
import com.solartracker.pro.core.design.LocationComparison
import com.solartracker.pro.core.design.MpptSpec
import com.solartracker.pro.core.design.PanelSpec
import com.solartracker.pro.core.design.PvDesigner
import com.solartracker.pro.core.design.SiteYield
import com.solartracker.pro.core.design.YieldEstimator
import com.solartracker.pro.core.economics.EconomicsEngine
import com.solartracker.pro.core.economics.EconomicsResult
import com.solartracker.pro.core.economics.EnergyYear
import com.solartracker.pro.core.economics.SystemCosts
import com.solartracker.pro.core.economics.TariffAssumptions
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.vehicle.VehicleEstimate
import com.solartracker.pro.core.vehicle.VehicleSolarConfig
import com.solartracker.pro.core.vehicle.VehicleSolarEstimator
import com.solartracker.pro.data.AppSettings
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private enum class Tool(val label: String, val feature: Feature) {
    DESIGNER("Projektant PV", Feature.DESIGNER),
    ECONOMICS("Ekonomia", Feature.ECONOMICS),
    LOCATIONS("Lokalizacje", Feature.LOCATION_COMPARISON),
    VEHICLE("Pojazd", Feature.VEHICLE),
}

/** Planning tools. Pure calculations from core; inputs come from the user or the saved installation. */
@Composable
fun ToolsScreen(settings: AppSettings, access: FeatureAccessManager, subscription: SubscriptionState, modifier: Modifier = Modifier) {
    var tool by rememberSaveable { mutableIntStateOf(0) }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle("Narzędzia", "Projekt, ekonomia, porównanie lokalizacji, pojazd")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool.entries.forEach { t ->
                FilterChip(selected = tool == t.ordinal, onClick = { tool = t.ordinal }, label = { Text(t.label) }, modifier = Modifier.testTag("tool_${t.name}"))
            }
        }
        Text("Plan: ${subscription.plan.name} — ${subscription.source.label}", style = MaterialTheme.typography.bodySmall)
        val t = Tool.entries[tool]
        val locked = access.reason(t.feature)
        if (locked != null) {
            SectionCard { Text(locked, color = MaterialTheme.colorScheme.error) }
        } else when (t) {
            Tool.DESIGNER -> DesignerTool(settings)
            Tool.ECONOMICS -> EconomicsTool(settings)
            Tool.LOCATIONS -> LocationsTool(settings)
            Tool.VEHICLE -> VehicleTool(settings)
        }
    }
}

private fun String.num(): Double? = replace(',', '.').trim().toDoubleOrNull()?.takeIf { it.isFinite() }
private fun f(v: Double, d: Int = 1) = String.format(Locale.ROOT, "%.${d}f", v)

@Composable
private fun Num(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { onChange(it.take(12)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier)
}

@Composable
private fun Pair2(a: @Composable (Modifier) -> Unit, b: @Composable (Modifier) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { a(Modifier.weight(1f)); b(Modifier.weight(1f)) }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- Designer

@Composable
private fun DesignerTool(settings: AppSettings) {
    // Datasheet values are left empty on purpose: the user copies them from the real panel / inverter.
    val v = remember { mutableStateListOf(*Array(17) { if (it == 14) "-25" else "" }) }
    val labels = listOf(
        "Moc panelu [W]", "Voc [V]", "Vmp [V]", "Isc [A]", "Imp [A]", "Wsp. temp. Voc [%/°C]", "Wsp. temp. Pmax [%/°C]", "Długość [m]", "Szerokość [m]",
        "Liczba MPPT", "MPPT min [V]", "MPPT max [V]", "Maks. napięcie wejścia [V]", "Maks. prąd na MPPT [A]", "Min. temp. otoczenia [°C]", "Moc AC falownika [W]", "Cel mocy [kWp] (opcj.)",
    )
    var area by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<DesignResult?>(null) }
    var yield by remember { mutableStateOf<AnnualYield?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var run by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf<DesignInput?>(null) }

    SectionCard {
        Text("Panel (karta katalogowa)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        for (i in 0 until 9 step 2) {
            if (i + 1 < 9) Pair2({ Num(labels[i], v[i], { s -> v[i] = s }, it) }, { Num(labels[i + 1], v[i + 1], { s -> v[i + 1] = s }, it) })
            else Num(labels[i], v[i], { s -> v[i] = s }, Modifier.fillMaxWidth())
        }
        Text("Falownik / regulator MPPT", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        for (i in 9 until 17 step 2) {
            if (i + 1 < 17) Pair2({ Num(labels[i], v[i], { s -> v[i] = s }, it) }, { Num(labels[i + 1], v[i + 1], { s -> v[i + 1] = s }, it) })
            else Num(labels[i], v[i], { s -> v[i] = s }, Modifier.fillMaxWidth())
        }
        Pair2({ Num("Powierzchnia [m²] (opcj.)", area, { s -> area = s }, it) }, { Num("Koszt [zł/Wp] (opcj.)", cost, { s -> cost = s }, it) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val n = v.map { it.num() }
            val required = (0..15)
            if (required.any { n[it] == null }) { error = "Uzupełnij wszystkie pola z kart katalogowych (cel mocy, powierzchnia i koszt są opcjonalne)"; return@Button }
            error = null
            input = DesignInput(
                PanelSpec(n[0]!!, n[1]!!, n[2]!!, n[3]!!, n[4]!!, n[5]!! / 100, n[6]!! / 100, n[7]!!, n[8]!!),
                MpptSpec(n[9]!!.toInt().coerceAtLeast(1), n[10]!!, n[11]!!, n[12]!!, n[13]!!, ratedAcW = n[15]!!),
                targetPowerW = n[16]?.let { it * 1000 }, areaM2 = area.num(), minAmbientC = n[14]!!, costPerWp = cost.num(),
            )
            run++
        }, modifier = Modifier.testTag("designer_run")) { Text("Zaprojektuj") }
    }
    LaunchedEffect(run) {
        val i = input ?: return@LaunchedEffect
        val r = withContext(Dispatchers.Default) { PvDesigner.design(i) }
        result = r
        yield = r.layout?.let { l ->
            withContext(Dispatchers.Default) {
                YieldEstimator.annual(PvArrayConfig(l.panels, i.panel.powerW, settings.system.tiltDeg, settings.system.azimuthDeg), LossProfile(), settings.location, stepMinutes = 60)
            }
        }
    }
    result?.let { r ->
        SectionCard {
            Text("Wynik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val l = r.layout
            if (l == null) Text("Brak poprawnej konfiguracji", color = MaterialTheme.colorScheme.error) else {
                Line("Układ", "${l.panelsPerString} szt. × ${l.stringsPerTracker} string × ${l.trackersUsed} MPPT")
                Line("Paneli / moc DC", "${l.panels} szt. / ${f(r.dcPowerW / 1000, 2)} kWp")
                Line("Powierzchnia paneli", "${f(r.areaM2)} m²")
                Line("Voc stringu przy mrozie", "${f(r.vocColdV)} V")
                Line("Vmp gorący / zimny", "${f(r.vmpHotV)} / ${f(r.vmpColdV)} V")
                Line("Prąd na MPPT", "${f(r.trackerCurrentA)} A")
                Line("DC/AC", f(r.dcAcRatio, 2))
                r.investment?.let { Line("Koszt paneli", "${f(it, 0)} zł") }
            }
            Text("Zakres paneli w stringu: ${r.minSeries}–${r.maxSeries}, maks. stringów równolegle: ${r.maxParallel}", style = MaterialTheme.typography.bodySmall)
            r.warnings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error) }
            yield?.let { y ->
                Line("Produkcja roczna (czyste niebo)", "${f(y.clearSkyKwh, 0)} kWh")
                Line("Uzysk jednostkowy (czyste niebo)", "${f(y.specificClearSkyKwhPerKwp, 0)} kWh/kWp")
                EstimateBadge(text = "GÓRNA GRANICA — BEZ CHMUR")
                Text("Kąt ${f(settings.system.tiltDeg, 0)}°, azymut ${f(settings.system.azimuthDeg, 0)}°, lokalizacja: ${settings.locationName}. Realna produkcja wymaga danych klimatycznych lub kalibracji.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------------------------------------------------------- Economics

@Composable
private fun EconomicsTool(settings: AppSettings) {
    var pv by rememberSaveable { mutableStateOf("") }
    var battery by rememberSaveable { mutableStateOf("") }
    var install by rememberSaveable { mutableStateOf("") }
    var maintenance by rememberSaveable { mutableStateOf("") }
    var grid by rememberSaveable { mutableStateOf(settings.prices.gridPricePerKwh?.toString() ?: "") }
    var feedIn by rememberSaveable { mutableStateOf(settings.prices.feedInPricePerKwh?.toString() ?: "") }
    var production by rememberSaveable { mutableStateOf("") }
    var selfUse by rememberSaveable { mutableStateOf("") }
    var throughput by rememberSaveable { mutableStateOf("") }
    var escalation by rememberSaveable { mutableStateOf("0") }
    var discount by rememberSaveable { mutableStateOf("5") }
    var years by rememberSaveable { mutableStateOf("25") }
    var result by remember { mutableStateOf<EconomicsResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var upperBound by remember { mutableStateOf<Double?>(null) }
    var fillRun by remember { mutableIntStateOf(0) }

    SectionCard {
        Text("Koszty i założenia", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Pair2({ Num("Panele + konstrukcja [zł]", pv, { s -> pv = s }, it) }, { Num("Bateria [zł]", battery, { s -> battery = s }, it) })
        Pair2({ Num("Montaż/falownik [zł]", install, { s -> install = s }, it) }, { Num("Serwis [zł/rok]", maintenance, { s -> maintenance = s }, it) })
        Pair2({ Num("Cena z sieci [zł/kWh]", grid, { s -> grid = s }, it) }, { Num("Cena oddania [zł/kWh]", feedIn, { s -> feedIn = s }, it) })
        Pair2({ Num("Produkcja [kWh/rok]", production, { s -> production = s }, it) }, { Num("Autokonsumpcja [%]", selfUse, { s -> selfUse = s }, it) })
        OutlinedButton(onClick = { fillRun++ }) { Text("Pokaż górną granicę produkcji (czyste niebo)") }
        upperBound?.let { Text("Czyste niebo dla ${settings.locationName}: ${f(it, 0)} kWh/rok — realna produkcja jest niższa (chmury). Wpisz wartość z pomiarów lub kalibracji.", style = MaterialTheme.typography.bodySmall) }
        Pair2({ Num("Przepływ przez baterię [kWh/rok]", throughput, { s -> throughput = s }, it) }, { Num("Wzrost cen [%/rok]", escalation, { s -> escalation = s }, it) })
        Pair2({ Num("Stopa dyskontowa [%]", discount, { s -> discount = s }, it) }, { Num("Okres [lat]", years, { s -> years = s }, it) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val g = grid.num(); val p = production.num(); val su = selfUse.num()
            if (g == null || p == null || su == null || pv.num() == null) { error = "Wymagane: koszt paneli, cena z sieci, produkcja i autokonsumpcja"; return@Button }
            error = null
            val self = p * (su / 100).coerceIn(0.0, 1.0)
            result = EconomicsEngine.evaluate(
                SystemCosts(pv = pv.num()!!, battery = battery.num() ?: 0.0, installation = install.num() ?: 0.0, maintenancePerYear = maintenance.num() ?: 0.0),
                TariffAssumptions(g, feedIn.num() ?: 0.0, priceEscalation = (escalation.num() ?: 0.0) / 100, discountRate = (discount.num() ?: 5.0) / 100),
                EnergyYear(p, self, p - self, batteryThroughputKwh = throughput.num() ?: 0.0),
                lifetimeYears = years.num()?.toInt()?.coerceIn(1, 40) ?: 25,
            )
        }) { Text("Oblicz") }
    }
    LaunchedEffect(fillRun) {
        if (fillRun == 0) return@LaunchedEffect
        val s = settings.system
        upperBound = withContext(Dispatchers.Default) {
            YieldEstimator.annual(PvArrayConfig(1, s.peakPowerKw * 1000, s.tiltDeg, s.azimuthDeg), LossProfile(), settings.location, stepMinutes = 60).clearSkyKwh
        }
    }
    result?.let { r ->
        SectionCard {
            Text("Wynik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Line("Inwestycja", "${f(r.investment, 0)} zł")
            Line("Oszczędność w 1. roku", "${f(r.firstYearSavings, 0)} zł")
            Line("Prosty zwrot", r.simplePaybackYears?.let { "${f(it)} lat" } ?: "brak zwrotu")
            Line("Zwrot zdyskontowany", r.paybackYears?.let { "${f(it)} lat" } ?: "brak zwrotu w okresie")
            Line("NPV", "${f(r.npv, 0)} zł")
            Line("ROI", "${f(r.roiPercent, 0)}%")
            r.lcoe?.let { Line("LCOE", "${f(it, 3)} zł/kWh") }
            r.storageCostPerKwh?.let { Line("Koszt magazynowania", "${f(it, 3)} zł/kWh") }
            EstimateBadge(text = "WYLICZENIE Z PODANYCH ZAŁOŻEŃ")
        }
    }
}

// ---------------------------------------------------------------- Locations

@Composable
private fun LocationsTool(settings: AppSettings) {
    val sites = remember { mutableStateListOf(settings.locationName to settings.location) }
    var name by rememberSaveable { mutableStateOf("") }
    var lat by rememberSaveable { mutableStateOf("") }
    var lon by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<SiteYield>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var run by remember { mutableIntStateOf(0) }

    SectionCard {
        Text("Porównanie lokalizacji", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Ta sama instalacja (${f(settings.system.peakPowerKw, 2)} kWp, ${f(settings.system.tiltDeg, 0)}°) policzona dla różnych miejsc. Model czystego nieba — porównuje geometrię słońca, nie klimat.", style = MaterialTheme.typography.bodySmall)
        sites.forEachIndexed { i, (n, l) ->
            Row(Modifier.fillMaxWidth()) {
                Text("$n (${f(l.latitude, 2)}, ${f(l.longitude, 2)})", Modifier.weight(1f))
                if (i > 0) TextButton(onClick = { sites.removeAt(i) }) { Text("Usuń") }
            }
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Nazwa") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Pair2({ Num("Szerokość [°]", lat, { s -> lat = s }, it) }, { Num("Długość [°]", lon, { s -> lon = s }, it) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val a = lat.num(); val b = lon.num()
                if (a == null || b == null || a !in -90.0..90.0 || b !in -180.0..180.0 || name.isBlank()) { error = "Podaj nazwę i poprawne współrzędne"; return@OutlinedButton }
                error = null; sites += name.trim() to GeoLocation(a, b); name = ""; lat = ""; lon = ""
            }) { Text("Dodaj") }
            Button(onClick = { run++ }, enabled = !busy) { Text(if (busy) "Liczenie…" else "Porównaj") }
        }
    }
    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        busy = true
        val s = settings.system
        results = withContext(Dispatchers.Default) {
            LocationComparison.compare(sites.toList(), PvArrayConfig(1, s.peakPowerKw * 1000, s.tiltDeg, s.azimuthDeg), LossProfile())
        }
        busy = false
    }
    if (results.isNotEmpty()) SectionCard {
        results.forEachIndexed { i, r ->
            Text("${i + 1}. ${r.name}", fontWeight = FontWeight.SemiBold)
            Line("Uzysk (czyste niebo)", "${f(r.specificKwhPerKwp, 0)} kWh/kWp · ${f(r.clearSkyKwh, 0)} kWh")
            Line("Optymalny kąt", "${f(r.optimalTiltDeg, 0)}° (+${f(r.optimalTiltGainPercent)}%)")
        }
        EstimateBadge(text = "GÓRNA GRANICA — BEZ CHMUR")
    }
}

// ---------------------------------------------------------------- Vehicle

@Composable
private fun VehicleTool(settings: AppSettings) {
    var power by rememberSaveable { mutableStateOf("") }
    var tilt by rememberSaveable { mutableStateOf("0") }
    var relAz by rememberSaveable { mutableStateOf("0") }
    var heading by rememberSaveable { mutableStateOf("0") }
    var consumption by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<Pair<VehicleEstimate, VehicleEstimate?>?>(null) }
    var cfg by remember { mutableStateOf<VehicleSolarConfig?>(null) }
    var run by remember { mutableIntStateOf(0) }

    SectionCard {
        Text("Tryb pojazdu (kamper, łódź, auto)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Pair2({ Num("Moc paneli [W]", power, { s -> power = s }, it) }, { Num("Kąt paneli [°]", tilt, { s -> tilt = s }, it) })
        Pair2({ Num("Kierunek paneli wzgl. przodu [°]", relAz, { s -> relAz = s }, it) }, { Num("Kurs pojazdu [°]", heading, { s -> heading = s }, it) })
        Num("Zużycie [kWh/100 km] (opcj.)", consumption, { s -> consumption = s }, Modifier.fillMaxWidth())
        Text("Lokalizacja: ${settings.locationName}. Okres: od teraz do końca dnia.", style = MaterialTheme.typography.bodySmall)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val p = power.num()
            if (p == null || p <= 0) { error = "Podaj moc paneli"; return@Button }
            error = null
            cfg = VehicleSolarConfig(p, (tilt.num() ?: 0.0).coerceIn(0.0, 90.0), relAz.num() ?: 0.0, consumptionKwhPer100Km = consumption.num())
            run++
        }) { Text("Oblicz") }
    }
    LaunchedEffect(run) {
        val c = cfg ?: return@LaunchedEffect
        val now = Instant.now()
        val end = now.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        result = withContext(Dispatchers.Default) {
            VehicleSolarEstimator.estimate(c, settings.location, heading.num() ?: 0.0, now, end) to
                (if (c.tiltDeg > 0) VehicleSolarEstimator.bestHeading(c, settings.location, now, end) else null)
        }
    }
    result?.let { (cur, best) ->
        SectionCard {
            Line("Energia do końca dnia", "${f(cur.energyKwh, 2)} kWh")
            cur.rangeKm?.let { Line("Zasięg z tej energii", "${f(it, 0)} km") }
            best?.let { Line("Najlepszy kurs parkowania", "${f(it.headingDeg, 0)}° → ${f(it.energyKwh, 2)} kWh") }
            if (best == null) Text("Panele płaskie — kierunek parkowania nie ma znaczenia.", style = MaterialTheme.typography.bodySmall)
            EstimateBadge(text = "GÓRNA GRANICA — BEZ CHMUR I CIENIA")
        }
    }
}
