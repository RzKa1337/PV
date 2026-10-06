package com.solartracker.pro.core.fixtures

import com.solartracker.pro.core.inverter.SmgRawBlocks
import com.solartracker.pro.core.inverter.SmgRegisterMap
import kotlin.math.roundToInt

/**
 * Synthetic SMG register snapshots for typical situations of a 6.2 kW / 48 V hybrid inverter.
 * Built with the community register map (SmgRegisterMap) – they test decoding and analysis logic,
 * they are NOT recordings from a real device (REAL_DEVICE_VALIDATION_REQUIRED).
 */
object AnenjiFixtures {
    data class Snapshot(
        val pvV: Double = 0.0, val pvA: Double = 0.0, val pvW: Int = 0, val pvChargeW: Int = 0,
        val batV: Double = 52.0, val batA: Double = 0.0, val batW: Int = 0, val soc: Int = 60,
        val gridV: Double = 0.0, val gridW: Int = 0, val loadW: Int = 400, val loadPct: Int = 7,
        val mode: Int = 3, val invTemp: Int = 35, val dcdcTemp: Int = 32,
        val warningBits: Long = 0, val faultBits: Long = 0,
    )

    private fun u16(v: Int) = v and 0xFFFF

    fun blocks(s: Snapshot): SmgRawBlocks {
        val live = IntArray(SmgRegisterMap.LIVE_COUNT)
        fun set(reg: Int, v: Int) { live[reg - SmgRegisterMap.LIVE_START] = u16(v) }
        set(SmgRegisterMap.OPERATING_MODE, s.mode)
        set(SmgRegisterMap.GRID_VOLTAGE, (s.gridV * 10).roundToInt())
        set(SmgRegisterMap.GRID_FREQUENCY, if (s.gridV > 0) 5000 else 0)
        set(SmgRegisterMap.GRID_POWER, s.gridW)
        set(SmgRegisterMap.INVERTER_POWER, s.loadW)
        set(SmgRegisterMap.OUTPUT_VOLTAGE, 2300)
        set(SmgRegisterMap.OUTPUT_CURRENT, (s.loadW / 230.0 * 10).roundToInt())
        set(SmgRegisterMap.OUTPUT_FREQUENCY, 5000)
        set(SmgRegisterMap.OUTPUT_POWER, s.loadW)
        set(SmgRegisterMap.OUTPUT_VA, (s.loadW * 1.1).roundToInt())
        set(SmgRegisterMap.BATTERY_VOLTAGE, (s.batV * 10).roundToInt())
        set(SmgRegisterMap.BATTERY_AVERAGE_CURRENT, (s.batA * 10).roundToInt())
        set(SmgRegisterMap.BATTERY_AVERAGE_POWER, s.batW)
        set(SmgRegisterMap.PV_VOLTAGE, (s.pvV * 10).roundToInt())
        set(SmgRegisterMap.PV_CURRENT, (s.pvA * 10).roundToInt())
        set(SmgRegisterMap.PV_POWER, s.pvW)
        set(SmgRegisterMap.PV_CHARGING_POWER, s.pvChargeW)
        set(SmgRegisterMap.LOAD_PERCENT, s.loadPct)
        set(SmgRegisterMap.DCDC_TEMPERATURE, s.dcdcTemp)
        set(SmgRegisterMap.INVERTER_TEMPERATURE, s.invTemp)
        set(SmgRegisterMap.BATTERY_PERCENT, s.soc)
        set(SmgRegisterMap.BATTERY_CURRENT, (s.batA * 10).roundToInt())
        val status = IntArray(SmgRegisterMap.STATUS_COUNT)
        status[SmgRegisterMap.FAULT_CODE - SmgRegisterMap.STATUS_START] = ((s.faultBits shr 16) and 0xFFFF).toInt()
        status[SmgRegisterMap.FAULT_CODE - SmgRegisterMap.STATUS_START + 1] = (s.faultBits and 0xFFFF).toInt()
        status[SmgRegisterMap.WARNING_CODE - SmgRegisterMap.STATUS_START] = ((s.warningBits shr 16) and 0xFFFF).toInt()
        status[SmgRegisterMap.WARNING_CODE - SmgRegisterMap.STATUS_START + 1] = (s.warningBits and 0xFFFF).toInt()
        return SmgRawBlocks(status, live)
    }

    /** Clear summer noon: ~3.9 kW PV, battery charging, off-grid. */
    val CLEAR_SUMMER_NOON = Snapshot(pvV = 380.0, pvA = 10.3, pvW = 3914, pvChargeW = 3300, batV = 54.8, batA = 60.0, batW = 3288, soc = 72, loadW = 520)
    /** Cloudy summer noon: ~0.9 kW PV. */
    val CLOUDY_SUMMER = Snapshot(pvV = 350.0, pvA = 2.6, pvW = 910, pvChargeW = 400, batV = 52.6, batA = 7.6, batW = 400, soc = 55, loadW = 480)
    /** Winter noon: low sun, ~1.2 kW. */
    val WINTER_NOON = Snapshot(pvV = 395.0, pvA = 3.0, pvW = 1185, pvChargeW = 700, batV = 51.8, batA = 13.5, batW = 700, soc = 41, loadW = 450, invTemp = 18)
    /** Daylight but PV reads exactly 0 (breaker off / string fault). */
    val ZERO_PV_DAY = Snapshot(pvV = 0.0, pvA = 0.0, pvW = 0, batV = 50.9, batA = -9.0, batW = -460, soc = 48, loadW = 450)
    /** Night, battery low and discharging. */
    val BATTERY_LOW = Snapshot(batV = 47.6, batA = -12.0, batW = -571, soc = 14, loadW = 550, warningBits = 1L shl 8)
    /** Battery full, PV limited to the load. */
    val BATTERY_FULL = Snapshot(pvV = 400.0, pvA = 1.6, pvW = 640, pvChargeW = 30, batV = 56.4, batA = 0.5, batW = 28, soc = 100, loadW = 600)
    /** High load from battery + PV. */
    val HIGH_LOAD = Snapshot(pvV = 370.0, pvA = 6.2, pvW = 2294, batV = 50.2, batA = -60.0, batW = -3012, soc = 52, loadW = 5200, loadPct = 84, invTemp = 61)
    /** Decoding problem: impossible values (wrong map / byte order). */
    val GARBAGE = Snapshot(pvV = 6553.0, pvA = 0.0, pvW = 30000, batV = 120.0, soc = 100, loadW = 400)
}
