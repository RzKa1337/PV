package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.analytics.AccuracyReport
import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.analytics.SkyClassifier
import com.solartracker.pro.core.pv.PvPointEstimate
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.solar.SunTimes
import com.solartracker.pro.core.weather.HourlyWeather
import com.solartracker.pro.core.weather.Psychrometrics
import com.solartracker.pro.core.weather.WeatherForecast
import com.solartracker.pro.core.weather.WeatherSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/** Where a value comes from – shown next to every number. */
enum class ValueQuality(val label: String) {
    FORECAST("PROGNOZA"), CACHED("Z PAMIĘCI"), CALCULATED("OBLICZONE"), MODEL("MODEL"), CLIMATE("KLIMAT"), CLEAR_SKY("CZYSTE NIEBO"),
    REAL("POMIAR"), PARTIAL("POMIAR CZĘŚCIOWY"), SIMULATED("SYMULACJA"), UNAVAILABLE("N/A"),
}

data class Provenance(val source: String, val generatedAt: Instant?, val validFor: Instant, val quality: ValueQuality)

/** Measured PV in an interval; [averageW] is null when there is no real data (never filled with a model value). */
data class ActualPv(val averageW: Double?, val coverage: Double, val quality: ValueQuality, val simulatedW: Double? = null)

data class HourForecast(
    val start: Instant,
    val end: Instant,
    val weather: HourlyWeather?,
    val weatherProvenance: Provenance,
    val dewPointC: Double?,
    val dewPointCalculated: Boolean,
    /** Sunlight blocked by clouds [%] (1 − clear-sky index); total cover only as a fallback. */
    val effectiveCloudPercent: Double?,
    val sunElevationDeg: Double,
    val sunAzimuthDeg: Double,
    /** Model irradiance at the middle of the hour [W/m²]. */
    val ghiWm2: Double?,
    val poaWm2: Double?,
    /** Mean expected AC power over the hour [W] (= expected energy in Wh). */
    val expectedW: Double,
    val expectedMinW: Double,
    val expectedMaxW: Double,
    /** Highest 10-minute expected value in the hour and when [W]. */
    val expectedPeakW: Double,
    val expectedPeakAt: Instant,
    /** The same engine on a cloudless sky [W] (impact of weather). */
    val clearSkyW: Double?,
    val modelConfidence: Double,
    val pvBasis: String,
    val actual: ActualPv,
) {
    val differenceW: Double? get() = actual.averageW?.takeIf { actual.quality == ValueQuality.REAL || actual.quality == ValueQuality.PARTIAL }?.let { it - expectedW }
    /** (actual − expected) / expected [%]; null below 50 W expected (ratio meaningless at dawn/dusk/night). */
    val deviationPercent: Double? get() = differenceW?.takeIf { expectedW >= 50 }?.let { it / expectedW * 100 }
    val daylight: Boolean get() = sunElevationDeg > 0 || expectedW > 0
}

enum class ConfidenceLevel(val label: String) { HIGH("WYSOKA"), MEDIUM("ŚREDNIA"), LOW("NISKA") }

data class ForecastConfidence(val level: ConfidenceLevel, val score: Double, val factors: List<String>)

data class DayForecast(
    val date: LocalDate,
    val expectedKwh: Double,
    val clearSkyKwh: Double?,
    val peakW: Double,
    val peakAt: Instant?,
    val sun: SunTimes,
    val actualSoFarKwh: Double?,
    val expectedSoFarKwh: Double?,
    val performancePercent: Double?,
    val confidence: ForecastConfidence,
    val condition: String,
    val icon: String,
    val weatherSource: WeatherSource,
    val maxTemperatureC: Double?,
    val minTemperatureC: Double?,
    val maxTemperatureAt: Instant?,
    val hours: List<HourForecast>,
)

data class PvWindow(val from: Instant, val to: Instant, val expectedW: Double, val reason: String?)

data class RainOutlook(
    val raining: Boolean,
    val from: Instant?,
    val to: Instant?,
    val precipitationMm: Double,
    val maxProbabilityPercent: Double?,
    /** Production reduction in the rain window vs a cloudless sky [%] and [kWh]; null = cannot be estimated. */
    val impactPercent: Double?,
    val reductionKwh: Double?,
    val note: String,
)

enum class AlertKind { RAIN, CLOUD, WIND, PV_PEAK, PV_BELOW, HEAT }

data class ForecastAlert(val kind: AlertKind, val icon: String, val title: String, val detail: String, val at: Instant?)

data class NowSummary(
    val hour: HourForecast?,
    val expectedW: Double?,
    val actualW: Double?,
    val actualQuality: ValueQuality,
    val simulatedW: Double?,
    val differenceW: Double?,
    val performancePercent: Double?,
)

data class HourlyPvReport(
    val location: GeoLocation,
    val zone: ZoneId,
    /** True when [zone] is the forecast location's zone from the provider; false = device zone (provider gave none). */
    val zoneFromProvider: Boolean,
    val generatedAt: Instant,
    val forecastFetchedAt: Instant?,
    val weatherQuality: ValueQuality,
    val hours: List<HourForecast>,
    val days: List<DayForecast>,
    val best: PvWindow?,
    val worst: PvWindow?,
    val rain: RainOutlook?,
    val alerts: List<ForecastAlert>,
    val accuracy: AccuracyReport?,
    val now: NowSummary,
)

/** Thresholds for weather alerts. Defaults are documented public scales, not tuned values. */
data class AlertThresholds(
    /** Beaufort 8 ("gale") starts at 17.2 m/s – gusts at which loose mobile panels and light structures are at risk. */
    val gustMs: Double = 17.2,
    /** Beaufort 6 ("strong breeze") starts at 10.8 m/s mean wind. */
    val meanWindMs: Double = 10.8,
    val heatC: Double = 30.0,
    /** Actual below forecast by more than this [%] (expected ≥ 200 W). */
    val pvBelowPercent: Double = 20.0,
)

/**
 * "SolarForecastEngine": hour-by-hour weather + expected PV (from [PredictivePvEngine], i.e. [com.solartracker.pro.core.pv.PvEstimator]
 * with weather irradiance, temperature, shading, calibration and inverter limit) + measured PV from history. Every hour
 * is its own record with provenance; nothing is interpolated without a flag and missing actual data stays N/A.
 *
 * @param expected expected power at an instant for this report (callers pass the engine WITHOUT the live nowcast for
 *   past instants, so the expectation does not follow the measurement it is compared with)
 * @param clearSky the same engine on a cloudless sky [kW] (for weather impact); null when unavailable
 * @param estimate model state (sun, GHI, POA) at an instant
 */
class HourlyPvForecastEngine(
    private val location: GeoLocation,
    private val forecast: WeatherForecast?,
    private val sourceAt: (Instant) -> WeatherSource,
    private val expected: (Instant) -> PvForecastPoint,
    private val clearSky: ((Instant) -> Double)?,
    private val estimate: (Instant) -> PvPointEstimate,
    private val fallbackZone: ZoneId,
    private val thresholds: AlertThresholds = AlertThresholds(),
) {
    val zone: ZoneId = forecast?.zone ?: fallbackZone

    fun build(now: Instant, days: Int, history: List<HistorySample>, accuracy: AccuracyReport?, forecastStale: Boolean, liveActualW: Double? = null,
              liveSimulated: Boolean = false): HourlyPvReport {
        val today = now.atZone(zone).toLocalDate()
        val from = today.atStartOfDay(zone).toInstant()
        val to = today.plusDays(days.toLong()).atStartOfDay(zone).toInstant()
        val starts = hourStarts(from, to)
        val rows = history.sortedBy { it.start }
        val weatherQuality = when {
            forecast == null -> ValueQuality.UNAVAILABLE
            forecastStale -> ValueQuality.CACHED
            else -> ValueQuality.FORECAST
        }
        val hours = starts.map { hour(it, now, rows, weatherQuality) }
        val dayList = hours.groupBy { it.start.atZone(zone).toLocalDate() }.toSortedMap()
            .filter { (d, list) -> !d.isBefore(today) && (d == today || list.any { h -> h.weather != null }) }
            .map { (d, list) -> day(d, list, today, now, accuracy, forecastStale) }
        val todayHours = hours.filter { it.start.atZone(zone).toLocalDate() == today }
        val current = hours.firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }
        val live = when {
            liveActualW != null && !liveSimulated -> liveActualW to ValueQuality.REAL
            liveActualW != null -> null to ValueQuality.SIMULATED
            else -> null to ValueQuality.UNAVAILABLE
        }
        val expectedNow = expected(now).expectedKw * 1000
        val nowSummary = NowSummary(current, expectedNow, live.first, live.second, if (liveSimulated) liveActualW else null,
            live.first?.let { it - expectedNow }, live.first?.takeIf { expectedNow >= 50 }?.let { it / expectedNow * 100 })
        val rain = rain(hours, now)
        return HourlyPvReport(location, zone, forecast?.zone != null, now, forecast?.fetchedAt, weatherQuality, hours, dayList,
            best(todayHours, now), worst(todayHours), rain, alerts(hours, dayList.firstOrNull(), now, nowSummary, rain), accuracy, nowSummary)
    }

    /** Local hour boundaries between [from] and [to] (Instant arithmetic: 23/25-hour DST days come out right). */
    private fun hourStarts(from: Instant, to: Instant): List<Instant> {
        val fromForecast = forecast?.hours?.map { it.startTime }?.filter { !it.isBefore(from) && it.isBefore(to) }
        if (!fromForecast.isNullOrEmpty()) {
            // Forecast hours plus any hours of today the forecast does not cover (e.g. cache from yesterday).
            val grid = generateSequence(from) { it.plus(Duration.ofHours(1)) }.takeWhile { it.isBefore(to) }
            return (fromForecast + grid.takeWhile { it.isBefore(fromForecast.first()) }).distinct().sorted()
        }
        return generateSequence(from) { it.plus(Duration.ofHours(1)) }.takeWhile { it.isBefore(minOf(to, from.plus(Duration.ofDays(2)))) }.toList()
    }

    private fun hour(start: Instant, now: Instant, rows: List<HistorySample>, weatherQuality: ValueQuality): HourForecast {
        val end = start.plus(Duration.ofHours(1))
        val mid = start.plus(Duration.ofMinutes(30))
        val w = forecast?.at(mid)
        val source = sourceAt(mid)
        val quality = when {
            w != null -> weatherQuality
            source == WeatherSource.CLIMATE -> ValueQuality.CLIMATE
            else -> ValueQuality.UNAVAILABLE
        }
        val sun = SolarCalculator.details(location, mid)
        val e = estimate(mid)
        val night = listOf(start, mid, end).all { SolarCalculator.position(location, it).elevationDeg < -1 }
        // 10-minute samples (6 per hour) – the same grid the stored forecasts use.
        val samples = if (night) emptyList() else (0 until 6).map { start.plusSeconds(300L + it * 600L) }.map { it to expected(it) }
        val expW = if (samples.isEmpty()) 0.0 else samples.sumOf { it.second.expectedKw } / samples.size * 1000
        val minW = if (samples.isEmpty()) 0.0 else samples.sumOf { it.second.minKw } / samples.size * 1000
        val maxW = if (samples.isEmpty()) 0.0 else samples.sumOf { it.second.maxKw } / samples.size * 1000
        val peak = samples.maxByOrNull { it.second.expectedKw }
        val clear = if (night) 0.0 else clearSky?.let { f -> (0 until 6).sumOf { f(start.plusSeconds(300L + it * 600L)) } / 6 * 1000 }
        val csi = w?.let { com.solartracker.pro.core.weather.WeatherEffects.clearSkyIndex(it, sun.position, mid) }
        val dew = w?.let { Psychrometrics.dewPoint(it) }
        return HourForecast(
            start, end, w,
            Provenance(if (w != null) "Open-Meteo" else if (source == WeatherSource.CLIMATE) "średnie klimatyczne" else "brak prognozy", forecast?.fetchedAt, mid, quality),
            dew?.first, dew?.second == true,
            SkyClassifier.effectiveCloudPercent(csi, w?.cloudCoverPercent),
            sun.elevationDeg, sun.azimuthDeg,
            e.ghi.takeIf { !night }, e.poa.takeIf { !night }, expW, minW, maxW,
            (peak?.second?.expectedKw ?: 0.0) * 1000, peak?.first ?: mid, clear,
            samples.map { it.second.confidence }.takeIf { it.isNotEmpty() }?.average() ?: 1.0,
            samples.firstOrNull()?.second?.basis ?: "noc",
            actual(start, end, now, rows),
        )
    }

    /** Measured mean power in [start, end) from history rows; simulator rows are reported separately, never as actual. */
    private fun actual(start: Instant, end: Instant, now: Instant, rows: List<HistorySample>): ActualPv {
        if (!start.isBefore(now)) return ActualPv(null, 0.0, ValueQuality.UNAVAILABLE)
        val span = Duration.between(start, minOf(end, now)).toMillis().toDouble().coerceAtLeast(1.0)
        val inHour = rows.filter { !it.start.isBefore(start) && it.start.isBefore(end) && it.pvW != null }
        fun mean(list: List<HistorySample>): Pair<Double, Double>? {
            val ms = list.sumOf { it.duration.toMillis().toDouble() }
            if (ms <= 0) return null
            return list.sumOf { it.pvW!! * it.duration.toMillis() } / ms to (ms / span).coerceAtMost(1.0)
        }
        val real = mean(inHour.filter { !it.simulated })
        val sim = mean(inHour.filter { it.simulated })?.first
        return when {
            real == null -> ActualPv(null, 0.0, if (sim != null) ValueQuality.SIMULATED else ValueQuality.UNAVAILABLE, sim)
            real.second >= 0.9 -> ActualPv(real.first, real.second, ValueQuality.REAL, sim)
            else -> ActualPv(real.first, real.second, ValueQuality.PARTIAL, sim)
        }
    }

    private fun day(d: LocalDate, hours: List<HourForecast>, today: LocalDate, now: Instant, accuracy: AccuracyReport?, stale: Boolean): DayForecast {
        val expectedKwh = hours.sumOf { it.expectedW } / 1000
        val clearKwh = hours.mapNotNull { it.clearSkyW }.takeIf { it.size == hours.size }?.sum()?.div(1000)
        val peak = hours.maxByOrNull { it.expectedPeakW }?.takeIf { it.expectedPeakW > 0 }
        val past = if (d == today) hours.filter { it.start.isBefore(now) } else emptyList()
        val withActual = past.filter { it.actual.averageW != null && (it.actual.quality == ValueQuality.REAL || it.actual.quality == ValueQuality.PARTIAL) }
        val actualKwh = withActual.takeIf { it.isNotEmpty() }?.sumOf { it.actual.averageW!! * it.actual.coverage * fraction(it, now) } ?.div(1000)
        // Expected over exactly the time covered by measurements (like with like).
        val expectedSoFar = withActual.takeIf { it.isNotEmpty() }?.sumOf { it.expectedW * it.actual.coverage * fraction(it, now) }?.div(1000)
        val performance = if (actualKwh != null && expectedSoFar != null && expectedSoFar >= 0.05) actualKwh / expectedSoFar * 100 else null
        val daylight = hours.filter { it.sunElevationDeg > 10 }
        val source = daylight.map { sourceAt(it.start.plus(Duration.ofMinutes(30))) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: WeatherSource.CLEAR_SKY
        val (condition, icon) = condition(daylight)
        val temps = hours.mapNotNull { h -> h.weather?.temperatureC?.let { h to it } }
        return DayForecast(d, expectedKwh, clearKwh, peak?.expectedPeakW ?: 0.0, peak?.expectedPeakAt, SolarCalculator.sunTimes(location, d),
            actualKwh, expectedSoFar, performance, confidence(d, today, daylight, source, accuracy, stale), condition, icon, source,
            temps.maxOfOrNull { it.second }, temps.minOfOrNull { it.second }, temps.maxByOrNull { it.second }?.first?.start, hours)
    }

    private fun fraction(h: HourForecast, now: Instant): Double =
        Duration.between(h.start, minOf(h.end, now)).toMillis() / 3_600_000.0

    private fun condition(daylight: List<HourForecast>): Pair<String, String> {
        val w = daylight.mapNotNull { it.weather }
        if (w.isEmpty()) return "brak prognozy" to "❔"
        val rain = w.sumOf { it.precipitationMm ?: 0.0 }
        val rainyHours = w.count { (it.precipitationMm ?: 0.0) >= 0.2 }
        val clouds = daylight.mapNotNull { it.effectiveCloudPercent }.takeIf { it.isNotEmpty() }?.average()
        return when {
            w.any { (it.weatherCode ?: 0) >= 95 } -> "burze" to "⛈️"
            w.any { (it.snowfallCm ?: 0.0) > 0.2 } -> "śnieg" to "🌨️"
            rain >= 1.0 || rainyHours >= 3 -> "deszczowo" to "🌧️"
            clouds == null -> "brak danych o chmurach" to "❔"
            clouds < 25 -> "słonecznie" to "☀️"
            clouds < 60 -> "częściowe zachmurzenie" to "⛅"
            else -> "pochmurno" to "☁️"
        }
    }

    /**
     * Product of explicit factors (each 0..1, all listed): weather source, lead time (forecast skill falls with
     * horizon), broken-cloud uncertainty (20–80 % blocked is hardest to place in time), completeness of irradiance
     * in the forecast, the PV model's own confidence (calibration, shading) and the measured accuracy history.
     */
    fun confidence(d: LocalDate, today: LocalDate, daylight: List<HourForecast>, source: WeatherSource, accuracy: AccuracyReport?, stale: Boolean): ForecastConfidence {
        val f = mutableListOf<Pair<String, Double>>()
        f += when (source) {
            WeatherSource.FORECAST -> "prognoza pogody" to 1.0
            WeatherSource.CLIMATE -> "tylko średnie klimatyczne" to 0.4
            WeatherSource.CLEAR_SKY -> "brak pogody (czyste niebo)" to 0.2
        }
        if (stale) f += "prognoza z pamięci (nieaktualna)" to 0.8
        val lead = java.time.temporal.ChronoUnit.DAYS.between(today, d)
        f += "horyzont +$lead d" to when { lead <= 1 -> 1.0; lead <= 3 -> 0.85; lead <= 7 -> 0.65; else -> 0.45 }
        val clouds = daylight.mapNotNull { it.effectiveCloudPercent }.takeIf { it.isNotEmpty() }?.average()
        if (clouds != null && clouds in 20.0..80.0) f += "zmienne zachmurzenie (${clouds.roundToInt()}%)" to 0.75
        if (daylight.isNotEmpty()) {
            val complete = daylight.count { it.weather?.hasIrradiance == true }.toDouble() / daylight.size
            if (complete < 1.0) f += "kompletność danych ${(complete * 100).roundToInt()}%" to complete.coerceAtLeast(0.2)
            val model = daylight.map { it.modelConfidence }.average()
            f += "model PV (kalibracja, zacienienie) ${(model * 100).roundToInt()}%" to (model / 0.8).coerceIn(0.3, 1.0)
        }
        val acc = accuracy?.takeIf { it.count >= 24 }?.accuracyPercent
        f += if (acc != null) "trafność historyczna ${acc.roundToInt()}%" to (acc / 100).coerceIn(0.4, 1.0) else "brak historii trafności" to 0.85
        val score = f.fold(1.0) { a, (_, v) -> a * v }
        val level = when { score >= 0.6 -> ConfidenceLevel.HIGH; score >= 0.35 -> ConfidenceLevel.MEDIUM; else -> ConfidenceLevel.LOW }
        return ForecastConfidence(level, score, f.map { (k, v) -> "$k: ×${"%.2f".format(java.util.Locale.ROOT, v)}" })
    }

    /** Hours whose expected power is within 90 % of the day's best hour (contiguous around it). */
    fun best(day: List<HourForecast>, now: Instant): PvWindow? {
        val top = day.maxByOrNull { it.expectedW }?.takeIf { it.expectedW >= 20 } ?: return null
        val i = day.indexOf(top)
        var a = i; var b = i
        while (a > 0 && day[a - 1].expectedW >= top.expectedW * 0.9) a--
        while (b < day.lastIndex && day[b + 1].expectedW >= top.expectedW * 0.9) b++
        return PvWindow(day[a].start, day[b].end, top.expectedPeakW, if (day[b].end.isBefore(now)) "już minęło" else null)
    }

    /** Longest daytime run (sun ≥ 15°) where weather leaves < 40 % of the clear-sky production, with the reason. */
    fun worst(day: List<HourForecast>): PvWindow? {
        val bad = day.map { h -> h.sunElevationDeg >= 15 && (h.clearSkyW ?: 0.0) > 50 && h.expectedW < h.clearSkyW!! * 0.4 }
        var best: IntRange? = null
        var i = 0
        while (i < day.size) {
            if (!bad[i]) { i++; continue }
            var j = i
            while (j + 1 < day.size && bad[j + 1]) j++
            if (best == null || j - i > best.last - best.first) best = i..j
            i = j + 1
        }
        val r = best ?: return null
        val hs = day.subList(r.first, r.last + 1)
        val rain = hs.any { (it.weather?.precipitationMm ?: 0.0) >= 0.2 }
        val clouds = hs.mapNotNull { it.effectiveCloudPercent }.takeIf { it.isNotEmpty() }?.average()
        val reason = listOfNotNull("deszcz".takeIf { rain }, clouds?.takeIf { it >= 60 }?.let { "gęste chmury (~${it.roundToInt()}% słońca zasłonięte)" },
            "śnieg na panelach".takeIf { hs.any { h -> "śnieg" in h.pvBasis } }).joinToString(" + ").ifEmpty { "słabe nasłonecznienie wg prognozy" }
        return PvWindow(hs.first().start, hs.last().end, hs.maxOf { it.expectedW }, reason)
    }

    private fun rainy(h: HourForecast): Boolean {
        val w = h.weather ?: return false
        val mm = w.precipitationMm ?: 0.0
        return mm >= 0.1 && (w.precipitationProbabilityPercent ?: 100.0) >= 40
    }

    /**
     * Rain in the next 12 hours from the HOURLY forecast (resolution: 1 hour – the arrival is the start of the first
     * rainy hour, not a minute-accurate prediction). The radar is shown as images only; no motion is extrapolated from it.
     */
    fun rain(hours: List<HourForecast>, now: Instant): RainOutlook? {
        val next = hours.filter { it.end.isAfter(now) && it.start.isBefore(now.plus(Duration.ofHours(12))) }
        if (next.isEmpty() || next.all { it.weather == null }) return null
        val first = next.indexOfFirst(::rainy)
        if (first < 0) return RainOutlook(false, null, null, 0.0, next.mapNotNull { it.weather?.precipitationProbabilityPercent }.maxOrNull(), null, null,
            "Brak opadów w prognozie na 12 h")
        var last = first
        while (last + 1 < next.size && rainy(next[last + 1])) last++
        val win = next.subList(first, last + 1)
        val clear = win.mapNotNull { it.clearSkyW }.takeIf { it.size == win.size }?.sum()?.div(1000)
        val exp = win.sumOf { it.expectedW } / 1000
        val (impact, reduction, note) = when {
            clear == null -> Triple(null, null, "Wpływu na PV nie da się oszacować (brak modelu czystego nieba)")
            clear < 0.05 -> Triple(null, null, "Opady w nocy – bez wpływu na produkcję PV")
            else -> Triple((1 - exp / clear) * 100, clear - exp, "Spadek względem bezchmurnego nieba w godzinach opadów")
        }
        return RainOutlook(!now.isBefore(next[first].start), next[first].start, next[last].end, win.sumOf { it.weather?.precipitationMm ?: 0.0 },
            win.mapNotNull { it.weather?.precipitationProbabilityPercent }.maxOrNull(), impact, reduction, note)
    }

    private fun alerts(hours: List<HourForecast>, today: DayForecast?, now: Instant, nowSummary: NowSummary, rain: RainOutlook?): List<ForecastAlert> = buildList {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        if (rain?.from != null && rain.from.isBefore(now.plus(Duration.ofHours(2)))) {
            add(ForecastAlert(AlertKind.RAIN, "🌧️", if (rain.raining) "Opady teraz (prognoza)" else "Opady w godz. ${fmt.format(rain.from)}–${fmt.format(rain.to)}",
                "${"%.1f".format(rain.precipitationMm)} mm" + (rain.maxProbabilityPercent?.let { ", prawdopodobieństwo do ${it.roundToInt()}%" } ?: "") +
                    " · rozdzielczość prognozy: 1 h", rain.from))
        }
        val ahead = hours.filter { it.start.isAfter(now.minus(Duration.ofHours(1))) && it.start.isBefore(now.plus(Duration.ofHours(12))) }
        val cloudy = ahead.filter { it.sunElevationDeg > 10 && (it.effectiveCloudPercent ?: 0.0) >= 80 }
        if (cloudy.size >= 2) add(ForecastAlert(AlertKind.CLOUD, "☁️", "Gęste zachmurzenie od ${fmt.format(cloudy.first().start)}",
            "${cloudy.size} h z ≥ 80% słońca zasłoniętego", cloudy.first().start))
        val day = hours.filter { it.start.isAfter(now.minus(Duration.ofHours(1))) && it.start.isBefore(now.plus(Duration.ofHours(24))) }
        day.firstOrNull { (it.weather?.windGustsMs ?: 0.0) >= thresholds.gustMs || (it.weather?.windSpeedMs ?: 0.0) >= thresholds.meanWindMs }?.let { h ->
            add(ForecastAlert(AlertKind.WIND, "💨", "Silny wiatr ok. ${fmt.format(h.start)}",
                "porywy ${h.weather?.windGustsMs?.let { "%.0f km/h".format(it * 3.6) } ?: "N/A"}, średnio ${h.weather?.windSpeedMs?.let { "%.0f km/h".format(it * 3.6) } ?: "N/A"} · " +
                    "próg: porywy ≥ ${"%.1f".format(thresholds.gustMs)} m/s (8° Beauforta) lub wiatr ≥ ${"%.1f".format(thresholds.meanWindMs)} m/s (6° B)", h.start))
        }
        today?.peakAt?.takeIf { it.isAfter(now) && !it.isAfter(now.plus(Duration.ofMinutes(30))) }?.let {
            add(ForecastAlert(AlertKind.PV_PEAK, "☀️", "Szczyt PV za ${Duration.between(now, it).toMinutes()} min", "ok. ${"%.0f".format(today.peakW)} W o ${fmt.format(it)}", it))
        }
        val dev = nowSummary.performancePercent
        if (dev != null && (nowSummary.expectedW ?: 0.0) >= 200 && dev < 100 - thresholds.pvBelowPercent) {
            add(ForecastAlert(AlertKind.PV_BELOW, "⚠️", "PV ${"%.0f".format(100 - dev)}% poniżej prognozy",
                "pomiar ${"%.0f".format(nowSummary.actualW)} W, oczekiwane ${"%.0f".format(nowSummary.expectedW)} W", now))
        }
        day.filter { it.start.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate() }
            .firstOrNull { (it.weather?.temperatureC ?: -99.0) > thresholds.heatC && it.end.isAfter(now) }?.let { h ->
                add(ForecastAlert(AlertKind.HEAT, "🌡️", "Temperatura powyżej ${thresholds.heatC.roundToInt()}°C od ${fmt.format(h.start)}",
                    "maks. ${today?.maxTemperatureC?.let { "%.1f°C".format(it) } ?: "N/A"} – wyższa temperatura ogniw obniża moc", h.start))
            }
    }

    companion object {
        /** Compass label of a wind direction (where the wind comes from). */
        fun compass(deg: Double?): String? = deg?.let {
            listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((it % 360) + 360) % 360 / 45.0).roundToInt() % 8]
        }

        fun round(x: Double, step: Double) = (x / step).roundToInt() * step

        internal fun nearlyEqual(a: Double, b: Double, eps: Double = 1e-6) = abs(a - b) <= eps
    }
}
