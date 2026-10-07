package com.solartracker.pro.core.energy

import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.quality.Quantity
import java.time.Duration
import kotlin.math.abs

/**
 * State of charge for display and predictions: the inverter/BMS value when it is reported (MEASURED),
 * otherwise an ESTIMATE from the resting battery voltage – only when the battery has been at rest
 * (|I| below [restCurrentA]) for at least [restTime]. Under load the result is UNKNOWN, never a guess.
 */
object SocEstimator {
    fun estimate(
        latest: InverterTelemetry?,
        recent: List<InverterTelemetry>,
        type: BatteryType,
        nominalVoltage: Double,
        restCurrentA: Double = 2.0,
        restTime: Duration = Duration.ofMinutes(10),
    ): Quantity {
        val t = latest ?: return Quantity.unknown("%", "SOC", "brak odczytu z falownika")
        t.battery.socPercent?.let { return Quantity(it, "%", DataKind.MEASURED, t.providerId, t.timestamp) }
        val v = t.battery.voltageV ?: return Quantity.unknown("%", "SOC", "falownik nie podaje SOC ani napięcia baterii")
        val window = (recent + t).filter { !it.timestamp.isBefore(t.timestamp.minus(restTime)) }
        val coversRest = window.isNotEmpty() && Duration.between(window.first().timestamp, t.timestamp) >= restTime.multipliedBy(9).dividedBy(10)
        val atRest = coversRest && window.all { r -> r.battery.currentA?.let { abs(it) < restCurrentA } ?: false }
        val q = BatteryChemistry.socFromRestingVoltage(type, v, nominalVoltage, atRest, t.timestamp)
        return if (q.kind == DataKind.ESTIMATED) q.copy(source = "napięcie spoczynkowe ${"%.1f".format(v)} V (${type.name})") else q
    }
}
