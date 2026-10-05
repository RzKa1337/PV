package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.solar.SolarPosition
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/** Irradiance on a horizontal surface [W/m²]. */
data class Irradiance(
    /** Direct normal irradiance. */
    val dni: Double,
    /** Diffuse horizontal irradiance. */
    val dhi: Double,
    /** Ambient air temperature [°C]; null = unknown (typical temperature losses assumed). */
    val ambientTemperatureC: Double? = null,
) {
    fun ghi(position: SolarPosition): Double =
        dni * max(0.0, cos(Math.toRadians(position.zenithDeg))) + dhi

    companion object {
        val ZERO = Irradiance(0.0, 0.0)
    }
}

/**
 * Source of solar irradiance. Swap the implementation (e.g. weather forecast data)
 * without touching the rest of the estimator.
 */
fun interface IrradianceModel {
    fun irradiance(position: SolarPosition, instant: Instant): Irradiance
}

/**
 * Simple clear-sky model (Meinel & Meinel air-mass attenuation, Kasten–Young air mass,
 * diffuse ≈ 10% of direct beam). It describes a cloudless day, so it is an upper
 * estimate of real production.
 */
class ClearSkyModel : IrradianceModel {
    override fun irradiance(position: SolarPosition, instant: Instant): Irradiance {
        if (!position.isAboveHorizon) return Irradiance.ZERO
        val zenith = position.zenithDeg
        val airMass = SolarCalculator.airMass(zenith) ?: return Irradiance.ZERO
        val extraterrestrial = extraterrestrialIrradiance(instant)
        val dni = extraterrestrial * 0.7.pow(airMass.pow(0.678))
        return Irradiance(dni = dni, dhi = 0.1 * dni)
    }

    companion object {
        const val SOLAR_CONSTANT = 1361.0

        /** Extraterrestrial normal irradiance [W/m²], corrected for Earth–Sun distance. */
        fun extraterrestrialIrradiance(instant: Instant): Double {
            val dayOfYear = instant.atZone(ZoneOffset.UTC).dayOfYear
            return SOLAR_CONSTANT * (1.0 + 0.033 * cos(2.0 * Math.PI * dayOfYear / 365.0))
        }
    }
}

/**
 * Plane-of-array irradiance [W/m²] for a tilted surface: beam + isotropic sky diffuse
 * + ground reflected.
 */
fun planeOfArrayIrradiance(
    irradiance: Irradiance,
    position: SolarPosition,
    tiltDeg: Double,
    panelAzimuthDeg: Double,
    albedo: Double = 0.2,
): Double {
    if (!position.isAboveHorizon) return 0.0
    val tilt = Math.toRadians(tiltDeg)
    val beam = irradiance.dni * max(0.0, cosIncidence(position, tiltDeg, panelAzimuthDeg))
    val skyDiffuse = irradiance.dhi * (1.0 + cos(tilt)) / 2.0
    val groundReflected = irradiance.ghi(position) * albedo * (1.0 - cos(tilt)) / 2.0
    return beam + skyDiffuse + groundReflected
}

/** Cosine of the angle between the sun's rays and the panel normal (may be negative = behind). */
fun cosIncidence(position: SolarPosition, tiltDeg: Double, panelAzimuthDeg: Double): Double {
    val zenith = Math.toRadians(position.zenithDeg)
    val tilt = Math.toRadians(tiltDeg)
    return (
        cos(zenith) * cos(tilt) +
            sin(zenith) * sin(tilt) * cos(Math.toRadians(position.azimuthDeg - panelAzimuthDeg))
        ).coerceIn(-1.0, 1.0)
}

/** Angle of incidence [°] of direct sunlight on the panel; 0° = rays perpendicular to the panel. */
fun angleOfIncidenceDeg(position: SolarPosition, tiltDeg: Double, panelAzimuthDeg: Double): Double =
    Math.toDegrees(kotlin.math.acos(cosIncidence(position, tiltDeg, panelAzimuthDeg)))
