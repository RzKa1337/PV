package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import com.solartracker.pro.core.diagnostics.MpptAnalyzer
import com.solartracker.pro.core.diagnostics.MpptConfig
import com.solartracker.pro.core.diagnostics.MpptFlag
import com.solartracker.pro.core.inverter.OperatingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

enum class AnomalyCategory(val label: String) { PV("PV"), BATTERY("Bateria"), INVERTER("Falownik"), GRID("Sieć"), COMMUNICATION("Komunikacja") }

enum class AnomalyType(val label: String, val category: AnomalyCategory) {
    LOW_PV("Nieoczekiwanie niskie PV", AnomalyCategory.PV),
    HIGH_PV("Nieoczekiwanie wysokie PV", AnomalyCategory.PV),
    MPPT_IMBALANCE("Nierównowaga MPPT", AnomalyCategory.PV),
    CLIPPING("Clipping (limit mocy)", AnomalyCategory.PV),
    RAPID_PV_DROP("Gwałtowny spadek PV", AnomalyCategory.PV),
    PV_VOLTAGE_ABNORMAL("Nietypowe napięcie PV", AnomalyCategory.PV),
    PV_CURRENT_ABNORMAL("Niespójny prąd/moc PV", AnomalyCategory.PV),
    SOC_DROP("Nieoczekiwany spadek SOC", AnomalyCategory.BATTERY),
    SOC_JUMP("Podejrzany skok SOC", AnomalyCategory.BATTERY),
    LOW_BATTERY("Niski poziom baterii", AnomalyCategory.BATTERY),
    VOLTAGE_SAG("Nietypowy spadek napięcia baterii", AnomalyCategory.BATTERY),
    CHARGE_CURRENT_ABNORMAL("Nietypowy prąd ładowania", AnomalyCategory.BATTERY),
    DISCHARGE_CURRENT_ABNORMAL("Nietypowy prąd rozładowania", AnomalyCategory.BATTERY),
    LONG_CHARGING("Bardzo długie ładowanie", AnomalyCategory.BATTERY),
    RESTART("Nieoczekiwany restart", AnomalyCategory.INVERTER),
    TEMPERATURE("Anomalia temperatury falownika", AnomalyCategory.INVERTER),
    OVERLOAD("Przeciążenie", AnomalyCategory.INVERTER),
    EFFICIENCY("Niska sprawność konwersji", AnomalyCategory.INVERTER),
    BYPASS("Nieoczekiwany bypass", AnomalyCategory.INVERTER),
    UNEXPECTED_GRID("Nieoczekiwany pobór z sieci", AnomalyCategory.GRID),
    GRID_TRANSITIONS("Częste przełączenia na sieć", AnomalyCategory.GRID),
    GRID_OUTAGE("Zanik napięcia sieci", AnomalyCategory.GRID),
    GRID_VOLTAGE("Napięcie sieci poza normą", AnomalyCategory.GRID),
    GRID_FREQUENCY("Częstotliwość sieci poza normą", AnomalyCategory.GRID),
    COMM_TIMEOUT("Przekroczenia czasu komunikacji", AnomalyCategory.COMMUNICATION),
    COMM_STALE("Zamrożone dane", AnomalyCategory.COMMUNICATION),
    COMM_RECONNECT("Ponowne połączenia", AnomalyCategory.COMMUNICATION),
    COMM_MISSING("Brakujące próbki", AnomalyCategory.COMMUNICATION),
    COMM_INVALID_FRAME("Nieprawidłowe ramki", AnomalyCategory.COMMUNICATION),
}

/** A detected anomaly with the samples behind it (for evidence and raw-data drill-down). */
data class Anomaly(
    val type: AnomalyType,
    val start: Instant,
    val end: Instant,
    val observed: Double?,
    val expected: Double?,
    val unit: String,
    val detail: String,
    val samples: List<AnalyzerSample>,
)

/** Inputs shared by all forensic steps. */
data class ForensicContext(
    val samples: List<AnalyzerSample>,
    val events: List<AnenjiEvent>,
    val comm: List<CommRecord>,
    val zone: ZoneId,
    val context: ContextProvider?,
    val baseline: SystemBaselineEngine,
    val settings: AnenjiSettingsSnapshot?,
    val battery: BatteryContext?,
    val pvLimitW: Double?,
    /** Price of backup energy (grid or generator) per kWh; null = cost N/A. */
    val pricePerKwh: Double?,
    val canExport: Boolean = false,
)

/**
 * Time-series anomaly detection. Not a single threshold: each rule combines absolute/relative limits with the historical
 * baseline, sun position, the PV model/weather, battery and load state, events and settings. Without the needed inputs a
 * rule is skipped (never "normal" by default).
 */
object ForensicAnomalyDetector {
    const val MIN_RUN = 3 // consecutive samples for a sustained anomaly

    fun detect(f: ForensicContext): List<Anomaly> {
        val s = f.samples.sortedBy { it.time }
        if (s.isEmpty()) return emptyList()
        val out = mutableListOf<Anomaly>()
        val ctx = f.context

        // ---- PV vs model and baseline (sustained runs).
        if (ctx != null) {
            fun runs(pred: (AnalyzerSample, AnalysisContext) -> Boolean): List<List<AnalyzerSample>> {
                val res = mutableListOf<List<AnalyzerSample>>(); var cur = mutableListOf<AnalyzerSample>()
                for (x in s) {
                    val c = ctx.at(x.time)
                    if (c != null && pred(x, c)) cur.add(x) else { if (cur.size >= MIN_RUN) res += cur; cur = mutableListOf() }
                }
                if (cur.size >= MIN_RUN) res += cur
                return res
            }
            val curtailed = { x: AnalyzerSample -> !f.canExport && (x[Channel.SOC] ?: 0.0) >= 97 }
            runs { x, c ->
                val p = x[Channel.PV_POWER]; val e = c.expectedPvW
                p != null && e != null && e > 300 && (c.sunElevationDeg ?: 0.0) > 10 && p < e * 0.7 && !curtailed(x) && c.snowExpected != true
            }.forEach { r ->
                // Confirm against the installation's own baseline before calling it low.
                val mid = r[r.size / 2]
                val b = f.baseline.pvAt(mid.time)
                val p = mid[Channel.PV_POWER]!!
                if (b == null || p < b.low * 0.85) {
                    out += Anomaly(AnomalyType.LOW_PV, r.first().time, r.last().time, r.map { it[Channel.PV_POWER]!! }.average(),
                        r.mapNotNull { ctx.at(it.time)?.expectedPvW }.average(), "W",
                        "PV ${p.toInt()} W przy oczekiwanych ${ctx.at(mid.time)?.expectedPvW?.toInt()} W" + (b?.let { " (typowo ${it.low.toInt()}–${it.high.toInt()} W)" } ?: " (brak baseline)"), r)
                }
            }
            runs { x, c -> val p = x[Channel.PV_POWER]; val e = c.expectedPvW; p != null && e != null && e > 300 && p > e * 1.3 }.forEach { r ->
                val b = f.baseline.pvAt(r[r.size / 2].time)
                if (b == null || r[r.size / 2][Channel.PV_POWER]!! > b.high * 1.15) out += Anomaly(AnomalyType.HIGH_PV, r.first().time, r.last().time,
                    r.map { it[Channel.PV_POWER]!! }.average(), r.mapNotNull { ctx.at(it.time)?.expectedPvW }.average(), "W", "PV powyżej modelu o ponad 30%", r)
            }
            // Rapid drop on a clear sky (a cloud drop is not an anomaly).
            for ((a, b) in s.zipWithNext()) {
                val pa = a[Channel.PV_POWER]; val pb = b[Channel.PV_POWER]
                val ca = ctx.at(a.time); val cb = ctx.at(b.time)
                if (pa != null && pb != null && pa > 500 && pb < pa * 0.5 && Duration.between(a.time, b.time) <= Duration.ofMinutes(10) &&
                    (ca?.clearSkyIndex ?: 0.0) >= 0.8 && (cb?.clearSkyIndex ?: 0.0) >= 0.8 && (cb?.expectedPvW ?: 0.0) > pa * 0.7) {
                    out += Anomaly(AnomalyType.RAPID_PV_DROP, a.time, b.time, pb, pa, "W", "PV ${pa.toInt()} → ${pb.toInt()} W w ${Duration.between(a.time, b.time).toMinutes()} min przy czystym niebie", listOf(a, b))
                }
            }
        }

        // ---- Clipping at the configured limit.
        f.pvLimitW?.let { limit ->
            sustained(s) { (it[Channel.PV_POWER] ?: 0.0) >= limit * 0.97 }.forEach { r ->
                out += Anomaly(AnomalyType.CLIPPING, r.first().time, r.last().time, r.maxOf { it[Channel.PV_POWER]!! }, limit, "W", "PV na limicie ${limit.toInt()} W", r)
            }
        }

        // ---- MPPT imbalance (peer comparison, equal split when not configured).
        sustained(s) { x ->
            x.mppts.size >= 2 && run {
                val peak = 1000.0
                MpptAnalyzer.analyze(x.mppts, x.mppts.map { MpptConfig(it.index, peak) }).abnormal.isNotEmpty()
            }
        }.forEach { r ->
            val a = MpptAnalyzer.analyze(r.last().mppts, r.last().mppts.map { MpptConfig(it.index, 1000.0) })
            val bad = a.statuses.filter { it.flag == MpptFlag.ABNORMAL }
            out += Anomaly(AnomalyType.MPPT_IMBALANCE, r.first().time, r.last().time, bad.firstOrNull()?.peerDeviationPercent, 0.0, "%",
                bad.joinToString { "${it.label} ${"%.0f".format(it.peerDeviationPercent)}% względem pozostałych" }, r)
        }

        // ---- PV voltage outlier while producing (robust: 5 × MAD from the median).
        val pvV = s.filter { (it[Channel.PV_POWER] ?: 0.0) > 200 }.mapNotNull { x -> x[Channel.PV_VOLTAGE]?.let { x to it } }
        if (pvV.size >= 20) {
            val med = Stats.median(pvV.map { it.second })!!
            val mad = Stats.median(pvV.map { abs(it.second - med) })!!.coerceAtLeast(med * 0.02)
            pvV.filter { abs(it.second - med) > 5 * 1.4826 * mad }.groupConsecutive(s).forEach { r ->
                out += Anomaly(AnomalyType.PV_VOLTAGE_ABNORMAL, r.first().time, r.last().time, r.first()[Channel.PV_VOLTAGE], med, "V", "Napięcie PV odbiega od typowego ${med.toInt()} V", r)
            }
        }
        // ---- PV power vs V × I.
        s.filter { x -> val v = x[Channel.PV_VOLTAGE]; val i = x[Channel.PV_CURRENT]; val p = x[Channel.PV_POWER]
            v != null && i != null && p != null && p > 200 && abs(v * i - p) / p > 0.35 }.groupConsecutive(s).filter { it.size >= MIN_RUN }.forEach { r ->
            out += Anomaly(AnomalyType.PV_CURRENT_ABNORMAL, r.first().time, r.last().time, r.first()[Channel.PV_POWER], r.first()[Channel.PV_VOLTAGE]!! * r.first()[Channel.PV_CURRENT]!!,
                "W", "Moc PV niespójna z V×I (>35%)", r)
        }

        // ---- Battery.
        val capAh = f.settings?.number(SettingKey.BATTERY_CAPACITY) ?: f.battery?.capacityAh
        for ((a, b) in s.zipWithNext()) {
            val sa = a[Channel.SOC] ?: continue; val sb = b[Channel.SOC] ?: continue
            val dt = Duration.between(a.time, b.time)
            if (dt <= Duration.ofMinutes(5) && abs(sb - sa) >= 10) {
                out += Anomaly(AnomalyType.SOC_JUMP, a.time, b.time, sb, sa, "%", "SOC ${sa.toInt()} → ${sb.toInt()}% w ${dt.toMinutes()} min", listOf(a, b))
            }
        }
        if (capAh != null) {
            // SOC falling faster than the measured battery energy explains.
            windows(s, Duration.ofMinutes(30)).forEach { w ->
                val s0 = w.first()[Channel.SOC]; val s1 = w.last()[Channel.SOC]
                val ah = w.zipWithNext().sumOf { (a, b) ->
                    val i = a[Channel.BATTERY_CURRENT] ?: return@sumOf Double.NaN
                    i * Duration.between(a.time, b.time).toMillis() / 3_600_000.0
                }
                if (s0 != null && s1 != null && ah.isFinite()) {
                    val explained = ah / capAh * 100
                    val observed = s1 - s0
                    if (observed <= -15 && observed < explained - 10) out += Anomaly(AnomalyType.SOC_DROP, w.first().time, w.last().time, observed, explained, "p.p.",
                        "SOC spadło o ${(-observed).toInt()} p.p., a przepływ energii tłumaczy ${(-explained).toInt()} p.p.", w)
                }
            }
            sustained(s) { (it[Channel.BATTERY_CURRENT] ?: 0.0) < -capAh * 1.0 }.forEach { r ->
                out += Anomaly(AnomalyType.DISCHARGE_CURRENT_ABNORMAL, r.first().time, r.last().time, r.minOf { it[Channel.BATTERY_CURRENT]!! }, -capAh, "A", "Rozładowanie powyżej 1C (${capAh.toInt()} A)", r)
            }
        }
        f.settings?.number(SettingKey.MAX_CHARGE_CURRENT)?.let { lim ->
            sustained(s) { (it[Channel.BATTERY_CURRENT] ?: 0.0) > lim * 1.1 }.forEach { r ->
                out += Anomaly(AnomalyType.CHARGE_CURRENT_ABNORMAL, r.first().time, r.last().time, r.maxOf { it[Channel.BATTERY_CURRENT]!! }, lim, "A", "Prąd ładowania powyżej nastawy ${lim.toInt()} A", r)
            }
        }
        // Voltage sag not explained by a current step (> 5 % within 10 min while the current changed < 20 %).
        for ((a, b) in s.zipWithNext()) {
            val va = a[Channel.BATTERY_VOLTAGE] ?: continue; val vb = b[Channel.BATTERY_VOLTAGE] ?: continue
            val ia = a[Channel.BATTERY_CURRENT] ?: continue; val ib = b[Channel.BATTERY_CURRENT] ?: continue
            if (Duration.between(a.time, b.time) <= Duration.ofMinutes(10) && vb < va * 0.95 && abs(ib - ia) <= maxOf(2.0, abs(ia) * 0.2)) {
                out += Anomaly(AnomalyType.VOLTAGE_SAG, a.time, b.time, vb, va, "V", "Napięcie ${"%.1f".format(va)} → ${"%.1f".format(vb)} V bez zmiany prądu", listOf(a, b))
            }
        }
        // Long charging days that never reach full.
        s.groupBy { it.time.atZone(f.zone).toLocalDate() }.forEach { (_, day) ->
            val charging = day.zipWithNext().filter { (a, _) -> (a[Channel.BATTERY_POWER] ?: 0.0) > 50 }
            val hours = charging.sumOf { (a, b) -> Duration.between(a.time, b.time).toMinutes().coerceAtMost(15) } / 60.0
            val maxSoc = day.mapNotNull { it[Channel.SOC] }.maxOrNull()
            if (hours > 10 && maxSoc != null && maxSoc < 95) out += Anomaly(AnomalyType.LONG_CHARGING, day.first().time, day.last().time, hours, 10.0, "h",
                "Ładowanie przez ${"%.1f".format(hours)} h bez osiągnięcia 95% SOC (maks. ${maxSoc.toInt()}%)", charging.map { it.first })
        }
        // Low battery warnings (from the event log) become anomalies to analyse.
        f.events.filter { it.category == EventCategory.BATTERY }.forEach { e ->
            val w = s.filter { !it.time.isBefore(e.start) && !it.time.isAfter(e.end ?: e.start) }
            out += Anomaly(AnomalyType.LOW_BATTERY, e.start, e.end ?: e.start, w.mapNotNull { it[Channel.SOC] }.minOrNull(), null, "%", e.description, w)
        }

        // ---- Inverter.
        f.events.filter { it.description.startsWith("Restart") }.forEach { e ->
            out += Anomaly(AnomalyType.RESTART, e.start, e.start, null, null, "", e.description, s.filter { abs(Duration.between(it.time, e.start).toMinutes()) <= 10 })
        }
        sustained(s) { x ->
            val t = x[Channel.INVERTER_TEMPERATURE] ?: return@sustained false
            val b = f.baseline.hourly(Channel.INVERTER_TEMPERATURE, x.time)
            t > 75 || (b != null && t > b.high + 15)
        }.forEach { r -> out += Anomaly(AnomalyType.TEMPERATURE, r.first().time, r.last().time, r.maxOf { it[Channel.INVERTER_TEMPERATURE]!! }, null, "°C", "Temperatura falownika wyższa niż zwykle o tej porze", r) }
        sustained(s) { (it[Channel.LOAD_PERCENT] ?: 0.0) > 100 }.forEach { r ->
            out += Anomaly(AnomalyType.OVERLOAD, r.first().time, r.last().time, r.maxOf { it[Channel.LOAD_PERCENT]!! }, 100.0, "%", "Obciążenie powyżej 100%", r)
        }
        f.events.filter { "przeciąż" in it.description.lowercase() }.forEach { e ->
            out += Anomaly(AnomalyType.OVERLOAD, e.start, e.end ?: e.start, null, null, "", e.description, s.filter { abs(Duration.between(it.time, e.start).toMinutes()) <= 5 })
        }
        s.groupBy { it.time.atZone(f.zone).toLocalDate() }.forEach { (_, day) ->
            val (eff, n) = com.solartracker.pro.core.diagnostics.ConversionEfficiency.median(day.map { AnalyzerSamples.toTelemetry(it) })
            if (eff != null && n >= 10 && eff < 0.8) out += Anomaly(AnomalyType.EFFICIENCY, day.first().time, day.last().time, eff * 100, 90.0, "%",
                "Mediana sprawności konwersji ${(eff * 100).toInt()}% z $n odczytów", day)
        }
        sustained(s) { it.mode == OperatingMode.BYPASS && (it[Channel.SOC] ?: 0.0) > 40 }.forEach { r ->
            out += Anomaly(AnomalyType.BYPASS, r.first().time, r.last().time, r.first()[Channel.SOC], null, "%", "Bypass mimo dostępnej baterii (SOC > 40%)", r)
        }

        // ---- Grid.
        sustained(s) { (it[Channel.GRID_POWER] ?: 0.0) > 50 && (it[Channel.SOC] ?: 0.0) > 40 }.forEach { r ->
            out += Anomaly(AnomalyType.UNEXPECTED_GRID, r.first().time, r.last().time, r.map { it[Channel.GRID_POWER]!! }.average(), 0.0, "W",
                "Pobór z sieci przy SOC ${r.first()[Channel.SOC]!!.toInt()}%", r)
        }
        f.events.filter { it.category == EventCategory.GRID }.groupBy { it.start.atZone(f.zone).toLocalDate() }.filterValues { it.size > 6 }.forEach { (d, ev) ->
            out += Anomaly(AnomalyType.GRID_TRANSITIONS, ev.first().start, ev.last().start, ev.size.toDouble(), 6.0, "", "${ev.size} przełączeń na sieć ($d)", emptyList())
        }
        // Outage: the grid is normally present here, then its voltage collapses while the inverter keeps reporting.
        if (s.any { (it[Channel.GRID_VOLTAGE] ?: 0.0) > 180 }) sustained(s) { x -> (x[Channel.GRID_VOLTAGE] ?: Double.NaN) < 50 }.forEach { r ->
            out += Anomaly(AnomalyType.GRID_OUTAGE, r.first().time, r.last().time, r.maxOf { it[Channel.GRID_VOLTAGE]!! }, 230.0, "V",
                "Brak napięcia sieci przez ${Duration.between(r.first().time, r.last().time).toMinutes() + 5} min", r)
        }
        // EN 50160 limits for 230 V / 50 Hz networks (±10 % voltage, ±1 % frequency); only when the grid is present.
        sustained(s) { x -> val v = x[Channel.GRID_VOLTAGE]; v != null && v > 100 && (v < 207 || v > 253) }.forEach { r ->
            out += Anomaly(AnomalyType.GRID_VOLTAGE, r.first().time, r.last().time, r.first()[Channel.GRID_VOLTAGE], 230.0, "V", "Napięcie sieci poza 207–253 V (EN 50160)", r)
        }
        sustained(s) { x -> val fz = x[Channel.GRID_FREQUENCY]; val v = x[Channel.GRID_VOLTAGE] ?: 0.0; fz != null && v > 100 && (fz < 49.5 || fz > 50.5) }.forEach { r ->
            out += Anomaly(AnomalyType.GRID_FREQUENCY, r.first().time, r.last().time, r.first()[Channel.GRID_FREQUENCY], 50.0, "Hz", "Częstotliwość poza 49,5–50,5 Hz", r)
        }

        // ---- Communication.
        val comm = AnenjiCommunicationAnalyzer.analyze(s, f.comm)
        failureRuns(f.comm).forEach { (start, end, kind) ->
            val type = when (kind) { CommError.INVALID_FRAME, CommError.CRC -> AnomalyType.COMM_INVALID_FRAME; else -> AnomalyType.COMM_TIMEOUT }
            out += Anomaly(type, start, end, Duration.between(start, end).seconds.toDouble(), 0.0, "s", "Brak odpowiedzi falownika (${kind.label})", emptyList())
        }
        if (comm.reconnects > 3) out += Anomaly(AnomalyType.COMM_RECONNECT, f.comm.first().time, f.comm.last().time, comm.reconnects.toDouble(), 3.0, "", "${comm.reconnects} ponownych połączeń", emptyList())
        if (comm.frozenRuns > 0) out += Anomaly(AnomalyType.COMM_STALE, s.first().time, s.last().time, comm.frozenRuns.toDouble(), 0.0, "", "${comm.frozenRuns} serii identycznych odczytów", emptyList())
        val interval = AnenjiEventLog.typicalInterval(s)
        if (interval != null) s.zipWithNext().filter { (a, b) -> Duration.between(a.time, b.time) > interval.multipliedBy(3) && Duration.between(a.time, b.time) > Duration.ofMinutes(2) }
            .filter { (a, b) -> f.comm.none { !it.ok && !it.time.isBefore(a.time) && !it.time.isAfter(b.time) } }
            .forEach { (a, b) -> out += Anomaly(AnomalyType.COMM_MISSING, a.time, b.time, Duration.between(a.time, b.time).toMinutes().toDouble(), 0.0, "min", "Luka w danych ${Duration.between(a.time, b.time).toMinutes()} min", listOf(a, b)) }
        return out.sortedBy { it.start }
    }

    private fun sustained(s: List<AnalyzerSample>, pred: (AnalyzerSample) -> Boolean): List<List<AnalyzerSample>> {
        val res = mutableListOf<List<AnalyzerSample>>(); var cur = mutableListOf<AnalyzerSample>()
        for (x in s) if (pred(x)) cur.add(x) else { if (cur.size >= MIN_RUN) res += cur; cur = mutableListOf() }
        if (cur.size >= MIN_RUN) res += cur
        return res
    }

    /** Non-overlapping windows of [d]. */
    private fun windows(s: List<AnalyzerSample>, d: Duration): List<List<AnalyzerSample>> {
        val res = mutableListOf<List<AnalyzerSample>>(); var cur = mutableListOf<AnalyzerSample>()
        for (x in s) { if (cur.isNotEmpty() && Duration.between(cur.first().time, x.time) > d) { res += cur; cur = mutableListOf() }; cur.add(x) }
        if (cur.size >= 2) res += cur
        return res
    }

    private fun List<Pair<AnalyzerSample, Double>>.groupConsecutive(all: List<AnalyzerSample>): List<List<AnalyzerSample>> {
        val idx = all.withIndex().associate { it.value.time to it.index }
        val res = mutableListOf<List<AnalyzerSample>>(); var cur = mutableListOf<AnalyzerSample>(); var last = -2
        for ((x, _) in this) { val i = idx[x.time] ?: continue; if (i != last + 1 && cur.isNotEmpty()) { res += cur; cur = mutableListOf() }; cur.add(x); last = i }
        if (cur.isNotEmpty()) res += cur
        return res
    }

    @JvmName("groupConsecutiveSamples")
    private fun List<AnalyzerSample>.groupConsecutive(all: List<AnalyzerSample>): List<List<AnalyzerSample>> = map { it to 0.0 }.groupConsecutive(all)

    private fun failureRuns(c: List<CommRecord>): List<Triple<Instant, Instant, CommError>> {
        val res = mutableListOf<Triple<Instant, Instant, CommError>>()
        var start: CommRecord? = null; var last: CommRecord? = null
        for (r in c.sortedBy { it.time }) {
            if (!r.ok) { if (start == null) start = r; last = r }
            else if (start != null) { res += Triple(start.time, r.time, start.error ?: CommError.OTHER); start = null }
        }
        start?.let { res += Triple(it.time, last!!.time, it.error ?: CommError.OTHER) }
        return res
    }
}
