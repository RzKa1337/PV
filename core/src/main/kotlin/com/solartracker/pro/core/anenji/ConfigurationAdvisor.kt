package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import com.solartracker.pro.core.energy.BatteryChemistry
import com.solartracker.pro.core.energy.BatteryType
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

enum class FindingSeverity { INFO, WARNING, CRITICAL }

/** One finding of the analyzer: always with reason, evidence, confidence and impact. Advice only – nothing is changed. */
data class Finding(
    val title: String,
    val severity: FindingSeverity,
    val reason: String,
    val evidence: List<String>,
    val confidence: Double,
    val impact: String,
    val impactKwh: Double? = null,
    val possibleCauses: List<String> = emptyList(),
)

/** Battery as configured in the app (user input) – used when the inverter does not expose its settings. */
data class BatteryContext(val type: BatteryType, val nominalVoltage: Double, val capacityAh: Double?)

/**
 * "AnenjiConfigurationAdvisor": READ ONLY. Detects potentially sub-optimal settings from the data and the snapshot
 * ("Potencjalnie nieoptymalne ustawienie: X") and never writes anything to the inverter.
 */
object AnenjiConfigurationAdvisor {
    const val NEAR_LIMIT = 0.97

    fun analyze(
        samples: List<AnalyzerSample>,
        settings: AnenjiSettingsSnapshot?,
        battery: BatteryContext?,
        pvLimitW: Double?,
        zone: ZoneId,
        expectedPvW: ((Instant) -> Double?)? = null,
    ): List<Finding> = buildList {
        val s = samples.sortedBy { it.time }.filter { it.origin != DataOrigin.SIMULATOR || samples.all { x -> x.origin == DataOrigin.SIMULATOR } }
        capacity(s, settings, battery)?.let { add(it) }
        clipping(s, pvLimitW, zone, expectedPvW)?.let { add(it) }
        chargeLimit(s, settings, expectedPvW)?.let { add(it) }
        addAll(voltages(s, settings, battery))
        outputVoltage(s, settings)?.let { add(it) }
    }

    /** Usable Ah from coulomb counting over discharges of ≥ 20 SOC points vs the configured capacity. */
    internal fun capacity(s: List<AnalyzerSample>, settings: AnenjiSettingsSnapshot?, battery: BatteryContext?): Finding? {
        val configured = settings?.number(SettingKey.BATTERY_CAPACITY) ?: battery?.capacityAh ?: return null
        val source = if (settings?.number(SettingKey.BATTERY_CAPACITY) != null) "ustawienie z logu" else "ustawienia aplikacji"
        val estimates = mutableListOf<Double>()
        var start: AnalyzerSample? = null
        var ah = 0.0
        for ((a, b) in s.zipWithNext()) {
            val dtH = Duration.between(a.time, b.time).toMillis() / 3_600_000.0
            val current = a[Channel.BATTERY_CURRENT] ?: a[Channel.BATTERY_POWER]?.let { p -> a[Channel.BATTERY_VOLTAGE]?.takeIf { it > 1 }?.let { p / it } }
            val socA = a[Channel.SOC]; val socB = b[Channel.SOC]
            val discharging = current != null && current < -0.5 && socA != null && socB != null && socB <= socA + 0.5 && dtH < 0.25
            if (discharging) {
                if (start == null) { start = a; ah = 0.0 }
                ah += -current!! * dtH
            } else if (start != null) {
                val d = (start[Channel.SOC] ?: 0.0) - (a[Channel.SOC] ?: 0.0)
                if (d >= 20) estimates += ah / (d / 100)
                start = null
            }
        }
        start?.let { st -> val d = (st[Channel.SOC] ?: 0.0) - (s.last()[Channel.SOC] ?: 0.0); if (d >= 20) estimates += ah / (d / 100) }
        val observed = Stats.median(estimates) ?: return null
        val ratio = observed / configured
        if (ratio >= 0.85 && ratio <= 1.15) return Finding("Pojemność baterii zgodna z obserwacją", FindingSeverity.INFO,
            "Pojemność z liczenia ładunku zgadza się z ustawieniem", listOf("Ustawiona: ${configured.toInt()} Ah ($source)", "Zaobserwowana: ~${observed.toInt()} Ah z ${estimates.size} rozładowań"),
            (0.5 + 0.08 * estimates.size).coerceAtMost(0.85), "brak")
        return Finding(
            "Rozbieżność pojemności baterii", FindingSeverity.WARNING,
            if (ratio < 1) "Zaobserwowana pojemność jest mniejsza niż ustawiona" else "Zaobserwowana pojemność jest większa niż ustawiona",
            listOf("Ustawiona: ${configured.toInt()} Ah ($source)", "Zaobserwowana: ~${observed.toInt()} Ah (mediana z ${estimates.size} rozładowań ≥ 20 p.p. SOC)",
                "SOC pochodzi z falownika – przy SOC liczonym z napięcia wynik jest mniej pewny"),
            (0.4 + 0.08 * estimates.size).coerceAtMost(0.85),
            "Prognozy czasu pracy z baterii mogą być błędne o ok. ${(abs(1 - ratio) * 100).toInt()}%",
            possibleCauses = listOf("degradacja baterii", "nieprawidłowy model SOC w falowniku/BMS", "błędnie ustawiona pojemność baterii"),
        )
    }

    /** PV repeatedly at the inverter/charger limit; lost energy only when an expected PV curve is available. */
    internal fun clipping(s: List<AnalyzerSample>, pvLimitW: Double?, zone: ZoneId, expected: ((Instant) -> Double?)?): Finding? {
        val limit = pvLimitW ?: return null
        val clipped = s.zipWithNext().filter { (a, _) -> (a[Channel.PV_POWER] ?: 0.0) >= limit * NEAR_LIMIT }
        if (clipped.isEmpty()) return null
        val days = clipped.map { it.first.time.atZone(zone).toLocalDate() }.distinct()
        val minutes = clipped.sumOf { (a, b) -> Duration.between(a.time, b.time).toMinutes().coerceAtMost(15) }
        val lost = expected?.let { f -> clipped.sumOf { (a, b) ->
            val dtH = Duration.between(a.time, b.time).toMillis() / 3_600_000.0
            ((f(a.time) ?: 0.0) - (a[Channel.PV_POWER] ?: 0.0)).coerceAtLeast(0.0) * dtH.coerceAtMost(0.25) / 1000
        } }
        val spanDays = Duration.between(s.first().time, s.last().time).toDays().coerceAtLeast(1)
        return Finding(
            "PV osiąga limit falownika", if (days.size >= 5) FindingSeverity.WARNING else FindingSeverity.INFO,
            "Produkcja PV wielokrotnie dochodzi do limitu ${limit.toInt()} W",
            listOf("Clipping w ${days.size} dniach (łącznie ok. $minutes min) w ciągu $spanDays dni danych") +
                (lost?.let { listOf("Utracona energia (model − pomiar przy limicie): ${"%.1f".format(it)} kWh") } ?: listOf("Bez krzywej oczekiwanej produkcji utraconej energii nie da się policzyć (N/A)")),
            if (lost != null) 0.75 else 0.6,
            lost?.let { "ok. ${"%.1f".format(it)} kWh w okresie, szacunkowo ~${"%.0f".format(it / spanDays * 365)} kWh/rok (zależy od sezonu)" } ?: "nieznany (brak modelu)",
            lost,
        )
    }

    /** Battery charging at the configured current limit while PV seems to have more to give. */
    internal fun chargeLimit(s: List<AnalyzerSample>, settings: AnenjiSettingsSnapshot?, expected: ((Instant) -> Double?)?): Finding? {
        val limit = settings?.number(SettingKey.MAX_CHARGE_CURRENT) ?: return null
        val at = s.filter { (it[Channel.BATTERY_CURRENT] ?: 0.0) >= limit * NEAR_LIMIT }
        if (at.size < 3) return null
        val curtailed = expected?.let { f -> at.count { x -> val e = f(x.time); val p = x[Channel.PV_POWER]; e != null && p != null && p < e * 0.85 } }
        return Finding(
            "Ładowanie często na limicie prądu", FindingSeverity.WARNING,
            "Prąd ładowania baterii osiąga ustawiony limit ${limit.toInt()} A",
            listOf("Odczytów na limicie: ${at.size} z ${s.size}") + (curtailed?.let { listOf("W $it z nich PV było poniżej możliwości modelu (prawdopodobnie ograniczone)") } ?: emptyList()),
            if (curtailed != null && curtailed > 0) 0.7 else 0.5,
            "Możliwa niewykorzystana energia PV (gdy brak innych odbiorów)",
            possibleCauses = listOf("zbyt niski limit prądu ładowania względem baterii i PV", "limit wymagany przez producenta baterii – sprawdź kartę katalogową przed zmianą"),
        )
    }

    /** Charging/cut-off voltages compared with the chemistry's resting-voltage curve (physics, no vendor guesses). */
    internal fun voltages(s: List<AnalyzerSample>, settings: AnenjiSettingsSnapshot?, battery: BatteryContext?): List<Finding> = buildList {
        val b = battery ?: return@buildList
        val p = BatteryChemistry.profile(b.type)
        val cells = Math.round(b.nominalVoltage / p.cellVoltage).toInt().coerceAtLeast(1)
        val fullRest = p.ocv.last().second * cells
        val emptyRest = p.ocv.first().second * cells
        fun ocvAt(soc: Double) = p.ocv.zipWithNext().firstOrNull { (a, c) -> soc in a.first..c.first }?.let { (a, c) ->
            (a.second + (c.second - a.second) * (soc - a.first) / (c.first - a.first)) * cells } ?: fullRest
        val bulk = settings?.number(SettingKey.BULK_VOLTAGE)
        val float = settings?.number(SettingKey.FLOAT_VOLTAGE)
        val cutoff = settings?.number(SettingKey.LOW_VOLTAGE_CUTOFF)
        val reconnect = settings?.number(SettingKey.RECONNECT_VOLTAGE)
        bulk?.let { v ->
            if (v < fullRest) add(Finding("Potencjalnie nieoptymalne ustawienie: napięcie ładowania", FindingSeverity.WARNING,
                "Napięcie ładowania jest niższe niż napięcie spoczynkowe pełnej baterii dla wybranej chemii",
                listOf("Ładowanie: ${"%.1f".format(v)} V", "Pełna bateria (spoczynek, ${b.type}, $cells ogniw): ~${"%.1f".format(fullRest)} V"),
                0.8, "Bateria nie naładuje się do 100% – mniejsza dostępna energia",
                possibleCauses = listOf("ustawiony inny typ baterii niż rzeczywisty", "napięcie dobrane dla innej liczby ogniw", "świadome ograniczenie (żywotność) – wtedy zignoruj")))
        }
        if (bulk != null && float != null && float > bulk + 0.05) add(Finding("Niespójne napięcia ładowania", FindingSeverity.WARNING,
            "Napięcie float jest wyższe niż bulk/absorpcji", listOf("Bulk: ${"%.1f".format(bulk)} V", "Float: ${"%.1f".format(float)} V"), 0.9,
            "Nieprawidłowy przebieg ładowania"))
        cutoff?.let { v ->
            if (v < emptyRest) add(Finding("Potencjalnie nieoptymalne ustawienie: napięcie odcięcia", FindingSeverity.WARNING,
                "Odcięcie poniżej napięcia pustej baterii dla wybranej chemii – ryzyko głębokiego rozładowania",
                listOf("Odcięcie: ${"%.1f".format(v)} V", "0% SOC (spoczynek): ~${"%.1f".format(emptyRest)} V"), 0.7, "Skrócenie żywotności baterii"))
            else if (v > ocvAt(20.0)) add(Finding("Wysokie napięcie odcięcia", FindingSeverity.INFO,
                "Odcięcie powyżej napięcia odpowiadającego ~20% SOC – część pojemności nie jest używana",
                listOf("Odcięcie: ${"%.1f".format(v)} V", "~20% SOC (spoczynek): ~${"%.1f".format(ocvAt(20.0))} V",
                    "Uwaga: pod obciążeniem napięcie spada, więc zapas bywa celowy"), 0.5, "Mniejsza energia dostępna w nocy"))
        }
        if (cutoff != null && reconnect != null && reconnect <= cutoff) add(Finding("Niespójne napięcia odcięcia i powrotu", FindingSeverity.WARNING,
            "Napięcie powrotu nie jest wyższe niż napięcie odcięcia", listOf("Odcięcie: ${"%.1f".format(cutoff)} V", "Powrót: ${"%.1f".format(reconnect)} V"), 0.9,
            "Możliwe wielokrotne przełączanie (oscylacje) przy niskiej baterii"))
        // Observation without settings: the battery never reaches its full resting voltage.
        val maxV = s.mapNotNull { it[Channel.BATTERY_VOLTAGE] }.maxOrNull()
        val days = s.map { it.time.atZone(ZoneId.of("UTC")).toLocalDate() }.distinct().size
        if (maxV != null && days >= 7 && maxV < fullRest * 0.98) add(Finding("Bateria nie osiąga pełnego naładowania", FindingSeverity.INFO,
            "W całym okresie napięcie baterii nie zbliżyło się do napięcia pełnej baterii",
            listOf("Maks. napięcie: ${"%.1f".format(maxV)} V w $days dniach", "Pełna bateria (spoczynek): ~${"%.1f".format(fullRest)} V"), 0.5,
            "Możliwe zbyt niskie napięcie ładowania lub za mało energii PV",
            possibleCauses = listOf("za mało PV względem zużycia", "zbyt niskie napięcie ładowania", "inny typ baterii niż w ustawieniach aplikacji")))
    }

    internal fun outputVoltage(s: List<AnalyzerSample>, settings: AnenjiSettingsSnapshot?): Finding? {
        val set = settings?.number(SettingKey.OUTPUT_VOLTAGE) ?: return null
        val measured = Stats.median(s.mapNotNull { it[Channel.AC_VOLTAGE]?.takeIf { v -> v > 50 } }) ?: return null
        if (abs(measured - set) / set < 0.05) return null
        return Finding("Napięcie wyjściowe inne niż nastawa", FindingSeverity.INFO, "Mediana napięcia wyjścia różni się od nastawy o ponad 5%",
            listOf("Nastawa: ${set.toInt()} V", "Mediana pomiaru: ${"%.1f".format(measured)} V"), 0.5, "Możliwy błąd mapy rejestrów lub pomiaru")
    }
}
