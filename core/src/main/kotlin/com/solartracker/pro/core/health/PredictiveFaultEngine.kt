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

/** Live/forecast context for predictive alerts. Every field is optional: missing data = no alert. */
data class PredictiveAlertInput(
    val now: java.time.Instant,
    val security: com.solartracker.pro.core.forecast.EnergySecurity? = null,
    /** Validated (sanitized) newest reading and recent readings (oldest first, e.g. last 15 min). */
    val telemetry: com.solartracker.pro.core.inverter.InverterTelemetry? = null,
    val recent: List<com.solartracker.pro.core.inverter.InverterTelemetry> = emptyList(),
    val batteryType: com.solartracker.pro.core.energy.BatteryType? = null,
    val batteryNominalVoltage: Double = 48.0,
    /** Configurable limits (typical values, not vendor data). */
    val batteryMaxTempC: Double = 50.0,
    val inverterMaxTempC: Double = 75.0,
    val linkFailures: Int = 0,
    val linkOfflineSince: java.time.Instant? = null,
    /** Forecast accuracy of the last 7 days and of the 7 days before. */
    val accuracyRecent: com.solartracker.pro.core.analytics.AccuracyReport? = null,
    val accuracyPrevious: com.solartracker.pro.core.analytics.AccuracyReport? = null,
    val typicalLoadKw: Double? = null,
    /** Share of today's production samples at the inverter limit (0..1). */
    val clippingShare: Double? = null,
    val performance: PerformanceReport? = null,
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

    /**
     * Predictive alerts from forecasts and recent live data: battery depletion, over-temperature,
     * communication loss, forecast deterioration, unexpected load, clipping, charging and voltage
     * anomalies, unusually low PV. Each alert carries reason, evidence, confidence and an action.
     */
    fun predictive(i: PredictiveAlertInput): List<FaultWarning> = buildList {
        val now = i.now
        fun w(id: String, title: String, sev: FaultSeverity, reason: String, evidence: List<String>, conf: Double, action: String, level: FaultLevel = FaultLevel.ANOMALY) =
            add(FaultWarning(id, title, sev, level, now, reason, evidence, conf.coerceIn(0.05, 0.99), action))

        i.security?.let { s ->
            if (s.shortageExpected && s.minSocAt != null) {
                val at = s.minSocAt
                val socAt6 = s.milestones.firstOrNull { it.label == "06:00" }
                w("battery-depletion", "BATTERY DEPLETION EXPECTED – przewidywane rozładowanie baterii", if (s.gridBackup) FaultSeverity.WARNING else FaultSeverity.CRITICAL,
                    "Prognoza: minimum SOC (${s.minSocPercent?.toInt()}%) zostanie osiągnięte ok. ${at.atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0)}",
                    listOfNotNull("SOC teraz: ${s.socNow?.toInt()}%", socAt6?.let { "Przewidywany SOC o 06:00: ${it.socPercent}% (${it.lowPercent}–${it.highPercent}%)" },
                        s.securityPercent?.let { "Bezpieczeństwo energetyczne: $it%" }),
                    s.confidence, if (s.gridBackup) "Odłóż duże odbiorniki lub zaplanuj pobór z sieci w tańszej taryfie" else "Ogranicz zużycie lub doładuj baterię (agregat/sieć) przed wieczorem")
            } else if (s.risk == com.solartracker.pro.core.forecast.EnergyRisk.MEDIUM && s.minSocAtPessimistic != null) {
                w("battery-depletion-risk", "Ryzyko rozładowania baterii", FaultSeverity.INFO,
                    "W gorszym scenariuszu (mniej słońca / większe zużycie) bateria osiągnie minimum", listOf(s.explanation), s.confidence * 0.7,
                    "Obserwuj zużycie wieczorem")
            }
        }

        val t = i.telemetry
        t?.battery?.temperatureC?.let { bt ->
            if (bt >= i.batteryMaxTempC) w("battery-overtemp", "Przegrzanie baterii", FaultSeverity.CRITICAL,
                "Temperatura baterii ${"%.0f".format(bt)} °C ≥ limit ${"%.0f".format(i.batteryMaxTempC)} °C", listOf("Pomiar falownika"), 0.9,
                "Zmniejsz prąd ładowania/rozładowania, sprawdź wentylację")
        }
        val invTemps = (i.recent + listOfNotNull(t)).mapNotNull { r -> r.inverter.temperatureC?.let { r.timestamp to it } }
        invTemps.lastOrNull()?.let { (_, temp) ->
            val first = invTemps.first()
            val minutes = java.time.Duration.between(first.first, invTemps.last().first).toMinutes().coerceAtLeast(1)
            val rate = (temp - first.second) / minutes * 10 // °C per 10 min
            if (temp >= i.inverterMaxTempC) w("inverter-overtemp", "Przegrzanie falownika", FaultSeverity.CRITICAL,
                "Temperatura falownika ${"%.0f".format(temp)} °C", listOf("Limit ${"%.0f".format(i.inverterMaxTempC)} °C"), 0.85, "Zmniejsz obciążenie, sprawdź wentylatory i przepływ powietrza")
            else if (temp >= i.inverterMaxTempC - 10 && rate > 2) w("inverter-overtemp-trend", "Rosnąca temperatura falownika", FaultSeverity.WARNING,
                "Temperatura ${"%.0f".format(temp)} °C rośnie o ${"%.1f".format(rate)} °C/10 min", listOf("Odczyty z ostatnich $minutes min"), 0.6,
                "Ogranicz duże odbiorniki do czasu spadku temperatury")
        }

        if (i.linkOfflineSince != null || i.linkFailures >= 3) {
            val mins = i.linkOfflineSince?.let { java.time.Duration.between(it, now).toMinutes() }
            w("comm-loss", "Utrata komunikacji z falownikiem", if ((mins ?: 0) >= 10) FaultSeverity.CRITICAL else FaultSeverity.WARNING,
                "Brak poprawnych odczytów" + (mins?.let { " od $it min" } ?: ""), listOf("Nieudane odczyty z rzędu: ${i.linkFailures}"), 0.95,
                "Sprawdź zasilanie mostka RS232/Wi-Fi, kabel i adres IP", FaultLevel.FAULT)
        }

        val rec = i.accuracyRecent
        val prev = i.accuracyPrevious
        if (rec != null && prev != null && rec.count >= 24 && prev.count >= 24 && prev.mae > 0 && rec.mae > prev.mae * 1.3) {
            w("forecast-deterioration", "Pogorszenie trafności prognozy", FaultSeverity.INFO,
                "Średni błąd wzrósł z ${"%.2f".format(prev.mae)} do ${"%.2f".format(rec.mae)} kWh/h", listOf("Ostatnie 7 dni vs poprzednie 7 dni"), 0.6,
                "Sprawdź zacienienie (nowe przeszkody), zabrudzenie paneli i ustawienia instalacji")
        }

        val load = t?.load?.powerW?.div(1000.0)
        val typical = i.typicalLoadKw
        if (load != null && typical != null && typical > 0.1) {
            val recentLoads = i.recent.mapNotNull { it.load.powerW?.div(1000.0) }
            val sustained = recentLoads.size >= 5 && recentLoads.takeLast(5).all { it > typical * 2 }
            if (load > typical * 2 && load - typical > 0.5 && sustained) w("unexpected-load", "Nieoczekiwany wzrost zużycia", FaultSeverity.WARNING,
                "Zużycie ${"%.2f".format(load)} kW, zwykle o tej porze ${"%.2f".format(typical)} kW", listOf("Utrzymuje się w kolejnych odczytach"), 0.7,
                "Sprawdź, czy nie pracuje niepotrzebnie duży odbiornik")
        }

        i.clippingShare?.let { share ->
            if (share > 0.15) w("pv-clipping", "Częste ograniczanie mocy PV (clipping)", FaultSeverity.INFO,
                "Falownik ogranicza moc przez ${"%.0f".format(share * 100)}% czasu produkcji dziś", listOf("Próbki przy limicie mocy falownika lub pełnej baterii"), 0.7,
                "Przesuń zużycie na godziny południowe (EMS) – energia jest tracona")
        }

        // Charging anomaly: sustained PV surplus, battery not full, yet no charging.
        val window = i.recent.takeLast(20)
        if (window.size >= 10) {
            val surplus = window.mapNotNull { r -> r.pv.powerW?.let { pv -> r.load.powerW?.let { pv - it } } }
            val charge = window.mapNotNull { it.battery.powerW }
            val soc = window.mapNotNull { it.battery.socPercent }
            if (surplus.size >= 10 && charge.size >= 10 && soc.isNotEmpty() && surplus.average() > 500 && soc.last() < 90 && charge.average() < 50) {
                w("charging-anomaly", "Anomalia ładowania baterii", FaultSeverity.WARNING,
                    "Nadwyżka PV ${"%.0f".format(surplus.average())} W, SOC ${soc.last().toInt()}%, ale bateria się nie ładuje",
                    listOf("Średnia moc baterii ${"%.0f".format(charge.average())} W w ${window.size} odczytach"), 0.65,
                    "Sprawdź ustawienia priorytetu ładowania, prąd ładowania i komunikację z BMS")
            }
        }

        // Voltage anomaly: resting voltage inconsistent with reported SOC. Only for lead-based chemistries:
        // lithium resting voltage shows hysteresis and a plateau, so it cannot contradict the BMS SOC.
        val type = i.batteryType?.takeIf {
            it == com.solartracker.pro.core.energy.BatteryType.AGM || it == com.solartracker.pro.core.energy.BatteryType.GEL ||
                it == com.solartracker.pro.core.energy.BatteryType.LEAD_ACID
        }
        val bat = t?.battery
        if (type != null && bat?.voltageV != null && bat.socPercent != null && bat.currentA != null && kotlin.math.abs(bat.currentA) < 2.0) {
            val est = com.solartracker.pro.core.energy.BatteryChemistry.socFromRestingVoltage(type, bat.voltageV, i.batteryNominalVoltage, atRest = true)
            val v = est.value
            val unc = est.uncertainty ?: 100.0
            if (v != null && unc <= 15 && kotlin.math.abs(v - bat.socPercent) > 25 + unc) {
                w("battery-voltage-anomaly", "Anomalia napięcia baterii", FaultSeverity.WARNING,
                    "Napięcie spoczynkowe ${"%.1f".format(bat.voltageV)} V odpowiada ok. ${v.toInt()}% SOC, falownik podaje ${bat.socPercent.toInt()}%",
                    listOf("Prąd baterii ${"%.1f".format(bat.currentA)} A (stan spoczynku)", "Krzywa napięcia: ${type.name}"), 0.5,
                    "Sprawdź typ baterii w ustawieniach falownika, połączenia i kalibrację SOC")
            }
        }

        i.performance?.let { p ->
            val unknown = p.losses.firstOrNull { it.cause == LossCause.UNKNOWN }?.percent
            if (p.performancePercent != null && unknown != null && unknown > 25) {
                w("pv-low", "Produkcja PV nietypowo niska", FaultSeverity.WARNING,
                    "Moc ${"%.0f".format(p.actualW)} W przy oczekiwanych ${"%.0f".format(p.expectedW)} W – niewyjaśnione ${"%.0f".format(unknown)}%",
                    p.losses.filter { it.cause != LossCause.UNKNOWN }.map { "${it.cause.label}: ${"%.1f".format(it.percent)}% (${it.basis})" }, 0.55,
                    "Sprawdź zacienienie, zabrudzenie, bezpieczniki i połączenia stringów")
            }
        }
    }
}
