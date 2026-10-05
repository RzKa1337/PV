package com.solartracker.pro.core.analytics

import com.solartracker.pro.core.inverter.InverterTelemetry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Power flows [W] between PV, battery, grid and load derived from MEASURED totals.
 * The split itself (which source feeds which sink) is CALCULATED with the usual priority
 * PV → load → battery → grid, the same rules as the model in EnergyFlowSimulator.
 */
data class RealEnergyFlow(
    val pvToLoad: Double,
    val pvToBattery: Double,
    val pvToGrid: Double,
    val batteryToLoad: Double,
    val gridToLoad: Double,
    val gridToBattery: Double,
    /** Sources − sinks: conversion losses or inconsistent readings. */
    val residualW: Double,
    /** True when the measured totals balance within tolerance. */
    val consistent: Boolean,
    /** Fields that were missing (flow cannot be complete). */
    val missing: List<String>,
) {
    val active: List<Pair<String, Double>>
        get() = listOf(
            "PV → odbiory" to pvToLoad, "PV → bateria" to pvToBattery, "PV → sieć" to pvToGrid,
            "Bateria → odbiory" to batteryToLoad, "Sieć → odbiory" to gridToLoad, "Sieć → bateria" to gridToBattery,
        ).filter { it.second >= THRESHOLD_W }

    companion object {
        const val THRESHOLD_W = 20.0

        /** Inverter self-consumption and conversion losses make an exact balance impossible. */
        fun tolerance(throughputW: Double) = max(150.0, 0.12 * throughputW)

        fun from(t: InverterTelemetry): RealEnergyFlow {
            val missing = buildList {
                if (t.pv.powerW == null) add("moc PV")
                if (t.load.powerW == null) add("moc odbiorów")
                if (t.battery.powerW == null && t.battery.connected != false) add("moc baterii")
                if (t.grid.powerW == null) add("moc sieci")
            }
            return decompose(
                pvW = t.pv.powerW ?: 0.0,
                loadW = t.load.powerW ?: 0.0,
                batteryW = t.battery.powerW ?: 0.0,
                gridW = t.grid.powerW ?: 0.0,
                missing = missing,
            )
        }

        /**
         * @param batteryW + charging, − discharging
         * @param gridW + import, − export
         */
        fun decompose(pvW: Double, loadW: Double, batteryW: Double, gridW: Double, missing: List<String> = emptyList()): RealEnergyFlow {
            val pv = max(0.0, pvW)
            val load = max(0.0, loadW)
            val charge = max(0.0, batteryW)
            val discharge = max(0.0, -batteryW)
            val import = max(0.0, gridW)
            val export = max(0.0, -gridW)

            val pvToLoad = min(pv, load)
            var loadLeft = load - pvToLoad
            val batteryToLoad = min(discharge, loadLeft)
            loadLeft -= batteryToLoad
            val gridToLoad = min(import, loadLeft)

            var pvLeft = pv - pvToLoad
            val pvToBattery = min(pvLeft, charge)
            pvLeft -= pvToBattery
            val gridToBattery = min(max(0.0, charge - pvToBattery), max(0.0, import - gridToLoad))
            val pvToGrid = min(pvLeft, export)

            val sources = pv + discharge + import
            val sinks = load + charge + export
            val residual = sources - sinks
            return RealEnergyFlow(
                pvToLoad, pvToBattery, pvToGrid, batteryToLoad, gridToLoad, gridToBattery,
                residualW = residual,
                consistent = missing.isEmpty() && abs(residual) <= tolerance(max(sources, sinks)),
                missing = missing,
            )
        }
    }
}
