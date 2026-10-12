package com.solartracker.pro.core.analytics

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherSource
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Energy the user read themselves (inverter display, meter, bill) for the whole local days [from]…[to], both
 * inclusive. A single day has `from == to`. This is a user-entered measurement, not telemetry.
 */
data class ManualProductionEntry(val from: LocalDate, val to: LocalDate, val kwh: Double) {
    val days: Int get() = ChronoUnit.DAYS.between(from, to).toInt() + 1
}

/** Why an entry cannot be saved. Texts live in the app (translated). */
enum class ManualEntryProblem {
    /** Energy missing, negative or not a number. */
    INVALID_ENERGY,
    END_BEFORE_START,
    /** The period includes today or a future day – that day's production is not complete yet. */
    NOT_FINISHED,
    /** Longer than [ManualProduction.MAX_DAYS]. */
    TOO_LONG,
    /** Shares at least one day with an entry that is already saved. */
    OVERLAP,
    /** More energy than the array could produce under any sky – most likely Wh typed as kWh or a wrong period. */
    IMPLAUSIBLE,
}

object ManualProduction {
    const val MAX_DAYS = 366

    /**
     * Upper bound of the daily yield [kWh per kWp]. The best fixed arrays on Earth reach about 8 on a perfect day;
     * anything above this cannot be the production of the configured array.
     */
    const val MAX_KWH_PER_KWP_DAY = 10.0

    fun overlaps(a: ManualProductionEntry, b: ManualProductionEntry): Boolean = !a.to.isBefore(b.from) && !b.to.isBefore(a.from)

    /** @param peakPowerKw configured array size; null or 0 skips the plausibility check */
    fun validate(entry: ManualProductionEntry, today: LocalDate, existing: List<ManualProductionEntry>, peakPowerKw: Double?): ManualEntryProblem? = when {
        !entry.kwh.isFinite() || entry.kwh < 0 -> ManualEntryProblem.INVALID_ENERGY
        entry.to.isBefore(entry.from) -> ManualEntryProblem.END_BEFORE_START
        !entry.to.isBefore(today) -> ManualEntryProblem.NOT_FINISHED
        entry.days > MAX_DAYS -> ManualEntryProblem.TOO_LONG
        existing.any { overlaps(it, entry) } -> ManualEntryProblem.OVERLAP
        peakPowerKw != null && peakPowerKw > 0 && entry.kwh > peakPowerKw * entry.days * MAX_KWH_PER_KWP_DAY -> ManualEntryProblem.IMPLAUSIBLE
        else -> null
    }

    private val DAY_FIRST = Regex("""(\d{1,2})[./](\d{1,2})[./](\d{4})""")

    /** A date as people type it: ISO `2026-10-05` or day-first `5.10.2026` / `05/10/2026`; null when it is not a date. */
    fun parseDate(text: String): LocalDate? {
        val t = text.trim()
        runCatching { return LocalDate.parse(t) }
        val m = DAY_FIRST.matchEntire(t) ?: return null
        return runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
    }

    /** [existing] with [entry] added, newest first. The caller validates first. */
    fun add(existing: List<ManualProductionEntry>, entry: ManualProductionEntry): List<ManualProductionEntry> =
        (existing + entry).sortedByDescending { it.from }
}

/** Plain-text storage: one entry per line, `from;to;kWh` with ISO dates and a dot decimal. */
object ManualProductionCodec {
    fun encode(entries: List<ManualProductionEntry>): String = entries.joinToString("\n") { "${it.from};${it.to};${it.kwh}" }

    /** Tolerant: broken lines, impossible entries and entries overlapping an earlier line are skipped, never invented. */
    fun decode(text: String): List<ManualProductionEntry> {
        val out = ArrayList<ManualProductionEntry>()
        for (line in text.lineSequence()) {
            val parts = line.trim().split(';')
            if (parts.size != 3) continue
            val entry = runCatching { ManualProductionEntry(LocalDate.parse(parts[0]), LocalDate.parse(parts[1]), parts[2].toDouble()) }.getOrNull() ?: continue
            if (!entry.kwh.isFinite() || entry.kwh < 0 || entry.to.isBefore(entry.from) || entry.days > ManualProduction.MAX_DAYS) continue
            if (out.any { ManualProduction.overlaps(it, entry) }) continue
            out += entry
        }
        return out.sortedByDescending { it.from }
    }
}

/** What the model's irradiance for a past day was based on; from best to worst. */
enum class EstimateBasis {
    /** Hourly weather of that very day (provider archive or forecast) for every daylight hour. */
    WEATHER,
    /** Typical month from climate averages – right on average over weeks, not for one particular day. */
    CLIMATE,
    /** Cloudless sky – an upper bound. */
    CLEAR_SKY,
}

data class DayEstimate(val kwh: Double, val basis: EstimateBasis)

/**
 * Model energy of whole past days from the shared [PvEstimator] (same model as the rest of the app: no shading,
 * no snow, no calibration), with the basis of the irradiance stated per day.
 */
class PastDayEstimator(
    private val weather: WeatherAwareIrradianceModel,
    private val system: PvSystem,
    private val location: GeoLocation,
    private val zone: ZoneId,
) {
    private val estimator = PvEstimator(weather)

    fun estimate(date: LocalDate): DayEstimate {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        // The day is only as good as its worst daylight hour.
        var basis = EstimateBasis.WEATHER
        var t = start.plusSeconds(1800)
        while (t.isBefore(end)) {
            if (SolarCalculator.position(location, t).isAboveHorizon) {
                val b = when (weather.sourceAt(t)) {
                    WeatherSource.FORECAST -> EstimateBasis.WEATHER
                    WeatherSource.CLIMATE -> EstimateBasis.CLIMATE
                    WeatherSource.CLEAR_SKY -> EstimateBasis.CLEAR_SKY
                }
                if (b > basis) basis = b
            }
            t = t.plusSeconds(3600)
        }
        return DayEstimate(estimator.dailyEnergyKwh(system, location, date, zone), basis)
    }
}

data class EntryComparison(
    val entry: ManualProductionEntry,
    /** Model energy for the same days [kWh]; null when the model has no estimate for some day. */
    val estimatedKwh: Double?,
    /** Worst basis among the entry's days. */
    val basis: EstimateBasis?,
    /** actual / estimated; null when the estimate is too small to divide by (night-like days). */
    val ratio: Double?,
    /** Actual yield [kWh per kWp per day]; null without a configured array size. */
    val yieldKwhPerKwpDay: Double?,
) {
    val differencePercent: Double? get() = ratio?.let { (it - 1) * 100 }
}

data class ComparisonSummary(val entries: Int, val days: Int, val actualKwh: Double, val estimatedKwh: Double) {
    /** Energy-weighted actual / estimated. */
    val ratio: Double? get() = if (estimatedKwh > 0) actualKwh / estimatedKwh else null
}

data class ManualComparison(
    val rows: List<EntryComparison>,
    /** Entries compared with the weather of their own days – the only fair comparison. */
    val weatherBased: ComparisonSummary?,
    /** Entries compared with a typical month or a clear sky – indicative only. */
    val other: ComparisonSummary?,
    /**
     * actual / model over the weather-based entries, offered as the installation's correction factor only when
     * there are at least [ManualProductionAnalyzer.MIN_DAYS_FOR_FACTOR] such days; null otherwise.
     */
    val suggestedFactor: Double?,
    /** Weather-based days still missing before a factor is offered (0 when there are enough). */
    val daysMissingForFactor: Int,
    /** Enough days, but the ratio is outside [ManualProductionAnalyzer.PLAUSIBLE_FACTOR]: check the settings and readings. */
    val ratioImplausible: Boolean,
)

object ManualProductionAnalyzer {
    /** A factor from a handful of days mostly measures the weather forecast error of those days. */
    const val MIN_DAYS_FOR_FACTOR = 14

    /** Below this model yield [kWh per kWp per day] a ratio is noise (same threshold as the forecast MAPE). */
    const val MIN_ESTIMATE_KWH_PER_KWP_DAY = 0.05

    /** Ratios outside this range point to wrong settings or wrong readings, not to a correction factor. */
    val PLAUSIBLE_FACTOR = 0.4..1.6

    /**
     * @param estimate model energy and its basis for one local day; null when unavailable
     * @param peakPowerKw configured array size (for the yield and the minimum-estimate threshold)
     */
    fun compare(entries: List<ManualProductionEntry>, peakPowerKw: Double, estimate: (LocalDate) -> DayEstimate?): ManualComparison {
        val rows = entries.sortedByDescending { it.from }.map { e ->
            var sum = 0.0
            var basis: EstimateBasis? = EstimateBasis.WEATHER
            var complete = true
            var d = e.from
            while (!d.isAfter(e.to)) {
                val day = estimate(d)
                if (day == null || !day.kwh.isFinite()) { complete = false; break }
                sum += day.kwh
                if (day.basis > basis!!) basis = day.basis
                d = d.plusDays(1)
            }
            val estimated = sum.takeIf { complete }
            val floor = peakPowerKw.coerceAtLeast(0.0) * e.days * MIN_ESTIMATE_KWH_PER_KWP_DAY
            EntryComparison(
                entry = e,
                estimatedKwh = estimated,
                basis = basis.takeIf { complete },
                ratio = estimated?.takeIf { it > 0 && it >= floor }?.let { e.kwh / it },
                yieldKwhPerKwpDay = if (peakPowerKw > 0) e.kwh / peakPowerKw / e.days else null,
            )
        }
        fun summary(rs: List<EntryComparison>): ComparisonSummary? = rs.filter { it.ratio != null }.takeIf { it.isNotEmpty() }
            ?.let { ok -> ComparisonSummary(ok.size, ok.sumOf { it.entry.days }, ok.sumOf { it.entry.kwh }, ok.sumOf { it.estimatedKwh!! }) }
        val weather = summary(rows.filter { it.basis == EstimateBasis.WEATHER })
        val other = summary(rows.filter { it.basis != null && it.basis != EstimateBasis.WEATHER })
        val weatherDays = weather?.days ?: 0
        return ManualComparison(
            rows = rows,
            weatherBased = weather,
            other = other,
            suggestedFactor = weather?.ratio?.takeIf { weatherDays >= MIN_DAYS_FOR_FACTOR && it in PLAUSIBLE_FACTOR },
            daysMissingForFactor = (MIN_DAYS_FOR_FACTOR - weatherDays).coerceAtLeast(0),
            ratioImplausible = weatherDays >= MIN_DAYS_FOR_FACTOR && weather?.ratio?.let { it !in PLAUSIBLE_FACTOR } == true,
        )
    }
}
