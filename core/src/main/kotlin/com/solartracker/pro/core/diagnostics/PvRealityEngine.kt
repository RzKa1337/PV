package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.pv.PvLossBreakdown
import com.solartracker.pro.core.pv.PvSimulationEngine
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.quality.Quantity
import com.solartracker.pro.core.solar.GeoLocation
import java.time.Instant
import kotlin.math.abs

/** One step of the PV loss chain, in the order the losses occur. */
enum class LossStep(val label: String) {
    AOI("Kąt padania (AOI)"),
    TEMPERATURE("Temperatura"),
    SPECTRAL("Widmo"),
    SNOW("Śnieg"),
    SOILING("Zabrudzenie"),
    SHADING("Zacienienie"),
    MISMATCH("Mismatch"),
    DEGRADATION("Degradacja"),
    DC_WIRING("Okablowanie DC"),
    MPPT("MPPT"),
    INVERTER("Falownik"),
    CLIPPING("Clipping"),
    AC_WIRING("Okablowanie AC"),
    CALIBRATION("Korekta kalibracji"),
    UNEXPLAINED("Niewyjaśniona"),
}

/**
 * One loss in watts (positive = power lost). [modelled] = false means the step is part of the chain but has
 * no input data (shown as "nie modelowane", never as a made-up number).
 */
data class LossItem(
    val step: LossStep,
    val watts: Double,
    val percentOfTheoretical: Double,
    val kind: DataKind,
    val basis: String,
    val modelled: Boolean = true,
)

/** Where the irradiance behind "expected" comes from – the largest source of uncertainty without a sensor. */
enum class IrradianceBasis(val label: String, val baseConfidence: Double) {
    SENSOR("czujnik nasłonecznienia", 0.95),
    FORECAST("prognoza pogody (Open-Meteo)", 0.65),
    CLIMATE("średnie klimatyczne", 0.30),
    CLEAR_SKY("model bezchmurnego nieba", 0.20),
}

enum class RealityStatus(val label: String) {
    OK("Produkcja zgodna z oczekiwaną"),
    BELOW("Produkcja poniżej oczekiwanej"),
    ABOVE("Produkcja powyżej oczekiwanej"),
    NO_MEASUREMENT("Brak aktualnego pomiaru"),
    LOW_LIGHT("Za mało światła do oceny"),
    NIGHT("Noc"),
}

/**
 * "How much SHOULD the array produce now, how much does it produce and why is there a difference?"
 *
 * theoreticalW − Σ modelled losses (± calibration) = expectedW; actualW − expectedW = −unexplainedW.
 */
data class PvReality(
    val time: Instant,
    val poaWm2: Double,
    val cellTemperatureC: Double?,
    /** Array power at this irradiance at 25 °C without any loss (+ bifacial rear gain) [W]. */
    val theoreticalW: Double,
    val losses: List<LossItem>,
    /** Model output after all modelled losses and the calibration correction [W]. */
    val expectedW: Double,
    val actual: Quantity?,
    /** Model − actual not explained by any modelled loss [W] (negative = better than the model). */
    val unexplainedW: Double?,
    /** (actual − expected) / expected [%]. */
    val deviationPercent: Double?,
    /** Relative uncertainty of [expectedW] (± fraction) – the band within which a deviation is not meaningful. */
    val uncertainty: Double,
    val confidence: Double,
    val irradianceBasis: IrradianceBasis,
    val status: RealityStatus,
    val breakdown: PvLossBreakdown,
) {
    val actualW: Double? get() = actual?.value
    val deviationW: Double? get() = actualW?.let { it - expectedW }
}

/**
 * Builds [PvReality] on top of the existing loss chain ([PvSimulationEngine]) – no second PV model.
 * Measured power must be a fresh, validated value; simulated data is accepted but keeps [DataKind.SIMULATED].
 */
object PvRealityEngine {
    private val engine = PvSimulationEngine()

    /** Below this share of the array peak the comparison is dominated by noise. */
    const val MIN_THEORETICAL_SHARE = 0.08

    /**
     * @param actual measured AC PV power (MEASURED/SIMULATED); STALE/LAST_KNOWN values are not compared
     * @param shadingFactor power factor from the shading model; [shadingConfidence] its confidence 0..1
     * @param calibrationFactor learned real/model factor; applied only when [calibrationConfidence] > 0
     * @param clearSkyIndex forecast GHI / clear-sky GHI for the hour (broken clouds = less confidence)
     */
    fun assess(
        array: PvArrayConfig,
        losses: LossProfile,
        location: GeoLocation,
        time: Instant,
        conditions: PvConditions,
        actual: Quantity?,
        irradianceBasis: IrradianceBasis,
        shadingFactor: Double = 1.0,
        shadingConfidence: Double = 0.5,
        calibrationFactor: Double = 1.0,
        calibrationConfidence: Double = 0.0,
        clearSkyIndex: Double? = null,
    ): PvReality {
        val point = engine.simulate(array, losses, location, time, { conditions }, shadingFactor.coerceIn(0.0, 1.0))
        val b = point.losses
        val theoretical = b.idealDcW + b.bifacialGainW
        fun pct(w: Double) = if (theoretical > 0) w / theoretical * 100 else 0.0
        val assumed = "założenie z profilu strat"
        val items = mutableListOf(
            LossItem(LossStep.AOI, b.aoiLossW, pct(b.aoiLossW), DataKind.ESTIMATED, "model IAM (ASHRAE b₀ = ${array.iamB0})"),
            LossItem(LossStep.TEMPERATURE, b.temperatureLossW, pct(b.temperatureLossW), DataKind.ESTIMATED,
                if (conditions.ambientC != null) "model temperatury ogniw" else "typowa strata (brak temperatury powietrza)"),
            LossItem(LossStep.SPECTRAL, 0.0, 0.0, DataKind.UNAVAILABLE, "brak danych widmowych – nie modelowane", modelled = false),
            LossItem(LossStep.SNOW, b.snowLossW, pct(b.snowLossW), DataKind.ESTIMATED, "prognoza śniegu"),
            LossItem(LossStep.SOILING, b.soilingLossW, pct(b.soilingLossW), DataKind.ESTIMATED, assumed),
            LossItem(LossStep.SHADING, b.shadingLossW, pct(b.shadingLossW), DataKind.ESTIMATED, "model zacienienia"),
            LossItem(LossStep.MISMATCH, b.mismatchLossW, pct(b.mismatchLossW), DataKind.ESTIMATED, assumed),
            LossItem(LossStep.DEGRADATION, b.degradationLossW, pct(b.degradationLossW), DataKind.ESTIMATED, assumed),
            LossItem(LossStep.DC_WIRING, b.dcWiringLossW, pct(b.dcWiringLossW), DataKind.ESTIMATED, assumed),
            LossItem(LossStep.MPPT, b.mpptLossW, pct(b.mpptLossW), DataKind.ESTIMATED, assumed),
            LossItem(LossStep.INVERTER, b.inverterLossW, pct(b.inverterLossW), DataKind.ESTIMATED, "sprawność falownika"),
            LossItem(LossStep.CLIPPING, b.clippingLossW, pct(b.clippingLossW), DataKind.CALCULATED, "limit mocy falownika"),
            LossItem(LossStep.AC_WIRING, b.acWiringLossW, pct(b.acWiringLossW), DataKind.ESTIMATED, assumed),
        )
        val calibrated = calibrationConfidence > 0 && calibrationFactor.isFinite() && calibrationFactor > 0
        val expected = if (calibrated) b.acOutputW * calibrationFactor else b.acOutputW
        if (calibrated && abs(expected - b.acOutputW) > 1e-9) {
            // Positive watts = the calibration lowers the expectation (the model was optimistic).
            items += LossItem(LossStep.CALIBRATION, b.acOutputW - expected, pct(b.acOutputW - expected), DataKind.CALCULATED,
                "kalibracja z historii pomiarów (×${"%.3f".format(calibrationFactor)})")
        }

        val uncertainty = uncertainty(irradianceBasis, clearSkyIndex, shadingConfidence, if (calibrated) calibrationConfidence else 0.0)
        val peak = array.peakPowerW
        val usableMeasurement = actual?.takeIf { it.value != null && (it.kind == DataKind.MEASURED || it.kind == DataKind.SIMULATED) }
        val lightOk = theoretical >= peak * MIN_THEORETICAL_SHARE
        val status = when {
            !point.sun.isAboveHorizon -> RealityStatus.NIGHT
            !lightOk -> RealityStatus.LOW_LIGHT
            usableMeasurement == null -> RealityStatus.NO_MEASUREMENT
            else -> {
                val dev = (usableMeasurement.value!! - expected) / expected
                when {
                    dev < -maxOf(uncertainty, MIN_BAND) -> RealityStatus.BELOW
                    dev > maxOf(uncertainty, MIN_BAND) -> RealityStatus.ABOVE
                    else -> RealityStatus.OK
                }
            }
        }
        val compared = status == RealityStatus.OK || status == RealityStatus.BELOW || status == RealityStatus.ABOVE
        val actualW = usableMeasurement?.value
        val unexplained = if (compared) expected - actualW!! else null
        if (unexplained != null) items += LossItem(LossStep.UNEXPLAINED, unexplained, pct(unexplained), DataKind.CALCULATED, "model − pomiar")
        val confidence = if (!compared) 0.0 else (1.0 - uncertainty).coerceIn(0.05, 0.98) *
            (if (usableMeasurement?.kind == DataKind.SIMULATED) 0.5 else 1.0)
        return PvReality(
            time = time,
            poaWm2 = b.poaWm2,
            cellTemperatureC = point.cellTemperatureC,
            theoreticalW = theoretical,
            losses = items,
            expectedW = expected,
            actual = actual,
            unexplainedW = unexplained,
            deviationPercent = if (compared) (actualW!! - expected) / expected * 100 else null,
            uncertainty = uncertainty,
            confidence = confidence,
            irradianceBasis = irradianceBasis,
            status = status,
            breakdown = b,
        )
    }

    /** Minimum band so measurement and rounding noise never count as a deviation. */
    const val MIN_BAND = 0.05

    /**
     * Relative uncertainty of the expectation: irradiance source (largest term), broken clouds (forecast
     * hours averaged over clouds that come and go), shading model quality and calibration quality.
     */
    fun uncertainty(basis: IrradianceBasis, clearSkyIndex: Double?, shadingConfidence: Double, calibrationConfidence: Double): Double {
        var u = 1.0 - basis.baseConfidence
        if (basis != IrradianceBasis.SENSOR && clearSkyIndex != null && clearSkyIndex in 0.3..0.85) u += 0.15
        u += (1.0 - shadingConfidence.coerceIn(0.0, 1.0)) * 0.08
        u *= 1.0 - 0.3 * calibrationConfidence.coerceIn(0.0, 1.0)
        return u.coerceIn(MIN_BAND, 0.9)
    }
}
