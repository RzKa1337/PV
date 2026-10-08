package com.solartracker.pro.ui.radar

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.forecast.ConfidenceLevel
import com.solartracker.pro.core.forecast.HourForecast
import com.solartracker.pro.core.forecast.HourlyPvForecastEngine
import com.solartracker.pro.core.forecast.HourlyPvReport
import com.solartracker.pro.core.forecast.ValueQuality
import com.solartracker.pro.core.radar.RadarFrameKind
import com.solartracker.pro.core.radar.RadarFreshnessEvaluator
import com.solartracker.pro.core.radar.RadarStatus
import com.solartracker.pro.core.weather.OpenMeteo
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.i18n.tr
import com.solartracker.pro.ui.WeatherState
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.energy.MonitorWhileVisible
import com.solartracker.pro.ui.theme.ChartColors
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private fun w(x: Double?): String = x?.let { if (it >= 1000) String.format(Locale.ROOT, "%,.0f W", it) else String.format(Locale.ROOT, "%.0f W", it) } ?: "N/A"
private fun c(x: Double?, d: Int = 1): String = x?.let { String.format(Locale.ROOT, "%.${d}f°C", it) } ?: "N/A"
private fun pct(x: Double?): String = x?.let { "${it.roundToInt()}%" } ?: "N/A"
private fun kwh(x: Double?): String = x?.let { String.format(Locale.ROOT, "%.2f kWh", it) } ?: "N/A"
private fun kmh(ms: Double?): String = ms?.let { "${(it * 3.6).roundToInt()} km/h" } ?: "N/A"

private fun qualityLabel(q: ValueQuality): String = when (q) {
    ValueQuality.FORECAST -> tr("PROGNOZA", "FORECAST")
    ValueQuality.CACHED -> tr("Z PAMIĘCI", "CACHED")
    ValueQuality.CALCULATED -> tr("OBLICZONE", "CALCULATED")
    ValueQuality.MODEL -> tr("MODEL", "MODEL")
    ValueQuality.CLIMATE -> tr("KLIMAT", "CLIMATE")
    ValueQuality.CLEAR_SKY -> tr("CZYSTE NIEBO", "CLEAR SKY")
    ValueQuality.REAL -> tr("POMIAR", "REAL")
    ValueQuality.PARTIAL -> tr("POMIAR CZĘŚCIOWY", "PARTIAL")
    ValueQuality.SIMULATED -> tr("SYMULACJA", "SIMULATED")
    ValueQuality.UNAVAILABLE -> "N/A"
}

private fun levelLabel(l: ConfidenceLevel) = when (l) {
    ConfidenceLevel.HIGH -> tr("WYSOKA", "HIGH"); ConfidenceLevel.MEDIUM -> tr("ŚREDNIA", "MEDIUM"); ConfidenceLevel.LOW -> tr("NISKA", "LOW")
}

/** 🌦️ Radar & Prognoza: radar, current weather, PV forecast vs measurement, hourly and daily forecast. */
@Composable
fun RadarScreen(vm: EnergyCenterViewModel, weather: WeatherState, locationName: String, onRefreshWeather: () -> Unit, modifier: Modifier = Modifier) {
    MonitorWhileVisible(vm, weather)
    DisposableEffect(Unit) {
        vm.setRadarVisible(true)
        onDispose { vm.setRadarVisible(false) }
    }
    val st by vm.radar.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
            vm.loadRadar(force = false) // re-reads the index at most every 5 min
            vm.refreshRadarForecast()
        }
    }
    val report = st.report
    val zone = report?.zone ?: ZoneId.systemDefault()
    val hm = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    val dayFmt = remember(zone) { DateTimeFormatter.ofPattern("EEE d.MM").withZone(zone) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag("radar_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Header
        ScreenTitle(tr("🌦️ Radar i prognoza", "🌦️ Radar & Forecast"),
            listOfNotNull(locationName.takeIf { it.isNotBlank() }, weather.updatedAt?.let { tr("prognoza z ", "forecast from ") + hm.format(it) }).joinToString(" · "))
        if (report != null) {
            Text(tr("Godziny w strefie: ", "Times in zone: ") + zone.id + if (!report.zoneFromProvider) tr(" (strefa telefonu – dostawca nie podał strefy lokalizacji)", " (device zone – provider gave no location zone)") else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val forecastAge = weather.forecast?.fetchedAt?.let { Duration.between(it, now) }
        if (weather.error != null || (forecastAge != null && forecastAge > Duration.ofHours(3))) {
            SectionCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
                Text(tr("Offline / prognoza z pamięci", "Offline / cached forecast"), fontWeight = FontWeight.Bold)
                weather.error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                weather.forecast?.fetchedAt?.let { Text(tr("Prognoza pobrana: ", "Forecast downloaded: ") + DateTimeFormatter.ofPattern("d.MM HH:mm").withZone(zone).format(it), style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = onRefreshWeather) { Text(tr("Odśwież", "Refresh")) }
            }
        }
        if (!weather.enabled) Text(tr("Pogoda wyłączona w ustawieniach – prognoza PV liczona z modelu bezchmurnego nieba / klimatu.",
            "Weather is off in settings – PV forecast uses the clear-sky / climate model."), color = MaterialTheme.colorScheme.error)

        RadarCard(vm, st, now, hm, zone)

        if (st.computing && report == null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(); Spacer(Modifier.width(8.dp)); Text(tr("Liczę prognozę…", "Computing forecast…")) }
        st.reportError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (report != null) {
            DataQualityRow(report, st)
            CurrentWeatherCard(report, hm)
            TodayCard(report, hm)
            OutlookCard(report, hm)
            ChartCard(vm, report, st.rangeDays, hm, dayFmt)
            HourlyListCard(report, st.rangeDays, now, hm, dayFmt)
            DailyCard(report, dayFmt)
            AccuracyCard(report)
        }
        Text(OpenMeteo.ATTRIBUTION + " · " + com.solartracker.pro.core.radar.RainViewer.ATTRIBUTION + " · " + tr("Mapa © OpenStreetMap", "Map © OpenStreetMap"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RadarCard(vm: EnergyCenterViewModel, st: com.solartracker.pro.energy.RadarUiState, now: Instant, hm: DateTimeFormatter, zone: ZoneId) {
    val context = LocalContext.current
    val snap = st.snapshot
    val frames = snap?.frames.orEmpty()
    var index by rememberSaveable(snap?.generatedAt) { mutableIntStateOf(frames.indexOfLast { it.kind == RadarFrameKind.PAST }.coerceAtLeast(0)) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(playing, frames.size) {
        while (playing && frames.isNotEmpty()) { delay(700); index = (index + 1) % frames.size }
    }
    val fresh = RadarFreshnessEvaluator.evaluate(snap, now, st.fromCache)
    SectionCard(Modifier.testTag("radar_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Radar opadów", "Precipitation radar"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val color = when (fresh.status) { RadarStatus.LIVE -> ChartColors.charge; RadarStatus.CACHED -> ChartColors.pv; else -> MaterialTheme.colorScheme.error }
            Text(when (fresh.status) {
                RadarStatus.LIVE -> tr("NA ŻYWO", "LIVE"); RadarStatus.STALE -> tr("DANE NIEAKTUALNE", "STALE DATA")
                RadarStatus.CACHED -> tr("Z PAMIĘCI", "CACHED"); RadarStatus.UNAVAILABLE -> tr("NIEDOSTĘPNY", "UNAVAILABLE")
            }, color = color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("radar_status"))
        }
        fresh.age?.let { age ->
            Text(tr("Dane sprzed: ", "Data age: ") + "${age.toMinutes()} min · " + tr("ostatnia klatka ", "last frame ") + hm.format(fresh.latest),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        st.radarError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        val settingsLocation = vm.settings.collectAsStateWithLifecycle().value?.location
        if (settingsLocation != null) {
            RadarMap(settingsLocation.latitude, settingsLocation.longitude, frames.getOrNull(index), snap?.maxZoom ?: 7,
                Modifier.fillMaxWidth().height(300.dp))
        }
        if (frames.isNotEmpty()) {
            val f = frames[index.coerceIn(0, frames.lastIndex)]
            Text((if (f.kind == RadarFrameKind.NOWCAST) tr("Prognoza radaru (nowcast) ", "Radar nowcast ") else tr("Obserwacja ", "Observed ")) + hm.format(f.time) +
                " (" + Duration.between(f.time, now).toMinutes().let { if (it >= 0) "−$it min" else "+${-it} min" } + ")", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { playing = !playing }) { Text(if (playing) "⏸" else "▶") }
                Slider(value = index.toFloat(), onValueChange = { playing = false; index = it.roundToInt() }, valueRange = 0f..(frames.size - 1).coerceAtLeast(1).toFloat(),
                    steps = (frames.size - 2).coerceAtLeast(0), modifier = Modifier.weight(1f))
            }
        } else if (!st.loading) {
            Text(tr("Brak klatek radaru – nie pokazuję niczego, czego nie ma w danych.", "No radar frames – nothing is shown that is not in the data."), style = MaterialTheme.typography.bodySmall)
        }
        if (st.loading) CircularProgressIndicator(Modifier.padding(4.dp))
        Text(tr("Legenda: kolory skali RainViewer „Universal Blue” – im intensywniejszy kolor, tym silniejszy opad. Radar pokazuje opad, nie chmury bez opadu.",
            "Legend: RainViewer “Universal Blue” scale – the more intense the colour, the heavier the precipitation. Radar shows precipitation, not dry clouds."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.loadRadar(force = true) }) { Text(tr("Odśwież radar", "Refresh radar")) }
            val loc = vm.settings.collectAsStateWithLifecycle().value?.location
            if (loc != null) OutlinedButton(onClick = {
                // Windy is opened in the browser (no scraping, no unofficial API, no key in the app).
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.windy.com/?radar,${String.format(Locale.ROOT, "%.3f,%.3f", loc.latitude, loc.longitude)},8")))
            }) { Text(tr("Otwórz w Windy", "Open in Windy")) }
        }
    }
}

@Composable
private fun DataQualityRow(r: HourlyPvReport, st: com.solartracker.pro.energy.RadarUiState) {
    val today = r.days.firstOrNull()
    val radar = RadarFreshnessEvaluator.evaluate(st.snapshot, r.generatedAt, st.fromCache).status
    Row(Modifier.horizontalScroll(rememberScrollState()).testTag("radar_quality"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            tr("Pewność prognozy: ", "Forecast confidence: ") + (today?.confidence?.level?.let(::levelLabel) ?: "N/A"),
            tr("PV teraz: ", "Actual PV: ") + qualityLabel(r.now.actualQuality),
            tr("Pogoda: ", "Weather: ") + qualityLabel(r.weatherQuality),
            "Radar: " + when (radar) { RadarStatus.LIVE -> "LIVE"; RadarStatus.STALE -> "STALE"; RadarStatus.CACHED -> "CACHED"; RadarStatus.UNAVAILABLE -> "N/A" },
        ).forEach { FilterChip(selected = false, onClick = {}, label = { Text(it, style = MaterialTheme.typography.labelSmall) }) }
    }
}

@Composable
private fun CurrentWeatherCard(r: HourlyPvReport, hm: DateTimeFormatter) {
    val h = r.now.hour
    val wx = h?.weather
    SectionCard(Modifier.testTag("radar_now")) {
        Text(tr("Teraz", "Now") + (h?.let { " · ${hm.format(it.start)}–${hm.format(it.end)}" } ?: ""), fontWeight = FontWeight.Bold)
        if (h == null || wx == null) {
            Text(tr("Brak prognozy pogody dla tej godziny (N/A)", "No weather forecast for this hour (N/A)"))
        } else {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(c(wx.temperatureC), fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                wx.apparentTemperatureC?.let { Text(tr("odczuwalna ", "feels like ") + c(it), style = MaterialTheme.typography.bodySmall) }
            }
            Line(tr("Wilgotność", "Humidity"), pct(wx.relativeHumidityPercent))
            Line(tr("Punkt rosy", "Dew point"), c(h.dewPointC) + if (h.dewPointCalculated) tr(" (obliczony)", " (calculated)") else "")
            Line(tr("Słońce zasłonięte przez chmury", "Sunlight blocked by clouds"), pct(h.effectiveCloudPercent) + " · " + tr("zachm. całkowite ", "total cover ") + pct(wx.cloudCoverPercent))
            Line(tr("Wiatr", "Wind"), kmh(wx.windSpeedMs) + " " + (HourlyPvForecastEngine.compass(wx.windDirectionDeg) ?: "") + " · " + tr("porywy ", "gusts ") + kmh(wx.windGustsMs))
            Line(tr("Opad (ta godzina)", "Precipitation (this hour)"), (wx.precipitationMm?.let { String.format(Locale.ROOT, "%.1f mm", it) } ?: "N/A") +
                (wx.precipitationProbabilityPercent?.let { " · ${it.roundToInt()}%" } ?: ""))
            Text(tr("Źródło: Open-Meteo, prognoza dla ", "Source: Open-Meteo, forecast valid for ") + hm.format(h.start) + "–" + hm.format(h.end) + " · " + qualityLabel(h.weatherProvenance.quality),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Line(tr("PV teraz", "PV now"), when (r.now.actualQuality) {
            ValueQuality.REAL -> w(r.now.actualW)
            ValueQuality.SIMULATED -> "N/A · " + tr("symulator ", "simulator ") + w(r.now.simulatedW) + " (SIMULATED)"
            else -> "N/A"
        }, bold = true)
        Line(tr("Oczekiwane (model)", "Expected (model)"), w(r.now.expectedW))
        r.now.differenceW?.let { Line(tr("Różnica", "Difference"), String.format(Locale.ROOT, "%+.0f W", it)) }
        r.now.performancePercent?.let { Line(tr("Wydajność", "Performance"), pct(it)) }
        Text(tr("Oczekiwane: Solar Tracker PV Model (PvEstimator + pogoda, temperatura, zacienienie, kalibracja). PV teraz: falownik Anenji (pomiar).",
            "Expected: Solar Tracker PV Model (PvEstimator + weather, temperature, shading, calibration). PV now: Anenji inverter (measured)."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold)
    }
}

@Composable
private fun TodayCard(r: HourlyPvReport, hm: DateTimeFormatter) {
    val d = r.days.firstOrNull() ?: return
    SectionCard(Modifier.testTag("radar_today")) {
        Text(tr("DZIŚ", "TODAY") + " ${d.icon} ${d.condition}", fontWeight = FontWeight.Bold)
        Text(tr("Prognoza PV: ", "Expected PV: ") + kwh(d.expectedKwh), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Line(tr("Szczyt", "Peak"), w(d.peakW) + (d.peakAt?.let { " · " + hm.format(it) } ?: ""))
        Line(tr("Wschód / zachód", "Sunrise / sunset"), (d.sun.sunrise?.let(hm::format) ?: "—") + " / " + (d.sun.sunset?.let(hm::format) ?: "—"))
        Line(tr("Górowanie / długość dnia", "Solar noon / daylight"), hm.format(d.sun.solarNoon) + " / " + "${d.sun.dayLength.toHours()} h ${d.sun.dayLength.toMinutes() % 60} min")
        Line(tr("Temperatura min / max", "Temperature min / max"), c(d.minTemperatureC) + " / " + c(d.maxTemperatureC) + (d.maxTemperatureAt?.let { " (" + tr("najcieplej ", "warmest ") + hm.format(it) + ")" } ?: ""))
        if (d.actualSoFarKwh != null) {
            Line(tr("Wyprodukowano do teraz", "Actual so far"), kwh(d.actualSoFarKwh), bold = true)
            Line(tr("Oczekiwane w tym czasie", "Expected so far"), kwh(d.expectedSoFarKwh))
            Line(tr("Wydajność", "Performance"), d.performancePercent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: "N/A")
        } else Line(tr("Wyprodukowano do teraz", "Actual so far"), tr("N/A (brak pomiarów z falownika)", "N/A (no inverter measurements)"))
        Text(tr("Pewność: ", "Confidence: ") + levelLabel(d.confidence.level) + " · " + d.confidence.factors.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutlookCard(r: HourlyPvReport, hm: DateTimeFormatter) {
    SectionCard(Modifier.testTag("radar_outlook")) {
        r.best?.let { b ->
            Text("☀️ " + tr("Najlepsza produkcja: ", "Best production: ") + "${hm.format(b.from)}–${hm.format(b.to)}", fontWeight = FontWeight.Bold)
            Text(tr("Szczyt ok. ", "Expected peak ~") + w(b.expectedW) + (b.reason?.let { " ($it)" } ?: ""), style = MaterialTheme.typography.bodySmall)
        } ?: Text(tr("Brak produkcji PV w prognozie na dziś", "No PV production expected today"))
        r.worst?.let { b ->
            Text("🌧️ " + tr("Najgorsze warunki: ", "Worst conditions: ") + "${hm.format(b.from)}–${hm.format(b.to)}", fontWeight = FontWeight.Bold)
            Text(tr("PV do ", "PV up to ") + w(b.expectedW) + " · " + tr("powód: ", "reason: ") + (b.reason ?: "—"), style = MaterialTheme.typography.bodySmall)
        }
        r.rain?.let { rain ->
            HorizontalDivider()
            if (rain.from == null) Text("🌤️ " + rain.note, style = MaterialTheme.typography.bodySmall)
            else {
                Text("🌧️ " + (if (rain.raining) tr("Opady teraz (prognoza)", "Rain now (forecast)") else tr("Opady od ", "Rain from ") + hm.format(rain.from)) +
                    " – " + tr("do ", "until ") + hm.format(rain.to), fontWeight = FontWeight.Bold)
                Text(String.format(Locale.ROOT, "%.1f mm", rain.precipitationMm) + (rain.maxProbabilityPercent?.let { " · ${it.roundToInt()}%" } ?: "") +
                    " · " + (rain.impactPercent?.let { tr("wpływ na PV: ", "PV impact: ") + "−${it.roundToInt()}% (~${String.format(Locale.ROOT, "%.2f", rain.reductionKwh)} kWh)" }
                        ?: tr("wpływu nie da się oszacować", "impact estimate unavailable")), style = MaterialTheme.typography.bodySmall)
                Text(rain.note + tr(" · źródło: prognoza godzinowa Open-Meteo (rozdzielczość 1 h); radar służy do podglądu.", " · source: Open-Meteo hourly forecast (1 h resolution); radar is for viewing."),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (r.alerts.isNotEmpty()) {
            HorizontalDivider()
            r.alerts.forEach { a ->
                Text("${a.icon} ${a.title}", fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("radar_alert_${a.kind.name}"))
                Text(a.detail, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ChartCard(vm: EnergyCenterViewModel, r: HourlyPvReport, range: Int, hm: DateTimeFormatter, dayFmt: DateTimeFormatter) {
    val start = r.days.firstOrNull()?.hours?.firstOrNull()?.start ?: return
    val hours = r.hours.filter { !it.start.isBefore(start) && it.start.isBefore(start.plus(Duration.ofDays(range.toLong()))) }
    if (hours.isEmpty()) return
    var selected by remember(range, r.generatedAt) { mutableIntStateOf(hours.indexOfFirst { !r.generatedAt.isBefore(it.start) && r.generatedAt.isBefore(it.end) }.coerceAtLeast(0)) }
    val coveredDays = r.days.size
    SectionCard(Modifier.testTag("radar_chart")) {
        Text(tr("Moc PV [W]", "PV power [W]"), fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "24 h", 2 to "48 h", 7 to tr("7 dni", "7 days"), 14 to tr("14 dni", "14 days")).forEach { (d, label) ->
                FilterChip(selected = range == d, enabled = d <= 2 || coveredDays >= d, onClick = { vm.setRadarRange(d) }, label = { Text(label) })
            }
        }
        val expColor = ChartColors.pv
        val actColor = ChartColors.consumption
        val grid = MaterialTheme.colorScheme.outlineVariant
        val sel = MaterialTheme.colorScheme.primary
        val maxW = (hours.maxOf { maxOf(it.expectedMaxW, it.actual.averageW ?: 0.0) }).coerceAtLeast(100.0) * 1.1
        Canvas(Modifier.fillMaxWidth().height(200.dp).pointerInput(hours.size) {
            detectTapGestures { o -> selected = ((o.x / size.width) * hours.size).toInt().coerceIn(0, hours.lastIndex) }
        }) {
            val dx = size.width / hours.size
            fun y(v: Double) = size.height - (v / maxW * size.height).toFloat()
            for (k in 1..3) drawLine(grid, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), 1f)
            hours.forEachIndexed { i, h -> if (h.start.atZone(r.zone).hour == 0 && i > 0) drawLine(grid, Offset(i * dx, 0f), Offset(i * dx, size.height), 1f) }
            // Uncertainty band of the expected power.
            val band = Path().apply {
                hours.forEachIndexed { i, h -> val x = i * dx + dx / 2; if (i == 0) moveTo(x, y(h.expectedMaxW)) else lineTo(x, y(h.expectedMaxW)) }
                hours.indices.reversed().forEach { i -> lineTo(i * dx + dx / 2, y(hours[i].expectedMinW)) }
                close()
            }
            drawPath(band, expColor.copy(alpha = 0.15f))
            val exp = Path().apply { hours.forEachIndexed { i, h -> val x = i * dx + dx / 2; if (i == 0) moveTo(x, y(h.expectedW)) else lineTo(x, y(h.expectedW)) } }
            drawPath(exp, expColor, style = Stroke(width = 4f))
            // Actual: only real measurements, broken where there is no data (never bridged).
            var path: Path? = null
            hours.forEachIndexed { i, h ->
                val a = h.actual.averageW?.takeIf { h.actual.quality == ValueQuality.REAL || h.actual.quality == ValueQuality.PARTIAL }
                val x = i * dx + dx / 2
                if (a == null) { path?.let { drawPath(it, actColor, style = Stroke(width = 4f)) }; path = null }
                else path = (path ?: Path().apply { moveTo(x, y(a)) }).apply { lineTo(x, y(a)) }
            }
            path?.let { drawPath(it, actColor, style = Stroke(width = 4f)) }
            val sx = selected * dx + dx / 2
            drawLine(sel, Offset(sx, 0f), Offset(sx, size.height), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(if (range <= 2) hm.format(hours.first().start) else dayFmt.format(hours.first().start), style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text(tr("max ", "max ") + w(maxW / 1.1), style = MaterialTheme.typography.labelSmall)
        }
        Text("━ " + tr("oczekiwana (model)", "expected (model)") + "   ━ " + tr("rzeczywista (pomiar)", "actual (measured)") + "   ▒ " + tr("zakres niepewności", "uncertainty"),
            style = MaterialTheme.typography.labelSmall)
        Text(tr("Oczekiwana – pomarańczowa, rzeczywista – niebieska. Dotknij wykresu, aby zobaczyć godzinę.", "Expected – orange, actual – blue. Tap the chart to see an hour."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HourDetails(hours[selected.coerceIn(0, hours.lastIndex)], hm, dayFmt)
        // Humidity and temperature of the same hours.
        Text(tr("Wilgotność [%] i temperatura", "Humidity [%] and temperature"), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        val tmin = hours.mapNotNull { it.weather?.temperatureC }.minOrNull()
        val tmax = hours.mapNotNull { it.weather?.temperatureC }.maxOrNull()
        Canvas(Modifier.fillMaxWidth().height(90.dp)) {
            val dx = size.width / hours.size
            var p: Path? = null
            hours.forEachIndexed { i, h ->
                val v = h.weather?.relativeHumidityPercent
                val x = i * dx + dx / 2
                if (v == null) { p?.let { drawPath(it, ChartColors.consumption, style = Stroke(3f)) }; p = null }
                else { val yy = size.height - (v / 100 * size.height).toFloat(); p = (p ?: Path().apply { moveTo(x, yy) }).apply { lineTo(x, yy) } }
            }
            p?.let { drawPath(it, ChartColors.consumption, style = Stroke(3f)) }
            if (tmin != null && tmax != null && tmax > tmin) {
                var q: Path? = null
                hours.forEachIndexed { i, h ->
                    val v = h.weather?.temperatureC
                    val x = i * dx + dx / 2
                    if (v == null) { q?.let { drawPath(it, ChartColors.grid, style = Stroke(3f)) }; q = null }
                    else { val yy = size.height - ((v - tmin) / (tmax - tmin) * size.height * 0.9 + size.height * 0.05).toFloat(); q = (q ?: Path().apply { moveTo(x, yy) }).apply { lineTo(x, yy) } }
                }
                q?.let { drawPath(it, ChartColors.grid, style = Stroke(3f)) }
            }
        }
        Text("━ RH 0–100%   ━ " + tr("temperatura ", "temperature ") + c(tmin) + "…" + c(tmax), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun HourDetails(h: HourForecast, hm: DateTimeFormatter, dayFmt: DateTimeFormatter) {
    val wx = h.weather
    SectionCard(Modifier.testTag("radar_tooltip"), containerColor = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Text("${dayFmt.format(h.start)} ${hm.format(h.start)}–${hm.format(h.end)}", fontWeight = FontWeight.Bold)
        Line(tr("Temperatura", "Temperature"), c(wx?.temperatureC) + (wx?.apparentTemperatureC?.let { " (" + tr("odczuwalna ", "feels ") + c(it) + ")" } ?: ""))
        Line(tr("Wilgotność", "Humidity"), pct(wx?.relativeHumidityPercent))
        Line(tr("Punkt rosy", "Dew point"), c(h.dewPointC) + if (h.dewPointCalculated) tr(" (obliczony: Magnus)", " (calculated: Magnus)") else "")
        Line(tr("Chmury", "Clouds"), pct(wx?.cloudCoverPercent) + " · " + tr("niskie ", "low ") + pct(wx?.cloudLowPercent) + " · " + tr("średnie ", "mid ") + pct(wx?.cloudMidPercent) +
            " · " + tr("wysokie ", "high ") + pct(wx?.cloudHighPercent))
        Line(tr("Słońce zasłonięte", "Sunlight blocked"), pct(h.effectiveCloudPercent))
        Line(tr("Opad", "Precipitation"), (wx?.precipitationMm?.let { String.format(Locale.ROOT, "%.1f mm", it) } ?: "N/A") + " · " + pct(wx?.precipitationProbabilityPercent) +
            ((wx?.snowfallCm)?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, " · " + tr("śnieg %.1f cm", "snow %.1f cm"), it) } ?: ""))
        Line(tr("Wiatr", "Wind"), kmh(wx?.windSpeedMs) + " " + (HourlyPvForecastEngine.compass(wx?.windDirectionDeg) ?: "") + " · " + tr("porywy ", "gusts ") + kmh(wx?.windGustsMs))
        Line(tr("UV", "UV"), wx?.uvIndex?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "N/A")
        Line(tr("Wysokość / azymut słońca", "Solar elevation / azimuth"), String.format(Locale.ROOT, "%.1f° / %.0f°", h.sunElevationDeg, h.sunAzimuthDeg))
        Line("GHI / POA (model)", (h.ghiWm2?.let { "${it.roundToInt()}" } ?: "N/A") + " / " + (h.poaWm2?.let { "${it.roundToInt()} W/m²" } ?: "N/A"))
        HorizontalDivider()
        Line(tr("Oczekiwane PV", "Expected PV"), w(h.expectedW) + " (" + w(h.expectedMinW) + "–" + w(h.expectedMaxW) + ")", bold = true)
        h.clearSkyW?.let { Line(tr("Przy czystym niebie", "Clear sky"), w(it)) }
        Line(tr("Rzeczywiste PV", "Actual PV"), when (h.actual.quality) {
            ValueQuality.REAL -> w(h.actual.averageW)
            ValueQuality.PARTIAL -> w(h.actual.averageW) + tr(" (dane z ", " (data for ") + "${(h.actual.coverage * 100).roundToInt()}%" + tr(" godziny)", " of the hour)")
            ValueQuality.SIMULATED -> "N/A · SIMULATED " + w(h.actual.simulatedW)
            else -> "N/A"
        }, bold = true)
        h.differenceW?.let { Line(tr("Różnica", "Difference"), String.format(Locale.ROOT, "%+.0f W", it) + (h.deviationPercent?.let { d -> String.format(Locale.ROOT, " (%+.1f%%)", d) } ?: "")) }
        Text(tr("Pogoda: ", "Weather: ") + h.weatherProvenance.source + (h.weatherProvenance.generatedAt?.let { tr(", prognoza z ", ", issued ") + hm.format(it) } ?: "") +
            tr(", ważna dla ", ", valid for ") + hm.format(h.start) + " · " + qualityLabel(h.weatherProvenance.quality) + " · PV: Solar Tracker PV Model (${h.pvBasis})",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HourlyListCard(r: HourlyPvReport, range: Int, now: Instant, hm: DateTimeFormatter, dayFmt: DateTimeFormatter) {
    val start = r.days.firstOrNull()?.hours?.firstOrNull()?.start ?: return
    val all = r.hours.filter { !it.start.isBefore(start) && it.start.isBefore(start.plus(Duration.ofDays(range.toLong()))) }
    // Longer ranges list daylight hours only (night rows are all zero PV).
    val hours = if (range > 2) all.filter { it.sunElevationDeg > 0 } else all
    SectionCard(Modifier.testTag("radar_hourly")) {
        Text(tr("Prognoza godzinowa", "Hourly forecast"), fontWeight = FontWeight.Bold)
        Text(tr("Czas · temp · RH · rosa · chmury · opad · wiatr · słońce · PV oczek. / rzecz. – dotknij wiersza, aby rozwinąć", "Time · temp · RH · dew · clouds · rain · wind · sun · PV exp. / act. – tap a row to expand"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (range > 2) Text(tr("Pokazane tylko godziny dzienne.", "Daylight hours only."), style = MaterialTheme.typography.labelSmall)
        var lastDay: java.time.LocalDate? = null
        hours.forEach { h ->
            val d = h.start.atZone(r.zone).toLocalDate()
            if (d != lastDay) { Text(dayFmt.format(h.start), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium); lastDay = d }
            var open by rememberSaveable(h.start.epochSecond) { mutableStateOf(false) }
            val current = !now.isBefore(h.start) && now.isBefore(h.end)
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 2.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val wx = h.weather
                    Text(hm.format(h.start) + if (current) " ◀" else "", fontFamily = FontFamily.Monospace, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.bodySmall)
                    Text(c(wx?.temperatureC), style = MaterialTheme.typography.bodySmall)
                    Text(pct(wx?.relativeHumidityPercent), style = MaterialTheme.typography.bodySmall)
                    Text("❄" + c(h.dewPointC), style = MaterialTheme.typography.bodySmall)
                    Text("☁" + pct(wx?.cloudCoverPercent), style = MaterialTheme.typography.bodySmall)
                    Text("💧" + (wx?.precipitationMm?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—") + "mm " + pct(wx?.precipitationProbabilityPercent), style = MaterialTheme.typography.bodySmall)
                    Text("💨" + kmh(wx?.windSpeedMs), style = MaterialTheme.typography.bodySmall)
                    Text("☀" + String.format(Locale.ROOT, "%.0f°", h.sunElevationDeg), style = MaterialTheme.typography.bodySmall)
                    Text(w(h.expectedW), fontWeight = FontWeight.SemiBold, color = ChartColors.pv, style = MaterialTheme.typography.bodySmall)
                    Text(if (h.actual.quality == ValueQuality.REAL || h.actual.quality == ValueQuality.PARTIAL) w(h.actual.averageW) else "N/A",
                        fontWeight = FontWeight.SemiBold, color = ChartColors.consumption, style = MaterialTheme.typography.bodySmall)
                }
                if (open) HourDetails(h, hm, dayFmt)
            }
        }
    }
}

@Composable
private fun DailyCard(r: HourlyPvReport, dayFmt: DateTimeFormatter) {
    SectionCard(Modifier.testTag("radar_daily")) {
        Text(tr("Produkcja PV – kolejne dni", "PV production – next days"), fontWeight = FontWeight.Bold)
        r.days.forEachIndexed { i, d ->
            val label = when (i) { 0 -> tr("Dziś", "Today"); 1 -> tr("Jutro", "Tomorrow"); else -> dayFmt.format(d.date.atStartOfDay(r.zone).toInstant()) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$label ${d.icon}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(kwh(d.expectedKwh), fontWeight = FontWeight.Bold)
            }
            Text("${d.condition} · " + tr("pewność ", "confidence ") + levelLabel(d.confidence.level) + " · " + c(d.minTemperatureC, 0) + "…" + c(d.maxTemperatureC, 0) +
                (d.clearSkyKwh?.takeIf { it > 0 }?.let { " · " + tr("czyste niebo ", "clear sky ") + kwh(it) } ?: ""), style = MaterialTheme.typography.labelSmall)
        }
        Text(tr("Dni dalsze niż zasięg prognozy nie są pokazywane.", "Days beyond the forecast range are not shown."), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AccuracyCard(r: HourlyPvReport) {
    SectionCard(Modifier.testTag("radar_accuracy")) {
        Text(tr("Trafność prognozy (dzień naprzód, 30 dni)", "Forecast accuracy (day ahead, 30 days)"), fontWeight = FontWeight.Bold)
        val a = r.accuracy
        if (a == null || a.count == 0) Text(tr("Za mało danych: potrzebne są zapisane prognozy i pomiary z falownika.", "Not enough data: stored forecasts and inverter measurements are needed."),
            style = MaterialTheme.typography.bodySmall)
        else {
            Line(tr("Godzin porównanych", "Hours compared"), "${a.count}")
            Line("MAE", String.format(Locale.ROOT, "%.3f kWh/h", a.mae))
            Line("RMSE", String.format(Locale.ROOT, "%.3f kWh/h", a.rmse))
            Line("Bias", a.biasPercent?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "N/A")
            Line("MAPE", a.mapePercent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: tr("N/A (za mała produkcja)", "N/A (too little production)"))
            Text(tr("Bez automatycznej korekty – dane służą do oceny pewności.", "No automatic correction – used to rate confidence."), style = MaterialTheme.typography.labelSmall)
        }
    }
}
