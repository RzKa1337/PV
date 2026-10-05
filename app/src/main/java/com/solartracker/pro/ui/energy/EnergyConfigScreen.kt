package com.solartracker.pro.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.inverter.InverterConfig
import com.solartracker.pro.core.inverter.InverterLink
import com.solartracker.pro.core.inverter.InverterProtocol
import com.solartracker.pro.core.shading.LatLon
import com.solartracker.pro.core.shading.LocationAccuracy
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.energy.SiteConfig
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import java.util.Locale

@Composable
fun EnergyConfigScreen(vm: EnergyCenterViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val inverter by vm.inverterConfig.collectAsStateWithLifecycle()
    val site by vm.siteConfig.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("← Centrum energii") }
        ScreenTitle("Konfiguracja", "Falownik, instalacja i lokalizacja")
        inverter?.let { InverterForm(it, vm::saveInverter) }
        site?.let { LocationSection(vm, it) }
        site?.let { SiteForm(it, vm::saveSite) }
    }
}

@Composable
private fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(value, { onChange(it.take(12)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier)
}

private fun String.num(): Double? = replace(',', '.').trim().toDoubleOrNull()

@Composable
private fun InverterForm(current: InverterConfig, onSave: (InverterConfig) -> List<String>) {
    var c by remember(current) { mutableStateOf(current) }
    var host by remember(current) { mutableStateOf(current.host) }
    var port by remember(current) { mutableStateOf(current.port.toString()) }
    var slave by remember(current) { mutableStateOf(current.slaveId.toString()) }
    var poll by remember(current) { mutableStateOf(current.pollIntervalSeconds.toString()) }
    var rated by remember(current) { mutableStateOf(current.ratedPowerW.toInt().toString()) }
    var mppt by remember(current) { mutableStateOf(current.mpptCount.toString()) }
    var errors by remember(current) { mutableStateOf(emptyList<String>()) }
    SectionCard {
        Text("Falownik", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Monitorowanie falownika", Modifier.weight(1f))
            Switch(checked = c.enabled, onCheckedChange = { c = c.copy(enabled = it) })
        }
        Text("Model: Anenji 6.2 kW 48 V (ANJ-6200W-48V). Połączenie przez port RS232 falownika. Wbudowane Wi-Fi wysyła dane do chmury producenta i nie jest obsługiwane lokalnie.",
            style = MaterialTheme.typography.bodySmall)
        Text("Połączenie", fontWeight = FontWeight.SemiBold)
        ChoiceRow(InverterLink.entries.map { it to it.label }, c.link) { c = c.copy(link = it) }
        if (c.link == InverterLink.TCP_SERIAL_BRIDGE || c.link == InverterLink.MODBUS_TCP_GATEWAY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.trim().take(253) }, label = { Text("Adres IP mostka") }, singleLine = true, modifier = Modifier.weight(2f))
                NumberField("Port", port, { port = it }, Modifier.weight(1f))
            }
        }
        if (c.link == InverterLink.SIMULATOR) Text("Symulator pokazuje dane testowe oznaczone jako SYMULATOR – nie są to pomiary.", color = MaterialTheme.colorScheme.error)
        if (c.link != InverterLink.SIMULATOR) {
            Text("Protokół", fontWeight = FontWeight.SemiBold)
            ChoiceRow(InverterProtocol.entries.map { it to it.label }, c.protocol) { c = c.copy(protocol = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Adres Modbus", slave, { slave = it }, Modifier.weight(1f))
            NumberField("Odczyt co [s]", poll, { poll = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Moc falownika [W]", rated, { rated = it }, Modifier.weight(1f))
            NumberField("Liczba MPPT", mppt, { mppt = it }, Modifier.weight(1f))
        }
        Text("Częstszy odczyt = szybsze dane, ale większe zużycie baterii telefonu. Odczyt działa tylko, gdy Centrum energii jest otwarte.", style = MaterialTheme.typography.bodySmall)
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val updated = c.copy(
                host = host, port = port.toIntOrNull() ?: -1, slaveId = slave.toIntOrNull() ?: -1,
                pollIntervalSeconds = poll.toIntOrNull() ?: -1, ratedPowerW = rated.num() ?: -1.0, mpptCount = mppt.toIntOrNull() ?: -1,
            )
            errors = onSave(updated)
        }) { Text("Zapisz falownik") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationSection(vm: EnergyCenterViewModel, site: SiteConfig) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var pickOnMap by rememberSaveable { mutableStateOf(false) }
    val location = settings?.location ?: return
    val point = LatLon(location.latitude, location.longitude)
    SectionCard {
        Text("Lokalizacja instalacji", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("${settings?.locationName} · ${String.format(Locale.ROOT, "%.6f, %.6f", point.lat, point.lon)} · ${Fmt.m(location.elevationM)} n.p.m.")
        Text("Dokładność: ${site.locationAccuracy.label}", color = if (site.locationAccuracy.factor < 0.8) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(if (site.locationConfirmed) "Lokalizacja potwierdzona ✓" else "Lokalizacja NIEPOTWIERDZONA – zacienienie nie jest obliczane",
            fontWeight = FontWeight.SemiBold, color = if (site.locationConfirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedTextField(query, { query = it.take(200) }, label = { Text("Miasto, adres lub kod pocztowy") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.search(query) }, enabled = !busy && query.trim().length >= 2) { Text(if (busy) "Szukam…" else "Szukaj") }
            OutlinedButton(onClick = { pickOnMap = !pickOnMap }) { Text(if (pickOnMap) "Zamknij mapę" else "Wskaż na mapie") }
        }
        results.forEach { r ->
            TextButton(onClick = { vm.setLocation(r.point, r.name.take(60), r.accuracy, r.elevationM) }) {
                Text("${r.name} (${r.accuracy.label}, ${r.source})")
            }
        }
        if (pickOnMap) {
            Text("Dotknij mapy, aby ustawić dokładny punkt instalacji (np. środek dachu z panelami).", style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                OsmMap(center = point, marker = point, shapes = emptyList(), modifier = Modifier.fillMaxSize(), onTap = { p ->
                    vm.setLocation(p, settings?.locationName?.takeIf { it.isNotBlank() } ?: "Punkt z mapy", LocationAccuracy.MAP_POINT)
                })
                Text("N ↑", Modifier.align(Alignment.TopEnd).padding(8.dp), fontWeight = FontWeight.Bold)
            }
            Text("Mapa © OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
        }
        if (!site.locationConfirmed) Button(onClick = vm::confirmLocation) { Text("Potwierdź lokalizację") }
        if (site.locationAccuracy.factor < 0.8) Text("Lokalizacja jest przybliżona – wskaż dokładny punkt na mapie, aby zacienienie było wiarygodne.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SiteForm(current: SiteConfig, onSave: (SiteConfig) -> List<String>) {
    var panels by remember(current) { mutableStateOf(current.panelCount.toString()) }
    var rows by remember(current) { mutableStateOf(current.rows.toString()) }
    var model by remember(current) { mutableStateOf(current.panelModel) }
    var w by remember(current) { mutableStateOf(current.panelWidthM.toString()) }
    var l by remember(current) { mutableStateOf(current.panelLengthM.toString()) }
    var h by remember(current) { mutableStateOf(current.panelBaseHeightM.toString()) }
    var strings by remember(current) { mutableStateOf(current.strings.toString()) }
    var mppt by remember(current) { mutableStateOf(current.mpptCount.toString()) }
    var radius by remember(current) { mutableStateOf(current.obstacleRadiusM.toString()) }
    var batteryV by remember(current) { mutableStateOf(current.batteryVoltage.toString()) }
    var export by remember(current) { mutableStateOf(current.gridExportAllowed) }
    var errors by remember(current) { mutableStateOf(emptyList<String>()) }
    SectionCard {
        Text("Instalacja PV (geometria)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Moc, kąt i azymut ustawiasz w zakładce Ustawienia. Tu podaj rozmieszczenie paneli – potrzebne do obliczeń zacienienia.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(model, { model = it.take(60) }, label = { Text("Model panelu") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Liczba paneli", panels, { panels = it }, Modifier.weight(1f))
            NumberField("Rzędy", rows, { rows = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Szerokość [m]", w, { w = it }, Modifier.weight(1f))
            NumberField("Długość [m]", l, { l = it }, Modifier.weight(1f))
        }
        NumberField("Wysokość dolnej krawędzi paneli nad gruntem [m]", h, { h = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Stringi", strings, { strings = it }, Modifier.weight(1f))
            NumberField("MPPT", mppt, { mppt = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Promień przeszkód [m]", radius, { radius = it }, Modifier.weight(1f))
            NumberField("Napięcie baterii [V]", batteryV, { batteryV = it }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Oddawanie energii do sieci możliwe", Modifier.weight(1f))
            Switch(checked = export, onCheckedChange = { export = it })
        }
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            errors = onSave(
                current.copy(
                    panelCount = panels.toIntOrNull() ?: -1, rows = rows.toIntOrNull() ?: -1, panelModel = model,
                    panelWidthM = w.num() ?: -1.0, panelLengthM = l.num() ?: -1.0, panelBaseHeightM = h.num() ?: -1.0,
                    strings = strings.toIntOrNull() ?: -1, mpptCount = mppt.toIntOrNull() ?: -1, obstacleRadiusM = radius.toIntOrNull() ?: -1,
                    batteryVoltage = batteryV.num() ?: 48.0, gridExportAllowed = export,
                ),
            )
        }) { Text("Zapisz instalację") }
    }
}
