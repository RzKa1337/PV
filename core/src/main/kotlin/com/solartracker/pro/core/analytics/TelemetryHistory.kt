package com.solartracker.pro.core.analytics

import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.OperatingMode
import java.time.Duration
import java.time.Instant

/**
 * Aggregated history row (mean values over [start, end)). Stored instead of every live reading,
 * so the database grows slowly: LIVE 1–5 s in memory → HISTORY rows every 30 s → SUMMARY 15 min.
 */
data class HistorySample(
    val start: Instant,
    val end: Instant,
    val samples: Int,
    val pvW: Double?,
    val pvMaxW: Double?,
    val loadW: Double?,
    val batteryW: Double?,
    val gridW: Double?,
    val batteryVoltageV: Double?,
    val batteryCurrentA: Double?,
    val socPercent: Double?,
    val inverterTemperatureC: Double?,
    val batteryTemperatureC: Double?,
    /** Energy integrated from power over the bucket [kWh] (CALCULATED). */
    val pvEnergyKwh: Double,
    val loadEnergyKwh: Double,
    val gridImportKwh: Double,
    val gridExportKwh: Double,
    val batteryChargeKwh: Double,
    val batteryDischargeKwh: Double,
    val mode: OperatingMode?,
    val faultCodes: Set<Int>,
    val warningCodes: Set<Int>,
    /** Recorded from the simulator – kept apart from real device data in every analysis. */
    val simulated: Boolean = false,
) {
    val duration: Duration get() = Duration.between(start, end)
}

/**
 * Builds [HistorySample]s from live telemetry. Energy is integrated with the trapezoid rule; gaps
 * longer than [maxGap] are not bridged (no invented energy while the link was down).
 */
class TelemetryAggregator(
    val bucket: Duration = Duration.ofSeconds(30),
    private val maxGap: Duration = Duration.ofSeconds(60),
) {
    init {
        require(bucket >= Duration.ofSeconds(1))
    }

    private val readings = mutableListOf<InverterTelemetry>()
    private var bucketStart: Instant? = null
    private var previous: InverterTelemetry? = null
    private val energy = DoubleArray(6)

    /** Adds a reading; returns completed samples (usually zero or one). */
    fun add(t: InverterTelemetry): List<HistorySample> {
        val out = mutableListOf<HistorySample>()
        val start = bucketStart ?: floor(t.timestamp).also { bucketStart = it }
        if (!t.timestamp.isBefore(start.plus(bucket))) {
            close(complete = true)?.let(out::add)
            bucketStart = floor(t.timestamp)
            previous = previous?.takeIf { Duration.between(it.timestamp, t.timestamp) <= maxGap }
        }
        previous?.let { p ->
            val dt = Duration.between(p.timestamp, t.timestamp)
            if (!dt.isNegative && !dt.isZero && dt <= maxGap) integrate(p, t, dt.toMillis() / 3_600_000.0)
        }
        readings += t
        previous = t
        return out
    }

    /** Emits the current partial bucket (e.g. when monitoring stops). */
    fun flush(): HistorySample? = close(complete = false)

    private fun close(complete: Boolean): HistorySample? {
        val start = bucketStart ?: return null
        if (readings.isEmpty()) return null
        val r = readings.toList()
        fun mean(f: (InverterTelemetry) -> Double?): Double? = r.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()
        val sample = HistorySample(
            start = start,
            end = if (complete) start.plus(bucket) else maxOf(r.last().timestamp, start.plusSeconds(1)),
            samples = r.size,
            pvW = mean { it.pv.powerW },
            pvMaxW = r.mapNotNull { it.pv.powerW }.maxOrNull(),
            loadW = mean { it.load.powerW },
            batteryW = mean { it.battery.powerW },
            gridW = mean { it.grid.powerW },
            batteryVoltageV = mean { it.battery.voltageV },
            batteryCurrentA = mean { it.battery.currentA },
            socPercent = r.lastOrNull { it.battery.socPercent != null }?.battery?.socPercent,
            inverterTemperatureC = mean { it.inverter.temperatureC },
            batteryTemperatureC = mean { it.battery.temperatureC },
            pvEnergyKwh = energy[0], loadEnergyKwh = energy[1], gridImportKwh = energy[2], gridExportKwh = energy[3],
            batteryChargeKwh = energy[4], batteryDischargeKwh = energy[5],
            mode = r.last().inverter.mode,
            faultCodes = r.flatMap { it.inverter.faults }.map { it.code }.toSet(),
            warningCodes = r.flatMap { it.inverter.warnings }.map { it.code }.toSet(),
        )
        readings.clear()
        energy.fill(0.0)
        bucketStart = null
        return sample
    }

    private fun integrate(a: InverterTelemetry, b: InverterTelemetry, hours: Double) {
        fun trap(x: Double?, y: Double?) = if (x != null && y != null) (x + y) / 2.0 * hours / 1000.0 else 0.0
        energy[0] += trap(a.pv.powerW, b.pv.powerW)
        energy[1] += trap(a.load.powerW, b.load.powerW)
        energy[2] += trap(a.grid.importPowerW, b.grid.importPowerW)
        energy[3] += trap(a.grid.exportPowerW, b.grid.exportPowerW)
        energy[4] += trap(a.battery.chargePowerW, b.battery.chargePowerW)
        energy[5] += trap(a.battery.dischargePowerW, b.battery.dischargePowerW)
    }

    private fun floor(t: Instant): Instant {
        val s = bucket.seconds
        return Instant.ofEpochSecond(t.epochSecond - Math.floorMod(t.epochSecond, s))
    }

    companion object {
        /** Combines history rows into coarser summaries (e.g. 15 min or 1 h). */
        fun summarize(rows: List<HistorySample>, period: Duration): List<HistorySample> {
            require(period.seconds > 0)
            return rows.sortedBy { it.start }
                .groupBy { Instant.ofEpochSecond(it.start.epochSecond - Math.floorMod(it.start.epochSecond, period.seconds)) }
                .map { (start, g) ->
                    fun wmean(f: (HistorySample) -> Double?): Double? {
                        val pairs = g.mapNotNull { r -> f(r)?.let { it to r.duration.seconds.toDouble().coerceAtLeast(1.0) } }
                        return if (pairs.isEmpty()) null else pairs.sumOf { it.first * it.second } / pairs.sumOf { it.second }
                    }
                    HistorySample(
                        start = start, end = start.plus(period), samples = g.sumOf { it.samples },
                        pvW = wmean { it.pvW }, pvMaxW = g.mapNotNull { it.pvMaxW }.maxOrNull(), loadW = wmean { it.loadW },
                        batteryW = wmean { it.batteryW }, gridW = wmean { it.gridW }, batteryVoltageV = wmean { it.batteryVoltageV },
                        batteryCurrentA = wmean { it.batteryCurrentA }, socPercent = g.lastOrNull { it.socPercent != null }?.socPercent,
                        inverterTemperatureC = wmean { it.inverterTemperatureC }, batteryTemperatureC = wmean { it.batteryTemperatureC },
                        pvEnergyKwh = g.sumOf { it.pvEnergyKwh }, loadEnergyKwh = g.sumOf { it.loadEnergyKwh },
                        gridImportKwh = g.sumOf { it.gridImportKwh }, gridExportKwh = g.sumOf { it.gridExportKwh },
                        batteryChargeKwh = g.sumOf { it.batteryChargeKwh }, batteryDischargeKwh = g.sumOf { it.batteryDischargeKwh },
                        mode = g.last().mode, faultCodes = g.flatMap { it.faultCodes }.toSet(), warningCodes = g.flatMap { it.warningCodes }.toSet(),
                        simulated = g.any { it.simulated },
                    )
                }
        }
    }
}
