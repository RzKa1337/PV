package com.solartracker.pro.core.analytics

import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.IssueType
import com.solartracker.pro.core.inverter.TelemetryIssue
import com.solartracker.pro.core.inverter.LinkStatus
import java.time.Duration
import java.time.Instant

enum class AnomalyType(val title: String, val severity: AlertSeverity) {
    INVERTER_OFFLINE("FALOWNIK OFFLINE", AlertSeverity.CRITICAL),
    COMMUNICATION_LOST("Utrata komunikacji", AlertSeverity.WARNING),
    PV_UNDERPERFORMANCE("PV UNDERPERFORMANCE – produkcja poniżej oczekiwań", AlertSeverity.WARNING),
    POSSIBLE_PV_FAULT("POSSIBLE PV FAULT – brak produkcji mimo słońca", AlertSeverity.CRITICAL),
    EXPECTED_SHADING("EXPECTED SHADING – spadek zgodny z profilem zacienienia", AlertSeverity.INFO),
    UNEXPECTED_SHADING("Nieoczekiwane zacienienie", AlertSeverity.WARNING),
    ABNORMAL_SOC("Nietypowa zmiana SOC – sprawdź dane baterii", AlertSeverity.WARNING),
    UNEXPECTED_LOAD("UNEXPECTED LOAD – nagły wzrost zużycia", AlertSeverity.INFO),
    BATTERY_LOW("Niski poziom baterii", AlertSeverity.WARNING),
    BATTERY_OVERVOLTAGE("Przepięcie baterii", AlertSeverity.CRITICAL),
    BATTERY_UNDERVOLTAGE("Za niskie napięcie baterii", AlertSeverity.CRITICAL),
    BATTERY_OVERTEMPERATURE("Przegrzanie baterii", AlertSeverity.CRITICAL),
    INVERTER_OVERTEMPERATURE("Przegrzanie falownika", AlertSeverity.CRITICAL),
    OVERLOAD("Przeciążenie", AlertSeverity.CRITICAL),
    GRID_FAULT("Problem z siecią", AlertSeverity.WARNING),
    INVERTER_FAULT("Błąd falownika", AlertSeverity.CRITICAL),
    MPPT_FAULT("Problem MPPT / wejścia PV", AlertSeverity.WARNING),
    ABNORMAL_PRODUCTION("Nietypowa produkcja (model zaniża)", AlertSeverity.INFO),
    MISSING_BUILDING_HEIGHT("Brak wysokości przeszkody", AlertSeverity.INFO),
    LOW_CONFIDENCE_SHADING("Niska pewność modelu zacienienia", AlertSeverity.INFO),
    INVALID_TELEMETRY("Błędne dane z falownika (odrzucone)", AlertSeverity.WARNING),
    FROZEN_TELEMETRY("Dane z falownika nie zmieniają się", AlertSeverity.WARNING),
}

enum class AlertSeverity { INFO, WARNING, CRITICAL }

data class Anomaly(val type: AnomalyType, val detail: String, val key: String = type.name)

/** Inputs of one detection pass (one live reading). */
data class AnomalyInput(
    val now: Instant,
    val telemetry: InverterTelemetry?,
    val link: LinkStatus,
    /** Model PV power before and after the computed shading [kW]. */
    val expectedUnshadedKw: Double?,
    val expectedShadedKw: Double?,
    /** Plane-of-array irradiance from the model or weather [W/m²]. */
    val poaWm2: Double?,
    val curtailed: Boolean,
    val batteryMinSocPercent: Double?,
    val batteryNominalVoltage: Double = 48.0,
    /** Typical load for this hour from history [kW] (null without history). */
    val typicalLoadKw: Double?,
    /** Previous reading to judge rates of change. */
    val previous: InverterTelemetry?,
    val batteryCapacityKwh: Double?,
    /** Issues found by the telemetry validator for this reading. */
    val validationIssues: List<TelemetryIssue> = emptyList(),
)

/** Rule-based (no ML) real-time anomaly detection. */
object AnomalyDetector {

    fun detect(i: AnomalyInput): List<Anomaly> = detectReading(i) + validation(i.validationIssues)

    private fun validation(issues: List<TelemetryIssue>): List<Anomaly> = issues.mapNotNull { issue ->
        when (issue.type) {
            IssueType.OUT_OF_RANGE, IssueType.INCONSISTENT, IssueType.JUMP ->
                Anomaly(AnomalyType.INVALID_TELEMETRY, "${issue.type.label}: ${issue.detail}", "invalid-${issue.field ?: "x"}")
            IssueType.FROZEN -> Anomaly(AnomalyType.FROZEN_TELEMETRY, issue.detail)
            else -> null
        }
    }

    private fun detectReading(i: AnomalyInput): List<Anomaly> {
        val out = mutableListOf<Anomaly>()
        when (i.link) {
            LinkStatus.OFFLINE -> return listOf(Anomaly(AnomalyType.INVERTER_OFFLINE, "Falownik nie odpowiada"))
            LinkStatus.DEGRADED -> out += Anomaly(AnomalyType.COMMUNICATION_LOST, "Ostatni odczyt nieudany")
            else -> Unit
        }
        val t = i.telemetry ?: return out
        val pvKw = t.pv.powerW?.div(1000.0)

        // PV performance vs model (after shading).
        if (pvKw != null && i.expectedShadedKw != null && i.expectedUnshadedKw != null && !i.curtailed) {
            val shaded = i.expectedShadedKw
            val unshaded = i.expectedUnshadedKw
            if (pvKw < 0.02 && (i.poaWm2 ?: 0.0) > 300 && shaded > 0.5) {
                out += Anomaly(AnomalyType.POSSIBLE_PV_FAULT, "PV = 0 przy ${i.poaWm2!!.toInt()} W/m² i braku przewidywanego cienia")
            } else if (shaded > 0.3 && pvKw < shaded * 0.7) {
                out += Anomaly(AnomalyType.PV_UNDERPERFORMANCE, "Oczekiwano ${f(shaded)} kW (po zacienieniu), jest ${f(pvKw)} kW")
            } else if (unshaded - shaded > 0.2 && pvKw < unshaded * 0.85 && pvKw >= shaded * 0.8) {
                out += Anomaly(AnomalyType.EXPECTED_SHADING, "Spadek do ${f(pvKw)} kW zgodny z obliczonym cieniem (−${f(unshaded - shaded)} kW)")
            } else if (shaded > 1.0 && pvKw > shaded * 1.4) {
                out += Anomaly(AnomalyType.ABNORMAL_PRODUCTION, "Pomiar ${f(pvKw)} kW, model ${f(shaded)} kW")
            }
        }

        // Battery.
        val soc = t.battery.socPercent
        if (soc != null && i.batteryMinSocPercent != null && soc <= i.batteryMinSocPercent + 5) {
            out += Anomaly(AnomalyType.BATTERY_LOW, "SOC ${soc.toInt()}%")
        }
        val v = t.battery.voltageV
        if (v != null) {
            val cells = i.batteryNominalVoltage / 3.2 // LiFePO4 cells (16 for 48 V)
            if (v > cells * 3.65 + 0.5) out += Anomaly(AnomalyType.BATTERY_OVERVOLTAGE, "Napięcie ${f(v)} V")
            if (v < cells * 2.8) out += Anomaly(AnomalyType.BATTERY_UNDERVOLTAGE, "Napięcie ${f(v)} V")
        }
        t.battery.temperatureC?.let { if (it > 50) out += Anomaly(AnomalyType.BATTERY_OVERTEMPERATURE, "${it.toInt()}°C") }
        t.inverter.temperatureC?.let { if (it > 75) out += Anomaly(AnomalyType.INVERTER_OVERTEMPERATURE, "${it.toInt()}°C") }
        t.load.percent?.let { if (it > 100) out += Anomaly(AnomalyType.OVERLOAD, "Obciążenie ${it.toInt()}%") }

        // SOC rate of change vs measured battery power.
        val p = i.previous
        if (p != null && soc != null && p.battery.socPercent != null && i.batteryCapacityKwh != null) {
            val hours = Duration.between(p.timestamp, t.timestamp).toMillis() / 3_600_000.0
            if (hours in 1e-4..0.5) {
                val socChange = soc - p.battery.socPercent
                val powerKw = listOfNotNull(p.battery.powerW, t.battery.powerW).average().takeIf { !it.isNaN() }?.div(1000.0)
                val expectedChange = powerKw?.let { it * hours / i.batteryCapacityKwh * 100.0 }
                if (expectedChange != null && kotlin.math.abs(socChange - expectedChange) > 5.0 + kotlin.math.abs(expectedChange) * 2) {
                    out += Anomaly(AnomalyType.ABNORMAL_SOC, "SOC zmienił się o ${f(socChange)} pp, z mocy baterii wynika ${f(expectedChange)} pp")
                }
            }
        }

        // Load.
        val loadKw = t.load.powerW?.div(1000.0)
        if (loadKw != null && i.typicalLoadKw != null && loadKw > i.typicalLoadKw * 2.5 + 0.5) {
            out += Anomaly(AnomalyType.UNEXPECTED_LOAD, "Zużycie ${f(loadKw)} kW, typowo ${f(i.typicalLoadKw)} kW")
        }

        // Inverter-reported events.
        t.inverter.faults.forEach { out += Anomaly(classifyFault(it.description), it.description, "fault-${it.code}") }
        t.inverter.warnings.filter { it.description.contains("sieci") }.forEach {
            out += Anomaly(AnomalyType.GRID_FAULT, it.description, "warn-${it.code}")
        }
        t.inverter.warnings.filter { it.description.contains("PV") }.forEach {
            out += Anomaly(AnomalyType.MPPT_FAULT, it.description, "warn-${it.code}")
        }
        return out
    }

    private fun classifyFault(d: String) = when {
        d.contains("PV") -> AnomalyType.MPPT_FAULT
        d.contains("Przegrzanie") -> AnomalyType.INVERTER_OVERTEMPERATURE
        d.contains("Przeciążenie") -> AnomalyType.OVERLOAD
        d.contains("baterii", ignoreCase = true) -> AnomalyType.BATTERY_OVERVOLTAGE.takeIf { d.contains("Przepięcie") } ?: AnomalyType.INVERTER_FAULT
        else -> AnomalyType.INVERTER_FAULT
    }

    private fun f(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)
}

/** Alert shown to the user; repeated detections of the same problem are grouped. */
data class Alert(
    val key: String,
    val type: AnomalyType,
    val detail: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val occurrences: Int,
    val active: Boolean,
    /** True when this update should produce a notification (new or re-raised after cooldown). */
    val notify: Boolean,
)

/**
 * Groups anomalies into alerts: one alert per key, counted while repeated, cleared when the problem
 * disappears for [clearAfter]; notifications at most once per [cooldown] per key (no spam).
 */
class AlertManager(
    private val clearAfter: Duration = Duration.ofMinutes(2),
    private val cooldown: Duration = Duration.ofMinutes(30),
    /** Detections needed before a WARNING/INFO alert becomes active (debounce). */
    private val confirmations: Int = 2,
) {
    private class Entry(var alert: Alert, var lastNotified: Instant?, var hits: Int)

    private val entries = LinkedHashMap<String, Entry>()

    fun update(now: Instant, anomalies: List<Anomaly>): List<Alert> {
        val seen = anomalies.associateBy { it.key }
        for ((key, a) in seen) {
            val e = entries[key]
            if (e == null) {
                entries[key] = Entry(Alert(key, a.type, a.detail, now, now, 1, false, false), null, 1)
            } else {
                e.hits++
                e.alert = e.alert.copy(detail = a.detail, lastSeen = now, occurrences = e.alert.occurrences + 1)
            }
        }
        val result = mutableListOf<Alert>()
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val (key, e) = iterator.next()
            val present = key in seen
            if (!present && Duration.between(e.alert.lastSeen, now) > clearAfter) {
                iterator.remove()
                continue
            }
            val needed = if (e.alert.type.severity == AlertSeverity.CRITICAL) 1 else confirmations
            val active = present && e.hits >= needed || (!present && e.alert.active)
            val notify = active && present && (e.lastNotified == null || Duration.between(e.lastNotified, now) >= cooldown) &&
                e.alert.type.severity != AlertSeverity.INFO
            if (notify) e.lastNotified = now
            e.alert = e.alert.copy(active = active, notify = notify)
            if (active) result += e.alert
        }
        return result.sortedByDescending { it.type.severity }
    }
}
