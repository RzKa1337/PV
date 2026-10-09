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
    /** Wind speed at 10 m [m/s]; null = unknown (cell temperature from the NOCT model, without wind). */
    val windMs: Double? = null,
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

/** Plane-of-array irradiance split into its components [W/m²]. */
data class PoaComponents(val beam: Double, val skyDiffuse: Double, val groundReflected: Double) {
    val total: Double get() = beam + skyDiffuse + groundReflected

    companion object {
        val ZERO = PoaComponents(0.0, 0.0, 0.0)
    }
}

/** Transposition of the diffuse sky light onto a tilted plane. */
enum class SkyDiffuseModel {
    /** Liu–Jordan: uniform sky. Underestimates sun-facing planes on clear days. */
    ISOTROPIC,

    /**
     * Hay–Davies (1980): a circumsolar part, weighted by the anisotropy index A = DNI / extraterrestrial DNI and
     * projected like the beam, plus an isotropic rest. Same formula as pvlib `irradiance.haydavies`.
     */
    HAY_DAVIES,
}

/** Lower limit of cos(zenith) in the beam projection ratio (pvlib uses the same 0.01745 ≈ cos 89°). */
private const val MIN_COS_ZENITH = 0.01745

/**
 * Components of the plane-of-array irradiance for a tilted surface: beam + sky diffuse ([skyModel]) + ground
 * reflected (isotropic, [albedo]). [extraterrestrialDni] [W/m²] is only used by Hay–Davies.
 */
fun poaComponents(
    irradiance: Irradiance,
    position: SolarPosition,
    tiltDeg: Double,
    panelAzimuthDeg: Double,
    albedo: Double = 0.2,
    skyModel: SkyDiffuseModel = SkyDiffuseModel.HAY_DAVIES,
    extraterrestrialDni: Double = ClearSkyModel.SOLAR_CONSTANT,
): PoaComponents {
    if (!position.isAboveHorizon) return PoaComponents.ZERO
    val tilt = Math.toRadians(tiltDeg)
    val cosAoi = max(0.0, cosIncidence(position, tiltDeg, panelAzimuthDeg))
    val dhi = max(0.0, irradiance.dhi)
    val isotropicView = (1.0 + cos(tilt)) / 2.0
    val sky = when (skyModel) {
        SkyDiffuseModel.ISOTROPIC -> dhi * isotropicView
        SkyDiffuseModel.HAY_DAVIES -> {
            val ai = if (extraterrestrialDni > 0) (max(0.0, irradiance.dni) / extraterrestrialDni).coerceIn(0.0, 1.0) else 0.0
            val rb = cosAoi / max(cos(Math.toRadians(position.zenithDeg)), MIN_COS_ZENITH)
            dhi * (ai * rb + (1.0 - ai) * isotropicView)
        }
    }
    return PoaComponents(
        beam = max(0.0, irradiance.dni) * cosAoi,
        skyDiffuse = sky,
        groundReflected = irradiance.ghi(position) * albedo * (1.0 - cos(tilt)) / 2.0,
    )
}

/** Plane-of-array irradiance [W/m²] for a tilted surface (sum of [poaComponents]). */
fun planeOfArrayIrradiance(
    irradiance: Irradiance,
    position: SolarPosition,
    tiltDeg: Double,
    panelAzimuthDeg: Double,
    albedo: Double = 0.2,
    skyModel: SkyDiffuseModel = SkyDiffuseModel.HAY_DAVIES,
    extraterrestrialDni: Double = ClearSkyModel.SOLAR_CONSTANT,
): Double = poaComponents(irradiance, position, tiltDeg, panelAzimuthDeg, albedo, skyModel, extraterrestrialDni).total

/**
 * ASHRAE incidence angle modifier for the beam component: 1 − b₀·(1/cos θ − 1), 0 at θ ≥ 90°.
 * b₀ ≈ 0.05 for glass-covered crystalline modules (front-glass reflection grows at oblique angles).
 */
fun ashraeIam(cosAoi: Double, b0: Double): Double {
    if (cosAoi <= 0.0) return 0.0
    return (1.0 - b0 * (1.0 / cosAoi - 1.0)).coerceIn(0.0, 1.0)
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
