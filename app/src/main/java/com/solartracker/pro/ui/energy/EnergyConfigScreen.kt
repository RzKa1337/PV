package com.solartracker.pro.ui.energy

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.mutableStateListOf
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
import com.solartracker.pro.core.ems.FlexibleLoad
import com.solartracker.pro.core.energy.CoolingLoadProfile
import com.solartracker.pro.core.energy.CoolingMode
import com.solartracker.pro.core.energy.CoolingScheduleEntry
import com.solartracker.pro.core.ems.GeneratorConfig
import com.solartracker.pro.core.ems.validate
import com.solartracker.pro.core.inverter.InverterConfig
import com.solartracker.pro.core.inverter.InverterLink
import com.solartracker.pro.core.inverter.InverterProtocol
import com.solartracker.pro.core.shading.LatLon
import com.solartracker.pro.core.shading.LocationAccuracy
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.energy.SiteConfig
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.uiLabel
import androidx.compose.ui.platform.LocalContext
import java.time.LocalTime
import java.util.Locale
import java.util.UUID

@Composable
fun EnergyConfigScreen(vm: EnergyCenterViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val inverter by vm.inverterConfig.collectAsStateWithLifecycle()
    val site by vm.siteConfig.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back_energy_center)) }
        ScreenTitle(stringResource(R.string.cfg_title), stringResource(R.string.cfg_subtitle))
        inverter?.let { InverterForm(it, vm::saveInverter) }
        site?.let { LocationSection(vm, it) }
        site?.let { SiteForm(it, vm::saveSite) }
        EmsSection(vm)
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
        Text(stringResource(R.string.inverter), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cfg_monitoring), Modifier.weight(1f))
            Switch(checked = c.enabled, onCheckedChange = { c = c.copy(enabled = it) })
        }
        Text(stringResource(R.string.cfg_model_hint),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.cfg_connection), fontWeight = FontWeight.SemiBold)
        ChoiceRow(InverterLink.entries.map { it to it.label }, c.link) { c = c.copy(link = it) }
        if (c.link == InverterLink.TCP_SERIAL_BRIDGE || c.link == InverterLink.MODBUS_TCP_GATEWAY) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(host, { host = it.trim().take(253) }, label = { Text(stringResource(R.string.cfg_bridge_ip)) }, singleLine = true, modifier = Modifier.weight(2f))
                NumberField("Port", port, { port = it }, Modifier.weight(1f))
            }
        }
        if (c.link == InverterLink.SIMULATOR) Text(stringResource(R.string.cfg_simulator_hint), color = MaterialTheme.colorScheme.error)
        if (c.link != InverterLink.SIMULATOR) {
            Text(stringResource(R.string.cfg_protocol), fontWeight = FontWeight.SemiBold)
            ChoiceRow(InverterProtocol.entries.map { it to it.label }, c.protocol) { c = c.copy(protocol = it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.cfg_modbus_address), slave, { slave = it }, Modifier.weight(1f))
            NumberField(stringResource(R.string.cfg_poll), poll, { poll = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.cfg_rated_power), rated, { rated = it }, Modifier.weight(1f))
            NumberField(stringResource(R.string.cfg_mppt_count), mppt, { mppt = it }, Modifier.weight(1f))
        }
        Text(stringResource(R.string.cfg_poll_hint), style = MaterialTheme.typography.bodySmall)
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val updated = c.copy(
                host = host, port = port.toIntOrNull() ?: -1, slaveId = slave.toIntOrNull() ?: -1,
                pollIntervalSeconds = poll.toIntOrNull() ?: -1, ratedPowerW = rated.num() ?: -1.0, mpptCount = mppt.toIntOrNull() ?: -1,
            )
            errors = onSave(updated)
        }) { Text(stringResource(R.string.cfg_save_inverter)) }
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
    BackHandler(enabled = pickOnMap) { pickOnMap = false }
    val context = LocalContext.current
    val location = settings?.location ?: return
    val point = LatLon(location.latitude, location.longitude)
    SectionCard {
        Text(stringResource(R.string.cfg_site_location), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.cfg_location_line, settings?.locationName.orEmpty(), String.format(Locale.ROOT, "%.6f, %.6f", point.lat, point.lon), Fmt.m(location.elevationM).orEmpty()))
        Text(stringResource(R.string.cfg_accuracy, site.locationAccuracy.uiLabel), color = if (site.locationAccuracy.factor < 0.8) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(if (site.locationConfirmed) stringResource(R.string.cfg_location_confirmed) else stringResource(R.string.cfg_location_unconfirmed),
            fontWeight = FontWeight.SemiBold, color = if (site.locationConfirmed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        OutlinedTextField(query, { query = it.take(200) }, label = { Text(stringResource(R.string.cfg_search_field)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { vm.search(query) }, enabled = !busy && query.trim().length >= 2) { Text(if (busy) stringResource(R.string.cfg_searching) else stringResource(R.string.search)) }
            OutlinedButton(onClick = { pickOnMap = !pickOnMap }) { Text(if (pickOnMap) stringResource(R.string.cfg_close_map) else stringResource(R.string.cfg_pick_on_map)) }
        }
        results.forEach { r ->
            TextButton(onClick = { vm.setLocation(r.point, r.name.take(60), r.accuracy, r.elevationM) }) {
                Text("${r.name} (${r.accuracy.uiLabel}, ${r.source})")
            }
        }
        if (pickOnMap) {
            Text(stringResource(R.string.cfg_tap_map), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.fillMaxWidth().height(320.dp)) {
                OsmMap(center = point, marker = point, shapes = emptyList(), modifier = Modifier.fillMaxSize(), onTap = { p ->
                    vm.setLocation(p, settings?.locationName?.takeIf { it.isNotBlank() } ?: context.getString(R.string.cfg_map_point), LocationAccuracy.MAP_POINT)
                })
                Text("N ↑", Modifier.align(Alignment.TopEnd).padding(8.dp), fontWeight = FontWeight.Bold)
            }
            Text(stringResource(R.string.map_attribution), style = MaterialTheme.typography.labelSmall)
        }
        if (!site.locationConfirmed) Button(onClick = vm::confirmLocation) { Text(stringResource(R.string.cfg_confirm_location)) }
        if (site.locationAccuracy.factor < 0.8) Text(stringResource(R.string.cfg_location_approx), style = MaterialTheme.typography.bodySmall)
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
        Text(stringResource(R.string.cfg_site_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.cfg_site_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(model, { model = it.take(60) }, label = { Text(stringResource(R.string.cfg_panel_model)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.cfg_panel_count), panels, { panels = it }, Modifier.weight(1f))
            NumberField(stringResource(R.string.cfg_rows), rows, { rows = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.cfg_width_m), w, { w = it }, Modifier.weight(1f))
            NumberField(stringResource(R.string.cfg_length_m), l, { l = it }, Modifier.weight(1f))
        }
        NumberField(stringResource(R.string.cfg_base_height), h, { h = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.cfg_strings), strings, { strings = it }, Modifier.weight(1f))
            NumberField("MPPT", mppt, { mppt = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.cfg_obstacle_radius), radius, { radius = it }, Modifier.weight(1f))
            NumberField(stringResource(R.string.cfg_battery_voltage), batteryV, { batteryV = it }, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cfg_export_allowed), Modifier.weight(1f))
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
        }) { Text(stringResource(R.string.cfg_save_site)) }
    }
}

private fun String.time(): LocalTime? = trim().takeIf { it.isNotEmpty() }?.let { t ->
    runCatching { LocalTime.parse(if (t.length == 4 && t[1] == ':') "0$t" else t) }.getOrNull()
}

/** Flexible loads and generator used by EMS recommendations (nothing is switched by the app). */
@Composable
private fun EmsSection(vm: EnergyCenterViewModel) {
    val loads by vm.flexibleLoads.collectAsStateWithLifecycle()
    val generator by vm.generator.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var kw by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("") }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    var surplusOnly by remember { mutableStateOf(true) }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    val context = LocalContext.current
    SectionCard {
        Text(stringResource(R.string.cfg_ems_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.cfg_ems_hint),
            style = MaterialTheme.typography.bodySmall)
        if (loads.isEmpty()) Text(stringResource(R.string.cfg_no_loads), style = MaterialTheme.typography.bodyMedium)
        loads.forEach { l ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(l.name, fontWeight = FontWeight.SemiBold)
                    Text(String.format(Locale.ROOT, "%.2f kW × %.2f h", l.powerKw, l.hours) +
                        (if (l.earliest != null || l.latest != null) " · ${l.earliest ?: "—"}–${l.latest ?: "—"}" else "") +
                        (if (l.surplusOnly) stringResource(R.string.cfg_surplus_only_suffix) else ""), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { vm.saveFlexibleLoads(loads.filterNot { it.id == l.id }) }) { Text(stringResource(R.string.remove)) }
            }
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(R.string.cfg_power_kw), kw, { kw = it }, Modifier.weight(1f))
            NumberField(stringResource(R.string.cfg_run_hours), hours, { hours = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(from, { from = it.take(5) }, label = { Text(stringResource(R.string.cfg_from_hhmm)) }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(to, { to = it.take(5) }, label = { Text(stringResource(R.string.cfg_to_hhmm)) }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cfg_surplus_only), Modifier.weight(1f))
            Switch(checked = surplusOnly, onCheckedChange = { surplusOnly = it })
        }
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = {
            val f = from.time(); val t = to.time()
            val timeErrors = buildList {
                if (from.isNotBlank() && f == null) add(context.getString(R.string.cfg_bad_from))
                if (to.isNotBlank() && t == null) add(context.getString(R.string.cfg_bad_to))
            }
            val load = FlexibleLoad(UUID.randomUUID().toString(), name.trim(), kw.num() ?: -1.0, hours.num() ?: -1.0, f, t, 0, surplusOnly)
            errors = timeErrors + load.validate() + (if (loads.size >= 20) listOf(context.getString(R.string.cfg_max_loads)) else emptyList())
            if (errors.isEmpty()) {
                vm.saveFlexibleLoads(loads + load)
                name = ""; kw = ""; hours = ""; from = ""; to = ""
            }
        }) { Text(stringResource(R.string.cfg_add_load)) }
    }
    GeneratorForm(generator, vm::saveGenerator)
    val cooling by vm.cooling.collectAsStateWithLifecycle()
    CoolingForm(cooling, vm::saveCooling)
}

/** Optional cold room: modelled as a compressor duty cycle, not as constant nominal power. */
@Composable
private fun CoolingForm(current: CoolingLoadProfile?, onSave: (CoolingLoadProfile?) -> Unit) {
    val context = LocalContext.current
    val defaultName = stringResource(R.string.cfg_cold_room)
    var enabled by remember(current) { mutableStateOf(current != null) }
    var name by remember(current) { mutableStateOf(current?.name ?: defaultName) }
    var nominal by remember(current) { mutableStateOf(current?.nominalPowerW?.toInt()?.toString() ?: "") }
    var minimum by remember(current) { mutableStateOf(current?.minimumPowerW?.toInt()?.toString() ?: "") }
    var mode by remember(current) { mutableStateOf(current?.mode ?: CoolingMode.AVERAGE) }
    val schedule = remember(current) { mutableStateListOf<CoolingScheduleEntry>().apply { addAll(current?.schedule.orEmpty()) } }
    var schedFrom by remember(current) { mutableStateOf("") }
    var schedTo by remember(current) { mutableStateOf("") }
    var schedW by remember(current) { mutableStateOf("") }
    var average by remember(current) { mutableStateOf(current?.averagePowerW?.toInt()?.toString() ?: "") }
    var duty by remember(current) { mutableStateOf(current?.dutyAtReference?.let { (it * 100).toInt().toString() } ?: "") }
    var target by remember(current) { mutableStateOf(current?.targetTemperatureC?.toString() ?: "") }
    var reference by remember(current) { mutableStateOf((current?.referenceAmbientC ?: 25.0).toString()) }
    var open by remember(current) { mutableStateOf(current?.let { c -> c.operatingStart.takeIf { it != c.operatingEnd }?.toString() } ?: "") }
    var close by remember(current) { mutableStateOf(current?.let { c -> c.operatingEnd.takeIf { it != c.operatingStart }?.toString() } ?: "") }
    var idle by remember(current) { mutableStateOf(((current?.idleDutyFactor ?: 1.0) * 100).toInt().toString()) }
    var pre by remember(current) { mutableStateOf(current?.preCoolingEnabled ?: false) }
    var preTarget by remember(current) { mutableStateOf(current?.preCoolingTargetC?.toString() ?: "") }
    var preHours by remember(current) { mutableStateOf((current?.preCoolingHours ?: 2.0).toString()) }
    var errors by remember(current) { mutableStateOf(emptyList<String>()) }
    var saved by remember(current) { mutableStateOf(false) }
    SectionCard {
        Text(stringResource(R.string.cfg_cold_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cfg_have_cold), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Text(stringResource(R.string.cfg_cold_hint),
            style = MaterialTheme.typography.bodySmall)
        if (enabled) {
            OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.cfg_nominal_w), nominal, { nominal = it }, Modifier.weight(1f))
                NumberField(stringResource(R.string.cfg_idle_w), minimum, { minimum = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == CoolingMode.AVERAGE, onClick = { mode = CoolingMode.AVERAGE }, label = { Text(stringResource(R.string.cfg_know_average)) })
                FilterChip(selected = mode == CoolingMode.DUTY_CYCLE, onClick = { mode = CoolingMode.DUTY_CYCLE }, label = { Text(stringResource(R.string.cfg_know_duty)) })
            }
            FilterChip(selected = mode == CoolingMode.SCHEDULE, onClick = { mode = CoolingMode.SCHEDULE }, label = { Text(stringResource(R.string.cfg_schedule)) })
            if (mode == CoolingMode.SCHEDULE) {
                Text(stringResource(R.string.cfg_schedule_hint), style = MaterialTheme.typography.bodySmall)
                schedule.forEachIndexed { i, e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.start}–${e.end}: ${e.powerW.toInt()} W", Modifier.weight(1f))
                        TextButton(onClick = { schedule.removeAt(i) }) { Text(stringResource(R.string.remove)) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(schedFrom, { schedFrom = it.take(5) }, label = { Text(stringResource(R.string.from)) }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(schedTo, { schedTo = it.take(5) }, label = { Text(stringResource(R.string.to)) }, singleLine = true, modifier = Modifier.weight(1f))
                    NumberField(stringResource(R.string.cfg_power_w), schedW, { schedW = it }, Modifier.weight(1f))
                }
                OutlinedButton(onClick = {
                    val f = schedFrom.time(); val t = schedTo.time(); val w = schedW.num()
                    if (f != null && t != null && w != null && f != t && w >= 0) {
                        schedule += CoolingScheduleEntry(f, t, w); schedFrom = ""; schedTo = ""; schedW = ""
                    } else errors = listOf(context.getString(R.string.cfg_schedule_error))
                }) { Text(stringResource(R.string.consumption_add_period)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (mode) {
                    CoolingMode.AVERAGE -> NumberField(stringResource(R.string.cfg_average_w), average, { average = it }, Modifier.weight(1f))
                    CoolingMode.DUTY_CYCLE -> NumberField(stringResource(R.string.cfg_duty), duty, { duty = it }, Modifier.weight(1f))
                    CoolingMode.SCHEDULE -> Unit
                }
                if (mode != CoolingMode.SCHEDULE) NumberField(stringResource(R.string.cfg_at_ambient), reference, { reference = it }, Modifier.weight(1f))
            }
            NumberField(stringResource(R.string.cfg_room_temp), target, { target = it }, Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(open, { open = it.take(5) }, label = { Text(stringResource(R.string.cfg_open_from)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(close, { close = it.take(5) }, label = { Text(stringResource(R.string.cfg_open_to)) }, singleLine = true, modifier = Modifier.weight(1f))
            }
            NumberField(stringResource(R.string.cfg_idle_factor), idle, { idle = it }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cfg_precool), Modifier.weight(1f))
                Switch(checked = pre, onCheckedChange = { pre = it })
            }
            if (pre) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.cfg_precool_to), preTarget, { preTarget = it }, Modifier.weight(1f))
                NumberField(stringResource(R.string.cfg_precool_hours), preHours, { preHours = it }, Modifier.weight(1f))
            }
        }
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        current?.let { c ->
            val day = c.dailyEnergyKwh(java.time.LocalDate.now(), java.time.ZoneId.systemDefault(), c.referenceAmbientC)
            Text(stringResource(R.string.cfg_cold_estimate, String.format(Locale.ROOT, "%.0f", c.referenceAmbientC), String.format(Locale.ROOT, "%.1f", day)), style = MaterialTheme.typography.bodySmall)
        }
        if (saved) Text(stringResource(R.string.saved), color = MaterialTheme.colorScheme.primary)
        Button(onClick = {
            saved = false
            if (!enabled) { onSave(null); saved = true; errors = emptyList(); return@Button }
            val o = open.time(); val c = close.time()
            val p = CoolingLoadProfile(
                name = name.trim().ifEmpty { defaultName }, nominalPowerW = nominal.num() ?: -1.0, minimumPowerW = minimum.num() ?: -1.0, mode = mode,
                averagePowerW = average.num().takeIf { mode == CoolingMode.AVERAGE }, dutyAtReference = duty.num()?.div(100).takeIf { mode == CoolingMode.DUTY_CYCLE },
                schedule = if (mode == CoolingMode.SCHEDULE) schedule.toList() else emptyList(),
                targetTemperatureC = target.num() ?: 99.0, referenceAmbientC = reference.num() ?: 25.0,
                operatingStart = o ?: LocalTime.MIDNIGHT, operatingEnd = c ?: LocalTime.MIDNIGHT, idleDutyFactor = (idle.num() ?: 100.0) / 100,
                preCoolingEnabled = pre, preCoolingTargetC = preTarget.num(), preCoolingHours = preHours.num() ?: 2.0,
            )
            errors = p.validate() + buildList {
                if (open.isNotBlank() && o == null) add(context.getString(R.string.cfg_bad_open))
                if (close.isNotBlank() && c == null) add(context.getString(R.string.cfg_bad_close))
            }
            if (errors.isEmpty()) { onSave(p); saved = true }
        }) { Text(stringResource(R.string.cfg_save_cold)) }
    }
}

@Composable
private fun GeneratorForm(current: GeneratorConfig?, onSave: (GeneratorConfig?) -> Unit) {
    var enabled by remember(current) { mutableStateOf(current != null) }
    var kw by remember(current) { mutableStateOf(current?.ratedKw?.toString() ?: "") }
    var start by remember(current) { mutableStateOf((current?.startSocPercent ?: 25.0).toInt().toString()) }
    var stop by remember(current) { mutableStateOf((current?.stopSocPercent ?: 80.0).toInt().toString()) }
    var minRun by remember(current) { mutableStateOf((current?.minRunHours ?: 1.0).toString()) }
    var fuel by remember(current) { mutableStateOf(current?.litersPerKwh?.toString() ?: "") }
    var errors by remember(current) { mutableStateOf(emptyList<String>()) }
    var saved by remember(current) { mutableStateOf(false) }
    SectionCard {
        Text(stringResource(R.string.source_generator), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.cfg_have_generator), Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = { enabled = it })
        }
        Text(stringResource(R.string.cfg_generator_hint), style = MaterialTheme.typography.bodySmall)
        if (enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.cfg_power_kw), kw, { kw = it }, Modifier.weight(1f))
                NumberField(stringResource(R.string.cfg_min_run), minRun, { minRun = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(stringResource(R.string.cfg_start_soc), start, { start = it }, Modifier.weight(1f))
                NumberField(stringResource(R.string.cfg_stop_soc), stop, { stop = it }, Modifier.weight(1f))
            }
            NumberField(stringResource(R.string.cfg_fuel), fuel, { fuel = it }, Modifier.fillMaxWidth())
        }
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        if (saved) Text(stringResource(R.string.saved), color = MaterialTheme.colorScheme.primary)
        Button(onClick = {
            if (!enabled) { onSave(null); saved = true; errors = emptyList(); return@Button }
            val g = GeneratorConfig(kw.num() ?: -1.0, start.num() ?: -1.0, stop.num() ?: -1.0, minRun.num() ?: -1.0, fuel.num())
            errors = g.validate()
            if (errors.isEmpty()) { onSave(g); saved = true }
        }) { Text(stringResource(R.string.cfg_save_generator)) }
    }
}
