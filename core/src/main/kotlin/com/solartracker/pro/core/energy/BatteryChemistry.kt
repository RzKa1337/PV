package com.solartracker.pro.core.energy

import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.quality.Quantity
import java.time.Instant
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Typical chemistry parameters (manufacturer-independent averages – ESTIMATED; use the datasheet of
 * your battery when available). Resting open-circuit voltages are per cell, at 25 °C, after ≥ 2 h rest.
 */
data class ChemistryProfile(
    val type: BatteryType,
    val cellVoltage: Double,
    /** SOC % → resting cell voltage, ascending. */
    val ocv: List<Pair<Double, Double>>,
    val roundTripEfficiency: Double,
    /** Recommended maximum depth of discharge for long life. */
    val recommendedDod: Double,
    /** Cycles to 80% capacity at [referenceDod]. */
    val cycleLife: Double,
    val referenceDod: Double,
    /** Wöhler exponent: cycles ∝ (DoDref/DoD)^k. */
    val dodExponent: Double,
    val peukertExponent: Double,
    /** Capacity change per °C below 25 °C (fraction). */
    val coldCapacityPerC: Double,
    /** Charging is not allowed below this cell temperature [°C]. */
    val minChargeTempC: Double,
    /** Calendar capacity loss per year (fraction). */
    val calendarLossPerYear: Double,
)

object BatteryChemistry {
    val PROFILES: Map<BatteryType, ChemistryProfile> = mapOf(
        BatteryType.LIFEPO4 to ChemistryProfile(
            BatteryType.LIFEPO4, 3.2,
            listOf(0.0 to 2.50, 10.0 to 3.00, 20.0 to 3.20, 30.0 to 3.25, 50.0 to 3.28, 70.0 to 3.31, 90.0 to 3.34, 100.0 to 3.40),
            0.95, 0.9, 6000.0, 0.8, 1.2, 1.03, 0.003, 0.0, 0.02,
        ),
        BatteryType.LI_ION to ChemistryProfile(
            BatteryType.LI_ION, 3.6,
            listOf(0.0 to 3.00, 10.0 to 3.45, 20.0 to 3.55, 50.0 to 3.70, 80.0 to 3.95, 100.0 to 4.15),
            0.93, 0.8, 2500.0, 0.8, 1.3, 1.05, 0.004, 0.0, 0.03,
        ),
        BatteryType.AGM to ChemistryProfile(
            BatteryType.AGM, 2.0,
            listOf(0.0 to 1.93, 10.0 to 1.96, 20.0 to 1.98, 30.0 to 2.00, 40.0 to 2.02, 50.0 to 2.04, 60.0 to 2.06, 70.0 to 2.08, 80.0 to 2.10, 90.0 to 2.12, 100.0 to 2.14),
            0.85, 0.5, 600.0, 0.5, 1.5, 1.12, 0.01, -20.0, 0.04,
        ),
        BatteryType.GEL to ChemistryProfile(
            BatteryType.GEL, 2.0,
            listOf(0.0 to 1.94, 10.0 to 1.97, 20.0 to 1.99, 30.0 to 2.01, 40.0 to 2.03, 50.0 to 2.05, 60.0 to 2.07, 70.0 to 2.09, 80.0 to 2.11, 90.0 to 2.12, 100.0 to 2.14),
            0.85, 0.6, 1000.0, 0.5, 1.4, 1.15, 0.01, -20.0, 0.03,
        ),
        BatteryType.LEAD_ACID to ChemistryProfile(
            BatteryType.LEAD_ACID, 2.0,
            listOf(0.0 to 1.88, 10.0 to 1.92, 20.0 to 1.94, 30.0 to 1.97, 40.0 to 1.99, 50.0 to 2.02, 60.0 to 2.04, 70.0 to 2.06, 80.0 to 2.08, 90.0 to 2.10, 100.0 to 2.12),
            0.80, 0.5, 400.0, 0.5, 1.5, 1.25, 0.01, -20.0, 0.05,
        ),
        // "Custom/other": conservative lead-acid-like values – enter datasheet values when known.
        BatteryType.OTHER to ChemistryProfile(
            BatteryType.OTHER, 2.0,
            listOf(0.0 to 1.88, 10.0 to 1.92, 20.0 to 1.94, 30.0 to 1.97, 40.0 to 1.99, 50.0 to 2.02, 60.0 to 2.04, 70.0 to 2.06, 80.0 to 2.08, 90.0 to 2.10, 100.0 to 2.12),
            0.80, 0.5, 400.0, 0.5, 1.5, 1.25, 0.01, -20.0, 0.05,
        ),
    )

    fun profile(type: BatteryType): ChemistryProfile = PROFILES.getValue(type)

    /**
     * SOC from a RESTING pack voltage (ESTIMATED, ± uncertainty). Under load or right after charging the
     * voltage is not at rest and the result is marked UNKNOWN. LiFePO4's flat curve gives a wide band.
     */
    fun socFromRestingVoltage(type: BatteryType, packVoltage: Double, nominalPackVoltage: Double, atRest: Boolean, time: Instant? = null): Quantity {
        if (!atRest) return Quantity.unknown("%", "napięcie baterii", "napięcie pod obciążeniem – nie odzwierciedla SOC")
        val p = profile(type)
        val cells = (nominalPackVoltage / p.cellVoltage).let { Math.round(it).toInt() }.coerceAtLeast(1)
        val v = packVoltage / cells
        val soc = interpolateSoc(p.ocv, v)
        // Uncertainty from the local slope: ±10 mV/cell measurement & temperature error.
        val slope = slopeAt(p.ocv, soc) // V per % SOC
        val unc = if (slope <= 0) 50.0 else (0.010 / slope).coerceIn(2.0, 50.0)
        return Quantity(soc, "%", DataKind.ESTIMATED, "napięcie spoczynkowe ($cells ogniw ${type.name})", time, confidence = (1 - unc / 50).coerceIn(0.0, 1.0), uncertainty = unc)
    }

    private fun interpolateSoc(ocv: List<Pair<Double, Double>>, v: Double): Double {
        if (v <= ocv.first().second) return 0.0
        if (v >= ocv.last().second) return 100.0
        for (i in 1 until ocv.size) {
            val (s0, v0) = ocv[i - 1]
            val (s1, v1) = ocv[i]
            if (v <= v1) return s0 + (s1 - s0) * (v - v0) / (v1 - v0)
        }
        return 100.0
    }

    private fun slopeAt(ocv: List<Pair<Double, Double>>, soc: Double): Double {
        val i = ocv.indexOfFirst { it.first >= soc }.coerceIn(1, ocv.size - 1)
        val (s0, v0) = ocv[i - 1]
        val (s1, v1) = ocv[i]
        return (v1 - v0) / (s1 - s0)
    }

    /** Peukert: usable capacity at a discharge power relative to the 20-hour rate (lead-acid strongly affected). */
    fun effectiveCapacityKwh(type: BatteryType, nominalKwh: Double, dischargeKw: Double, cellTempC: Double? = null): Double {
        val p = profile(type)
        val c20Power = nominalKwh / 20.0
        val rate = if (dischargeKw <= c20Power) 1.0 else (c20Power / dischargeKw).pow(p.peukertExponent - 1.0)
        val cold = cellTempC?.let { if (it < 25.0) 1.0 - p.coldCapacityPerC * (25.0 - it) else 1.0 } ?: 1.0
        return nominalKwh * rate * cold.coerceIn(0.3, 1.0)
    }

    fun canCharge(type: BatteryType, cellTempC: Double?): Boolean = cellTempC == null || cellTempC >= profile(type).minChargeTempC

    /** Expected cycles to 80% capacity at a given depth of discharge (ESTIMATED). */
    fun cycleLife(type: BatteryType, dod: Double): Double {
        val p = profile(type)
        val d = dod.coerceIn(0.05, 1.0)
        return p.cycleLife * (p.referenceDod / d).pow(p.dodExponent)
    }
}

/** Ageing bookkeeping: equivalent full cycles, wear and state of health (ESTIMATED). */
data class BatteryAging(
    val equivalentFullCycles: Double,
    val averageDod: Double,
    val cycleWear: Double,
    val calendarWear: Double,
    /** Estimated capacity relative to new (1.0 = new, 0.8 = end of life by convention). */
    val stateOfHealth: Double,
    val remainingCycles: Double,
)

object BatteryAgingModel {
    /**
     * @param throughputKwh total discharged energy
     * @param averageDod average depth of discharge of the cycles (0..1)
     */
    fun estimate(type: BatteryType, usableKwh: Double, throughputKwh: Double, averageDod: Double, ageYears: Double): BatteryAging {
        val efc = if (usableKwh > 0) throughputKwh / usableKwh else 0.0
        val life = BatteryChemistry.cycleLife(type, averageDod)
        val cycleWear = (efc / life).coerceAtLeast(0.0) // 1.0 = reached 80%
        val calendar = BatteryChemistry.profile(type).calendarLossPerYear * ageYears.coerceAtLeast(0.0)
        val soh = (1.0 - 0.2 * cycleWear - calendar).coerceIn(0.0, 1.0)
        return BatteryAging(efc, averageDod, cycleWear, calendar, soh, max(0.0, life - efc))
    }
}

/** Time to full / to minimum SOC at the current power, with the constant-voltage taper near full. */
object BatteryTiming {
    /** Hours until [maxSoc] at [chargeKw] (> 0); charge slows above 90% (CV phase, ×2 time). */
    fun hoursToFull(battery: BatteryStorage, socPercent: Double, chargeKw: Double): Double? {
        if (chargeKw <= 0.01 || socPercent >= battery.maxSocPercent) return if (socPercent >= battery.maxSocPercent) 0.0 else null
        val eff = battery.chargeEfficiency
        val bulkEnd = min(90.0, battery.maxSocPercent)
        val bulk = max(0.0, bulkEnd - socPercent) / 100 * battery.usableCapacityKwh / (chargeKw * eff)
        val cv = max(0.0, battery.maxSocPercent - max(socPercent, bulkEnd)) / 100 * battery.usableCapacityKwh / (chargeKw * eff) * 2
        return bulk + cv
    }

    fun hoursToMinimum(battery: BatteryStorage, socPercent: Double, dischargeKw: Double): Double? {
        if (dischargeKw <= 0.01) return null
        val energy = max(0.0, socPercent - battery.minSocPercent) / 100 * battery.usableCapacityKwh * battery.dischargeEfficiency
        return energy / dischargeKw
    }
}

/**
 * Estimates real usable capacity from history (coulomb/energy counting) over segments where SOC moved
 * by ≥ [minSocSwing] pp in one direction: capacity = energy / ΔSOC.
 */
object BatteryCapacityEstimator {
    data class Segment(val socFrom: Double, val socTo: Double, val chargedKwh: Double, val dischargedKwh: Double)

    fun estimateKwh(segments: List<Segment>, chargeEfficiency: Double, dischargeEfficiency: Double, minSocSwing: Double = 20.0): Double? {
        val values = segments.mapNotNull { s ->
            val d = s.socTo - s.socFrom
            when {
                d >= minSocSwing && s.chargedKwh > 0 -> (s.chargedKwh - s.dischargedKwh) * chargeEfficiency / (d / 100)
                d <= -minSocSwing && s.dischargedKwh > 0 -> (s.dischargedKwh - s.chargedKwh) / dischargeEfficiency / (-d / 100)
                else -> null
            }
        }.filter { it > 0 }
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
