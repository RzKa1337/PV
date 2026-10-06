package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.EnergyFlowSimulator
import com.solartracker.pro.core.quality.DataKind
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class EnergyForecastRow(
    val time: Instant,
    val pvKw: Double,
    val shadingLossKw: Double,
    val loadKw: Double,
    /** + charging, − discharging. */
    val batteryKw: Double,
    /** + import, − export/unused surplus. */
    val gridKw: Double,
    val socPercent: Double?,
)

data class SocAt(val label: String, val time: Instant, val socPercent: Double)

data class BatteryPrediction(
    val startSoc: Double,
    val startKind: DataKind,
    val rows: List<EnergyForecastRow>,
    val milestones: List<SocAt>,
    /** Stored energy above the minimum SOC right now [kWh]. */
    val energyAvailableKwh: Double,
    val chargingStarts: Instant?,
    val chargingEnds: Instant?,
    /** First moment the battery reaches the minimum SOC, if within the horizon. */
    val emptyAt: Instant?,
    val confidence: Double,
)

/**
 * SOC prediction: steps forward from the measured SOC with forecast PV and load, applying the same
 * battery limits and efficiencies as the simulation model (EnergyFlowSimulator.instantFlow).
 */
class BatteryPredictor(
    private val battery: BatteryStorage,
    private val zone: ZoneId,
    /** DC→AC conversion efficiency of the inverter for AC loads (1.0 = ignored). */
    private val inverterEfficiency: Double = 1.0,
) {
    private val simulator = EnergyFlowSimulator()

    fun predict(
        now: Instant,
        startSoc: Double,
        startKind: DataKind,
        pv: (Instant) -> PvForecastPoint,
        load: (Instant) -> LoadForecastPoint,
        horizon: Duration = Duration.ofHours(24),
        step: Duration = Duration.ofMinutes(15),
    ): BatteryPrediction {
        require(battery.isValid)
        var soc = startSoc.coerceIn(0.0, 100.0)
        val rows = mutableListOf<EnergyForecastRow>()
        var t = now
        val end = now.plus(horizon)
        val h = step.seconds / 3600.0
        var chargingStart: Instant? = null
        var chargingEnd: Instant? = null
        var empty: Instant? = null
        val confidences = mutableListOf<Double>()
        while (!t.isAfter(end)) {
            val p = pv(t)
            val l = load(t)
            confidences += p.confidence * l.confidence
            val flow = simulator.instantFlow(p.expectedKw, l.kw / inverterEfficiency.coerceIn(0.5, 1.0), battery, soc)
            rows += EnergyForecastRow(t, p.expectedKw, p.shadingLossKw, l.kw, flow.batteryKw, flow.gridImportKw - flow.exportKw, soc)
            val stored = battery.storedKwh(soc) + flow.chargeKw * h * battery.chargeEfficiency - flow.dischargeKw * h / battery.dischargeEfficiency
            val next = (stored / battery.usableCapacityKwh * 100.0).coerceIn(battery.minSocPercent.coerceAtMost(soc), battery.maxSocPercent.coerceAtLeast(soc))
            if (flow.chargeKw > 0.05 && chargingStart == null) chargingStart = t
            if (chargingStart != null && chargingEnd == null && flow.chargeKw <= 0.05) chargingEnd = t
            if (empty == null && next <= battery.minSocPercent + 0.01 && soc > battery.minSocPercent + 0.01) empty = t.plus(step)
            soc = next
            t = t.plus(step)
        }
        val milestones = buildList {
            fun nearest(target: Instant, label: String) {
                rows.minByOrNull { kotlin.math.abs(Duration.between(it.time, target).seconds) }
                    ?.takeIf { kotlin.math.abs(Duration.between(it.time, target).toMinutes()) <= step.toMinutes() }
                    ?.socPercent?.let { add(SocAt(label, target, it)) }
            }
            nearest(now.plus(Duration.ofHours(1)), "za 1 h")
            nearest(now.plus(Duration.ofHours(3)), "za 3 h")
            val today = now.atZone(zone).toLocalDate()
            listOf(LocalTime.of(21, 0) to "wieczorem (21:00)", LocalTime.MIDNIGHT to "o północy", LocalTime.of(7, 0) to "rano (07:00)").forEach { (lt, label) ->
                var target = today.atTime(lt).atZone(zone).toInstant()
                if (!target.isAfter(now)) target = today.plusDays(1).atTime(lt).atZone(zone).toInstant()
                nearest(target, label)
            }
        }
        return BatteryPrediction(
            startSoc = startSoc,
            startKind = startKind,
            rows = rows,
            milestones = milestones,
            energyAvailableKwh = (battery.storedKwh(startSoc) - battery.storedKwh(battery.minSocPercent)).coerceAtLeast(0.0) * battery.dischargeEfficiency,
            chargingStarts = chargingStart,
            chargingEnds = chargingEnd,
            emptyAt = empty,
            confidence = (confidences.average().takeIf { !it.isNaN() } ?: 0.0) * (if (startKind == DataKind.MEASURED) 1.0 else 0.6),
        )
    }
}
