package com.solartracker.pro.core.anenji

import com.solartracker.pro.core.analytics.Stats
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** External context for correlation; every field optional (null = unknown, never assumed). */
fun interface ContextProvider {
    fun at(time: Instant): AnalysisContext?
}

data class AnalysisContext(
    /** Model PV power for this moment [W] (weather, shading, temperature) – from the loss chain / forecast engine. */
    val expectedPvW: Double? = null,
    val cloudCoverPercent: Double? = null,
    val clearSkyIndex: Double? = null,
    val ambientC: Double? = null,
    val sunElevationDeg: Double? = null,
)

data class TimelineEntry(val time: Instant, val text: String)

data class IncidentReport(
    val at: Instant,
    val from: Instant,
    val to: Instant,
    val timeline: List<TimelineEntry>,
    val conclusion: String,
    val evidence: List<String>,
    val confidence: Double,
    val samples: Int,
)

/**
 * "WHAT HAPPENED?" – reconstructs a window around a moment from samples, events and context, then states the most
 * likely chain of causes with the evidence behind it. Deterministic rules on measured data; no guessing beyond it.
 */
object IncidentAnalyzer {
    fun analyze(
        at: Instant,
        samples: List<AnalyzerSample>,
        events: List<AnenjiEvent>,
        zone: ZoneId,
        context: ContextProvider? = null,
        window: Duration = Duration.ofMinutes(30),
    ): IncidentReport {
        val from = at.minus(window)
        val to = at.plus(window)
        val s = samples.filter { !it.time.isBefore(from) && !it.time.isAfter(to) }.sortedBy { it.time }
        val ev = events.filter { e -> !e.start.isBefore(from) && !e.start.isAfter(to) }
        val f = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        val tl = mutableListOf<TimelineEntry>()
        if (s.isEmpty()) {
            return IncidentReport(at, from, to, ev.map { TimelineEntry(it.start, it.description) },
                if (ev.isNotEmpty()) "Brak pomiarów w tym oknie – dostępne tylko zdarzenia" else "Brak danych w oknie ±${window.toMinutes()} min",
                emptyList(), if (ev.isNotEmpty()) 0.3 else 0.0, 0)
        }
        fun w(x: Double?) = x?.let { if (abs(it) >= 1000) "%.2f kW".format(it / 1000) else "%.0f W".format(it) } ?: "N/A"
        val first = s.first()
        tl += TimelineEntry(first.time, listOfNotNull(first[Channel.SOC]?.let { "SOC ${it.toInt()}%" }, first[Channel.PV_POWER]?.let { "PV ${w(it)}" },
            first[Channel.LOAD_POWER]?.let { "obciążenie ${w(it)}" }).joinToString(", ").ifEmpty { "początek okna" })

        val socThresholds = listOf(50.0, 30.0, 20.0, 10.0)
        for ((a, b) in s.zipWithNext()) {
            val sa = a[Channel.SOC]; val sb = b[Channel.SOC]
            if (sa != null && sb != null) socThresholds.filter { sb < it && sa >= it }.forEach { tl += TimelineEntry(b.time, "SOC spadło poniżej ${it.toInt()}% (${sb.toInt()}%)") }
            val pa = a[Channel.PV_POWER]; val pb = b[Channel.PV_POWER]
            if (pa != null && pb != null && abs(pb - pa) >= maxOf(300.0, 0.3 * maxOf(pa, pb))) tl += TimelineEntry(b.time, "PV ${if (pb > pa) "wzrosło" else "spadło"}: ${w(pa)} → ${w(pb)}")
            val la = a[Channel.LOAD_POWER]; val lb = b[Channel.LOAD_POWER]
            if (la != null && lb != null && abs(lb - la) >= maxOf(500.0, 0.5 * maxOf(la, 1.0))) tl += TimelineEntry(b.time, "Obciążenie ${if (lb > la) "wzrosło" else "spadło"}: ${w(la)} → ${w(lb)}")
            val va = a[Channel.BATTERY_VOLTAGE]; val vb = b[Channel.BATTERY_VOLTAGE]
            if (va != null && vb != null && vb < va * 0.97) tl += TimelineEntry(b.time, "Napięcie baterii spadło: ${"%.1f".format(va)} → ${"%.1f".format(vb)} V")
        }
        ev.forEach { tl += TimelineEntry(it.start, it.description) }

        // Correlation and conclusion.
        val evidence = mutableListOf<String>()
        val near = s.minBy { abs(Duration.between(it.time, at).seconds) }
        val soc = near[Channel.SOC]
        val pv = near[Channel.PV_POWER]
        val load = near[Channel.LOAD_POWER]
        val loadBefore = Stats.median(s.filter { it.time.isBefore(at.minus(Duration.ofMinutes(10))) }.mapNotNull { it[Channel.LOAD_POWER] })
        val ctx = context?.at(at)
        soc?.let { evidence += "SOC w chwili zdarzenia: ${it.toInt()}%" }
        pv?.let { evidence += "PV: ${w(it)}" + (ctx?.expectedPvW?.let { e -> " (model: ${w(e)})" } ?: "") }
        load?.let { evidence += "Obciążenie: ${w(it)}" + (loadBefore?.let { b -> " (wcześniej ~${w(b)})" } ?: "") }
        ctx?.cloudCoverPercent?.let { evidence += "Zachmurzenie (prognoza): ${it.toInt()}%" }
        val comm = ev.any { it.category == EventCategory.COMMUNICATION }
        val lowBatteryEvent = ev.any { it.category == EventCategory.BATTERY || "bateri" in it.description.lowercase() }
        val gridStart = ev.any { it.category == EventCategory.GRID }
        val overload = ev.any { "przeciąż" in it.description.lowercase() }
        val hot = ev.any { it.category == EventCategory.TEMPERATURE }
        val loadUp = load != null && loadBefore != null && load > loadBefore * 1.5 && load - loadBefore > 400
        val pvLow = pv != null && (ctx?.expectedPvW?.let { pv < it * 0.5 && it > 200 } ?: (pv < 200))
        val pvDrop = s.zipWithNext().any { (a, b) -> val pa = a[Channel.PV_POWER]; val pb = b[Channel.PV_POWER]; pa != null && pb != null && pa > 300 && pb < pa * 0.7 }
        val cloudy = (ctx?.cloudCoverPercent ?: 0.0) >= 60 || (ctx?.clearSkyIndex ?: 1.0) < 0.5
        val (conclusion, signals) = when {
            comm && s.size < 3 -> "Utrata komunikacji z falownikiem – brak danych do dalszej analizy" to 1
            (lowBatteryEvent || (soc != null && soc < 25)) && (loadUp || pvLow) ->
                "Rozładowanie baterii: " + listOfNotNull("duże obciążenie".takeIf { loadUp }, "mało energii z PV".takeIf { pvLow }).joinToString(" + ") +
                    (if (gridStart) " → zasilanie przejęła sieć" else "") to (1 + (if (loadUp) 1 else 0) + (if (pvLow) 1 else 0) + (if (gridStart) 1 else 0) + (if (lowBatteryEvent) 1 else 0))
            gridStart && soc != null && soc < 35 -> "Sieć przejęła zasilanie przy niskim SOC (${soc.toInt()}%)" to 2
            overload && loadUp -> "Przeciążenie: obciążenie wzrosło do ${w(load)}" to 3
            hot -> "Ostrzeżenie temperaturowe falownika" + (near[Channel.INVERTER_TEMPERATURE]?.let { " (${it.toInt()} °C)" } ?: "") to 2
            pvDrop && cloudy -> "Spadek PV przez zachmurzenie" to 2
            pvDrop && ctx?.expectedPvW != null && !cloudy -> "Spadek PV niewyjaśniony pogodą – możliwe zacienienie, MPPT lub połączenie" to 2
            comm -> "Przerwa w komunikacji w tym oknie" to 1
            else -> "Brak jednoznacznej przyczyny w danych" to 0
        }
        val dataFactor = (s.size / 10.0).coerceAtMost(1.0)
        val confidence = (0.2 + 0.18 * signals).coerceAtMost(0.95) * (0.5 + 0.5 * dataFactor)
        val timeline = tl.sortedBy { it.time }.distinctBy { it.time to it.text }.take(25)
        return IncidentReport(at, from, to, timeline.map { it.copy(text = "${f.format(it.time)} ${it.text}") }, conclusion, evidence, confidence, s.size)
    }
}

enum class WhyQuestion(val label: String) {
    LOW_ENERGY_DAY("Dlaczego było mało energii?"),
    BATTERY_LOW("Dlaczego bateria spadła tak nisko?"),
    PV_LOW("Dlaczego PV dawało mało?"),
    GRID_ACTIVATED("Dlaczego włączyła się sieć?"),
    ALARM("Dlaczego falownik zgłosił alarm?"),
    LOW_MORNING_SOC("Dlaczego rano SOC było niższe niż zwykle?"),
    MPPT_LOW("Dlaczego MPPT produkuje mniej?"),
    CLIPPING("Dlaczego wystąpił clipping?"),
}

data class WhyAnswer(val question: WhyQuestion, val date: LocalDate, val findings: List<String>, val conclusion: String, val confidence: Double, val incident: IncidentReport? = null)

/**
 * "WHY?" – answers fixed question types from data with rules, correlations, history and the physics model.
 * A language model could rephrase the answer, but it is never the source of numbers.
 */
object WhyAnalyzer {
    /** Maps a free-text question (PL/EN) to a type and a date; null when not understood. */
    fun parse(text: String, today: LocalDate): Pair<WhyQuestion, LocalDate>? {
        val t = text.lowercase()
        val date = when {
            "przedwczoraj" in t -> today.minusDays(2)
            "wczoraj" in t || "yesterday" in t -> today.minusDays(1)
            else -> today
        }
        val q = when {
            "mppt" in t -> WhyQuestion.MPPT_LOW
            "clipping" in t || "limit" in t -> WhyQuestion.CLIPPING
            "alarm" in t || "błąd" in t || "fault" in t || "ostrzeż" in t -> WhyQuestion.ALARM
            ("rano" in t || "morning" in t) && "soc" in t -> WhyQuestion.LOW_MORNING_SOC
            "grid" in t || "sieć" in t || "siec" in t -> WhyQuestion.GRID_ACTIVATED
            "bateri" in t || "soc" in t || "battery" in t -> WhyQuestion.BATTERY_LOW
            "pv" in t || "panel" in t -> WhyQuestion.PV_LOW
            "energi" in t || "energy" in t -> WhyQuestion.LOW_ENERGY_DAY
            else -> return null
        }
        return q to date
    }

    fun answer(
        question: WhyQuestion,
        date: LocalDate,
        samples: List<AnalyzerSample>,
        events: List<AnenjiEvent>,
        zone: ZoneId,
        context: ContextProvider? = null,
        pvLimitW: Double? = null,
    ): WhyAnswer {
        val s = samples.sortedBy { it.time }
        fun day(d: LocalDate) = s.filter { it.time.atZone(zone).toLocalDate() == d }
        val today = day(date)
        if (today.isEmpty()) return WhyAnswer(question, date, listOf("Brak danych z tego dnia"), "Nie da się odpowiedzieć bez danych", 0.0)
        val previous = (1L..7L).map { date.minusDays(it) }.map { day(it) }.filter { it.size >= 10 }
        fun energy(list: List<AnalyzerSample>, c: Channel): Double = list.zipWithNext().sumOf { (a, b) ->
            val dt = Duration.between(a.time, b.time).toMillis() / 3_600_000.0
            if (dt > 0.25) 0.0 else (a[c] ?: 0.0).coerceAtLeast(0.0) * dt / 1000
        }
        fun typical(f: (List<AnalyzerSample>) -> Double?) = Stats.median(previous.mapNotNull(f))
        fun pct(a: Double, b: Double) = if (b > 0) (a / b - 1) * 100 else 0.0
        val findings = mutableListOf<String>()
        return when (question) {
            WhyQuestion.LOW_ENERGY_DAY, WhyQuestion.PV_LOW -> {
                val pv = energy(today, Channel.PV_POWER)
                val load = energy(today, Channel.LOAD_POWER)
                val pvTyp = typical { energy(it, Channel.PV_POWER) }
                val loadTyp = typical { energy(it, Channel.LOAD_POWER) }
                val model = context?.let { c -> today.zipWithNext().sumOf { (a, b) ->
                    val dt = Duration.between(a.time, b.time).toMillis() / 3_600_000.0
                    if (dt > 0.25) 0.0 else (c.at(a.time)?.expectedPvW ?: 0.0) * dt / 1000
                } }?.takeIf { it > 0 }
                val clouds = context?.let { c -> Stats.median(today.mapNotNull { c.at(it.time)?.takeIf { x -> (x.sunElevationDeg ?: 0.0) > 10 }?.cloudCoverPercent }) }
                val coverage = coverage(today)
                findings += "PV: ${"%.1f".format(pv)} kWh" + (pvTyp?.let { " (typowo ${"%.1f".format(it)} kWh, ${"%+.0f".format(pct(pv, it))}%)" } ?: "")
                model?.let { findings += "Model (pogoda, zacienienie): ${"%.1f".format(it)} kWh → pomiar ${"%+.0f".format(pct(pv, it))}% względem modelu" }
                clouds?.let { findings += "Mediana zachmurzenia w dzień: ${it.toInt()}%" }
                findings += "Zużycie: ${"%.1f".format(load)} kWh" + (loadTyp?.let { " (typowo ${"%.1f".format(it)} kWh)" } ?: "")
                if (coverage < 0.9) findings += "Pokrycie danymi: ${(coverage * 100).toInt()}% dnia – część energii może nie być zarejestrowana"
                pvLimitW?.let { l -> val m = today.count { (it[Channel.PV_POWER] ?: 0.0) >= l * 0.97 }; if (m > 0) findings += "Odczyty przy limicie falownika (clipping): $m" }
                val (concl, conf) = when {
                    coverage < 0.6 -> "Duże braki danych – niska produkcja może wynikać z przerw w rejestracji" to 0.5
                    model != null && pv < model * 0.8 && (clouds == null || clouds < 50) -> "PV niższe od modelu przy dobrej pogodzie – możliwe zabrudzenie, zacienienie lub problem z PV/MPPT" to 0.7
                    clouds != null && clouds >= 60 -> "Pochmurny dzień (zachmurzenie ~${clouds.toInt()}%)" to 0.75
                    model != null && pv >= model * 0.9 -> "Produkcja zgodna z modelem – mało energii wynika z warunków (pora roku, pogoda)" to 0.7
                    question == WhyQuestion.LOW_ENERGY_DAY && loadTyp != null && load > loadTyp * 1.3 -> "Zużycie wyższe od typowego o ${pct(load, loadTyp).toInt()}%" to 0.65
                    pvTyp != null && pv < pvTyp * 0.7 -> "PV o ${(-pct(pv, pvTyp)).toInt()}% niższe niż w poprzednich dniach – bez danych pogodowych przyczyna niepewna" to 0.45
                    else -> "Brak wyraźnej anomalii względem poprzednich dni" to 0.4
                }
                WhyAnswer(question, date, findings, concl, conf)
            }
            WhyQuestion.BATTERY_LOW -> {
                val min = today.filter { it[Channel.SOC] != null }.minByOrNull { it[Channel.SOC]!! }
                    ?: return WhyAnswer(question, date, listOf("Brak SOC w danych"), "Nie da się odpowiedzieć bez SOC (N/A)", 0.0)
                val incident = IncidentAnalyzer.analyze(min.time, s, events, zone, context, Duration.ofMinutes(60))
                val before = s.filter { it.time.isAfter(min.time.minus(Duration.ofHours(12))) && !it.time.isAfter(min.time) }
                findings += "Minimum SOC ${min[Channel.SOC]!!.toInt()}% o ${min.time.atZone(zone).toLocalTime().withSecond(0).withNano(0)}"
                findings += "12 h przed: PV ${"%.1f".format(energy(before, Channel.PV_POWER))} kWh, zużycie ${"%.1f".format(energy(before, Channel.LOAD_POWER))} kWh"
                WhyAnswer(question, date, findings + incident.evidence, incident.conclusion, incident.confidence, incident)
            }
            WhyQuestion.GRID_ACTIVATED, WhyQuestion.ALARM -> {
                val pick = events.filter { it.start.atZone(zone).toLocalDate() == date }.lastOrNull { e ->
                    if (question == WhyQuestion.GRID_ACTIVATED) e.category == EventCategory.GRID
                    else e.severity != EventSeverity.INFO && e.category != EventCategory.COMMUNICATION
                } ?: return WhyAnswer(question, date, listOf("Brak takiego zdarzenia tego dnia w danych"), "Nie wykryto zdarzenia", 0.6)
                val incident = IncidentAnalyzer.analyze(pick.start, s, events, zone, context)
                val count = events.count { it.kindKey == pick.kindKey }
                WhyAnswer(question, date, listOf("${pick.description} o ${pick.start.atZone(zone).toLocalTime().withNano(0)}", "Wystąpień w całych danych: $count") + incident.evidence,
                    incident.conclusion, incident.confidence, incident)
            }
            WhyQuestion.LOW_MORNING_SOC -> {
                fun socAt(list: List<AnalyzerSample>, d: LocalDate, t: LocalTime) = list.minByOrNull { abs(Duration.between(it.time, d.atTime(t).atZone(zone).toInstant()).seconds) }
                    ?.takeIf { abs(Duration.between(it.time, d.atTime(t).atZone(zone).toInstant()).toMinutes()) <= 30 }?.get(Channel.SOC)
                val morning = socAt(today, date, LocalTime.of(7, 0)) ?: return WhyAnswer(question, date, listOf("Brak SOC około 07:00"), "N/A", 0.0)
                val typical = Stats.median(previous.mapNotNull { p -> p.firstOrNull()?.let { socAt(p, it.time.atZone(zone).toLocalDate(), LocalTime.of(7, 0)) } })
                val evening = socAt(day(date.minusDays(1)), date.minusDays(1), LocalTime.of(20, 0))
                val night = s.filter { it.time.isAfter(date.minusDays(1).atTime(20, 0).atZone(zone).toInstant()) && it.time.isBefore(date.atTime(7, 0).atZone(zone).toInstant()) }
                val nightLoad = energy(night, Channel.LOAD_POWER)
                val typicalNight = Stats.median((1L..7L).map { date.minusDays(it) }.map { d ->
                    energy(s.filter { it.time.isAfter(d.minusDays(1).atTime(20, 0).atZone(zone).toInstant()) && it.time.isBefore(d.atTime(7, 0).atZone(zone).toInstant()) }, Channel.LOAD_POWER)
                }.filter { it > 0 })
                findings += "SOC o 07:00: ${morning.toInt()}%" + (typical?.let { " (typowo ${it.toInt()}%)" } ?: "")
                evening?.let { findings += "SOC poprzedniego wieczoru (20:00): ${it.toInt()}%" }
                findings += "Zużycie nocne 20:00–07:00: ${"%.1f".format(nightLoad)} kWh" + (typicalNight?.let { " (typowo ${"%.1f".format(it)} kWh)" } ?: "")
                val (concl, conf) = when {
                    typicalNight != null && nightLoad > typicalNight * 1.25 -> "Większe zużycie w nocy (+${pct(nightLoad, typicalNight).toInt()}%)" to 0.75
                    evening != null && typical != null && evening < typical + 20 -> "Bateria była słabiej naładowana wieczorem (mało PV poprzedniego dnia)" to 0.65
                    else -> "Brak wyraźnej przyczyny w danych" to 0.35
                }
                WhyAnswer(question, date, findings, concl, conf)
            }
            WhyQuestion.MPPT_LOW -> {
                val withMppt = today.filter { it.mppts.size >= 2 }
                if (withMppt.isEmpty()) return WhyAnswer(question, date, listOf("Falownik nie raportuje danych poszczególnych MPPT"), "N/A – brak danych MPPT", 0.0)
                val means = withMppt.flatMap { it.mppts }.groupBy { it.index }.mapValues { (_, r) -> r.mapNotNull { it.powerW }.average() }
                means.forEach { (i, p) -> findings += "MPPT $i: średnio ${p.toInt()} W" }
                val ref = Stats.median(means.values.toList())!!
                val low = means.filterValues { it < ref * 0.85 }
                WhyAnswer(question, date, findings, if (low.isEmpty()) "MPPT pracują podobnie" else
                    "MPPT ${low.keys.joinToString()} niżej o ${((1 - low.values.min() / ref) * 100).toInt()}% – możliwe: zacienienie, mismatch, złącze, konfiguracja", if (low.isEmpty()) 0.6 else 0.7)
            }
            WhyQuestion.CLIPPING -> {
                val l = pvLimitW ?: return WhyAnswer(question, date, listOf("Nieznany limit mocy falownika/ładowarki"), "N/A", 0.0)
                val at = today.filter { (it[Channel.PV_POWER] ?: 0.0) >= l * 0.97 }
                if (at.isEmpty()) return WhyAnswer(question, date, listOf("Brak odczytów przy limicie ${l.toInt()} W"), "Clipping nie wystąpił", 0.8)
                findings += "Odczytów przy limicie: ${at.size} (od ${at.first().time.atZone(zone).toLocalTime().withNano(0)} do ${at.last().time.atZone(zone).toLocalTime().withNano(0)})"
                WhyAnswer(question, date, findings, "PV przekraczało możliwości falownika/ładowarki (${l.toInt()} W) – typowe w słoneczne południe, gdy moc paneli > limit", 0.8)
            }
        }
    }

    /** Share of the day covered by samples (gaps > 3 × typical interval are not covered). */
    fun coverage(day: List<AnalyzerSample>): Double {
        val interval = AnenjiEventLog.typicalInterval(day) ?: return 0.0
        val covered = day.zipWithNext().sumOf { (a, b) -> Duration.between(a.time, b.time).let { if (it > interval.multipliedBy(3)) 0L else it.toMillis() } }
        return (covered / 86_400_000.0).coerceIn(0.0, 1.0)
    }
}
