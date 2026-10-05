package com.solartracker.pro.ui.screens

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
        ScreenTitle("Ustawienia", "Zapisywane lokalnie w telefonie")
        PeakPowerSection(settings.system.peakPowerKw, actions::setPeakPower)
        TiltSection(settings.system.tiltDeg, actions::setTilt)
        AzimuthSection(settings.system.azimuthDeg, actions::setPanelAzimuth)
        BatterySection(settings.batteryEnabled, settings.battery, energyActions)
        ConsumptionSection(settings.consumption, energyActions)
        PricesSection(settings.prices, energyActions)
        LocationSection(settings, gpsStatus, actions)
        WeatherSection(settings.weatherEnabled, weather, actions)
        ThemeSection(settings.themeMode, actions::setThemeMode)
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
        SectionTitle("Moc instalacji")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(10) },
                label = { Text("Moc [kWp]") },
                singleLine = true,
                isError = !valid,
                supportingText = { if (!valid) Text("Podaj wartość 0–${PvSystem.MAX_PEAK_POWER_KW.toInt()} kWp") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(12.dp))
            Button(onClick = { parsed?.let(onSave) }, enabled = valid && parsed != current) { Text("Zapisz") }
        }
    }
}

@Composable
private fun TiltSection(current: Double, onSave: (Double) -> Unit) {
    var value by remember(current) { mutableFloatStateOf(current.toFloat()) }
    SectionCard {
        SectionTitle("Kąt paneli: ${value.roundToInt()}°")
        Text(
            "0° = poziomo, 90° = pionowo",
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
        SectionTitle("Azymut paneli: ${value.roundToInt()}° ${Format.compass(value.toDouble())}")
        Text(
            "0° = północ, 90° = wschód, 180° = południe, 270° = zachód",
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
        SectionTitle("Lokalizacja")
        Text(
            "Aktualnie: ${settings.locationName.ifBlank { "—" }} " +
                "(${if (settings.locationSource == LocationSource.GPS) "GPS" else "ręcznie"}) · " +
                Format.coordinates(loc.latitude, loc.longitude),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FilledTonalButton(onClick = actions::requestGpsLocation, enabled = gpsStatus != GpsStatus.Locating) {
            Icon(Icons.Outlined.MyLocation, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (gpsStatus == GpsStatus.Locating) "Ustalanie pozycji…" else "Użyj GPS telefonu")
        }
        when (gpsStatus) {
            is GpsStatus.Error -> Text(gpsStatus.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            GpsStatus.Success -> Text("Lokalizacja z GPS zapisana.", style = MaterialTheme.typography.bodySmall)
            else -> Unit
        }

        Text("Lub wpisz ręcznie:", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(60) },
            label = { Text("Nazwa (np. Dom)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = latText,
                onValueChange = { latText = it.take(12) },
                label = { Text("Szerokość") },
                supportingText = { Text("-90…90, N +") },
                isError = !latValid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = lonText,
                onValueChange = { lonText = it.take(12) },
                label = { Text("Długość") },
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
            label = { Text("Wysokość n.p.m. [m] (opcjonalnie)") },
            isError = !elevationValid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { if (lat != null && lon != null) actions.setManualLocation(lat, lon, name, elevation ?: 0.0) },
            enabled = latValid && lonValid && elevationValid,
        ) { Text("Zapisz lokalizację") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeSection(current: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val options = listOf(ThemeMode.SYSTEM to "Systemowy", ThemeMode.LIGHT to "Jasny", ThemeMode.DARK to "Ciemny")
    SectionCard {
        SectionTitle("Motyw")
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

@Composable
private fun WeatherSection(enabled: Boolean, weather: WeatherState, actions: SettingsActions) {
    SectionCard {
        SectionTitle("Pogoda")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Uwzględniaj pogodę", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Prognoza godzinowa (zachmurzenie, nasłonecznienie, temperatura) na 16 dni " +
                        "i średnie klimatyczne z ostatnich 3 lat. Wymaga internetu, dane są zapisywane do pracy offline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = enabled, onCheckedChange = actions::setWeatherEnabled)
        }
        if (enabled) {
            Text("Obecnie: ${describeSources(weather)}", style = MaterialTheme.typography.bodySmall)
            weather.forecast?.coversUntil?.let {
                Text(
                    "Prognoza do: ${it.atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            weather.climate?.let {
                Text(
                    when (it.source) {
                        ClimateSource.ARCHIVE -> "Klimat: archiwum Open-Meteo dla tej lokalizacji (${it.years} lata)"
                        ClimateSource.DEFAULT_POLAND -> "Klimat: przybliżone średnie dla Polski (offline)"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            weather.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            FilledTonalButton(onClick = actions::refreshWeather, enabled = !weather.loading) {
                Text(if (weather.loading) "Pobieranie…" else "Odśwież pogodę")
            }
            Text(OpenMeteo.ATTRIBUTION, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                "Wyłączone: obliczenia zakładają bezchmurne niebo (górna granica produkcji).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
