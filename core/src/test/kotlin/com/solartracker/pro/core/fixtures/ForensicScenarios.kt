package com.solartracker.pro.core.fixtures

import com.solartracker.pro.core.anenji.AnalysisContext
import com.solartracker.pro.core.anenji.AnalyzerSample
import com.solartracker.pro.core.anenji.AnenjiSettingsSnapshot
import com.solartracker.pro.core.anenji.Channel
import com.solartracker.pro.core.anenji.CommError
import com.solartracker.pro.core.anenji.CommRecord
import com.solartracker.pro.core.anenji.ContextProvider
import com.solartracker.pro.core.anenji.DataOrigin
import com.solartracker.pro.core.anenji.RawRef
import com.solartracker.pro.core.anenji.SettingKey
import com.solartracker.pro.core.inverter.MpptReading
import com.solartracker.pro.core.inverter.OperatingMode
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.sin

/**
 * SIMULATED forensic scenarios. Each takes the 30-day SIMULATED history of [AnalyzerFixtures] (origin SIMULATOR) and
 * injects one known fault on day [DAY] – the expected diagnosis is known by construction. Not recordings of a device.
 */
object ForensicScenarios {
    const val DAY = 27 // a normal (not cloudy, not spike) day
    const val INVERTER_VA = 6200.0

    enum class Scenario {
        SCENARIO_NORMAL, SCENARIO_LOW_PV, SCENARIO_MPPT_FAULT, SCENARIO_LOW_BATTERY, SCENARIO_HIGH_LOAD, SCENARIO_GRID_OUTAGE,
        SCENARIO_COMMUNICATION_FAILURE, SCENARIO_INVERTER_RESTART, SCENARIO_CONFIGURATION_CHANGE,
    }

    data class Data(
        val scenario: Scenario,
        val samples: List<AnalyzerSample>,
        val comm: List<CommRecord>,
        val settings: List<AnenjiSettingsSnapshot>,
    )

    fun at(hours: Double): Instant = AnalyzerFixtures.START.plus(Duration.ofDays(DAY.toLong())).plus(Duration.ofSeconds((hours * 3600).toLong()))
    val dayStart: Instant get() = at(0.0)
    val dayEnd: Instant get() = at(23.0 + 55.0 / 60)

    /** Model = the fixture's clear-sky curve; weather reported for cloudy days; no shading, no snow. */
    val context = ContextProvider { t ->
        val day = Duration.between(AnalyzerFixtures.START, t).toDays().toInt()
        val h = (t.epochSecond % 86_400) / 3600.0
        val clear = if (h in 6.0..18.0) AnalyzerFixtures.PV_PEAK_W * sin(PI * (h - 6) / 12) else 0.0
        AnalysisContext(expectedPvW = clear * if (AnalyzerFixtures.cloudy(day)) 0.3 else 1.0, cloudCoverPercent = if (AnalyzerFixtures.cloudy(day)) 90.0 else 5.0,
            clearSkyIndex = if (AnalyzerFixtures.cloudy(day)) 0.3 else 0.95, ambientC = 18.0,
            sunElevationDeg = if (h in 6.0..18.0) 15 + 30 * sin(PI * (h - 6) / 12) else -10.0, shadingFactor = 1.0, snowExpected = false)
    }

    private fun AnalyzerSample.with(vararg v: Pair<Channel, Double>) = copy(values = values + v)
    private fun inDay(s: AnalyzerSample, from: Double, to: Double) = !s.time.isBefore(at(from)) && s.time.isBefore(at(to))
    private fun raw(s: AnalyzerSample, scenario: Scenario) = RawRef("$scenario.csv", "linia ${Duration.between(AnalyzerFixtures.START, s.time).toMinutes() / 5 + 2}",
        s.time.toString(), s.values.entries.associate { it.key.name to "%.1f".format(java.util.Locale.ROOT, it.value) }, emptyMap())

    fun build(scenario: Scenario): Data {
        val month = AnalyzerFixtures.month(origin = DataOrigin.SIMULATOR)
        var s = month.samples
        var comm = month.comm
        var settings = emptyList<AnenjiSettingsSnapshot>()
        when (scenario) {
            Scenario.SCENARIO_NORMAL -> Unit
            Scenario.SCENARIO_LOW_PV -> s = s.map { if (inDay(it, 10.0, 14.0)) it.with(Channel.PV_POWER to it[Channel.PV_POWER]!! * 0.4) else it }
            Scenario.SCENARIO_MPPT_FAULT -> s = s.map { x ->
                val pv = x[Channel.PV_POWER]!!
                val bad = inDay(x, 10.0, 14.0)
                val p2 = if (bad) pv / 2 * 0.2 else pv / 2
                val m = listOf(MpptReading(1, 320.0, pv / 2 / 320, pv / 2), MpptReading(2, if (bad) 300.0 else 320.0, p2 / 320, p2))
                x.copy(values = x.values + (Channel.PV_POWER to pv / 2 + p2), mppts = if (pv > 0) m else emptyList())
            }
            Scenario.SCENARIO_LOW_BATTERY -> {
                // A 1.5 kW load all evening: the battery runs down, the low-battery warning (bit 8) appears, the grid takes over.
                var soc = s.first { it.time == at(18.0) }[Channel.SOC]!!
                s = s.map { x ->
                    if (!inDay(x, 18.0, 24.0)) return@map x
                    val load = x[Channel.LOAD_POWER]!! + 1500
                    var battery = x[Channel.PV_POWER]!! - load
                    var grid = 0.0
                    if (soc <= 20.0 && battery < 0) { grid = -battery; battery = 0.0 }
                    soc = (soc + battery * (5.0 / 60.0) / (AnalyzerFixtures.CAPACITY_AH * AnalyzerFixtures.VOLTAGE) * 100).coerceIn(10.0, 100.0)
                    x.copy(values = x.values + listOf(Channel.LOAD_POWER to load, Channel.BATTERY_POWER to battery, Channel.BATTERY_CURRENT to battery / AnalyzerFixtures.VOLTAGE,
                        Channel.SOC to soc, Channel.GRID_POWER to grid, Channel.BATTERY_VOLTAGE to 48.0 + soc / 100 * 6.0 - (if (battery < 0) minOf(1.5, -battery / 2000) else 0.0)),
                        mode = if (grid > 0) OperatingMode.GRID else OperatingMode.OFF_GRID, warnings = if (soc < 25) setOf(8) else emptySet())
                }
            }
            Scenario.SCENARIO_HIGH_LOAD -> s = s.map { x ->
                val load = if (inDay(x, 19.0, 19.5)) 7000.0 else x[Channel.LOAD_POWER]!!
                x.with(Channel.LOAD_POWER to load, Channel.LOAD_PERCENT to load / 0.9 / INVERTER_VA * 100)
            }
            Scenario.SCENARIO_GRID_OUTAGE -> s = s.map { x ->
                val out = inDay(x, 15.0, 16.0)
                x.with(Channel.GRID_VOLTAGE to if (out) 0.0 else 230.0, Channel.GRID_FREQUENCY to if (out) 0.0 else 50.0)
            }
            Scenario.SCENARIO_COMMUNICATION_FAILURE -> {
                s = s.filterNot { inDay(it, 12.0, 12.67) }
                comm = comm.map { r -> if (!r.time.isBefore(at(12.0)) && r.time.isBefore(at(12.67))) CommRecord(r.time, false, CommError.TIMEOUT, detail = "brak odpowiedzi") else r }
            }
            Scenario.SCENARIO_INVERTER_RESTART -> {
                var uptime = 0.0
                s = s.map { x -> if (!x.time.isBefore(at(15.0)) && x.time.isBefore(at(24.0))) x.with(Channel.UPTIME to uptime).also { uptime += 300 } else x }
            }
            Scenario.SCENARIO_CONFIGURATION_CHANGE -> {
                settings = listOf(
                    AnenjiSettingsSnapshot.build(at(6.0), "SIMULATED", imported = mapOf(SettingKey.MAX_CHARGE_CURRENT to 60.0), importedText = mapOf(SettingKey.OUTPUT_SOURCE_PRIORITY to "SBU")),
                    AnenjiSettingsSnapshot.build(at(7.0), "SIMULATED", imported = mapOf(SettingKey.MAX_CHARGE_CURRENT to 60.0), importedText = mapOf(SettingKey.OUTPUT_SOURCE_PRIORITY to "USB")),
                )
                // After switching to utility-first the load is fed from the grid although the battery is well charged.
                s = s.map { x -> if (inDay(x, 7.0, 10.0)) x.with(Channel.GRID_POWER to x[Channel.LOAD_POWER]!!, Channel.BATTERY_POWER to x[Channel.PV_POWER]!!) else x }
            }
        }
        s = s.map { if (inDay(it, 0.0, 24.0)) it.copy(raw = raw(it, scenario)) else it }
        return Data(scenario, s, comm, settings)
    }
}
