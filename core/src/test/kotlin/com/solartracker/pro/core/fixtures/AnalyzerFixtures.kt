package com.solartracker.pro.core.fixtures

import com.solartracker.pro.core.anenji.AnalyzerSample
import com.solartracker.pro.core.anenji.Channel
import com.solartracker.pro.core.anenji.CommError
import com.solartracker.pro.core.anenji.CommRecord
import com.solartracker.pro.core.anenji.DataOrigin
import com.solartracker.pro.core.inverter.OperatingMode
import java.time.Duration
import java.time.Instant
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * SYNTHETIC 30-day Anenji-like history (5-minute samples, UTC) built from a simple energy balance – NOT a recording of a
 * real device. Known ground truth for the analyzer tests:
 * - battery 230 Ah × 48 V, SOC integrated from PV − load;
 * - every 5th day cloudy (PV × 0.3); clear days clip at 1800 W;
 * - after each cloudy day (days 5, 10, 15, …) the battery is low by the morning and a 2.4 kW load runs 06:30–07:00;
 *   SOC < 25 % sets warning bit 8 ("Niskie napięcie baterii");
 * - grid takes over below 20 % SOC;
 * - communication lost on day 10, 12:00–12:10 (timeouts, no samples);
 * - inverter restart (uptime reset) on day 20 at 03:00.
 */
object AnalyzerFixtures {
    val START: Instant = Instant.parse("2026-09-01T00:00:00Z")
    const val CAPACITY_AH = 230.0
    const val VOLTAGE = 48.0
    const val PV_LIMIT_W = 1800.0
    const val PV_PEAK_W = 2400.0

    data class Month(val samples: List<AnalyzerSample>, val comm: List<CommRecord>)

    fun cloudy(day: Int) = day % 5 == 4
    fun spikeDay(day: Int) = day % 5 == 0 && day > 0

    fun pvAt(t: Instant): Double {
        val day = Duration.between(START, t).toDays().toInt()
        val h = (t.epochSecond % 86_400) / 3600.0
        val clear = if (h in 6.0..18.0) PV_PEAK_W * sin(PI * (h - 6) / 12) else 0.0
        return min(PV_LIMIT_W, clear * if (cloudy(day)) 0.3 else 1.0)
    }

    fun month(days: Int = 30, origin: DataOrigin = DataOrigin.DEVICE): Month {
        val samples = mutableListOf<AnalyzerSample>()
        val comm = mutableListOf<CommRecord>()
        var soc = 60.0
        var uptime = 0.0
        val step = Duration.ofMinutes(5)
        var t = START
        val end = START.plus(Duration.ofDays(days.toLong()))
        while (t.isBefore(end)) {
            val day = Duration.between(START, t).toDays().toInt()
            val h = (t.epochSecond % 86_400) / 3600.0
            val outage = day == 10 && h >= 12.0 && h < 12.0 + 10 / 60.0
            if (outage) {
                comm += CommRecord(t, false, CommError.TIMEOUT, detail = "brak odpowiedzi")
                t = t.plus(step); continue
            }
            if (day == 20 && h == 3.0) uptime = 0.0 else uptime += 300
            val pv = pvAt(t)
            val load = 400.0 + (if (spikeDay(day) && h >= 6.5 && h < 7.0) 2000.0 else 0.0) + (if (h in 18.0..22.0) 300.0 else 0.0)
            var battery = pv - load
            var grid = 0.0
            if (soc <= 20.0 && battery < 0) { grid = -battery; battery = 0.0 }
            if (soc >= 100.0 && battery > 0) battery = 0.0
            soc = (soc + battery * (5.0 / 60.0) / (CAPACITY_AH * VOLTAGE) * 100).coerceIn(10.0, 100.0)
            val bv = 48.0 + soc / 100 * 6.0 - (if (battery < 0) min(1.5, -battery / 2000) else 0.0)
            val v = mutableMapOf(
                Channel.PV_POWER to pv, Channel.PV_VOLTAGE to (if (pv > 0) 320.0 else 0.0), Channel.LOAD_POWER to load, Channel.LOAD_APPARENT to load / 0.9,
                Channel.BATTERY_POWER to battery, Channel.BATTERY_VOLTAGE to bv, Channel.BATTERY_CURRENT to battery / VOLTAGE, Channel.SOC to soc,
                Channel.GRID_POWER to grid, Channel.AC_VOLTAGE to 230.0, Channel.AC_FREQUENCY to 50.0, Channel.INVERTER_TEMPERATURE to 30.0 + pv / 100,
                Channel.UPTIME to uptime,
            )
            samples += AnalyzerSample(t, v, origin, if (grid > 0) OperatingMode.GRID else OperatingMode.OFF_GRID, if (soc < 25) setOf(8) else emptySet())
            comm += CommRecord(t, true, latencyMs = 120)
            t = t.plus(step)
        }
        return Month(samples, comm)
    }
}
