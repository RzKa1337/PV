package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.quality.DataKind
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.exp

data class LoadForecastPoint(val time: Instant, val kw: Double, val kind: DataKind, val confidence: Double, val basis: String)

/**
 * Load forecast without ML: hourly profiles split into working days / weekends, weighted by age
 * (recent weeks count more), with outlier hours (sudden anomalies) removed using the median.
 * Without enough history the user's configured consumption profile is used (ESTIMATED).
 */
/** Anything that forecasts the household/site load. */
fun interface LoadModel {
    fun at(t: Instant): LoadForecastPoint
}

/**
 * Adds a modelled cooling load ([CoolingLoadProfile]) to a base load forecast, using the forecast air
 * temperature for the compressor duty. Use it only when the base forecast does not already contain
 * the cold room (i.e. no measured load history).
 */
class CoolingAwareLoad(
    private val base: LoadModel,
    private val cooling: com.solartracker.pro.core.energy.CoolingLoadProfile,
    private val zone: ZoneId,
    private val ambientC: (Instant) -> Double?,
) : LoadModel {
    override fun at(t: Instant): LoadForecastPoint {
        val b = base.at(t)
        val c = cooling.averagePowerKw(t, zone, ambientC(t))
        return b.copy(kw = b.kw + c, kind = DataKind.ESTIMATED, basis = b.basis + " + ${cooling.name} (model cyklu sprężarki)")
    }
}

class LoadForecaster(
    history: List<HistorySample>,
    private val zone: ZoneId,
    private val fallback: ConsumptionProfile,
    private val now: Instant,
    private val halfLifeDays: Double = 14.0,
    private val minDays: Int = 3,
) : LoadModel {
    private data class HourValue(val kw: Double, val weight: Double)

    private val profiles: Map<Pair<Boolean, Int>, Double>
    private val spread: Map<Pair<Boolean, Int>, Double>
    val historyDays: Int

    init {
        val rows = history.filter { it.loadW != null && it.end.isBefore(now) }
        historyDays = rows.map { it.start.atZone(zone).toLocalDate() }.toSet().size
        val byKey = rows.groupBy { key(it.start) }.mapValues { (_, list) ->
            list.map { r ->
                val ageDays = Duration.between(r.start, now).toHours() / 24.0
                HourValue(r.loadW!! / 1000.0, exp(-ageDays * Math.log(2.0) / halfLifeDays) * r.duration.seconds.coerceAtLeast(1))
            }
        }
        val clean = byKey.mapValues { (_, values) ->
            val med = median(values.map { it.kw })
            val mad = median(values.map { abs(it.kw - med) }).coerceAtLeast(0.05)
            values.filter { abs(it.kw - med) <= 4 * 1.4826 * mad }
        }
        profiles = clean.filterValues { it.isNotEmpty() }.mapValues { (_, v) -> v.sumOf { it.kw * it.weight } / v.sumOf { it.weight } }
        spread = clean.filterValues { it.size >= 2 }.mapValues { (_, v) ->
            val m = v.map { it.kw }.average()
            kotlin.math.sqrt(v.sumOf { (it.kw - m) * (it.kw - m) } / (v.size - 1))
        }
    }

    private fun key(t: Instant): Pair<Boolean, Int> {
        val z = t.atZone(zone)
        return (z.dayOfWeek == DayOfWeek.SATURDAY || z.dayOfWeek == DayOfWeek.SUNDAY) to z.hour
    }

    val usesHistory: Boolean get() = historyDays >= minDays

    override fun at(t: Instant): LoadForecastPoint {
        val k = key(t)
        val learned = profiles[k] ?: profiles[(!k.first) to k.second]
        if (usesHistory && learned != null) {
            val cv = (spread[k] ?: learned * 0.5) / learned.coerceAtLeast(0.05)
            val confidence = ((historyDays / 14.0).coerceAtMost(1.0) * (1 - cv.coerceIn(0.0, 0.8))).coerceIn(0.1, 0.95)
            return LoadForecastPoint(t, learned, DataKind.FORECAST, confidence, "profil godzinowy z $historyDays dni pomiarów")
        }
        return LoadForecastPoint(t, fallback.powerKwAtHour(t.atZone(zone).hour), DataKind.ESTIMATED, 0.3, "profil zużycia z ustawień (brak historii pomiarów)")
    }

    /** Typical load for this hour (null without enough history) – used for anomaly detection. */
    fun typicalKw(t: Instant): Double? = if (usesHistory) profiles[key(t)] else null

    companion object {
        fun median(v: List<Double>): Double {
            if (v.isEmpty()) return 0.0
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
