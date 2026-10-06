package com.solartracker.pro.core.energy

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** How the user describes the cold room's consumption. */
enum class CoolingMode(val label: String) {
    /** Measured or known average power; the model only shapes it by temperature and opening hours. */
    AVERAGE("średnia moc"),
    /** Compressor duty cycle at a reference ambient temperature. */
    DUTY_CYCLE("cykl pracy sprężarki"),
    /** Explicit power per time window. */
    SCHEDULE("harmonogram"),
}

data class CoolingScheduleEntry(val start: LocalTime, val end: LocalTime, val powerW: Double) {
    fun contains(t: LocalTime): Boolean = if (end.isAfter(start)) !t.isBefore(start) && t.isBefore(end) else !t.isBefore(start) || t.isBefore(end)
}

/**
 * Large cooling load (cold room / refrigerated container). It does NOT draw its nominal power all the
 * time: the compressor cycles. Average power = standby + duty × (nominal − standby), where the duty
 * follows the heat gain ∝ (ambient − target). Values come from the user (nameplate, meter or app of
 * the refrigeration controller); nothing is assumed beyond that proportionality.
 */
data class CoolingLoadProfile(
    val name: String = "Chłodnia",
    val nominalPowerW: Double,
    /** Power when the compressor is off (fans, controller, lighting). */
    val minimumPowerW: Double,
    val mode: CoolingMode,
    /** AVERAGE mode: average power at [referenceAmbientC]. */
    val averagePowerW: Double? = null,
    /** DUTY_CYCLE mode: compressor on-time fraction at [referenceAmbientC]. */
    val dutyAtReference: Double? = null,
    val schedule: List<CoolingScheduleEntry> = emptyList(),
    val targetTemperatureC: Double,
    val referenceAmbientC: Double = 25.0,
    /** Opening hours (door openings, loading); outside them [idleDutyFactor] scales the duty. */
    val operatingStart: LocalTime = LocalTime.of(0, 0),
    val operatingEnd: LocalTime = LocalTime.of(0, 0),
    val idleDutyFactor: Double = 1.0,
    val preCoolingEnabled: Boolean = false,
    val preCoolingTargetC: Double? = null,
    val preCoolingHours: Double = 2.0,
) {
    fun validate(): List<String> = buildList {
        if (nominalPowerW !in 50.0..100_000.0) add("Moc nominalna 50–100 000 W")
        if (minimumPowerW < 0 || minimumPowerW >= nominalPowerW) add("Moc minimalna 0 – poniżej nominalnej")
        when (mode) {
            CoolingMode.AVERAGE -> if (averagePowerW == null || averagePowerW !in minimumPowerW..nominalPowerW) add("Średnia moc między minimalną a nominalną")
            CoolingMode.DUTY_CYCLE -> if (dutyAtReference == null || dutyAtReference !in 0.0..1.0) add("Cykl pracy 0–100%")
            CoolingMode.SCHEDULE -> if (schedule.isEmpty() || schedule.any { it.powerW !in 0.0..nominalPowerW }) add("Harmonogram: moc 0–nominalna")
        }
        if (targetTemperatureC !in -40.0..20.0) add("Temperatura docelowa −40…20 °C")
        if (referenceAmbientC <= targetTemperatureC) add("Temperatura odniesienia musi być wyższa od docelowej")
        if (idleDutyFactor !in 0.0..1.5) add("Współczynnik poza godzinami 0–1,5")
        if (preCoolingEnabled && (preCoolingTargetC == null || preCoolingTargetC >= targetTemperatureC)) add("Temperatura wychładzania niższa od docelowej")
        if (preCoolingHours !in 0.5..8.0) add("Czas wychładzania 0,5–8 h")
    }

    /** Duty at the reference ambient implied by the chosen mode. */
    private val referenceDuty: Double
        get() = when (mode) {
            CoolingMode.DUTY_CYCLE -> dutyAtReference ?: 0.0
            CoolingMode.AVERAGE -> ((averagePowerW ?: minimumPowerW) - minimumPowerW) / (nominalPowerW - minimumPowerW)
            CoolingMode.SCHEDULE -> 0.0
        }

    private fun inOperatingHours(t: LocalTime): Boolean =
        operatingStart == operatingEnd || CoolingScheduleEntry(operatingStart, operatingEnd, 0.0).contains(t)

    private fun inPreCooling(t: LocalTime): Boolean {
        if (!preCoolingEnabled || operatingStart == operatingEnd) return false
        val start = operatingStart.minusMinutes((preCoolingHours * 60).roundToInt().toLong())
        return CoolingScheduleEntry(start, operatingStart, 0.0).contains(t)
    }

    /** Compressor duty 0..1 at [time] for an ambient temperature (null = reference ambient). */
    fun duty(time: Instant, zone: ZoneId, ambientC: Double?): Double {
        if (mode == CoolingMode.SCHEDULE) return 0.0
        val t = time.atZone(zone).toLocalTime()
        val ambient = ambientC ?: referenceAmbientC
        val target = if (inPreCooling(t)) preCoolingTargetC ?: targetTemperatureC else targetTemperatureC
        val gain = ((ambient - target) / (referenceAmbientC - targetTemperatureC)).coerceAtLeast(0.0)
        val factor = if (inOperatingHours(t) || inPreCooling(t)) 1.0 else idleDutyFactor
        return (referenceDuty * gain * factor).coerceIn(0.0, 1.0)
    }

    /** Mean electrical power [kW] around [time] (what an energy forecast needs). */
    fun averagePowerKw(time: Instant, zone: ZoneId, ambientC: Double?): Double {
        if (mode == CoolingMode.SCHEDULE) {
            val t = time.atZone(zone).toLocalTime()
            return (schedule.firstOrNull { it.contains(t) }?.powerW ?: minimumPowerW) / 1000.0
        }
        return (minimumPowerW + duty(time, zone, ambientC) * (nominalPowerW - minimumPowerW)) / 1000.0
    }

    /**
     * Instantaneous power for a cycling compressor (for simulations / charts): ON at nominal power for
     * duty × [cycleMinutes] at the start of each cycle, otherwise the minimum power.
     */
    fun instantPowerKw(time: Instant, zone: ZoneId, ambientC: Double?, cycleMinutes: Int = 20): Double {
        if (mode == CoolingMode.SCHEDULE) return averagePowerKw(time, zone, ambientC)
        val d = duty(time, zone, ambientC)
        val secondsIntoCycle = Math.floorMod(time.epochSecond, cycleMinutes * 60L)
        return (if (secondsIntoCycle < d * cycleMinutes * 60) nominalPowerW else minimumPowerW) / 1000.0
    }

    /** Daily energy [kWh] for a constant ambient temperature (sanity figure for the user). */
    fun dailyEnergyKwh(date: java.time.LocalDate, zone: ZoneId, ambientC: Double?): Double =
        (0 until 96).sumOf { averagePowerKw(date.atStartOfDay(zone).toInstant().plusSeconds(it * 900L + 450), zone, ambientC) } * 0.25
}
