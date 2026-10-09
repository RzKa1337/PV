package com.solartracker.pro.ui.tools

import com.solartracker.pro.ui.components.StatusLabel
import com.solartracker.pro.ui.components.StatusLevel
import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalContext
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
import com.solartracker.pro.core.design.AdjustableMount
import com.solartracker.pro.core.design.TiltOptimizer
import com.solartracker.pro.core.design.TiltRecommendation
import com.solartracker.pro.core.vehicle.HeadingProfile
import com.solartracker.pro.core.vehicle.VehicleBody
import com.solartracker.pro.core.vehicle.VehicleEstimate
import com.solartracker.pro.core.vehicle.VehiclePanel
import com.solartracker.pro.core.vehicle.VehicleSolarConfig
import com.solartracker.pro.core.vehicle.VehicleSolarEstimator
import com.solartracker.pro.data.AppSettings
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SunnyScene
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.uiLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

private enum class Tool(@StringRes val label: Int, val feature: Feature) {
    DESIGNER(R.string.tool_designer, Feature.DESIGNER),
    ECONOMICS(R.string.tool_economics, Feature.ECONOMICS),
    LOCATIONS(R.string.tool_locations, Feature.LOCATION_COMPARISON),
    VEHICLE(R.string.tool_vehicle, Feature.VEHICLE),
    TILT(R.string.tool_tilt, Feature.TILT_OPTIMIZER),
}

/** Planning tools. Pure calculations from core; inputs come from the user or the saved installation. */
@Composable
fun ToolsScreen(settings: AppSettings, access: FeatureAccessManager, subscription: SubscriptionState, modifier: Modifier = Modifier) {
    var tool by rememberSaveable { mutableIntStateOf(0) }
    // System Back returns to the first tool before leaving the tab.
    BackHandler(enabled = tool != 0) { tool = 0 }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenTitle(stringResource(R.string.tab_tools), stringResource(R.string.tools_subtitle), scene = SunnyScene.ATACAMA)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool.entries.forEach { t ->
                FilterChip(selected = tool == t.ordinal, onClick = { tool = t.ordinal }, label = { Text(stringResource(t.label)) }, modifier = Modifier.testTag("tool_${t.name}"))
            }
        }
        Text("Plan: ${subscription.plan.name} — ${subscription.source.uiLabel}", style = MaterialTheme.typography.bodySmall)
        val t = Tool.entries[tool]
        val locked = access.reason(t.feature)
        if (locked != null) {
            SectionCard { Text(locked, color = MaterialTheme.colorScheme.error) }
        } else when (t) {
            Tool.DESIGNER -> DesignerTool(settings)
            Tool.ECONOMICS -> EconomicsTool(settings)
            Tool.LOCATIONS -> LocationsTool(settings)
            Tool.VEHICLE -> VehicleTool(settings)
            Tool.TILT -> TiltTool(settings)
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
    val context = LocalContext.current
    val labels = listOf(
        stringResource(R.string.ds_panel_power), "Voc [V]", "Vmp [V]", "Isc [A]", "Imp [A]", stringResource(R.string.ds_voc_coeff), stringResource(R.string.ds_pmax_coeff),
        stringResource(R.string.cfg_length_m), stringResource(R.string.cfg_width_m),
        stringResource(R.string.cfg_mppt_count), "MPPT min [V]", "MPPT max [V]", stringResource(R.string.ds_max_input_v), stringResource(R.string.ds_max_mppt_a),
        stringResource(R.string.ds_min_ambient), stringResource(R.string.ds_ac_power), stringResource(R.string.ds_target_power),
    )
    var area by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<DesignResult?>(null) }
    var yield by remember { mutableStateOf<AnnualYield?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var run by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf<DesignInput?>(null) }

    SectionCard {
        Text(stringResource(R.string.ds_panel_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        for (i in 0 until 9 step 2) {
            if (i + 1 < 9) Pair2({ Num(labels[i], v[i], { s -> v[i] = s }, it) }, { Num(labels[i + 1], v[i + 1], { s -> v[i + 1] = s }, it) })
            else Num(labels[i], v[i], { s -> v[i] = s }, Modifier.fillMaxWidth())
        }
        Text(stringResource(R.string.ds_inverter_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        for (i in 9 until 17 step 2) {
            if (i + 1 < 17) Pair2({ Num(labels[i], v[i], { s -> v[i] = s }, it) }, { Num(labels[i + 1], v[i + 1], { s -> v[i + 1] = s }, it) })
            else Num(labels[i], v[i], { s -> v[i] = s }, Modifier.fillMaxWidth())
        }
        Pair2({ Num(stringResource(R.string.ds_area), area, { s -> area = s }, it) }, { Num(stringResource(R.string.ds_cost), cost, { s -> cost = s }, it) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val n = v.map { it.num() }
            val required = (0..15)
            if (required.any { n[it] == null }) { error = context.getString(R.string.ds_fill_all); return@Button }
            error = null
            input = DesignInput(
                PanelSpec(n[0]!!, n[1]!!, n[2]!!, n[3]!!, n[4]!!, n[5]!! / 100, n[6]!! / 100, n[7]!!, n[8]!!),
                MpptSpec(n[9]!!.toInt().coerceAtLeast(1), n[10]!!, n[11]!!, n[12]!!, n[13]!!, ratedAcW = n[15]!!),
                targetPowerW = n[16]?.let { it * 1000 }, areaM2 = area.num(), minAmbientC = n[14]!!, costPerWp = cost.num(),
            )
            run++
        }, modifier = Modifier.testTag("designer_run")) { Text(stringResource(R.string.ds_run)) }
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
            Text(stringResource(R.string.result), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val l = r.layout
            if (l == null) Text(stringResource(R.string.ds_no_config), color = MaterialTheme.colorScheme.error) else {
                Line(stringResource(R.string.ds_layout), stringResource(R.string.ds_layout_value, l.panelsPerString.toString(), l.stringsPerTracker.toString(), l.trackersUsed.toString()))
                Line(stringResource(R.string.ds_panels_dc), stringResource(R.string.ds_panels_dc_value, l.panels.toString(), f(r.dcPowerW / 1000, 2)))
                Line(stringResource(R.string.ds_panel_area), "${f(r.areaM2)} m²")
                Line(stringResource(R.string.ds_voc_cold), "${f(r.vocColdV)} V")
                Line(stringResource(R.string.ds_vmp), "${f(r.vmpHotV)} / ${f(r.vmpColdV)} V")
                Line(stringResource(R.string.ds_mppt_current), "${f(r.trackerCurrentA)} A")
                Line("DC/AC", f(r.dcAcRatio, 2))
                r.investment?.let { Line(stringResource(R.string.ds_panel_cost), "${f(it, 0)} zł") }
            }
            Text(stringResource(R.string.ds_series_range, r.minSeries.toString(), r.maxSeries.toString(), r.maxParallel.toString()), style = MaterialTheme.typography.bodySmall)
            r.warnings.forEach { StatusLabel(StatusLevel.CRITICAL, it, fontWeight = null) }
            yield?.let { y ->
                Line(stringResource(R.string.ds_annual_clear), "${f(y.clearSkyKwh, 0)} kWh")
                Line(stringResource(R.string.ds_specific_clear), "${f(y.specificClearSkyKwhPerKwp, 0)} kWh/kWp")
                EstimateBadge(text = stringResource(R.string.badge_upper_bound))
                Text(stringResource(R.string.ds_yield_hint, f(settings.system.tiltDeg, 0), f(settings.system.azimuthDeg, 0), settings.locationName), style = MaterialTheme.typography.bodySmall)
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
    val context = LocalContext.current

    SectionCard {
        Text(stringResource(R.string.eco_costs), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Pair2({ Num(stringResource(R.string.eco_pv), pv, { s -> pv = s }, it) }, { Num(stringResource(R.string.eco_battery), battery, { s -> battery = s }, it) })
        Pair2({ Num(stringResource(R.string.eco_install), install, { s -> install = s }, it) }, { Num(stringResource(R.string.eco_service), maintenance, { s -> maintenance = s }, it) })
        Pair2({ Num(stringResource(R.string.eco_grid_price), grid, { s -> grid = s }, it) }, { Num(stringResource(R.string.eco_feed_in), feedIn, { s -> feedIn = s }, it) })
        Pair2({ Num(stringResource(R.string.eco_production), production, { s -> production = s }, it) }, { Num(stringResource(R.string.eco_self_use), selfUse, { s -> selfUse = s }, it) })
        OutlinedButton(onClick = { fillRun++ }) { Text(stringResource(R.string.eco_show_upper)) }
        upperBound?.let { Text(stringResource(R.string.eco_upper, settings.locationName, f(it, 0)), style = MaterialTheme.typography.bodySmall) }
        Pair2({ Num(stringResource(R.string.eco_throughput), throughput, { s -> throughput = s }, it) }, { Num(stringResource(R.string.eco_escalation), escalation, { s -> escalation = s }, it) })
        Pair2({ Num(stringResource(R.string.eco_discount), discount, { s -> discount = s }, it) }, { Num(stringResource(R.string.eco_period), years, { s -> years = s }, it) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val g = grid.num(); val p = production.num(); val su = selfUse.num()
            if (g == null || p == null || su == null || pv.num() == null) { error = context.getString(R.string.eco_required); return@Button }
            error = null
            val self = p * (su / 100).coerceIn(0.0, 1.0)
            result = EconomicsEngine.evaluate(
                SystemCosts(pv = pv.num()!!, battery = battery.num() ?: 0.0, installation = install.num() ?: 0.0, maintenancePerYear = maintenance.num() ?: 0.0),
                TariffAssumptions(g, feedIn.num() ?: 0.0, priceEscalation = (escalation.num() ?: 0.0) / 100, discountRate = (discount.num() ?: 5.0) / 100),
                EnergyYear(p, self, p - self, batteryThroughputKwh = throughput.num() ?: 0.0),
                lifetimeYears = years.num()?.toInt()?.coerceIn(1, 40) ?: 25,
            )
        }) { Text(stringResource(R.string.eco_calculate)) }
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
            Text(stringResource(R.string.result), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Line(stringResource(R.string.eco_investment), "${f(r.investment, 0)} zł")
            Line(stringResource(R.string.eco_first_year), "${f(r.firstYearSavings, 0)} zł")
            Line(stringResource(R.string.eco_simple_payback), r.simplePaybackYears?.let { stringResource(R.string.eco_years_value, f(it)) } ?: stringResource(R.string.energy_no_payback))
            Line(stringResource(R.string.eco_discounted), r.paybackYears?.let { stringResource(R.string.eco_years_value, f(it)) } ?: stringResource(R.string.eco_no_payback_period))
            Line("NPV", "${f(r.npv, 0)} zł")
            Line("ROI", "${f(r.roiPercent, 0)}%")
            r.lcoe?.let { Line("LCOE", "${f(it, 3)} zł/kWh") }
            r.storageCostPerKwh?.let { Line(stringResource(R.string.eco_storage_cost), "${f(it, 3)} zł/kWh") }
            EstimateBadge(text = stringResource(R.string.eco_badge))
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
    val context = LocalContext.current

    SectionCard {
        Text(stringResource(R.string.loc_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.loc_hint, f(settings.system.peakPowerKw, 2), f(settings.system.tiltDeg, 0)), style = MaterialTheme.typography.bodySmall)
        sites.forEachIndexed { i, (n, l) ->
            Row(Modifier.fillMaxWidth()) {
                Text("$n (${f(l.latitude, 2)}, ${f(l.longitude, 2)})", Modifier.weight(1f))
                if (i > 0) TextButton(onClick = { sites.removeAt(i) }) { Text(stringResource(R.string.remove)) }
            }
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Pair2({ Num(stringResource(R.string.loc_lat), lat, { s -> lat = s }, it) }, { Num(stringResource(R.string.loc_lon), lon, { s -> lon = s }, it) })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val a = lat.num(); val b = lon.num()
                if (a == null || b == null || a !in -90.0..90.0 || b !in -180.0..180.0 || name.isBlank()) { error = context.getString(R.string.loc_error); return@OutlinedButton }
                error = null; sites += name.trim() to GeoLocation(a, b); name = ""; lat = ""; lon = ""
            }) { Text(stringResource(R.string.add)) }
            Button(onClick = { run++ }, enabled = !busy) { Text(if (busy) stringResource(R.string.computing_short) else stringResource(R.string.compare)) }
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
            Line(stringResource(R.string.loc_yield), "${f(r.specificKwhPerKwp, 0)} kWh/kWp · ${f(r.clearSkyKwh, 0)} kWh")
            Line(stringResource(R.string.loc_optimal), "${f(r.optimalTiltDeg, 0)}° (+${f(r.optimalTiltGainPercent)}%)")
        }
        EstimateBadge(text = stringResource(R.string.badge_upper_bound))
    }
}

// ---------------------------------------------------------------- Vehicle

@Composable
private fun VehicleTool(settings: AppSettings) {
    var length by rememberSaveable { mutableStateOf("") }
    var width by rememberSaveable { mutableStateOf("") }
    var roofArea by rememberSaveable { mutableStateOf("") }
    var count by rememberSaveable { mutableStateOf("") }
    var power by rememberSaveable { mutableStateOf("") }
    var panelLength by rememberSaveable { mutableStateOf("") }
    var panelWidth by rememberSaveable { mutableStateOf("") }
    var tilt by rememberSaveable { mutableStateOf("0") }
    var relAz by rememberSaveable { mutableStateOf("0") }
    var heading by rememberSaveable { mutableStateOf("0") }
    var consumption by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf(emptyList<String>()) }
    var cfg by remember { mutableStateOf<VehicleSolarConfig?>(null) }
    var run by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<VehicleEstimate?>(null) }
    var profile by remember { mutableStateOf<HeadingProfile?>(null) }
    var tilts by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    val context = LocalContext.current

    SectionCard {
        Text(stringResource(R.string.veh_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.veh_body), fontWeight = FontWeight.SemiBold)
        Pair2({ Num(stringResource(R.string.cfg_length_m), length, { s -> length = s }, it) }, { Num(stringResource(R.string.cfg_width_m), width, { s -> width = s }, it) })
        Num(stringResource(R.string.veh_roof_area), roofArea, { s -> roofArea = s }, Modifier.fillMaxWidth())
        Text(stringResource(R.string.veh_panels), fontWeight = FontWeight.SemiBold)
        Pair2({ Num(stringResource(R.string.cfg_panel_count), count, { s -> count = s }, it) }, { Num(stringResource(R.string.ds_panel_power), power, { s -> power = s }, it) })
        Pair2({ Num(stringResource(R.string.veh_panel_length), panelLength, { s -> panelLength = s }, it) }, { Num(stringResource(R.string.veh_panel_width), panelWidth, { s -> panelWidth = s }, it) })
        Pair2({ Num(stringResource(R.string.veh_tilt), tilt, { s -> tilt = s }, it) }, { Num(stringResource(R.string.veh_rel_az), relAz, { s -> relAz = s }, it) })
        Pair2({ Num(stringResource(R.string.veh_heading), heading, { s -> heading = s }, it) }, { Num(stringResource(R.string.veh_consumption), consumption, { s -> consumption = s }, it) })
        Text(stringResource(R.string.veh_hint, settings.locationName),
            style = MaterialTheme.typography.bodySmall)
        errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = {
            val n = count.num()?.toInt() ?: 0
            val p = power.num()
            if (n !in 1..40 || p == null) { errors = listOf(context.getString(R.string.veh_error)); return@Button }
            val pl = panelLength.num() ?: 0.0
            val pw = panelWidth.num() ?: 0.0
            val body = if (length.num() != null && width.num() != null) VehicleBody(length.num()!!, width.num()!!, roofArea.num()) else null
            val panels = (0 until n).map { i ->
                VehiclePanel("Panel ${i + 1}", p, pl, pw, xM = i * pl, yM = 0.0, tiltDeg = (tilt.num() ?: 0.0), relativeAzimuthDeg = relAz.num() ?: 0.0)
            }
            val c = VehicleSolarConfig(p * n, consumptionKwhPer100Km = consumption.num(), body = body.takeIf { pl > 0 && pw > 0 },
                panels = panels.takeIf { pl > 0 && pw > 0 } ?: emptyList(), tiltDeg = tilt.num() ?: 0.0, relativeAzimuthDeg = relAz.num() ?: 0.0)
            errors = c.validate()
            if (errors.isEmpty()) { cfg = c; run++ }
        }, enabled = !busy) { Text(if (busy) stringResource(R.string.computing_short) else stringResource(R.string.eco_calculate)) }
    }
    LaunchedEffect(run) {
        val c = cfg ?: return@LaunchedEffect
        busy = true
        val now = Instant.now()
        val end = now.atZone(ZoneId.systemDefault()).toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()
        val h = heading.num() ?: 0.0
        withContext(Dispatchers.Default) {
            current = VehicleSolarEstimator.estimate(c, settings.location, h, now, end)
            profile = VehicleSolarEstimator.headingProfile(c, settings.location, now, end, stepDeg = 10)
            tilts = VehicleSolarEstimator.tiltComparison(c, settings.location, h, now, end, (0..90 step 10).map { it.toDouble() })
        }
        busy = false
    }
    current?.let { cur ->
        SectionCard {
            Line(stringResource(R.string.veh_power), "${f(cfg?.totalPowerW ?: 0.0, 0)} W")
            Line(stringResource(R.string.veh_energy, f(cur.headingDeg, 0)), "${f(cur.energyKwh, 2)} kWh")
            cur.rangeKm?.let { Line(stringResource(R.string.veh_range), "${f(it, 0)} km") }
            profile?.let { p ->
                if (p.flat) Text(stringResource(R.string.veh_flat, f(p.sensitivity * 100, 1)), style = MaterialTheme.typography.bodySmall)
                else {
                    Line(stringResource(R.string.veh_best_heading), "${f(p.best.headingDeg, 0)}° → ${f(p.best.energyKwh, 2)} kWh")
                    Line(stringResource(R.string.veh_worst_heading), "${f(p.worst.headingDeg, 0)}° → ${f(p.worst.energyKwh, 2)} kWh")
                }
                Text(stringResource(R.string.veh_heading_energy) + p.points.filter { it.headingDeg.toInt() % 45 == 0 }.joinToString(" · ") { "${f(it.headingDeg, 0)}°: ${f(it.energyKwh, 2)}" },
                    style = MaterialTheme.typography.bodySmall)
            }
            if (tilts.isNotEmpty()) {
                val best = tilts.maxBy { it.second }
                Line(stringResource(R.string.veh_best_tilt), "${f(best.first, 0)}° → ${f(best.second, 2)} kWh")
                Text(stringResource(R.string.veh_tilt_energy) + tilts.joinToString(" · ") { "${f(it.first, 0)}°: ${f(it.second, 2)}" }, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.veh_no_control), style = MaterialTheme.typography.bodySmall)
            }
            EstimateBadge(text = stringResource(R.string.veh_badge))
        }
    }
}

// ---------------------------------------------------------------- Tilt optimizer

@Composable
private fun TiltTool(settings: AppSettings) {
    var minTilt by rememberSaveable { mutableStateOf("") }
    var maxTilt by rememberSaveable { mutableStateOf("") }
    var run by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var rec by remember { mutableStateOf<TiltRecommendation?>(null) }
    val s = settings.system
    SectionCard {
        Text(stringResource(R.string.tilt_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.tilt_hint, f(s.peakPowerKw, 2), f(s.azimuthDeg, 0), settings.locationName),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.tilt_mount), fontWeight = FontWeight.SemiBold)
        Pair2({ Num(stringResource(R.string.tilt_min), minTilt, { v -> minTilt = v }, it) }, { Num(stringResource(R.string.tilt_max), maxTilt, { v -> maxTilt = v }, it) })
        Button(onClick = { run++ }, enabled = !busy) { Text(if (busy) stringResource(R.string.computing_short) else stringResource(R.string.angles_title)) }
    }
    LaunchedEffect(run) {
        if (run == 0) return@LaunchedEffect
        busy = true
        val mount = if (minTilt.num() != null && maxTilt.num() != null && maxTilt.num()!! > minTilt.num()!!) AdjustableMount(minTilt.num()!!, maxTilt.num()!!) else null
        rec = withContext(Dispatchers.Default) {
            TiltOptimizer.compare(PvArrayConfig(1, s.peakPowerKw * 1000, s.tiltDeg, s.azimuthDeg), LossProfile(), settings.location, java.time.LocalDate.now(), mount = mount)
        }
        busy = false
    }
    rec?.let { r ->
        SectionCard {
            Line(stringResource(R.string.tilt_best_static), "${f(r.bestStatic.tiltDeg, 0)}° → ${f(r.bestStatic.annualKwh, 0)} kWh")
            Line(stringResource(R.string.tilt_best_today), "${f(r.bestDaily.tiltDeg, 0)}° → ${f(r.bestDaily.dailyKwh, 1)} kWh")
            Line(stringResource(R.string.tilt_current, f(s.tiltDeg, 0)), r.yields.minBy { kotlin.math.abs(it.tiltDeg - s.tiltDeg) }.let { stringResource(R.string.tilt_per_year, f(it.annualKwh, 0)) })
            Text(stringResource(R.string.tilt_best_month), fontWeight = FontWeight.SemiBold)
            Text(r.bestMonthly.entries.joinToString(" · ") { "${it.key.getDisplayName(java.time.format.TextStyle.SHORT, com.solartracker.pro.ui.Format.locale)} ${f(it.value, 0)}°" },
                style = MaterialTheme.typography.bodySmall)
            Line(stringResource(R.string.tilt_monthly_gain), "+${f(r.monthlyAdjustGainPercent, 1)}%")
            Text(stringResource(R.string.tilt_year_energy) + r.yields.filter { it.tiltDeg.toInt() % 10 == 0 }.joinToString(" · ") { "${f(it.tiltDeg, 0)}°: ${f(it.annualKwh, 0)}" },
                style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.tilt_no_control), style = MaterialTheme.typography.bodySmall)
            EstimateBadge(text = stringResource(R.string.badge_upper_bound))
        }
    }
}
