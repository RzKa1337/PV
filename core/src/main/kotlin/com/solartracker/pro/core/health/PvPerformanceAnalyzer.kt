package com.solartracker.pro.core.health

import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.pv.PvSimulationEngine
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import java.time.Instant

/** Possible reason for a gap between expected and actual PV power. */
enum class LossCause(val label: String) {
    TEMPERATURE("Temperatura"),
    SHADING("Zacienienie"),
    SOILING("Zabrudzenie"),
    MISMATCH("Mismatch"),
    SNOW("Śnieg"),
    DEGRADATION("Degradacja"),
    WIRING("Okablowanie"),
    INVERTER("Falownik (sprawność)"),
    CLIPPING("Ograniczenie mocy (clipping)"),
    UNKNOWN("Nieznane"),
}

/** One estimated loss as a share of the expected power. Never a measurement. */
data class LossShare(val cause: LossCause, val percent: Double, val kind: DataKind, val basis: String)

data class PerformanceReport(
    val time: Instant,
    /** Power the array should give at this irradiance at 25 °C without losses [W] (weather-aware). */
    val expectedW: Double,
    val actualW: Double?,
    /** Model output after all modelled losses [W]. */
    val modelOutputW: Double,
    /** actual / expected [%]; null without a fresh measurement or at low light. */
    val performancePercent: Double?,
    val losses: List<LossShare>,
    /** Context: irradiance lost to weather compared with a clear sky [%] (not part of performance). */
    val weatherVsClearSkyPercent: Double?,
    val status: String,
)

/**
 * PV performance analysis: compares expected and actual power and splits the difference into modelled
 * losses (temperature, shading, soiling, mismatch, …) and an unexplained remainder. All shares are
 * ESTIMATED – soiling and mismatch come from the configured loss profile, not from a measurement.
 */
object PvPerformanceAnalyzer {
    private val engine = PvSimulationEngine()

    /**
     * @param conditions weather-based irradiance and temperature for [time]
     * @param clearSkyConditions clear-sky irradiance for the same time (for the weather context), optional
     * @param actualW fresh, validated measured PV power; null when not available
     * @param shadingFactor shading power factor from the shading model (1 = unshaded)
     * @param fresh false when the link is lost or data is stale
     */
    fun analyze(
        array: PvArrayConfig,
        losses: LossProfile,
        location: GeoLocation,
        time: Instant,
        conditions: PvConditions,
        actualW: Double?,
        shadingFactor: Double = 1.0,
        fresh: Boolean = true,
        clearSkyConditions: PvConditions? = null,
        minExpectedW: Double = 150.0,
    ): PerformanceReport {
        val point = engine.simulate(array, losses, location, time, { conditions }, shadingFactor)
        val b = point.losses
        val expected = b.idealDcW + b.bifacialGainW
        val weather = clearSkyConditions?.let { cs ->
            val clear = engine.simulate(array, losses, location, time, { cs }, shadingFactor).losses.let { it.idealDcW + it.bifacialGainW }
            if (clear > minExpectedW) ((clear - expected) / clear * 100).coerceIn(0.0, 100.0) else null
        }
        fun report(status: String, actual: Double? = null, perf: Double? = null, shares: List<LossShare> = emptyList()) =
            PerformanceReport(time, expected, actual, b.acOutputW, perf, shares, weather, status)

        if (expected < minExpectedW) return report("Za mało światła do oceny wydajności")
        if (!fresh || actualW == null) return report("Brak aktualnego pomiaru – komunikacja z falownikiem", actualW)

        fun pct(w: Double) = w / expected * 100
        val assumed = "założenie z profilu strat"
        val model = "model"
        val shares = buildList {
            add(LossShare(LossCause.TEMPERATURE, pct(b.temperatureLossW), DataKind.ESTIMATED, "$model (temperatura ogniw)"))
            add(LossShare(LossCause.SHADING, pct(b.shadingLossW), DataKind.ESTIMATED, "$model zacienienia"))
            add(LossShare(LossCause.SOILING, pct(b.soilingLossW), DataKind.ESTIMATED, assumed))
            add(LossShare(LossCause.MISMATCH, pct(b.mismatchLossW), DataKind.ESTIMATED, assumed))
            add(LossShare(LossCause.SNOW, pct(b.snowLossW), DataKind.ESTIMATED, model))
            add(LossShare(LossCause.DEGRADATION, pct(b.degradationLossW), DataKind.ESTIMATED, assumed))
            add(LossShare(LossCause.WIRING, pct(b.dcWiringLossW + b.acWiringLossW), DataKind.ESTIMATED, assumed))
            add(LossShare(LossCause.INVERTER, pct(b.inverterLossW), DataKind.ESTIMATED, model))
            add(LossShare(LossCause.CLIPPING, pct(b.clippingLossW), DataKind.ESTIMATED, "limit falownika"))
        }.filter { it.percent > 0.05 }
        // Remainder between the model output and the measurement (may be negative: better than the model).
        val unknown = pct(b.acOutputW - actualW)
        val all = shares + LossShare(LossCause.UNKNOWN, unknown, DataKind.CALCULATED, "pomiar − model po stratach")
        val perf = actualW / expected * 100
        val status = when {
            unknown > 10 -> "Produkcja poniżej modelu o ${"%.1f".format(unknown)} p.p. – przyczyna nieznana"
            unknown < -10 -> "Produkcja powyżej modelu – straty w profilu mogą być zawyżone"
            else -> "Produkcja zgodna z modelem"
        }
        return report(status, actualW, perf, all)
    }
}
