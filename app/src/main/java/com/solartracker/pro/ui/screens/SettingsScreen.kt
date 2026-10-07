package com.solartracker.pro.ui.screens

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import com.solartracker.pro.core.shading.GeocodeResult
import com.solartracker.pro.energy.ShadingRepository
import kotlinx.coroutines.launch
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.data.AppSettings
import com.solartracker.pro.data.LocationSource
import com.solartracker.pro.data.AppLanguage
import com.solartracker.pro.data.ThemeMode
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.GpsStatus
import com.solartracker.pro.ui.WeatherState
import com.solartracker.pro.ui.describeSources
import com.solartracker.pro.core.weather.ClimateSource
import com.solartracker.pro.core.weather.OpenMeteo
import androidx.compose.material3.Switch
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import kotlin.math.roundToInt

/** Callbacks from the settings screen; implemented by the ViewModel. */
interface SettingsActions {
    fun setPeakPower(kwp: Double)
    fun setTilt(degrees: Double)
    fun setPanelAzimuth(degrees: Double)
    fun setManualLocation(latitude: Double, longitude: Double, name: String, elevationM: Double)
    fun requestGpsLocation()
    fun setThemeMode(mode: ThemeMode)
    fun setLanguage(language: AppLanguage)
    fun setWeatherEnabled(enabled: Boolean)
    fun refreshWeather()
}

@Composable
fun SettingsScreen(
    settings: AppSettings?,
    gpsStatus: GpsStatus,
    weather: WeatherState,
    actions: SettingsActions,
    energyActions: EnergySettingsActions,
    modifier: Modifier = Modifier,
    footer: @Composable () -> Unit = {},
) {
    if (settings == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ScreenTitle(stringResource(R.string.settings_title), stringResource(R.string.settings_subtitle))
        PeakPowerSection(settings.system.peakPowerKw, actions::setPeakPower)
        TiltSection(settings.system.tiltDeg, actions::setTilt)
        AzimuthSection(settings.system.azimuthDeg, actions::setPanelAzimuth)
        BatterySection(settings.batteryEnabled, settings.battery, energyActions)
        ConsumptionSection(settings.consumption, energyActions)
        PricesSection(settings.prices, energyActions)
        LocationSection(settings, gpsStatus, actions)
        WeatherSection(settings.weatherEnabled, weather, actions)
        ThemeSection(settings.themeMode, actions::setThemeMode)
        LanguageSection(settings.language, actions::setLanguage)
        footer()
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun PeakPowerSection(current: Double, onSave: (Double) -> Unit) {
    var text by rememberSaveable(current) { mutableStateOf(Format.decimal(current)) }
    val parsed = Format.parseDecimal(text)
    val valid = parsed != null && parsed > 0.0 && parsed <= PvSystem.MAX_PEAK_POWER_KW
    SectionCard {
        SectionTitle(stringResource(R.string.settings_power_title))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(10) },
                label = { Text(stringResource(R.string.settings_power_label)) },
                singleLine = true,
                isError = !valid,
                supportingText = { if (!valid) Text(stringResource(R.string.settings_power_error, PvSystem.MAX_PEAK_POWER_KW.toInt().toString())) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(12.dp))
            Button(onClick = { parsed?.let(onSave) }, enabled = valid && parsed != current) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun TiltSection(current: Double, onSave: (Double) -> Unit) {
    var value by remember(current) { mutableFloatStateOf(current.toFloat()) }
    SectionCard {
        SectionTitle(stringResource(R.string.settings_tilt_title, value.roundToInt().toString()))
        Text(
            stringResource(R.string.settings_tilt_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = { value = it.roundToInt().toFloat() },
            onValueChangeFinished = { onSave(value.toDouble()) },
            valueRange = PvSystem.MIN_TILT_DEG.toFloat()..PvSystem.MAX_TILT_DEG.toFloat(),
            steps = 89,
        )
    }
}

@Composable
private fun AzimuthSection(current: Double, onSave: (Double) -> Unit) {
    var value by remember(current) { mutableFloatStateOf(current.toFloat()) }
    SectionCard {
        SectionTitle(stringResource(R.string.settings_azimuth_title, value.roundToInt().toString(), Format.compass(value.toDouble())))
        Text(
            stringResource(R.string.settings_azimuth_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = value,
            onValueChange = { value = it.roundToInt().toFloat() },
            onValueChangeFinished = { onSave(value.toDouble()) },
            valueRange = PvSystem.MIN_AZIMUTH_DEG.toFloat()..PvSystem.MAX_AZIMUTH_DEG.toFloat(),
            steps = 358,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(90.0 to "E", 135.0 to "SE", 180.0 to "S", 225.0 to "SW", 270.0 to "W").forEach { (deg, name) ->
                AssistChip(
                    onClick = {
                        value = deg.toFloat()
                        onSave(deg)
                    },
                    label = { Text("$name ${deg.toInt()}°") },
                )
            }
        }
    }
}

@Composable
private fun LocationSection(settings: AppSettings, gpsStatus: GpsStatus, actions: SettingsActions) {
    val loc = settings.location
    var name by rememberSaveable(loc) { mutableStateOf(settings.locationName) }
    var latText by rememberSaveable(loc) { mutableStateOf(Format.decimal(loc.latitude, 4)) }
    var lonText by rememberSaveable(loc) { mutableStateOf(Format.decimal(loc.longitude, 4)) }
    var elevationText by rememberSaveable(loc) { mutableStateOf(Format.decimal(loc.elevationM, 0)) }
    val lat = Format.parseDecimal(latText)
    val lon = Format.parseDecimal(lonText)
    val elevation = if (elevationText.isBlank()) 0.0 else Format.parseDecimal(elevationText)
    val elevationValid = elevation != null && elevation in -500.0..9000.0
    val latValid = lat != null && lat in -90.0..90.0
    val lonValid = lon != null && lon in -180.0..180.0

    SectionCard {
        SectionTitle(stringResource(R.string.settings_location_title))
        Text(
            stringResource(
                R.string.settings_location_current,
                settings.locationName.ifBlank { "—" },
                if (settings.locationSource == LocationSource.GPS) "GPS" else stringResource(R.string.settings_location_manual),
                Format.coordinates(loc.latitude, loc.longitude),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FilledTonalButton(onClick = actions::requestGpsLocation, enabled = gpsStatus != GpsStatus.Locating) {
            Icon(Icons.Outlined.MyLocation, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (gpsStatus == GpsStatus.Locating) stringResource(R.string.settings_gps_locating) else stringResource(R.string.settings_gps_use))
        }
        when (gpsStatus) {
            is GpsStatus.Error -> Text(gpsStatus.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            GpsStatus.Success -> Text(stringResource(R.string.settings_gps_saved), style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }

        CitySearch(onPick = { r ->
            actions.setManualLocation(r.point.lat, r.point.lon, r.name.substringBefore(',').take(60), r.elevationM ?: 0.0)
        })

        Text(stringResource(R.string.settings_manual_coords), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text(stringResource(R.string.settings_location_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it.take(12) },
                label = { Text(stringResource(R.string.settings_latitude)) },
                supportingText = { Text("-90…90, N +") },
                isError = !latValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lonText,
                onValueChange = { lonText = it.take(12) },
                label = { Text(stringResource(R.string.settings_longitude)) },
                supportingText = { Text("-180…180, E +") },
                isError = !lonValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
        }
        OutlinedTextField(
            value = elevationText,
            onValueChange = { elevationText = it.take(6) },
            label = { Text(stringResource(R.string.settings_elevation)) },
            isError = !elevationValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (lat != null && lon != null) actions.setManualLocation(lat, lon, name, elevation ?: 0.0) },
            enabled = latValid && lonValid && elevationValid,
        ) { Text(stringResource(R.string.settings_save_location)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSection(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(ThemeMode.SYSTEM to stringResource(R.string.option_system), ThemeMode.LIGHT to stringResource(R.string.theme_light), ThemeMode.DARK to stringResource(R.string.theme_dark))
    SectionCard {
        SectionTitle(stringResource(R.string.settings_theme))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = current == mode,
                    onClick = { onSelect(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) { Text(label) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSection(current: AppLanguage, onSelect: (AppLanguage) -> Unit) {
    // Language names are shown in their own language so they can be found in either UI language.
    val options = listOf(
        AppLanguage.POLISH to "Polski",
        AppLanguage.ENGLISH to "English",
        AppLanguage.SYSTEM to stringResource(R.string.option_system),
    )
    SectionCard {
        SectionTitle(stringResource(R.string.settings_language))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (language, label) ->
                SegmentedButton(
                    selected = current == language,
                    onClick = { onSelect(language) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) { Text(label) }
            }
        }
        Text(
            stringResource(R.string.settings_language_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeatherSection(enabled: Boolean, weather: WeatherState, actions: SettingsActions) {
    SectionCard {
        SectionTitle(stringResource(R.string.settings_weather))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_weather_use), style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.settings_weather_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = actions::setWeatherEnabled)
        }
        if (enabled) {
            Text(stringResource(R.string.settings_weather_now, describeSources(weather)), style = MaterialTheme.typography.bodySmall)
            weather.forecast?.coversUntil?.let {
                Text(
                    stringResource(R.string.settings_forecast_until, it.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            weather.climate?.let {
                Text(
                    when (it.source) {
                        ClimateSource.ARCHIVE -> stringResource(R.string.settings_climate_archive, it.years.toString())
                        ClimateSource.DEFAULT_POLAND -> stringResource(R.string.settings_climate_default)
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            weather.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FilledTonalButton(onClick = actions::refreshWeather, enabled = !weather.loading) {
                Text(if (weather.loading) stringResource(R.string.downloading) else stringResource(R.string.settings_weather_refresh))
            }
            Text(OpenMeteo.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                stringResource(R.string.settings_weather_off),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Search a city, address or postcode (OpenStreetMap / Open-Meteo) and use its coordinates. */
@Composable
private fun CitySearch(onPick: (GeocodeResult) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { ShadingRepository(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf(emptyList<GeocodeResult>()) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<String?>(null) }
    fun search() {
        if (query.trim().length < 2 || searching) return
        searching = true
        error = null
        picked = null
        scope.launch {
            results = runCatching { repo.search(query) }.getOrElse {
                error = context.getString(R.string.search_failed, it.message ?: context.getString(R.string.no_internet))
                emptyList()
            }
            if (results.isEmpty() && error == null) error = context.getString(R.string.search_not_found, query.trim())
            searching = false
        }
    }
    Text(stringResource(R.string.search_prompt), style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(100) },
            label = { Text(stringResource(R.string.search_example)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { search() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        FilledTonalButton(onClick = { search() }, enabled = !searching && query.trim().length >= 2) {
            Text(if (searching) "…" else stringResource(R.string.search))
        }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    results.forEach { r ->
        TextButton(onClick = {
            onPick(r)
            picked = r.name
            results = emptyList()
        }) {
            Column(Modifier.fillMaxWidth()) {
                Text(r.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${Format.coordinates(r.point.lat, r.point.lon)}" + (r.elevationM?.let { stringResource(R.string.elevation_asl_m, it.toInt().toString()) } ?: "") + " · ${r.accuracy.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    picked?.let { Text(stringResource(R.string.saved_item, it), style = MaterialTheme.typography.bodySmall) }
}
