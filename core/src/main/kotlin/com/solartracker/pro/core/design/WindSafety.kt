package com.solartracker.pro.core.design

import com.solartracker.pro.core.weather.HourlyWeather
import java.time.Instant
import java.time.ZoneId
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

enum class WindLevel(val label: String) {
    SAFE("Bezpiecznie"),
    CAUTION("Uwaga"),
    LOWER_PANELS("Obniż panele"),
    STOW_IMMEDIATELY("Złóż panele natychmiast"),
}

enum class MountingType(val label: String, val minForceCoefficient: Double) {
    ROOF_FLUSH("Płasko przy dachu", 0.3),
    TILT_FRAME("Rama z kątem", 0.3),
    VEHICLE_RACK("Stelaż na pojeździe", 0.3),
    GROUND("Konstrukcja gruntowa", 0.3),
}

/**
 * Wind the structure is designed for: [ratedGustMs] at [ratedTiltDeg]. [userProvided] = false means a
 * conservative default (no manufacturer data) – the result is then labelled as low confidence.
 */
data class WindRating(val ratedGustMs: Double, val ratedTiltDeg: Double, val userProvided: Boolean) {
    companion object {
        /** Conservative default when the user has not entered the frame's rating (~72 km/h at 30°). */
        val DEFAULT = WindRating(20.0, 30.0, userProvided = false)
    }
}

data class WindPanelSetup(
    val panelAreaM2: Double,
    val tiltDeg: Double,
    val mounting: MountingType,
    val rating: WindRating = WindRating.DEFAULT,
    val mount: AdjustableMount? = null,
)

data class WindAssessment(
    val level: WindLevel,
    val gustMs: Double,
    /** True when the gust came from the forecast; false = estimated from mean wind (× [GUST_FACTOR]). */
    val gustForecast: Boolean,
    val forceN: Double,
    val forceRatio: Double,
    /** Highest tilt that stays SAFE at this gust (within the mount range); planning advice only. */
    val recommendedTiltDeg: Double,
    val at: Instant?,
    val confidence: Double,
    val message: String,
)

/**
 * Wind safety for tilted / mobile PV: wind pressure q = ½·ρ·v² on the panel area with a normal-force coefficient
 * that grows with tilt (flat-plate approximation, Cn ≈ 1.2·sin α, with a floor for edge uplift). The force is
 * compared with the force at the rated gust. Advice only – the app never drives an actuator.
 */
object PvWindSafetyEngine {
    const val AIR_DENSITY = 1.225
    const val GUST_FACTOR = 1.5

    fun forceCoefficient(tiltDeg: Double, mounting: MountingType): Double =
        maxOf(mounting.minForceCoefficient, 1.2 * sin(Math.toRadians(tiltDeg.coerceIn(0.0, 90.0))))

    fun forceN(gustMs: Double, areaM2: Double, tiltDeg: Double, mounting: MountingType): Double =
        0.5 * AIR_DENSITY * gustMs.coerceAtLeast(0.0).pow(2) * areaM2 * forceCoefficient(tiltDeg, mounting)

    fun assess(setup: WindPanelSetup, gustMs: Double?, meanWindMs: Double?, at: Instant? = null, zone: ZoneId? = null): WindAssessment? {
        val gust = gustMs ?: meanWindMs?.let { it * GUST_FACTOR } ?: return null
        val rated = forceN(setup.rating.ratedGustMs, setup.panelAreaM2, setup.rating.ratedTiltDeg, setup.mounting)
        fun ratio(tilt: Double) = forceN(gust, setup.panelAreaM2, tilt, setup.mounting) / rated
        val r = ratio(setup.tiltDeg)
        val level = level(r)
        val mount = setup.mount
        val candidates = mount?.let { m -> generateSequence(m.minTiltDeg) { it + m.stepDeg }.takeWhile { it <= m.maxTiltDeg + 1e-9 }.toList() }
            ?: listOf(0.0, setup.tiltDeg).distinct()
        val safeTilt = candidates.filter { level(ratio(it)) == WindLevel.SAFE }.maxOrNull() ?: candidates.min()
        val recommended = if (level == WindLevel.SAFE) setup.tiltDeg else minOf(safeTilt, setup.tiltDeg)
        val kmh = (gust * 3.6).roundToInt()
        val time = if (at != null && zone != null) " ok. ${at.atZone(zone).toLocalTime().withMinute(0)}" else ""
        val message = when (level) {
            WindLevel.SAFE -> "Porywy $kmh km/h$time – bezpiecznie przy ${setup.tiltDeg.roundToInt()}°"
            else -> "Porywy $kmh km/h$time. Zalecana pozycja: ${recommended.roundToInt()}°" +
                if (setup.mounting == MountingType.VEHICLE_RACK) ". Przed jazdą zawsze złóż panele." else ""
        }
        val confidence = (if (gustMs != null) 0.75 else 0.5) * (if (setup.rating.userProvided) 1.0 else 0.6)
        return WindAssessment(level, gust, gustMs != null, forceN(gust, setup.panelAreaM2, setup.tiltDeg, setup.mounting), r, recommended, at, confidence, message)
    }

    /** Worst hour of the next [hours] of the forecast (by force ratio), null without wind data. */
    fun worstAhead(setup: WindPanelSetup, forecast: List<HourlyWeather>, now: Instant, hours: Long = 24, zone: ZoneId): WindAssessment? =
        forecast.filter { it.endTime.isAfter(now) && !it.endTime.isAfter(now.plusSeconds(hours * 3600)) }
            .mapNotNull { h -> assess(setup, h.windGustsMs, h.windSpeedMs, h.startTime, zone) }
            .maxByOrNull { it.forceRatio }

    /** Highest tilt (from [candidates]) that stays SAFE at [gustMs]; used as a constraint by the tilt scheduler. */
    fun maxSafeTilt(setup: WindPanelSetup, gustMs: Double, candidates: List<Double>): Double? {
        val rated = forceN(setup.rating.ratedGustMs, setup.panelAreaM2, setup.rating.ratedTiltDeg, setup.mounting)
        return candidates.filter { level(forceN(gustMs, setup.panelAreaM2, it, setup.mounting) / rated) == WindLevel.SAFE }.maxOrNull()
    }

    private fun level(ratio: Double) = when {
        ratio < 0.6 -> WindLevel.SAFE
        ratio < 0.8 -> WindLevel.CAUTION
        ratio < 1.0 -> WindLevel.LOWER_PANELS
        else -> WindLevel.STOW_IMMEDIATELY
    }
}
