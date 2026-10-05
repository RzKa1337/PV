package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.analytics.Alert
import com.solartracker.pro.core.analytics.ModelComparisonResult
import com.solartracker.pro.core.analytics.RealEnergyFlow
import com.solartracker.pro.core.inverter.BatteryFlowState
import com.solartracker.pro.core.inverter.Freshness
import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.OperatingMode
import com.solartracker.pro.core.shading.ShadingConfidence
import com.solartracker.pro.core.shading.ShadowForecast
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Everything the advisor may use. Missing pieces stay null – the advisor then says so. */
data class AdvisorContext(
    val now: Instant,
    val zone: ZoneId,
    val telemetry: InverterTelemetry?,
    val freshness: Freshness,
    val simulated: Boolean,
    val flow: RealEnergyFlow?,
    val today: DayProductionForecast?,
    val battery: BatteryPrediction?,
    val batteryMinSoc: Double?,
    val sunsetAt: Instant?,
    val comparison: ModelComparisonResult?,
    val shadingToday: List<ShadowForecast>,
    val nextShadow: ShadowForecast?,
    val shadingConfidence: ShadingConfidence?,
    val dailyShadingLossKwh: Double?,
    val alerts: List<Alert>,
    val gridChargingCostAdvice: String? = null,
)

enum class AdvisorQuestion(val text: String) {
    INVERTER_NOW("Co teraz robi mój falownik?"),
    PRODUCTION_NOW("Ile aktualnie produkuję?"),
    BATTERY_NOW("Ile mam w baterii?"),
    UNTIL_SUNSET("Ile wyprodukuję do zachodu?"),
    BATTERY_UNTIL_MORNING("Czy bateria wystarczy do rana?"),
    SOC_MORNING("Ile będę miał rano SOC?"),
    CHARGE_TODAY("Czy dzisiaj opłaca się ładować baterię?"),
    PRODUCTION_NORMAL("Czy produkcja jest normalna?"),
    WHY_LESS("Dlaczego produkuję mniej?"),
    SHADOW_WHEN("Kiedy dzisiaj pojawi się cień?"),
    SHADOW_WHICH("Który budynek zacienia panele?"),
    SHADOW_LOSS("Ile energii tracę przez zacienienie?"),
    HEIGHT_CONFIRMED("Czy wysokość tego budynku jest potwierdzona?"),
}

data class AdvisorAnswer(val question: AdvisorQuestion, val text: String, val usedData: List<String>, val complete: Boolean)

/**
 * Rule-based Solar Advisor: answers only from the provided data (telemetry, history, model, weather,
 * forecast, battery, shading). It never invents values; when data is missing it says what is missing.
 */
object SolarAdvisor {

    /** Matches free text to a known question (keywords), or null. */
    fun match(text: String): AdvisorQuestion? {
        val t = text.lowercase(Locale("pl"))
        return when {
            "wysokoś" in t && ("potwierdz" in t || "budyn" in t) -> AdvisorQuestion.HEIGHT_CONFIRMED
            "tracę" in t || ("strat" in t && "cie" in t) -> AdvisorQuestion.SHADOW_LOSS
            ("któr" in t || "co" in t.split(' ')) && ("zacien" in t || "cień" in t) -> AdvisorQuestion.SHADOW_WHICH
            "cień" in t || "cien" in t -> AdvisorQuestion.SHADOW_WHEN
            "dlaczego" in t -> AdvisorQuestion.WHY_LESS
            "normaln" in t -> AdvisorQuestion.PRODUCTION_NORMAL
            "opłaca" in t || "ładować" in t -> AdvisorQuestion.CHARGE_TODAY
            "rano" in t && "soc" in t -> AdvisorQuestion.SOC_MORNING
            "wystarczy" in t || "do rana" in t -> AdvisorQuestion.BATTERY_UNTIL_MORNING
            "zachod" in t -> AdvisorQuestion.UNTIL_SUNSET
            "bateri" in t || "soc" in t -> AdvisorQuestion.BATTERY_NOW
            "produkuj" in t || "produkcj" in t -> AdvisorQuestion.PRODUCTION_NOW
            "falownik" in t || "robi" in t -> AdvisorQuestion.INVERTER_NOW
            else -> null
        }
    }

    fun answer(q: AdvisorQuestion, c: AdvisorContext): AdvisorAnswer {
        val used = mutableListOf<String>()
        fun live(): Boolean {
            val ok = c.telemetry != null && c.freshness == Freshness.LIVE
            if (c.telemetry != null) used += if (c.simulated) "SYMULATOR (nie pomiar)" else "telemetria ${c.freshness.name}"
            return ok
        }
        val noLive = when {
            c.telemetry == null -> "Brak danych z falownika – podłącz Anenji w ustawieniach."
            c.freshness == Freshness.LAST_KNOWN -> "Falownik jest OFFLINE. Ostatnie znane dane z ${time(c.telemetry.timestamp, c)} – to nie jest bieżący pomiar."
            else -> "Dane z falownika są nieaktualne (z ${time(c.telemetry.timestamp, c)})."
        }
        val text: String = when (q) {
            AdvisorQuestion.INVERTER_NOW -> if (!live()) noLive else {
                val t = c.telemetry!!
                buildString {
                    append("Tryb: ${modeLabel(t.inverter.mode)}. ")
                    t.pv.powerW?.let { append("PV ${kw(it)}, ") }
                    t.load.powerW?.let { append("odbiory ${kw(it)}, ") }
                    when (t.battery.state) {
                        BatteryFlowState.CHARGING -> append("bateria ładuje się ${kw(t.battery.powerW!!)}")
                        BatteryFlowState.DISCHARGING -> append("bateria oddaje ${kw(-t.battery.powerW!!)}")
                        BatteryFlowState.IDLE -> append("bateria bez przepływu")
                        BatteryFlowState.DISCONNECTED -> append("bateria niepodłączona")
                        BatteryFlowState.UNKNOWN -> append("stan baterii nieznany")
                    }
                    t.grid.powerW?.let { append(if (it > 20) ", pobór z sieci ${kw(it)}" else if (it < -20) ", oddawanie do sieci ${kw(-it)}" else ", sieć bez przepływu") }
                    append(".")
                    c.flow?.active?.takeIf { it.isNotEmpty() }?.let { append(" Przepływy (obliczone): ${it.joinToString { (n, w) -> "$n ${kw(w)}" }}.") }
                    val alerts = c.alerts.filter { it.active }
                    if (alerts.isNotEmpty()) append(" Ostrzeżenia: ${alerts.joinToString { it.type.title }}.")
                }
            }
            AdvisorQuestion.PRODUCTION_NOW -> if (!live()) noLive else
                "Teraz (POMIAR ${time(c.telemetry!!.timestamp, c)}): ${c.telemetry.pv.powerW?.let(::kw) ?: "N/A – falownik nie podaje mocy PV"}." +
                    (c.today?.producedKwh?.let { " Dziś do tej pory: ${kwh(it)} (obliczone z pomiarów)." } ?: "")
            AdvisorQuestion.BATTERY_NOW -> if (!live()) noLive else {
                val b = c.telemetry!!.battery
                if (b.connected == false) "Falownik zgłasza, że bateria nie jest podłączona."
                else "SOC ${b.socPercent?.let { "${it.toInt()}%" } ?: "N/A"}, napięcie ${b.voltageV?.let { "%.1f V".format(Locale.ROOT, it) } ?: "N/A"}" +
                    (c.battery?.let { ". Dostępna energia powyżej minimalnego SOC: ${kwh(it.energyAvailableKwh)} (obliczone)." } ?: ".")
            }
            AdvisorQuestion.UNTIL_SUNSET -> c.today?.let { d ->
                used += "prognoza PV"
                "Do końca dnia oczekuję jeszcze ${kwh(d.remainingKwh)} (PROGNOZA, pewność ${pct(d.confidence)}; zakres ${kwh(d.minKwh - (d.producedKwh ?: 0.0))}–${kwh(d.maxKwh - (d.producedKwh ?: 0.0))})" +
                    (c.sunsetAt?.let { ", zachód o ${time(it, c)}" } ?: "") + "."
            } ?: "Brak prognozy produkcji."
            AdvisorQuestion.BATTERY_UNTIL_MORNING, AdvisorQuestion.SOC_MORNING -> c.battery?.let { b ->
                used += "prognoza baterii (start: ${b.startKind.label})"
                val morning = b.milestones.firstOrNull { it.label.startsWith("rano") }
                val min = c.batteryMinSoc
                when {
                    morning == null -> "Prognoza nie sięga do rana."
                    q == AdvisorQuestion.SOC_MORNING -> "Rano (07:00) przewiduję SOC ok. ${morning.socPercent.toInt()}% (PROGNOZA, pewność ${pct(b.confidence)})."
                    b.emptyAt != null && b.emptyAt.isBefore(morning.time) -> "Nie – bateria osiągnie minimalny SOC ok. ${time(b.emptyAt, c)}; potem energia z sieci/generatora."
                    else -> "Tak – rano przewiduję SOC ok. ${morning.socPercent.toInt()}%${min?.let { " (minimum ${it.toInt()}%)" } ?: ""}. PROGNOZA, pewność ${pct(b.confidence)}."
                }
            } ?: "Brak prognozy baterii – potrzebny SOC z falownika i konfiguracja baterii."
            AdvisorQuestion.CHARGE_TODAY -> {
                val b = c.battery
                val d = c.today
                when {
                    b == null || d == null -> "Za mało danych (prognoza PV i bateria)."
                    b.rows.maxOfOrNull { it.socPercent ?: 0.0 }?.let { it >= 95 } == true ->
                        "Nie trzeba ładować z sieci: prognoza PV (${kwh(d.remainingKwh)} do końca dnia) naładuje baterię do ok. ${b.rows.maxOf { it.socPercent ?: 0.0 }.toInt()}%."
                    else -> "Prognoza PV (${kwh(d.remainingKwh)}) nie naładuje baterii do pełna (max ok. ${b.rows.maxOfOrNull { it.socPercent ?: 0.0 }?.toInt()}%)." +
                        (c.gridChargingCostAdvice ?: " Doładowanie z sieci ma sens tylko przy taniej taryfie – podaj ceny w ustawieniach.")
                }
            }
            AdvisorQuestion.PRODUCTION_NORMAL, AdvisorQuestion.WHY_LESS -> c.comparison?.let { cmp ->
                used += "model PV vs pomiar"
                val diff = cmp.differencePercent
                when {
                    diff == null -> "Teraz model nie przewiduje produkcji (noc / Słońce za nisko)."
                    q == AdvisorQuestion.PRODUCTION_NORMAL && diff > -10 -> "Tak – pomiar ${kw(cmp.realKw * 1000)} vs model ${kw(cmp.modelKw * 1000)} (${signed(diff)}%)."
                    cmp.causes.isEmpty() -> "Pomiar ${kw(cmp.realKw * 1000)} vs model ${kw(cmp.modelKw * 1000)} (${signed(diff)}%). Brak jednoznacznej przyczyny w danych."
                    else -> "Pomiar ${kw(cmp.realKw * 1000)} vs model ${kw(cmp.modelKw * 1000)} (${signed(diff)}%). Najbardziej prawdopodobne: " +
                        cmp.causes.take(3).joinToString("; ") { "${it.cause.label} – ${it.explanation}" } + "."
                }
            } ?: "Brak porównania – potrzebny bieżący pomiar PV z falownika."
            AdvisorQuestion.SHADOW_WHEN -> {
                used += "model zacienienia"
                val events = c.shadingToday.filter { it.event.end.isAfter(c.now) }
                if (c.shadingConfidence == null) "Model zacienienia nie jest skonfigurowany – potwierdź lokalizację i przeszkody."
                else if (events.isEmpty()) "Dziś nie przewiduję już cienia na panelach (pewność modelu ${pct(c.shadingConfidence.score)})."
                else events.joinToString(" ") { "${it.obstacleName}: ${time(it.event.start, c)}–${time(it.event.end, c)} (${it.dayPart.label}, do ${(it.event.maxShadedFraction * 100).toInt()}% powierzchni)." } +
                    " Pewność ${pct(c.shadingConfidence.score)}."
            }
            AdvisorQuestion.SHADOW_WHICH -> {
                val e = c.shadingToday.firstOrNull { it.event.end.isAfter(c.now) } ?: c.nextShadow
                if (e == null) "Nie ma przewidywanego cienia w najbliższym czasie."
                else "${e.obstacleName} (wysokość ${e.obstacleHeightLabel}) – zacienia panele ${e.event.panels.sorted().joinToString { "#${it + 1}" }} od ${time(e.event.start, c)} do ${time(e.event.end, c)}."
            }
            AdvisorQuestion.SHADOW_LOSS -> c.dailyShadingLossKwh?.let {
                "Dziś zacienienie odbiera ok. ${kwh(it)} (OBLICZONE z geometrii, pewność ${c.shadingConfidence?.let { s -> pct(s.score) } ?: "?"})."
            } ?: "Brak obliczeń zacienienia."
            AdvisorQuestion.HEIGHT_CONFIRMED -> {
                val e = c.shadingToday.firstOrNull() ?: c.nextShadow
                if (e == null) "Żadna przeszkoda nie rzuca dziś cienia." else "Wysokość obiektu „${e.obstacleName}”: ${e.obstacleHeightLabel}."
            }
        }
        val complete = !text.startsWith("Brak") && !text.startsWith("Za mało") && !text.contains("N/A")
        return AdvisorAnswer(q, text, used.distinct(), complete)
    }

    private fun modeLabel(m: OperatingMode) = when (m) {
        OperatingMode.POWER_ON -> "uruchamianie"
        OperatingMode.STANDBY -> "czuwanie"
        OperatingMode.GRID -> "praca z sieci"
        OperatingMode.OFF_GRID -> "praca z baterii/PV (off-grid)"
        OperatingMode.BYPASS -> "bypass (odbiory z sieci)"
        OperatingMode.CHARGING -> "ładowanie"
        OperatingMode.FAULT -> "BŁĄD"
        OperatingMode.UNKNOWN -> "nieznany"
    }

    private val hm = DateTimeFormatter.ofPattern("HH:mm")
    private fun time(t: Instant, c: AdvisorContext) = hm.format(t.atZone(c.zone))
    private fun kw(w: Double) = String.format(Locale.ROOT, "%.2f kW", w / 1000.0)
    private fun kwh(v: Double) = String.format(Locale.ROOT, "%.2f kWh", v)
    private fun pct(v: Double) = "${(v * 100).toInt()}%"
    private fun signed(v: Double) = String.format(Locale.ROOT, "%+.1f", v)
}
