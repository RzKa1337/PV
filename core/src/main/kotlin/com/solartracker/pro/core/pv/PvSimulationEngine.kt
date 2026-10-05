package com.solartracker.pro.core.pv

import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.solar.SolarPosition
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

enum class TrackerType(val label: String) {
    FIXED("Stała konstrukcja"),
    /** Horizontal north–south axis, rotating east→west (±[PvArrayConfig.trackerMaxRotationDeg]). */
    SINGLE_AXIS("Tracker 1-osiowy (oś N–S)"),
    DUAL_AXIS("Tracker 2-osiowy"),
}

/** Physical PV field. Peak power = panels × panel power (STC). */
data class PvArrayConfig(
    val panelCount: Int,
    val panelPowerW: Double,
    val tiltDeg: Double,
    val azimuthDeg: Double,
    /** Power temperature coefficient [1/°C], e.g. −0.0035 for mono PERC, −0.0029 TOPCon. */
    val temperatureCoefficient: Double = -0.0035,
    /** Ground reflectance: 0.2 grass, 0.3 concrete, 0.6–0.8 fresh snow. */
    val albedo: Double = 0.2,
    /** Bifaciality × rear-side view factor (0 = monofacial; ~0.07–0.12 for raised bifacial). */
    val bifacialGain: Double = 0.0,
    val tracker: TrackerType = TrackerType.FIXED,
    val trackerMaxRotationDeg: Double = 60.0,
) {
    val peakPowerW: Double get() = panelCount * panelPowerW

    fun validate(): List<String> = buildList {
        if (panelCount !in 1..10_000) add("Liczba paneli 1–10000")
        if (panelPowerW !in 1.0..1000.0) add("Moc panelu 1–1000 W")
        if (tiltDeg !in 0.0..90.0) add("Kąt 0–90°")
        if (temperatureCoefficient !in -0.01..0.0) add("Współczynnik temperaturowy −1…0 %/°C")
        if (albedo !in 0.0..1.0) add("Albedo 0–1")
        if (bifacialGain !in 0.0..0.3) add("Zysk bifacial 0–30%")
    }
}

/** Loss chain parameters (fractions, 0.02 = 2%). Defaults ≈ PR 0.80–0.85 for a typical installation. */
data class LossProfile(
    val soiling: Double = 0.02,
    val mismatch: Double = 0.02,
    val dcWiring: Double = 0.015,
    val acWiring: Double = 0.01,
    /** Yearly power degradation (0.005 = 0.5%/year) and installation age. */
    val degradationPerYear: Double = 0.005,
    val ageYears: Double = 0.0,
    /** Peak inverter efficiency and its own consumption as a share of rated power. */
    val inverterPeakEfficiency: Double = 0.96,
    val inverterSelfConsumption: Double = 0.004,
    /** Inverter AC (or MPPT charger) power limit [W]; null = no clipping. */
    val inverterLimitW: Double? = null,
    /** Inverter rated power used for self consumption; defaults to the limit or the array peak. */
    val inverterRatedW: Double? = null,
) {
    fun validate(): List<String> = buildList {
        listOf(soiling, mismatch, dcWiring, acWiring).forEach { if (it !in 0.0..0.5) add("Straty 0–50%") }
        if (degradationPerYear !in 0.0..0.05) add("Degradacja 0–5%/rok")
        if (ageYears !in 0.0..60.0) add("Wiek 0–60 lat")
        if (inverterPeakEfficiency !in 0.5..1.0) add("Sprawność falownika 50–100%")
    }
}

/** Weather at the array; null fields are unknown (the model then uses documented defaults). */
data class PvConditions(
    val irradiance: Irradiance,
    val ambientC: Double? = null,
    val windMs: Double? = null,
    /** Snow depth on the ground [m]; panels are assumed covered when snow lies and it is freezing. */
    val snowDepthM: Double? = null,
)

/** Where each watt went – the "waterfall" from irradiance to AC power. */
data class PvLossBreakdown(
    val poaWm2: Double,
    /** Power the array would give at this irradiance at 25 °C without any loss [W]. */
    val idealDcW: Double,
    val bifacialGainW: Double,
    val temperatureLossW: Double,
    val snowLossW: Double,
    val soilingLossW: Double,
    val shadingLossW: Double,
    val mismatchLossW: Double,
    val degradationLossW: Double,
    val dcWiringLossW: Double,
    val dcInputW: Double,
    val inverterLossW: Double,
    val clippingLossW: Double,
    val acWiringLossW: Double,
    val acOutputW: Double,
) {
    val totalLossW: Double get() = idealDcW + bifacialGainW - acOutputW
    val performanceRatio: Double? get() = if (idealDcW > 1.0) acOutputW / idealDcW else null

    /** Named, non-zero losses in the order they occur. */
    val steps: List<Pair<String, Double>>
        get() = listOf(
            "Temperatura" to temperatureLossW, "Śnieg" to snowLossW, "Zabrudzenie" to soilingLossW, "Zacienienie" to shadingLossW,
            "Mismatch" to mismatchLossW, "Degradacja" to degradationLossW, "Okablowanie DC" to dcWiringLossW,
            "Falownik" to inverterLossW, "Clipping" to clippingLossW, "Okablowanie AC" to acWiringLossW,
        )
}

data class PvSimulationPoint(
    val time: Instant,
    val sun: SolarPosition,
    val surfaceTiltDeg: Double,
    val surfaceAzimuthDeg: Double,
    val cellTemperatureC: Double?,
    val snowCovered: Boolean,
    val losses: PvLossBreakdown,
) {
    val acPowerW: Double get() = losses.acOutputW
}

/**
 * Deterministic, UI-independent PV model with an explicit loss chain. Works offline: irradiance comes
 * from any [IrradianceModel] (clear sky, climate, forecast) or directly as [PvConditions].
 *
 * Order: POA (+ bifacial rear) → temperature (Faiman with wind, NOCT otherwise) → snow → soiling →
 * shading → mismatch → degradation → DC wiring → inverter (peak efficiency + self consumption) →
 * clipping at the inverter limit → AC wiring.
 */
class PvSimulationEngine {

    fun simulate(
        array: PvArrayConfig,
        losses: LossProfile,
        location: GeoLocation,
        instant: Instant,
        conditions: (SolarPosition) -> PvConditions,
        shadingPowerFactor: Double = 1.0,
    ): PvSimulationPoint {
        val sun = SolarCalculator.position(location, instant)
        val (tilt, azimuth) = surfaceOrientation(array, sun)
        val c = conditions(sun)
        if (!sun.isAboveHorizon) {
            return PvSimulationPoint(instant, sun, tilt, azimuth, c.ambientC, false, zero())
        }
        val poa = planeOfArrayIrradiance(c.irradiance, sun, tilt, azimuth, array.albedo)
        val ghi = c.irradiance.ghi(sun)
        val ideal = array.peakPowerW * poa / PvEstimator.STC_IRRADIANCE
        // Rear side sees mostly ground-reflected and diffuse light.
        val bifacial = array.peakPowerW * array.bifacialGain * (ghi * array.albedo + c.irradiance.dhi * 0.5) / PvEstimator.STC_IRRADIANCE

        val cell = c.ambientC?.let { cellTemperature(poa, it, c.windMs) }
        val tempFactor = cell?.let { (1.0 + array.temperatureCoefficient * (it - 25.0)).coerceIn(0.5, 1.2) } ?: TYPICAL_TEMPERATURE_FACTOR
        var p = ideal + bifacial
        val tempLoss = p * (1 - tempFactor); p -= tempLoss
        val covered = isSnowCovered(c, tilt)
        val snowLoss = if (covered) p else 0.0; p -= snowLoss
        val soilingLoss = p * losses.soiling; p -= soilingLoss
        val shadingLoss = p * (1 - shadingPowerFactor.coerceIn(0.0, 1.0)); p -= shadingLoss
        val mismatchLoss = p * losses.mismatch; p -= mismatchLoss
        val degradation = (losses.degradationPerYear * losses.ageYears).coerceIn(0.0, 0.5)
        val degradationLoss = p * degradation; p -= degradationLoss
        val dcWiringLoss = p * losses.dcWiring; p -= dcWiringLoss
        val dcInput = p

        val rated = losses.inverterRatedW ?: losses.inverterLimitW ?: array.peakPowerW
        val ac0 = if (dcInput <= 0) 0.0 else max(0.0, dcInput * losses.inverterPeakEfficiency - losses.inverterSelfConsumption * rated)
        val inverterLoss = dcInput - ac0
        val limit = losses.inverterLimitW ?: Double.MAX_VALUE
        val ac1 = min(ac0, limit)
        val clippingLoss = ac0 - ac1
        val acWiringLoss = ac1 * losses.acWiring
        val ac = ac1 - acWiringLoss
        return PvSimulationPoint(
            instant, sun, tilt, azimuth, cell, covered,
            PvLossBreakdown(poa, ideal, bifacial, tempLoss, snowLoss, soilingLoss, shadingLoss, mismatchLoss, degradationLoss,
                dcWiringLoss, dcInput, inverterLoss, clippingLoss, acWiringLoss, ac),
        )
    }

    /** Energy over a day [Wh] and the summed loss breakdown, sampled every [stepMinutes]. */
    fun daily(
        array: PvArrayConfig,
        losses: LossProfile,
        location: GeoLocation,
        dayStart: Instant,
        conditions: (Instant, SolarPosition) -> PvConditions,
        stepMinutes: Long = 10,
        shading: (Instant) -> Double = { 1.0 },
    ): PvLossBreakdown {
        var sum = zero()
        val h = stepMinutes / 60.0
        var t = dayStart.plusSeconds(stepMinutes * 30)
        val end = dayStart.plusSeconds(86_400)
        while (t.isBefore(end)) {
            val instant = t
            val point = simulate(array, losses, location, instant, { conditions(instant, it) }, shading(instant))
            sum = add(sum, point.losses, h)
            t = t.plusSeconds(stepMinutes * 60)
        }
        return sum
    }

    /** Surface tilt/azimuth for fixed arrays and trackers. */
    fun surfaceOrientation(array: PvArrayConfig, sun: SolarPosition): Pair<Double, Double> = when (array.tracker) {
        TrackerType.FIXED -> array.tiltDeg to array.azimuthDeg
        TrackerType.DUAL_AXIS -> if (sun.isAboveHorizon) min(90.0, sun.zenithDeg) to sun.azimuthDeg else 0.0 to array.azimuthDeg
        TrackerType.SINGLE_AXIS -> {
            if (!sun.isAboveHorizon) 0.0 to 90.0 else {
                // Ideal rotation of a horizontal N–S axis: tan R = tan(zenith) · sin(sunAz − 180°).
                val r = Math.toDegrees(atan(tan(Math.toRadians(sun.zenithDeg)) * sin(Math.toRadians(sun.azimuthDeg - 180.0))))
                    .coerceIn(-array.trackerMaxRotationDeg, array.trackerMaxRotationDeg)
                abs(r) to if (r >= 0) 270.0 else 90.0
            }
        }
    }

    companion object {
        /** Used when the air temperature is unknown (typical annual temperature loss). */
        const val TYPICAL_TEMPERATURE_FACTOR = 0.95
        const val FAIMAN_U0 = 25.0
        const val FAIMAN_U1 = 6.84

        /** Faiman cell temperature with wind; NOCT model (45 °C) without it. */
        fun cellTemperature(poa: Double, ambientC: Double, windMs: Double?): Double =
            if (windMs != null) ambientC + poa / (FAIMAN_U0 + FAIMAN_U1 * windMs.coerceAtLeast(0.0))
            else PvEstimator.cellTemperatureC(poa, ambientC)

        /**
         * Snow cover (ESTIMATED): panels are covered when ≥ 2 cm of snow lies and the air is at or below
         * +1 °C; steep panels (≥ 60°) shed snow. Unknown snow depth = not covered.
         */
        fun isSnowCovered(c: PvConditions, tiltDeg: Double): Boolean {
            val depth = c.snowDepthM ?: return false
            val air = c.ambientC ?: return false
            return depth >= 0.02 && air <= 1.0 && tiltDeg < 60.0
        }

        private fun zero() = PvLossBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        private fun add(a: PvLossBreakdown, b: PvLossBreakdown, h: Double) = PvLossBreakdown(
            a.poaWm2 + b.poaWm2 * h, a.idealDcW + b.idealDcW * h, a.bifacialGainW + b.bifacialGainW * h,
            a.temperatureLossW + b.temperatureLossW * h, a.snowLossW + b.snowLossW * h, a.soilingLossW + b.soilingLossW * h,
            a.shadingLossW + b.shadingLossW * h, a.mismatchLossW + b.mismatchLossW * h, a.degradationLossW + b.degradationLossW * h,
            a.dcWiringLossW + b.dcWiringLossW * h, a.dcInputW + b.dcInputW * h, a.inverterLossW + b.inverterLossW * h,
            a.clippingLossW + b.clippingLossW * h, a.acWiringLossW + b.acWiringLossW * h, a.acOutputW + b.acOutputW * h,
        )
    }
}

