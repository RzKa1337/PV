package com.solartracker.pro.ui.radar

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.forecast.AlertKind
import com.solartracker.pro.core.forecast.ConfidenceLevel
import com.solartracker.pro.core.forecast.DayForecast
import com.solartracker.pro.core.forecast.ForecastAlert
import com.solartracker.pro.core.forecast.HourForecast
import com.solartracker.pro.core.forecast.HourlyPvForecastEngine
import com.solartracker.pro.core.forecast.HourlyPvReport
import com.solartracker.pro.core.forecast.ValueQuality
import com.solartracker.pro.core.radar.RadarFrameKind
import com.solartracker.pro.core.radar.RadarFreshnessEvaluator
import com.solartracker.pro.core.radar.RadarStatus
import com.solartracker.pro.core.radar.RainViewer
import com.solartracker.pro.core.weather.OpenMeteo
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.energy.RadarUiState
import com.solartracker.pro.i18n.tr
import com.solartracker.pro.ui.WeatherState
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

// ---- Formatting (N/A instead of a guess) -------------------------------------------------------------------------

private fun w(x: Double?): String = x?.let { if (it >= 1000) String.format(Locale.ROOT, "%.2f kW", it / 1000) else "${it.roundToInt()} W" } ?: "N/A"
private fun c(x: Double?, d: Int = 1): String = x?.let { String.format(Locale.ROOT, "%.${d}f°", it) } ?: "N/A"
private fun pct(x: Double?): String = x?.let { "${it.roundToInt()}%" } ?: "N/A"
private fun kwh(x: Double?): String = x?.let { String.format(Locale.ROOT, "%.2f kWh", it) } ?: "N/A"
private fun kmh(ms: Double?): String = ms?.let { "${(it * 3.6).roundToInt()} km/h" } ?: "N/A"
private fun mm(x: Double?): String = x?.let { String.format(Locale.ROOT, "%.1f mm", it) } ?: "N/A"

private fun qualityLabel(q: ValueQuality): String = when (q) {
    ValueQuality.FORECAST -> tr("PROGNOZA", "FORECAST")
    ValueQuality.CACHED -> tr("Z PAMIĘCI", "CACHED")
    ValueQuality.CALCULATED -> tr("OBLICZONE", "CALCULATED")
    ValueQuality.MODEL -> "MODEL"
    ValueQuality.CLIMATE -> tr("KLIMAT", "CLIMATE")
    ValueQuality.CLEAR_SKY -> tr("CZYSTE NIEBO", "CLEAR SKY")
    ValueQuality.REAL -> tr("POMIAR", "REAL")
    ValueQuality.PARTIAL -> tr("CZĘŚCIOWY", "PARTIAL")
    ValueQuality.SIMULATED -> tr("SYMULACJA", "SIMULATED")
    ValueQuality.UNAVAILABLE -> "N/A"
}

private fun levelLabel(l: ConfidenceLevel) = when (l) {
    ConfidenceLevel.HIGH -> tr("wysoka", "high")
    ConfidenceLevel.MEDIUM -> tr("średnia", "medium")
    ConfidenceLevel.LOW -> tr("niska", "low")
}

/** Weather icon of one hour from the provider's WMO code, precipitation and the sunlight actually blocked. */
private fun skyIcon(h: HourForecast): String {
    val wx = h.weather ?: return "·"
    val code = wx.weatherCode ?: -1
    val night = h.sunElevationDeg <= 0
    return when {
        code >= 95 -> "⛈️"
        (wx.snowfallCm ?: 0.0) > 0.05 || code in 71..77 || code in 85..86 -> "🌨️"
        (wx.precipitationMm ?: 0.0) >= 0.1 || code in 51..67 || code in 80..82 -> "🌧️"
        code in 45..48 -> "🌫️"
        night -> if ((wx.cloudCoverPercent ?: 0.0) >= 70) "☁️" else "🌙"
        (h.effectiveCloudPercent ?: wx.cloudCoverPercent ?: 0.0) >= 70 -> "☁️"
        (h.effectiveCloudPercent ?: wx.cloudCoverPercent ?: 0.0) >= 30 -> "⛅"
        else -> "☀️"
    }
}

// ---- Screen --------------------------------------------------------------------------------------------------------

/** 🌦️ Radar & Prognoza: now, today's PV, alerts, radar, PV chart, hourly list, next days, accuracy. */
@Composable
fun RadarScreen(vm: EnergyCenterViewModel, weather: WeatherState, locationName: String, onRefreshWeather: () -> Unit, modifier: Modifier = Modifier) {
    MonitorWhileVisible(vm, weather)
    DisposableEffect(Unit) {
        vm.setRadarVisible(true)
        onDispose { vm.setRadarVisible(false) }
    }
    val st by vm.radar.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = Instant.now()
            vm.loadRadar(force = false) // the repository re-reads the index at most every 5 min
            vm.refreshRadarForecast()   // throttled in the view model
        }
    }
    val report = st.report
    val zone = report?.zone ?: ZoneId.systemDefault()
    val hm = remember(zone) { DateTimeFormatter.ofPattern("HH:mm").withZone(zone) }
    val dayFmt = remember(zone) { DateTimeFormatter.ofPattern("EEE d.MM").withZone(zone) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp).testTag("radar_screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Thin progress line while something loads – content stays visible (no blank screen).
        if (st.loading || st.computing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))

        HeroCard(report, weather, locationName, now, hm, onRefreshWeather)
        if (report == null) {
            if (st.reportError != null) Text(st.reportError!!, color = MaterialTheme.colorScheme.error)
            else SkeletonCard(tr("Liczę prognozę godzinową…", "Computing the hourly forecast…"))
        } else {
            report.alerts.takeIf { it.isNotEmpty() }?.let { AlertsColumn(it) }
            TodayTiles(report, hm)
            OutlookCard(report, hm)
        }
        RadarCard(vm, st, settings?.location?.latitude, settings?.location?.longitude, now, hm)
        if (report != null) {
            ChartCard(vm, report, st.rangeDays, now, hm, dayFmt)
            HourlyCard(report, now, hm, dayFmt)
            DaysRow(report, dayFmt)
            AccuracyCard(report)
            Text(tr("Godziny w strefie ", "Times in zone ") + zone.id + if (!report.zoneFromProvider) tr(" (strefa telefonu – dostawca nie podał strefy)", " (device zone – provider gave none)") else "",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(OpenMeteo.ATTRIBUTION + " · " + RainViewer.ATTRIBUTION + " · " + tr("Mapa © OpenStreetMap", "Map © OpenStreetMap"),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Pill(text: String, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurface, textTag: String? = null) {
    Surface(shape = RoundedCornerShape(50), color = container) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = content, maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).let { m -> textTag?.let { m.testTag(it) } ?: m })
    }
}

@Composable
private fun SkeletonCard(text: String) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ---- Hero: now ---------------------------------------------------------------------------------------------------

/** Background gradient and text colour of the "now" card from the sky of the current hour (contrast ≥ 4.5:1 for text). */
private fun heroStyle(h: HourForecast?): Pair<List<Color>, Color> {
    val wx = h?.weather
    val dark = Color.White
    return when {
        h == null || wx == null -> listOf(Color(0xFF546E7A), Color(0xFF37474F)) to dark
        (wx.precipitationMm ?: 0.0) >= 0.1 || (wx.weatherCode ?: 0) >= 51 -> listOf(Color(0xFF3F51B5), Color(0xFF263238)) to dark
        h.sunElevationDeg <= 0 -> listOf(Color(0xFF283593), Color(0xFF0D1440)) to dark
        (h.effectiveCloudPercent ?: wx.cloudCoverPercent ?: 0.0) >= 60 -> listOf(Color(0xFF607D8B), Color(0xFF455A64)) to dark
        else -> listOf(Color(0xFFFFCA28), Color(0xFFFF8F00)) to Color(0xFF2B1700)
    }
}

@Composable
private fun HeroCard(r: HourlyPvReport?, weather: WeatherState, locationName: String, now: Instant, hm: DateTimeFormatter, onRefresh: () -> Unit) {
    val h = r?.now?.hour
    val wx = h?.weather
    val forecastAge = weather.forecast?.fetchedAt?.let { Duration.between(it, now) }
    val offline = weather.error != null || (forecastAge != null && forecastAge > Duration.ofHours(3))
    val (gradient, fg) = heroStyle(h)
    Card(Modifier.fillMaxWidth().testTag("radar_now"), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        CompositionLocalProvider(LocalContentColor provides fg) {
        Column(Modifier.background(Brush.linearGradient(gradient)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(locationName.ifBlank { tr("Twoja instalacja", "Your installation") }, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(weather.updatedAt?.let { tr("Prognoza z ", "Forecast from ") + hm.format(it) } ?: tr("Brak prognozy", "No forecast"),
                        style = MaterialTheme.typography.labelMedium, color = fg.copy(alpha = 0.8f))
                }
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = tr("Odśwież pogodę", "Refresh weather")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h?.let(::skyIcon) ?: "❔", fontSize = 44.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(wx?.temperatureC?.let { c(it) + "C" } ?: "N/A", fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp)
                    Text(listOfNotNull(wx?.apparentTemperatureC?.let { tr("odczuwalna ", "feels like ") + c(it) },
                        h?.effectiveCloudPercent?.let { tr("słońce zasłonięte ", "sun blocked ") + pct(it) }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(tr("PV teraz", "PV now"), style = MaterialTheme.typography.labelMedium)
                    val pvNow = when (r?.now?.actualQuality) {
                        ValueQuality.REAL -> w(r.now.actualW)
                        ValueQuality.SIMULATED -> tr("symul.", "simul.")
                        else -> "N/A"
                    }
                    Text(pvNow, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("radar_pv_now"))
                    Text(tr("oczek. ", "exp. ") + w(r?.now?.expectedW), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (wx != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val pb = fg.copy(alpha = 0.16f)
                    Pill("💧 " + pct(wx.relativeHumidityPercent), pb, fg)
                    Pill(tr("rosa ", "dew ") + c(h?.dewPointC) + if (h?.dewPointCalculated == true) "*" else "", pb, fg)
                    Pill("💨 " + kmh(wx.windSpeedMs) + " " + (HourlyPvForecastEngine.compass(wx.windDirectionDeg) ?: ""), pb, fg)
                    Pill(tr("porywy ", "gusts ") + kmh(wx.windGustsMs), pb, fg)
                    Pill("🌧 " + mm(wx.precipitationMm) + " · " + pct(wx.precipitationProbabilityPercent), pb, fg)
                    wx.uvIndex?.let { Pill("UV " + String.format(Locale.ROOT, "%.1f", it), pb, fg) }
                }
            }
            r?.now?.let { n ->
                val parts = listOfNotNull(n.differenceW?.let { String.format(Locale.ROOT, "%+.0f W", it) }, n.performancePercent?.let { tr("wydajność ", "performance ") + pct(it) })
                if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
            // Data quality – what each number is.
            if (r != null) {
                Row(Modifier.horizontalScroll(rememberScrollState()).testTag("radar_quality"), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val onP = fg
                    val bg = fg.copy(alpha = 0.12f)
                    Pill(tr("Pogoda: ", "Weather: ") + qualityLabel(r.weatherQuality), bg, onP)
                    Pill(tr("PV: ", "PV: ") + qualityLabel(r.now.actualQuality), bg, onP)
                    r.days.firstOrNull()?.let { Pill(tr("Pewność: ", "Confidence: ") + levelLabel(it.confidence.level), bg, onP) }
                }
            }
            if (offline) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
                    Text(tr("Offline – prognoza z pamięci", "Offline – cached forecast") + (weather.forecast?.fetchedAt?.let { tr(", pobrana ", ", downloaded ") + DateTimeFormatter.ofPattern("d.MM HH:mm").withZone(hm.zone).format(it) } ?: "") +
                        (weather.error?.let { "\n$it" } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp))
                }
            }
            if (!weather.enabled) Text(tr("Pogoda wyłączona w ustawieniach – prognoza z modelu bezchmurnego nieba / klimatu.",
                "Weather is off in settings – forecast from the clear-sky / climate model."), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        }
    }
}

// ---- Alerts ------------------------------------------------------------------------------------------------------

@Composable
private fun AlertsColumn(alerts: List<ForecastAlert>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        alerts.forEach { a ->
            val warn = a.kind == AlertKind.RAIN || a.kind == AlertKind.WIND || a.kind == AlertKind.PV_BELOW || a.kind == AlertKind.HEAT
            Surface(shape = RoundedCornerShape(16.dp), color = if (warn) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.fillMaxWidth().testTag("radar_alert_${a.kind.name}")) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(a.icon, fontSize = 22.sp)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(a.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                        Text(a.detail, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// ---- Today ---------------------------------------------------------------------------------------------------------

@Composable
private fun Tile(label: String, value: String, foot: String?, modifier: Modifier = Modifier, accent: Color? = null) {
    Card(modifier, shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = accent ?: MaterialTheme.colorScheme.onSurface, maxLines = 1)
            foot?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
        }
    }
}

@Composable
private fun TodayTiles(r: HourlyPvReport, hm: DateTimeFormatter) {
    val d = r.days.firstOrNull() ?: return
    Column(Modifier.testTag("radar_today"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Dziś", "Today") + " · ${d.icon} ${d.condition}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile(tr("Prognoza PV", "Expected PV"), kwh(d.expectedKwh), d.clearSkyKwh?.takeIf { it > 0 }?.let { tr("czyste niebo ", "clear sky ") + kwh(it) }, Modifier.weight(1f), ChartColors.pv)
            Tile(tr("Szczyt", "Peak"), w(d.peakW), d.peakAt?.let { tr("ok. ", "at ") + hm.format(it) }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (d.actualSoFarKwh != null) Tile(tr("Wyprodukowano", "Produced"), kwh(d.actualSoFarKwh),
                tr("oczek. ", "exp. ") + kwh(d.expectedSoFarKwh) + (d.performancePercent?.let { " · " + String.format(Locale.ROOT, "%.0f%%", it) } ?: ""), Modifier.weight(1f), ChartColors.consumption)
            else Tile(tr("Wyprodukowano", "Produced"), "N/A", tr("brak pomiarów z falownika", "no inverter data"), Modifier.weight(1f))
            Tile(tr("Słońce", "Sun"), (d.sun.sunrise?.let(hm::format) ?: "—") + "–" + (d.sun.sunset?.let(hm::format) ?: "—"),
                tr("dzień ", "day ") + "${d.sun.dayLength.toHours()} h ${d.sun.dayLength.toMinutes() % 60} min · " + tr("górow. ", "noon ") + hm.format(d.sun.solarNoon), Modifier.weight(1f))
        }
        Text(tr("Temp. ", "Temp. ") + c(d.minTemperatureC, 0) + "…" + c(d.maxTemperatureC, 0) + (d.maxTemperatureAt?.let { tr(" · najcieplej ", " · warmest ") + hm.format(it) } ?: "") +
            " · " + tr("pewność ", "confidence ") + levelLabel(d.confidence.level), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutlookCard(r: HourlyPvReport, hm: DateTimeFormatter) {
    SectionCard(Modifier.testTag("radar_outlook")) {
        r.best?.let { b ->
            OutlookLine("☀️", tr("Najlepsza produkcja", "Best production"), "${hm.format(b.from)}–${hm.format(b.to)}",
                tr("szczyt ok. ", "peak ~") + w(b.expectedW) + (b.reason?.let { " · $it" } ?: ""))
        } ?: OutlookLine("🌙", tr("Dziś bez produkcji PV", "No PV today"), "", "")
        r.worst?.let { b -> OutlookLine("🌥️", tr("Najsłabiej", "Weakest"), "${hm.format(b.from)}–${hm.format(b.to)}", tr("do ", "up to ") + w(b.expectedW) + " · " + (b.reason ?: "")) }
        r.rain?.let { rain ->
            if (rain.from == null) OutlookLine("🌤️", tr("Opady", "Rain"), tr("brak w 12 h", "none in 12 h"), "")
            else OutlookLine("🌧️", if (rain.raining) tr("Pada (prognoza)", "Raining (forecast)") else tr("Opady", "Rain"),
                "${hm.format(rain.from)}–${hm.format(rain.to)}",
                mm(rain.precipitationMm) + (rain.maxProbabilityPercent?.let { " · ${it.roundToInt()}%" } ?: "") + " · " +
                    (rain.impactPercent?.let { "PV −${it.roundToInt()}% (~${String.format(Locale.ROOT, "%.2f", rain.reductionKwh)} kWh)" } ?: rain.note))
        }
        Text(tr("Opady z prognozy godzinowej (dokładność 1 h); radar służy do podglądu.", "Rain from the hourly forecast (1 h resolution); the radar is for viewing."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OutlookLine(icon: String, title: String, time: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 22.sp, modifier = Modifier.width(34.dp))
        Column(Modifier.weight(1f)) {
            Row { Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)); Text(time, fontWeight = FontWeight.Bold) }
            if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Radar -------------------------------------------------------------------------------------------------------

@Composable
private fun RadarCard(vm: EnergyCenterViewModel, st: RadarUiState, lat: Double?, lon: Double?, now: Instant, hm: DateTimeFormatter) {
    val context = LocalContext.current
    val snap = st.snapshot
    val frames = snap?.frames.orEmpty()
    var index by rememberSaveable(snap?.generatedAt?.epochSecond) { mutableIntStateOf(frames.indexOfLast { it.kind == RadarFrameKind.PAST }.coerceAtLeast(0)) }
    var playing by remember { mutableStateOf(false) }
    LaunchedEffect(playing, frames.size) {
        while (playing && frames.isNotEmpty()) { delay(600); index = (index + 1) % frames.size }
    }
    val fresh = RadarFreshnessEvaluator.evaluate(snap, now, st.fromCache)
    val frame = frames.getOrNull(index.coerceIn(0, (frames.size - 1).coerceAtLeast(0)))
    SectionCard(Modifier.testTag("radar_card")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Radar opadów", "Precipitation radar"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val (bg, fg) = when (fresh.status) {
                RadarStatus.LIVE -> ChartColors.charge to Color.White
                RadarStatus.CACHED -> ChartColors.pv to Color.Black
                else -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.onError
            }
            Pill(when (fresh.status) {
                RadarStatus.LIVE -> tr("NA ŻYWO", "LIVE"); RadarStatus.STALE -> tr("DANE NIEAKTUALNE", "STALE DATA")
                RadarStatus.CACHED -> tr("Z PAMIĘCI", "CACHED"); RadarStatus.UNAVAILABLE -> tr("NIEDOSTĘPNY", "UNAVAILABLE")
            }, bg, fg, textTag = "radar_status")
        }
        if (lat != null && lon != null) {
            Box(Modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(16.dp))) {
                RadarMap(lat, lon, frames, frame, snap?.maxZoom ?: RainViewer.DEFAULT_MAX_ZOOM, Modifier.fillMaxSize())
                frame?.let { f ->
                    Surface(shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)) {
                        Text((if (f.kind == RadarFrameKind.NOWCAST) tr("prognoza ", "nowcast ") else "") + hm.format(f.time) + " · " +
                            Duration.between(f.time, now).toMinutes().let { if (it >= 0) "−$it min" else "+${-it} min" },
                            color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
                if (st.loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)
            }
        }
        fresh.age?.let { age ->
            Text(tr("Dane sprzed: ", "Data age: ") + "${age.toMinutes()} min · " + tr("ostatnia klatka ", "last frame ") + hm.format(fresh.latest),
                style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        }
        st.radarError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (frames.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalIconButton(onClick = { playing = !playing }) {
                    Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, contentDescription = if (playing) tr("Pauza", "Pause") else tr("Odtwórz", "Play"))
                }
                Slider(value = index.toFloat(), onValueChange = { playing = false; index = it.roundToInt() }, valueRange = 0f..(frames.size - 1).toFloat(),
                    steps = (frames.size - 2).coerceAtLeast(0), modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
        } else if (frames.isEmpty() && !st.loading) {
            Text(tr("Brak klatek radaru – nic nie jest rysowane bez danych.", "No radar frames – nothing is drawn without data."), style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Legend: the provider's colour scale, from light to heavy precipitation (no dBZ values are invented).
            Box(Modifier.width(90.dp).height(8.dp).clip(RoundedCornerShape(4.dp))
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color(0xFF9BE7FF), Color(0xFF0064FF), Color(0xFFFFD600), Color(0xFFFF3D00), Color(0xFFD500F9)))))
            Spacer(Modifier.width(8.dp))
            Text(tr("słaby → silny opad (skala RainViewer)", "light → heavy (RainViewer scale)"), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.loadRadar(force = true) }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Odśwież", "Refresh"))
            }
            if (lat != null && lon != null) OutlinedButton(onClick = {
                // Windy opens in the browser (no scraping, no unofficial API, no key in the app).
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.windy.com/?radar,${String.format(Locale.ROOT, "%.3f,%.3f", lat, lon)},8")))
            }) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Windy") }
        }
    }
}

// ---- Chart -------------------------------------------------------------------------------------------------------

@Composable
private fun ChartCard(vm: EnergyCenterViewModel, r: HourlyPvReport, range: Int, now: Instant, hm: DateTimeFormatter, dayFmt: DateTimeFormatter) {
    val start = r.days.firstOrNull()?.hours?.firstOrNull()?.start ?: return
    val hours = remember(r.generatedAt, range) { r.hours.filter { !it.start.isBefore(start) && it.start.isBefore(start.plus(Duration.ofDays(range.toLong()))) } }
    if (hours.isEmpty()) return
    val nowIndex = hours.indexOfFirst { !now.isBefore(it.start) && now.isBefore(it.end) }
    var selected by remember(range, r.generatedAt) { mutableIntStateOf(nowIndex.coerceAtLeast(0)) }
    val maxW = remember(r.generatedAt, range) { hours.maxOf { maxOf(it.expectedMaxW, it.actual.averageW ?: 0.0) }.coerceAtLeast(100.0) * 1.1 }
    SectionCard(Modifier.testTag("radar_chart")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Moc PV", "PV power"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("max " + w(maxW / 1.1), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1 to "24 h", 2 to "48 h", 7 to tr("7 dni", "7 days"), 14 to tr("14 dni", "14 days")).forEach { (d, label) ->
                FilterChip(selected = range == d, enabled = d <= 2 || r.days.size >= d || range == d, onClick = { vm.setRadarRange(d) }, label = { Text(label) })
            }
        }
        val expColor = ChartColors.pv
        val actColor = ChartColors.consumption
        val grid = MaterialTheme.colorScheme.outlineVariant
        val sel = MaterialTheme.colorScheme.primary
        val nowColor = MaterialTheme.colorScheme.error
        val night = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        val measurer = rememberTextMeasurer()
        val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
        // Axis ticks: hours for 24/48 h, days for longer ranges – placed exactly at their positions.
        val ticks = remember(r.generatedAt, range) {
            hours.withIndex().filter { (i, h) ->
                val hr = h.start.atZone(r.zone).hour
                when (range) { 1 -> hr % 6 == 0; 2 -> hr % 12 == 0; else -> hr == 0 } && (i > 0 || range > 2)
            }.map { (i, h) -> i to if (range <= 2) hm.format(h.start) else dayFmt.format(h.start) }
        }
        val yTop = maxW / 1.1
        Canvas(Modifier.fillMaxWidth().height(210.dp).pointerInput(hours.size) {
            detectTapGestures { o ->
                val left = 40.dp.toPx()
                selected = (((o.x - left) / (size.width - left)) * hours.size).toInt().coerceIn(0, hours.lastIndex)
            }
        }) {
            val left = 40.dp.toPx()
            val bottom = 18.dp.toPx()
            val plotW = size.width - left
            val plotH = size.height - bottom
            val dx = plotW / hours.size
            fun x(i: Int) = left + i * dx + dx / 2
            fun y(v: Double) = plotH - (v / maxW * plotH).toFloat()
            // Night: the sun below the horizon.
            hours.forEachIndexed { i, h -> if (h.sunElevationDeg <= 0) drawRect(night, Offset(left + i * dx, 0f), androidx.compose.ui.geometry.Size(dx + 0.5f, plotH)) }
            // Horizontal grid with power labels (0, ½, max).
            listOf(0.0, yTop / 2, yTop).forEach { v ->
                val yy = y(v)
                drawLine(grid, Offset(left, yy), Offset(size.width, yy), 1f)
                val t = measurer.measure(if (v >= 1000) String.format(Locale.ROOT, "%.1fk", v / 1000) else "${v.roundToInt()}", labelStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 6f, (yy - t.size.height / 2).coerceIn(0f, plotH - t.size.height)))
            }
            ticks.forEach { (i, label) ->
                val tx = left + i * dx
                if (range > 2) drawLine(grid, Offset(tx, 0f), Offset(tx, plotH), 1.5f)
                val t = measurer.measure(label, labelStyle)
                drawText(t, topLeft = Offset((tx - t.size.width / 2).coerceIn(left, size.width - t.size.width), plotH + 3f))
            }
            // Uncertainty band and the expected curve with a soft area below it.
            val band = Path().apply {
                hours.forEachIndexed { i, h -> if (i == 0) moveTo(x(i), y(h.expectedMaxW)) else lineTo(x(i), y(h.expectedMaxW)) }
                hours.indices.reversed().forEach { i -> lineTo(x(i), y(hours[i].expectedMinW)) }
                close()
            }
            drawPath(band, expColor.copy(alpha = 0.14f))
            val exp = Path().apply { hours.forEachIndexed { i, h -> if (i == 0) moveTo(x(i), y(h.expectedW)) else lineTo(x(i), y(h.expectedW)) } }
            val area = Path().apply { addPath(exp); lineTo(x(hours.lastIndex), plotH); lineTo(x(0), plotH); close() }
            drawPath(area, Brush.verticalGradient(listOf(expColor.copy(alpha = 0.35f), expColor.copy(alpha = 0.02f)), 0f, plotH))
            drawPath(exp, expColor, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Actual: real measurements only, broken where there is no data (never bridged).
            var path: Path? = null
            hours.forEachIndexed { i, h ->
                val a = h.actual.averageW?.takeIf { h.actual.quality == ValueQuality.REAL || h.actual.quality == ValueQuality.PARTIAL }
                if (a == null) { path?.let { drawPath(it, actColor, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }; path = null }
                else {
                    path = (path ?: Path().apply { moveTo(x(i), y(a)) }).apply { lineTo(x(i), y(a)) }
                    if (range <= 2) drawCircle(actColor, 4.5f, Offset(x(i), y(a)))
                }
            }
            path?.let { drawPath(it, actColor, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            if (nowIndex >= 0) {
                val nx = left + nowIndex * dx + dx * (Duration.between(hours[nowIndex].start, now).toMinutes() / 60f)
                drawLine(nowColor, Offset(nx, 0f), Offset(nx, plotH), 2.5f)
                drawCircle(nowColor, 5f, Offset(nx, 4f))
            }
            val si = selected.coerceIn(0, hours.lastIndex)
            drawLine(sel, Offset(x(si), 0f), Offset(x(si), plotH), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawCircle(Color.White, 9f, Offset(x(si), y(hours[si].expectedW)))
            drawCircle(expColor, 6f, Offset(x(si), y(hours[si].expectedW)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            LegendDot(expColor, tr("oczekiwana", "expected"))
            LegendDot(actColor, tr("rzeczywista", "actual"))
            LegendDot(nowColor, tr("teraz", "now"))
            LegendDot(night.copy(alpha = 0.25f), tr("noc", "night"))
        }
        HourDetails(hours[selected.coerceIn(0, hours.lastIndex)], hm, dayFmt)
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun DetailLine(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun HourDetails(h: HourForecast, hm: DateTimeFormatter, dayFmt: DateTimeFormatter) {
    val wx = h.weather
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().testTag("radar_tooltip")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skyIcon(h), fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text("${dayFmt.format(h.start)} ${hm.format(h.start)}–${hm.format(h.end)}", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(w(h.expectedW), fontWeight = FontWeight.Bold, color = ChartColors.pv)
            }
            DetailLine(tr("Rzeczywiste PV", "Actual PV"), when (h.actual.quality) {
                ValueQuality.REAL -> w(h.actual.averageW)
                ValueQuality.PARTIAL -> w(h.actual.averageW) + " (${(h.actual.coverage * 100).roundToInt()}%" + tr(" godziny)", " of hour)")
                ValueQuality.SIMULATED -> "N/A · SIM " + w(h.actual.simulatedW)
                else -> "N/A"
            }, bold = true)
            h.differenceW?.let { DetailLine(tr("Różnica", "Difference"), String.format(Locale.ROOT, "%+.0f W", it) + (h.deviationPercent?.let { d -> String.format(Locale.ROOT, " (%+.1f%%)", d) } ?: "")) }
            DetailLine(tr("Zakres prognozy", "Forecast range"), w(h.expectedMinW) + " – " + w(h.expectedMaxW) + (h.clearSkyW?.let { " · " + tr("czyste niebo ", "clear sky ") + w(it) } ?: ""))
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DetailLine(tr("Temperatura", "Temperature"), c(wx?.temperatureC) + "C" + (wx?.apparentTemperatureC?.let { " (" + tr("odcz. ", "feels ") + c(it) + ")" } ?: ""))
            DetailLine(tr("Wilgotność · punkt rosy", "Humidity · dew point"), pct(wx?.relativeHumidityPercent) + " · " + c(h.dewPointC) + if (h.dewPointCalculated) tr(" (oblicz.)", " (calc.)") else "")
            DetailLine(tr("Chmury (nis./śr./wys.)", "Clouds (low/mid/high)"), pct(wx?.cloudCoverPercent) + " (" + pct(wx?.cloudLowPercent) + "/" + pct(wx?.cloudMidPercent) + "/" + pct(wx?.cloudHighPercent) + ")")
            DetailLine(tr("Słońce zasłonięte", "Sun blocked"), pct(h.effectiveCloudPercent))
            DetailLine(tr("Opad", "Precipitation"), mm(wx?.precipitationMm) + " · " + pct(wx?.precipitationProbabilityPercent) +
                ((wx?.snowfallCm)?.takeIf { it > 0 }?.let { String.format(Locale.ROOT, " · ❄ %.1f cm", it) } ?: ""))
            DetailLine(tr("Wiatr", "Wind"), kmh(wx?.windSpeedMs) + " " + (HourlyPvForecastEngine.compass(wx?.windDirectionDeg) ?: "") + " · " + tr("porywy ", "gusts ") + kmh(wx?.windGustsMs))
            DetailLine(tr("Słońce (wys./azymut)", "Sun (elev./azimuth)"), String.format(Locale.ROOT, "%.1f° / %.0f°", h.sunElevationDeg, h.sunAzimuthDeg))
            DetailLine("GHI / POA", (h.ghiWm2?.let { "${it.roundToInt()}" } ?: "N/A") + " / " + (h.poaWm2?.let { "${it.roundToInt()} W/m²" } ?: "N/A"))
            Text(h.weatherProvenance.source + (h.weatherProvenance.generatedAt?.let { tr(" z ", " issued ") + hm.format(it) } ?: "") + " · " +
                qualityLabel(h.weatherProvenance.quality) + " · PV: Solar Tracker model (${h.pvBasis})",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Hourly list: one day at a time (fast, readable) ------------------------------------------------------------

@Composable
private fun HourlyCard(r: HourlyPvReport, now: Instant, hm: DateTimeFormatter, dayFmt: DateTimeFormatter) {
    val days = r.days
    if (days.isEmpty()) return
    var dayIndex by rememberSaveable { mutableIntStateOf(0) }
    val day = days[dayIndex.coerceIn(0, days.lastIndex)]
    SectionCard(Modifier.testTag("radar_hourly")) {
        Text(tr("Prognoza godzinowa", "Hourly forecast"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEachIndexed { i, d ->
                FilterChip(selected = i == dayIndex, onClick = { dayIndex = i },
                    label = { Text(when (i) { 0 -> tr("Dziś", "Today"); 1 -> tr("Jutro", "Tomorrow"); else -> dayFmt.format(d.date.atStartOfDay(r.zone).toInstant()) }) })
            }
        }
        // Header
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            HeaderCell(tr("Godz.", "Time"), 52.dp); HeaderCell("", 30.dp); HeaderCell("Temp", 46.dp); HeaderCell(tr("Opad", "Rain"), 56.dp)
            HeaderCell(tr("Wiatr", "Wind"), 52.dp); Text(tr("PV oczek./rzecz.", "PV exp./act."), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
        }
        HorizontalDivider()
        val dayMax = day.hours.maxOfOrNull { it.expectedW }?.takeIf { it > 0 } ?: 1.0
        day.hours.forEach { h -> HourRow(h, now, hm, dayFmt, dayMax) }
        Text(tr("Dotknij godziny, aby zobaczyć wilgotność, punkt rosy, chmury, słońce i źródła danych.", "Tap an hour for humidity, dew point, clouds, sun and data sources."),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HeaderCell(text: String, width: androidx.compose.ui.unit.Dp) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(width), maxLines = 1)
}

@Composable
private fun HourRow(h: HourForecast, now: Instant, hm: DateTimeFormatter, dayFmt: DateTimeFormatter, dayMax: Double) {
    var open by rememberSaveable(h.start.epochSecond) { mutableStateOf(false) }
    val current = !now.isBefore(h.start) && now.isBefore(h.end)
    val wx = h.weather
    val bg = if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).clickable { open = !open }.padding(vertical = 6.dp, horizontal = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(hm.format(h.start), style = MaterialTheme.typography.bodySmall, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.width(52.dp))
            Text(skyIcon(h), modifier = Modifier.width(30.dp))
            Text(c(wx?.temperatureC, 0), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(46.dp))
            Text(wx?.precipitationProbabilityPercent?.let { "${it.roundToInt()}%" } ?: (wx?.precipitationMm?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "—"),
                style = MaterialTheme.typography.bodySmall, color = if ((wx?.precipitationProbabilityPercent ?: 0.0) >= 50) ChartColors.consumption else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(56.dp))
            Text(wx?.windSpeedMs?.let { "${(it * 3.6).roundToInt()}" } ?: "—", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(52.dp))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                Text(if (h.expectedW > 0) w(h.expectedW) else "—", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = ChartColors.pv)
                // Expected PV relative to the day's best hour – a quick visual profile.
                if (h.expectedW > 0) Box(Modifier.width(64.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                    Box(Modifier.fillMaxWidth((h.expectedW / dayMax).toFloat().coerceIn(0.03f, 1f)).height(4.dp).background(ChartColors.pv))
                }
                if (h.actual.quality == ValueQuality.REAL || h.actual.quality == ValueQuality.PARTIAL)
                    Text(w(h.actual.averageW), style = MaterialTheme.typography.labelSmall, color = ChartColors.consumption)
            }
        }
        AnimatedVisibility(open) { Column(Modifier.padding(top = 6.dp)) { HourDetails(h, hm, dayFmt) } }
    }
}

// ---- Next days ---------------------------------------------------------------------------------------------------

@Composable
private fun DaysRow(r: HourlyPvReport, dayFmt: DateTimeFormatter) {
    Column(Modifier.testTag("radar_daily"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Kolejne dni", "Next days"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val max = r.days.maxOfOrNull { it.expectedKwh }?.takeIf { it > 0 } ?: 1.0
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            r.days.forEachIndexed { i, d -> DayCard(d, i, r.zone, dayFmt, max) }
        }
        Text(tr("Pokazane tylko dni w zasięgu prognozy pogody.", "Only days within the weather forecast are shown."), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DayCard(d: DayForecast, i: Int, zone: ZoneId, dayFmt: DateTimeFormatter, max: Double) {
    Card(Modifier.widthIn(min = 108.dp), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when (i) { 0 -> tr("Dziś", "Today"); 1 -> tr("Jutro", "Tomorrow"); else -> dayFmt.format(d.date.atStartOfDay(zone).toInstant()) },
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(d.icon, fontSize = 28.sp)
            Text(String.format(Locale.ROOT, "%.1f kWh", d.expectedKwh), fontWeight = FontWeight.Bold)
            Box(Modifier.width(80.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
                Box(Modifier.fillMaxWidth((d.expectedKwh / max).toFloat().coerceIn(0f, 1f)).height(6.dp).background(ChartColors.pv))
            }
            Text(c(d.minTemperatureC, 0) + " / " + c(d.maxTemperatureC, 0), style = MaterialTheme.typography.labelSmall)
            val (bg, fg) = when (d.confidence.level) {
                ConfidenceLevel.HIGH -> ChartColors.charge.copy(alpha = 0.2f) to MaterialTheme.colorScheme.onSurface
                ConfidenceLevel.MEDIUM -> ChartColors.pv.copy(alpha = 0.25f) to MaterialTheme.colorScheme.onSurface
                ConfidenceLevel.LOW -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            }
            Pill(levelLabel(d.confidence.level), bg, fg)
        }
    }
}

// ---- Accuracy (collapsed by default) -----------------------------------------------------------------------------

@Composable
private fun AccuracyCard(r: HourlyPvReport) {
    var open by rememberSaveable { mutableStateOf(false) }
    SectionCard(Modifier.testTag("radar_accuracy").clickable { open = !open }) {
        val a = r.accuracy
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Trafność prognozy (30 dni)", "Forecast accuracy (30 days)"), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(a?.accuracyPercent?.let { pct(it) } ?: "N/A", fontWeight = FontWeight.Bold)
            Text(if (open) "  ▲" else "  ▼", style = MaterialTheme.typography.labelSmall)
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (a == null || a.count == 0) Text(tr("Za mało danych: potrzebne są zapisane prognozy i pomiary z falownika.", "Not enough data: stored forecasts and inverter measurements are needed."),
                    style = MaterialTheme.typography.bodySmall)
                else {
                    DetailLine(tr("Porównanych godzin", "Hours compared"), "${a.count}")
                    DetailLine("MAE", String.format(Locale.ROOT, "%.3f kWh/h", a.mae))
                    DetailLine("RMSE", String.format(Locale.ROOT, "%.3f kWh/h", a.rmse))
                    DetailLine("Bias", a.biasPercent?.let { String.format(Locale.ROOT, "%+.1f%%", it) } ?: "N/A")
                    DetailLine("MAPE", a.mapePercent?.let { String.format(Locale.ROOT, "%.1f%%", it) } ?: tr("N/A (mała produkcja)", "N/A (low production)"))
                }
                Text(tr("Prognoza „dzień naprzód” vs pomiar; bez automatycznej korekty.", "Day-ahead forecast vs measurement; no automatic correction."), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
