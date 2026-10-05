package com.solartracker.pro.core.widget

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherSource
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Last value read from the inverter (from the local history). */
data class LastMeasurement(val time: Instant, val pvW: Double?, val socPercent: Double?)

/** Compact state for the home-screen widget. Every number carries its data kind. */
data class SolarSummary(
    val time: Instant,
    val sunElevationDeg: Double,
    val sunAzimuthDeg: Double,
    val sunrise: Instant?,
    val sunset: Instant?,
    val modelPvKw: Double,
    val modelKind: DataKind,
    val weatherSource: WeatherSource,
    val todayExpectedKwh: Double,
    val remainingTodayKwh: Double,
    val measuredPvKw: Double?,
    val measuredSoc: Double?,
    /** MEASURED when recent, LAST_KNOWN when older, UNAVAILABLE without inverter data. */
    val measuredKind: DataKind,
    val measuredAt: Instant?,
)

object SolarSummaryBuilder {
    /** A measurement older than this is shown as LAST KNOWN, never as current. */
    val FRESH: Duration = Duration.ofMinutes(15)

    fun build(
        location: GeoLocation,
        system: PvSystem,
        weather: WeatherAwareIrradianceModel,
        now: Instant,
        zone: ZoneId,
        last: LastMeasurement?,
        stepMinutes: Long = 15,
    ): SolarSummary {
        val estimator = PvEstimator(weather)
        val point = estimator.pointEstimate(system, location, now)
        val date = now.atZone(zone).toLocalDate()
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        var total = 0.0
        var remaining = 0.0
        var t = start
        val h = stepMinutes / 60.0
        while (t.isBefore(end)) {
            val kwh = estimator.powerKw(system, location, t.plusSeconds(stepMinutes * 30)) * h
            total += kwh
            if (!t.plusSeconds(stepMinutes * 60).isBefore(now)) remaining += kwh
            t = t.plusSeconds(stepMinutes * 60)
        }
        val source = weather.sourceAt(now)
        val times = SolarCalculator.sunTimes(location, date)
        val fresh = last != null && Duration.between(last.time, now) <= FRESH && !last.time.isAfter(now.plusSeconds(60))
        return SolarSummary(
            time = now,
            sunElevationDeg = point.sun.elevationDeg,
            sunAzimuthDeg = point.sun.azimuthDeg,
            sunrise = times.sunrise,
            sunset = times.sunset,
            modelPvKw = point.powerKw,
            modelKind = if (source == WeatherSource.FORECAST) DataKind.FORECAST else DataKind.ESTIMATED,
            weatherSource = source,
            todayExpectedKwh = total,
            remainingTodayKwh = remaining,
            measuredPvKw = last?.pvW?.div(1000.0),
            measuredSoc = last?.socPercent,
            measuredKind = when {
                last == null -> DataKind.UNAVAILABLE
                fresh -> DataKind.MEASURED
                else -> DataKind.LAST_KNOWN
            },
            measuredAt = last?.time,
        )
    }
}
