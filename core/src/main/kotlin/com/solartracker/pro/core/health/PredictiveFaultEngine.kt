package com.solartracker.pro.core.health

import java.time.Instant

enum class FaultSeverity { INFO, WARNING, CRITICAL }

/** ANOMALY = data looks unusual (possible problem); FAULT = confirmed by the inverter itself. */
enum class FaultLevel(val label: String) { ANOMALY("Możliwa anomalia"), FAULT("Potwierdzony błąd") }

data class FaultWarning(
    val id: String,
    val title: String,
    val severity: FaultSeverity,
    val level: FaultLevel,
    val time: Instant,
    val reason: String,
    val evidence: List<String>,
    val confidence: Double,
    val recommendation: String,
)

/**
 * Early warnings from trends in daily statistics, before an obvious failure. Statistical rules only;
 * every warning lists its evidence and confidence, and anything not confirmed by the inverter is an
 * ANOMALY ("possible"), never a "fault".
 */
object PredictiveFaultEngine {

    fun analyze(days: List<DayStats>, now: Instant, inverterTempLimitC: Double = 75.0, linkQuality: Double? = null, stringPowersW: List<Double>? = null): List<FaultWarning> {
        val out = mutableListOf<FaultWarning>()
        val sorted = days.sortedBy { it.date }
        val today = sorted.lastOrNull() ?: return out
        val baseline = sorted.dropLast(1).takeLast(14)

        // 1) Sudden drop of the real/expected ratio vs the last 14 days.
        val ratios = baseline.mapNotNull { it.ratio }
        val r = today.ratio
        if (r != null && ratios.size >= 5) {
            val med = median(ratios)
            val drop = 1 - r / med
            if (drop > 0.15 && today.coverageHours >= 4) out += FaultWarning(
                "efficiency-drop", "Nagły spadek uzysku", if (drop > 0.35) FaultSeverity.CRITICAL else FaultSeverity.WARNING, FaultLevel.ANOMALY, now,
                "Dzisiejszy stosunek produkcji do modelu ${pct(r)} wobec typowego ${pct(med)} (−${pct(drop)})",
                listOf("${today.realKwh.f1()} kWh zmierzone, ${today.expectedKwh.f1()} kWh oczekiwane", "mediana z ${ratios.size} dni: ${pct(med)}"),
                conf(ratios.size, 14) * (today.coverageHours / 8).coerceAtMost(1.0),
                "Sprawdź panele (zabrudzenie, śnieg, nowe zacienienie), bezpieczniki i połączenia stringów.",
            )
        }

        // 2) PV voltage at power fell (possible bypass diode / panel / string fault).
        val volts = baseline.mapNotNull { it.pvVoltageAtPower }
        val v = today.pvVoltageAtPower
        if (v != null && volts.size >= 5) {
            val med = median(volts)
            if (v < med * 0.85) out += FaultWarning(
                "pv-voltage", "Spadek napięcia stringu PV", FaultSeverity.WARNING, FaultLevel.ANOMALY, now,
                "Napięcie PV przy dużej mocy ${v.f1()} V wobec typowego ${med.f1()} V",
                listOf("mediana z ${volts.size} dni", "spadek ${pct(1 - v / med)}"), conf(volts.size, 14),
                "Możliwa uszkodzona dioda bocznikująca lub panel – sprawdź napięcia paneli/stringu.",
            )
        }

        // 3) Inverter temperature at similar load rising or close to the limit.
        val temps = sorted.takeLast(10).mapNotNull { d -> d.inverterTempAtLoadC?.let { d.date.toEpochDay().toDouble() to it } }
        if (temps.size >= 5) {
            val fit = com.solartracker.pro.core.analytics.AutoCalibrationEngine.linearFit(temps.map { it.first }, temps.map { it.second })
            val last = temps.last().second
            if (fit.slope > 0.8 && fit.r2 > 0.5 || last > inverterTempLimitC - 5) out += FaultWarning(
                "inverter-temp", "Rosnąca temperatura falownika", if (last > inverterTempLimitC - 5) FaultSeverity.WARNING else FaultSeverity.INFO, FaultLevel.ANOMALY, now,
                "Temperatura przy podobnym obciążeniu rośnie o ${fit.slope.f1()} °C/dzień (ostatnio ${last.f1()} °C)",
                listOf("${temps.size} dni z obciążeniem > 50%", "R² = ${"%.2f".format(fit.r2)}"), conf(temps.size, 10) * fit.r2,
                "Sprawdź wentylator i przepływ powietrza wokół falownika, oczyść radiator.",
            )
        }

        // 4) Night base load increased (unexpected consumer).
        val nights = baseline.mapNotNull { it.nightLoadKw }
        val n = today.nightLoadKw
        if (n != null && nights.size >= 5) {
            val med = median(nights)
            if (n > med * 1.5 + 0.1) out += FaultWarning(
                "night-load", "Wyższe zużycie nocne", FaultSeverity.INFO, FaultLevel.ANOMALY, now,
                "Obciążenie nocne ${n.f2()} kW wobec typowego ${med.f2()} kW",
                listOf("mediana 01–05 z ${nights.size} dni"), conf(nights.size, 14),
                "Sprawdź, czy nie pracuje niepotrzebnie odbiornik (grzałka, pompa, chłodnia).",
            )
        }

        // 5) Repeated inverter faults (confirmed by the device).
        val faults = sorted.takeLast(7).flatMap { it.faultCodes.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        faults.filterValues { it >= 3 }.forEach { (code, count) ->
            out += FaultWarning(
                "fault-$code", "Powtarzający się błąd falownika (kod $code)", FaultSeverity.CRITICAL, FaultLevel.FAULT, now,
                "Kod błędu $code wystąpił $count razy w 7 dni", listOf("kody błędów z falownika"), 0.95,
                "Sprawdź opis kodu w instrukcji falownika; przy powtarzaniu skontaktuj się z serwisem.",
            )
        }

        // 6) Communication quality.
        if (linkQuality != null && linkQuality < 0.8) out += FaultWarning(
            "link", "Niestabilna komunikacja z falownikiem", FaultSeverity.WARNING, FaultLevel.ANOMALY, now,
            "Udane odczyty: ${pct(linkQuality)}", listOf("statystyka ostatnich odczytów"), 0.8,
            "Sprawdź zasięg Wi-Fi/LAN mostka lub kabel RS232/USB.",
        )

        // 7) String/MPPT imbalance (only when the inverter reports more than one).
        if (stringPowersW != null && stringPowersW.size >= 2 && stringPowersW.max() > 300) {
            val max = stringPowersW.max()
            val min = stringPowersW.min()
            if (min < max * 0.8) out += FaultWarning(
                "string-imbalance", "Różnica mocy między stringami/MPPT", FaultSeverity.WARNING, FaultLevel.ANOMALY, now,
                "Najsłabszy string ${min.toInt()} W wobec ${max.toInt()} W", listOf("bieżące moce MPPT"), 0.6,
                "Porównaj zacienienie i zabrudzenie stringów; jeśli to samo – sprawdź połączenia.",
            )
        }
        return out.sortedByDescending { it.severity }
    }

    private fun median(v: List<Double>): Double = v.sorted().let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    private fun conf(n: Int, full: Int) = (n.toDouble() / full).coerceIn(0.1, 1.0)
    private fun pct(f: Double) = "${(f * 100).toInt()}%"
    private fun Double.f1() = "%.1f".format(this)
    private fun Double.f2() = "%.2f".format(this)
}
