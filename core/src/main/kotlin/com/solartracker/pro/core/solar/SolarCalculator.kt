package com.solartracker.pro.core.solar

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.tan

/**
 * Geographic position in decimal degrees (north and east positive).
 * [elevationM] is the height above sea level (0 when unknown); it is used for the
 * pressure-corrected air mass.
 */
data class GeoLocation(val latitude: Double, val longitude: Double, val elevationM: Double = 0.0) {
    init {
        require(latitude in -90.0..90.0) { "Latitude out of range: $latitude" }
        require(longitude in -180.0..180.0) { "Longitude out of range: $longitude" }
        require(elevationM.isFinite() && elevationM in -500.0..9000.0) { "Elevation out of range: $elevationM" }
    }
}

/**
 * Full set of solar angles for one moment, all in full double precision.
 *
 * @property hourAngleDeg local hour angle, negative before and positive after solar noon (-180..180)
 * @property zenithDeg apparent zenith angle (= zenith distance) = 90° − apparent elevation
 * @property airMass relative optical air mass (Kasten–Young, pressure corrected for
 *   [GeoLocation.elevationM]); null when the sun is below the horizon
 */
data class SolarDetails(
    val elevationDeg: Double,
    val geometricElevationDeg: Double,
    val azimuthDeg: Double,
    val hourAngleDeg: Double,
    val declinationDeg: Double,
    val equationOfTimeMin: Double,
    val airMass: Double?,
) {
    val zenithDeg: Double get() = 90.0 - elevationDeg
    val position: SolarPosition get() = SolarPosition(elevationDeg, azimuthDeg)
}

/**
 * Position of the sun as seen by an observer.
 *
 * @property elevationDeg apparent elevation above the horizon (refraction corrected), -90..90
 * @property azimuthDeg compass bearing measured clockwise from north, 0..360
 */
data class SolarPosition(val elevationDeg: Double, val azimuthDeg: Double) {
    val zenithDeg: Double get() = 90.0 - elevationDeg
    val isAboveHorizon: Boolean get() = elevationDeg > 0.0
}

enum class DayType { NORMAL, POLAR_DAY, POLAR_NIGHT }

/**
 * Sun events for one calendar day. [sunrise] and [sunset] are null during polar day/night.
 */
data class SunTimes(
    val sunrise: Instant?,
    val sunset: Instant?,
    val solarNoon: Instant,
    val dayLength: Duration,
    val dayType: DayType,
)

/**
 * Solar position and sunrise/sunset based on the NOAA Solar Calculator equations
 * (Jean Meeus, "Astronomical Algorithms"). Accuracy is about ±1 minute for sun events
 * and a few hundredths of a degree for position between ±72° latitude.
 *
 * Pure Kotlin, no Android dependencies, so it can be unit tested on the JVM.
 */
object SolarCalculator {

    /** Zenith used for sunrise/sunset: 90° + refraction (34') + solar radius (16'). */
    private const val SUNRISE_ZENITH_DEG = 90.833
    private const val MINUTES_PER_DAY = 1440.0

    fun position(location: GeoLocation, instant: Instant): SolarPosition = details(location, instant).position

    /** All solar angles for [instant] at [location]; [position] is derived from this. */
    fun details(location: GeoLocation, instant: Instant): SolarDetails {
        val sun = sunParameters(julianDay(instant))
        val minutesUtc = minutesOfUtcDay(instant)
        var trueSolarTime = (minutesUtc + sun.equationOfTimeMin + 4.0 * location.longitude) % MINUTES_PER_DAY
        if (trueSolarTime < 0) trueSolarTime += MINUTES_PER_DAY
        val hourAngle = rad(trueSolarTime / 4.0 - 180.0)

        val lat = rad(location.latitude)
        val decl = rad(sun.declinationDeg)
        val cosZenith = (sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        val zenith = deg(acos(cosZenith))

        // Azimuth measured from south (westward positive), converted to a compass bearing.
        val azimuthFromSouth = atan2(sin(hourAngle), cos(hourAngle) * sin(lat) - tan(decl) * cos(lat))
        val azimuth = normalizeDegrees(deg(azimuthFromSouth) + 180.0)

        val geometricElevation = 90.0 - zenith
        val elevation = (geometricElevation + refractionCorrectionDeg(geometricElevation)).coerceIn(-90.0, 90.0)
        return SolarDetails(
            elevationDeg = elevation,
            geometricElevationDeg = geometricElevation,
            azimuthDeg = azimuth,
            hourAngleDeg = trueSolarTime / 4.0 - 180.0,
            declinationDeg = sun.declinationDeg,
            equationOfTimeMin = sun.equationOfTimeMin,
            airMass = airMass(90.0 - elevation, location.elevationM),
        )
    }

    /**
     * Kasten & Young (1989) relative air mass for an apparent zenith angle, scaled by the
     * standard-atmosphere pressure ratio exp(−h / 8434.5 m). Null below the horizon.
     */
    fun airMass(zenithDeg: Double, elevationM: Double = 0.0): Double? {
        if (zenithDeg >= 90.0) return null
        val am = 1.0 / (cos(rad(zenithDeg)) + 0.50572 * (96.07995 - zenithDeg).pow(-1.6364))
        return am * kotlin.math.exp(-elevationM / 8434.5)
    }

    /**
     * Sunrise, sunset and solar noon for the solar day of [date] at [location].
     * Events are returned as [Instant]s; format them in any time zone.
     */
    fun sunTimes(location: GeoLocation, date: LocalDate): SunTimes {
        val dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant()

        var noonMin = 720.0 - 4.0 * location.longitude
        repeat(3) {
            val sun = sunParameters(julianDay(dayStart.plusMinutes(noonMin)))
            noonMin = 720.0 - 4.0 * location.longitude - sun.equationOfTimeMin
        }
        val solarNoon = dayStart.plusMinutes(noonMin)

        val noonSun = sunParameters(julianDay(solarNoon))
        when (val noonState = sunriseHourAngleState(location.latitude, noonSun.declinationDeg)) {
            is HourAngleState.Polar -> return SunTimes(
                sunrise = null,
                sunset = null,
                solarNoon = solarNoon,
                dayLength = if (noonState.type == DayType.POLAR_DAY) Duration.ofHours(24) else Duration.ZERO,
                dayType = noonState.type,
            )
            is HourAngleState.Normal -> Unit
        }

        val sunrise = refineEvent(location, dayStart, noonMin, rising = true)
        val sunset = refineEvent(location, dayStart, noonMin, rising = false)
        if (sunrise == null || sunset == null) {
            // Borderline polar case: the sun just touches the horizon around noon.
            val elevationAtNoon = position(location, solarNoon).elevationDeg
            val type = if (elevationAtNoon > 0) DayType.POLAR_DAY else DayType.POLAR_NIGHT
            return SunTimes(
                sunrise = null,
                sunset = null,
                solarNoon = solarNoon,
                dayLength = if (type == DayType.POLAR_DAY) Duration.ofHours(24) else Duration.ZERO,
                dayType = type,
            )
        }
        return SunTimes(
            sunrise = sunrise,
            sunset = sunset,
            solarNoon = solarNoon,
            dayLength = Duration.between(sunrise, sunset),
            dayType = DayType.NORMAL,
        )
    }

    private fun refineEvent(location: GeoLocation, dayStart: Instant, noonMin: Double, rising: Boolean): Instant? {
        var eventMin = noonMin
        repeat(4) {
            val sun = sunParameters(julianDay(dayStart.plusMinutes(eventMin)))
            val state = sunriseHourAngleState(location.latitude, sun.declinationDeg)
            if (state !is HourAngleState.Normal) return null
            val sign = if (rising) 1.0 else -1.0
            eventMin = 720.0 - 4.0 * (location.longitude + sign * state.hourAngleDeg) - sun.equationOfTimeMin
        }
        return dayStart.plusMinutes(eventMin)
    }

    private sealed interface HourAngleState {
        data class Normal(val hourAngleDeg: Double) : HourAngleState
        data class Polar(val type: DayType) : HourAngleState
    }

    private fun sunriseHourAngleState(latitudeDeg: Double, declinationDeg: Double): HourAngleState {
        val lat = rad(latitudeDeg)
        val decl = rad(declinationDeg)
        val cosLatCosDecl = cos(lat) * cos(decl)
        if (abs(cosLatCosDecl) < 1e-12) {
            // At the poles the sun's elevation equals its declination for the whole day.
            val up = latitudeDeg * declinationDeg > 0
            return HourAngleState.Polar(if (up) DayType.POLAR_DAY else DayType.POLAR_NIGHT)
        }
        val cosH = cos(rad(SUNRISE_ZENITH_DEG)) / cosLatCosDecl - tan(lat) * tan(decl)
        return when {
            cosH > 1.0 -> HourAngleState.Polar(DayType.POLAR_NIGHT)
            cosH < -1.0 -> HourAngleState.Polar(DayType.POLAR_DAY)
            else -> HourAngleState.Normal(deg(acos(cosH)))
        }
    }

    private data class SunParameters(val declinationDeg: Double, val equationOfTimeMin: Double)

    private fun sunParameters(julianDay: Double): SunParameters {
        val t = (julianDay - 2451545.0) / 36525.0 // Julian century
        val meanLong = normalizeDegrees(280.46646 + t * (36000.76983 + t * 0.0003032))
        val meanAnomaly = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val m = rad(meanAnomaly)
        val center = sin(m) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m) * (0.019993 - 0.000101 * t) +
            sin(3 * m) * 0.000289
        val trueLong = meanLong + center
        val omega = rad(125.04 - 1934.136 * t)
        val apparentLong = trueLong - 0.00569 - 0.00478 * sin(omega)
        val meanObliquity = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val obliquity = meanObliquity + 0.00256 * cos(omega)
        val declination = deg(asin(sin(rad(obliquity)) * sin(rad(apparentLong))))

        val y = tan(rad(obliquity) / 2).let { it * it }
        val l0 = rad(meanLong)
        val eqTime = 4.0 * deg(
            y * sin(2 * l0) -
                2 * eccentricity * sin(m) +
                4 * eccentricity * y * sin(m) * cos(2 * l0) -
                0.5 * y * y * sin(4 * l0) -
                1.25 * eccentricity * eccentricity * sin(2 * m)
        )
        return SunParameters(declination, eqTime)
    }

    /** NOAA approximation of atmospheric refraction, in degrees. */
    private fun refractionCorrectionDeg(elevationDeg: Double): Double {
        if (elevationDeg > 85.0) return 0.0
        val te = tan(rad(elevationDeg))
        val arcSeconds = when {
            elevationDeg > 5.0 -> 58.1 / te - 0.07 / (te * te * te) + 0.000086 / (te * te * te * te * te)
            elevationDeg > -0.575 -> 1735.0 + elevationDeg * (-518.2 + elevationDeg * (103.4 + elevationDeg * (-12.79 + elevationDeg * 0.711)))
            else -> -20.772 / te
        }
        return arcSeconds / 3600.0
    }

    internal fun julianDay(instant: Instant): Double =
        instant.epochSecond / 86400.0 + instant.nano / 86_400e9 + 2440587.5

    private fun minutesOfUtcDay(instant: Instant): Double {
        val seconds = instant.epochSecond + instant.nano / 1e9
        val dayFraction = seconds / 86400.0 - floor(seconds / 86400.0)
        return dayFraction * MINUTES_PER_DAY
    }

    private fun Instant.plusMinutes(minutes: Double): Instant = plusMillis((minutes * 60_000.0).roundToLong())

    private fun rad(deg: Double) = Math.toRadians(deg)
    private fun deg(rad: Double) = Math.toDegrees(rad)
}

/** Normalizes an angle to the range [0, 360). */
fun normalizeDegrees(value: Double): Double {
    val r = value % 360.0
    return if (r < 0) r + 360.0 else r
}
