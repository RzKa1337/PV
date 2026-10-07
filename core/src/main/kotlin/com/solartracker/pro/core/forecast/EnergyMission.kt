package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.quality.DataKind
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

enum class MissionGoal(val label: String) {
    SURVIVE_NIGHT("Przetrwaj noc"),
    MAXIMIZE_SELF_CONSUMPTION("Maksymalna autokonsumpcja"),
    PROTECT_BATTERY("Chroń baterię"),
    RUN_COLD_ROOM("Utrzymaj chłodnię"),
    MINIMIZE_GENERATOR("Minimum agregatu"),
    MINIMIZE_GRID_COST("Minimum kosztu sieci"),
}

/**
 * What the user wants to achieve. [target] = local clock time the mission must hold until (next occurrence).
 * [protectSocPercent] is the floor for PROTECT_BATTERY; prices are needed only for the cost goals.
 */
data class MissionRequest(
    val goal: MissionGoal,
    val target: LocalTime = LocalTime.of(8, 0),
    val protectSocPercent: Double = 30.0,
    val gridPricePerKwh: Double? = null,
    val generatorPricePerKwh: Double? = null,
    /** Average cold-room power included in the load forecast [kW] (for RUN_COLD_ROOM reporting only). */
    val coldRoomKw: Double? = null,
)

data class MissionMetric(val label: String, val value: String, val kind: DataKind)

data class MissionResult(
    val goal: MissionGoal,
    val targetTime: Instant,
    /** Null when it cannot be judged (no battery or no SOC). */
    val achievable: Boolean?,
    /** Scenario-based estimate (ESTIMATED), not a statistical probability; null when not judged. */
    val probabilityPercent: Int?,
    val socNow: Double?,
    val socKind: DataKind,
    val milestones: List<SocMilestone>,
    val minSocExpected: Int?,
    /** Energy above the required floor at the lowest point (negative = missing) [kWh]. */
    val safetyMarginKwh: Double?,
    /** Energy that must come from grid/generator before the target (expected scenario) [kWh]. */
    val deficitKwh: Double?,
    val risk: EnergyRisk,
    val confidence: Double,
    val metrics: List<MissionMetric>,
    val recommendation: String,
)

/**
 * Energy missions on top of [EnergySecurityAnalyzer]: the same three battery scenarios (expected / pessimistic /
 * optimistic), judged against the user's goal. No new battery model and no control of the inverter.
 */
class EnergyMissionPlanner(private val security: EnergySecurityAnalyzer, private val zone: ZoneId) {

    fun evaluate(
        request: MissionRequest,
        now: Instant,
        socNow: Double?,
        socKind: DataKind,
        pv: (Instant) -> PvForecastPoint,
        load: (Instant) -> LoadForecastPoint,
        step: Duration = Duration.ofMinutes(15),
    ): MissionResult {
        val target = nextOccurrence(now, request.target)
        val horizon = Duration.between(now, target).plus(step)
        val battery = security.batteryConfig
        val set = security.scenarioSet(now, socNow, socKind, pv, load, horizon, step)
        if (battery == null || set == null) {
            return MissionResult(request.goal, target, null, null, socNow, socKind, emptyList(), null, null, null, EnergyRisk.UNKNOWN, 0.0,
                emptyList(), if (battery == null) "Skonfiguruj baterię, aby planować misje energetyczne" else "Brak aktualnego SOC – misji nie da się ocenić")
        }
        val h = step.seconds / 3600.0
        fun rows(p: BatteryPrediction) = p.rows.filter { !it.time.isAfter(target) }
        val floor = when (request.goal) {
            MissionGoal.PROTECT_BATTERY -> maxOf(request.protectSocPercent, battery.minSocPercent)
            else -> battery.minSocPercent
        }
        fun minSoc(p: BatteryPrediction) = rows(p).mapNotNull { it.socPercent }.minOrNull()
        fun imported(p: BatteryPrediction) = rows(p).sumOf { it.gridKw.coerceAtLeast(0.0) } * h
        fun ok(p: BatteryPrediction): Boolean = when (request.goal) {
            MissionGoal.MINIMIZE_GENERATOR, MissionGoal.MINIMIZE_GRID_COST -> imported(p) < 0.05
            MissionGoal.MAXIMIZE_SELF_CONSUMPTION -> true
            else -> (minSoc(p) ?: 0.0) >= floor + 0.5 && imported(p) < 0.05
        }
        val e = set.expected; val pess = set.pessimistic; val opt = set.optimistic
        val base = when {
            ok(pess) -> 0.97
            ok(e) -> 0.7
            ok(opt) -> 0.25
            else -> 0.03
        }
        val conf = e.confidence.coerceIn(0.0, 1.0)
        val probability = ((0.5 + (base - 0.5) * conf) * 100).roundToInt().coerceIn(1, 99)
        val minE = minSoc(e)
        val margin = minE?.let { (battery.storedKwh(it) - battery.storedKwh(floor)) * battery.dischargeEfficiency }
        val deficit = imported(e)
        val risk = when { !ok(e) -> EnergyRisk.HIGH; !ok(pess) -> EnergyRisk.MEDIUM; else -> EnergyRisk.LOW }
        val milestones = milestones(now, target, e, pess, opt)
        val metrics = mutableListOf<MissionMetric>()
        var recommendation: String
        when (request.goal) {
            MissionGoal.MAXIMIZE_SELF_CONSUMPTION -> {
                val pvKwh = rows(e).sumOf { it.pvKw } * h
                val surplus = rows(e).sumOf { (-it.gridKw).coerceAtLeast(0.0) } * h
                val share = if (pvKwh > 0.05) (1 - surplus / pvKwh) * 100 else null
                metrics += MissionMetric("Autokonsumpcja PV", share?.let { "${it.roundToInt()}%" } ?: "N/A", DataKind.FORECAST)
                metrics += MissionMetric("Niewykorzystana nadwyżka", "%.1f kWh".format(surplus), DataKind.FORECAST)
                val windows = surplusWindows(rows(e))
                recommendation = if (windows.isEmpty()) "Brak nadwyżek do przesunięcia – nic nie zmieniaj"
                else "Przesuń odbiory elastyczne na: ${windows.joinToString()}"
            }
            MissionGoal.MINIMIZE_GRID_COST -> {
                val price = request.gridPricePerKwh
                metrics += MissionMetric("Energia z sieci do celu", "%.1f kWh".format(deficit), DataKind.FORECAST)
                metrics += MissionMetric("Koszt", price?.let { "%.2f".format(deficit * it) } ?: "N/A (brak ceny)", DataKind.FORECAST)
                recommendation = if (deficit < 0.05) "Bez poboru z sieci do ${fmt(target)}" else "Ogranicz pobór przed ${fmt(e.emptyAt ?: target)}; przesuń duże odbiory na godziny z PV"
            }
            MissionGoal.MINIMIZE_GENERATOR -> {
                metrics += MissionMetric("Energia z agregatu", "%.1f kWh".format(deficit), DataKind.FORECAST)
                request.generatorPricePerKwh?.let { metrics += MissionMetric("Koszt paliwa", "%.2f".format(deficit * it), DataKind.FORECAST) }
                recommendation = if (deficit < 0.05) "Agregat niepotrzebny do ${fmt(target)}"
                else "Agregat potrzebny ok. ${fmt(e.emptyAt ?: target)} na ok. %.1f kWh – rozważ ograniczenie zużycia".format(deficit)
            }
            MissionGoal.RUN_COLD_ROOM -> {
                val hours = Duration.between(now, target).toMinutes() / 60.0
                request.coldRoomKw?.let { kw ->
                    val total = rows(e).sumOf { it.loadKw } * h
                    metrics += MissionMetric("Chłodnia do celu", "%.1f kWh".format(kw * hours), DataKind.ESTIMATED)
                    if (total > 0) metrics += MissionMetric("Udział chłodni w zużyciu", "${(kw * hours / total * 100).roundToInt()}%", DataKind.ESTIMATED)
                }
                recommendation = if (ok(pess)) "Chłodnia może pracować bez ograniczeń" else "Wychłodź chłodnię mocniej w dzień (z PV) i ogranicz inne odbiory wieczorem"
            }
            MissionGoal.PROTECT_BATTERY -> {
                metrics += MissionMetric("Próg ochrony", "${floor.roundToInt()}%", DataKind.CALCULATED)
                recommendation = if (ok(e)) "SOC pozostanie powyżej ${floor.roundToInt()}%" else "SOC spadnie poniżej ${floor.roundToInt()}% – ogranicz odbiory nocne"
            }
            MissionGoal.SURVIVE_NIGHT -> {
                recommendation = when {
                    ok(pess) -> "Energii wystarczy z zapasem do ${fmt(target)}"
                    ok(e) -> "Energii powinno wystarczyć, ale bez zapasu – unikaj dużych odbiorów w nocy"
                    else -> "Energii zabraknie ok. ${fmt(e.emptyAt ?: target)} – ogranicz zużycie lub przygotuj zasilanie rezerwowe"
                }
            }
        }
        margin?.let { metrics.add(0, MissionMetric("Zapas bezpieczeństwa", "%+.1f kWh".format(it), DataKind.FORECAST)) }
        return MissionResult(request.goal, target, ok(e), probability, socNow, socKind, milestones, minE?.roundToInt(), margin, deficit, risk, conf, metrics, recommendation)
    }

    private fun nextOccurrence(now: Instant, time: LocalTime): Instant {
        var t = now.atZone(zone).toLocalDate().atTime(time).atZone(zone).toInstant()
        while (!t.isAfter(now)) t = t.plus(Duration.ofDays(1))
        return t
    }

    private fun milestones(now: Instant, target: Instant, e: BatteryPrediction, p: BatteryPrediction, o: BatteryPrediction): List<SocMilestone> {
        fun at(pred: BatteryPrediction, t: Instant) = pred.rows.lastOrNull { !it.time.isAfter(t) }?.socPercent
        val times = (listOf(0, 6).map { nextOccurrence(now, LocalTime.of(it, 0)) }.filter { it.isBefore(target) } + target).distinct().sorted()
        return times.mapNotNull { t ->
            val v = at(e, t) ?: return@mapNotNull null
            SocMilestone(fmt(t), t, v.roundToInt(), minOf(at(p, t) ?: v, v).roundToInt(), maxOf(at(o, t) ?: v, v).roundToInt())
        }
    }

    private fun surplusWindows(rows: List<EnergyForecastRow>): List<String> {
        val surplus = rows.filter { it.gridKw < -0.3 }.map { it.time.atZone(zone).hour }.distinct().sorted()
        if (surplus.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = surplus.first(); var prev = start
        for (hr in surplus.drop(1)) { if (hr != prev + 1) { ranges += start..prev; start = hr }; prev = hr }
        ranges += start..prev
        return ranges.map { "%02d:00–%02d:00".format(it.first, it.last + 1) }
    }

    private fun fmt(t: Instant) = t.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString()
}
