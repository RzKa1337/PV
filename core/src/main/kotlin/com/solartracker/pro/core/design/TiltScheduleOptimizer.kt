package com.solartracker.pro.core.design

import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.pv.PvSimulationEngine
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarPosition
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/** What a tilt change should maximise. */
enum class TiltObjective(val label: String) {
    /** All PV energy. */
    ENERGY("Maksimum energii"),
    /** PV energy the loads can use directly (min(PV, load)). */
    SELF_CONSUMPTION("Maksimum autokonsumpcji"),
    /** PV energy that can be used or stored (min(PV, load + charge limit)). */
    BATTERY("Maksimum ładowania baterii"),
}

/**
 * Limits of a real (manual or motorised) adjustable mount. [moveEnergyWh] = energy per move (motor) or the
 * effort equivalent; [maxTiltAt] = wind (or other) safety limit per time, from [PvWindSafetyEngine].
 */
data class TiltConstraints(
    val mount: AdjustableMount,
    val maxChanges: Int = 4,
    val minIntervalMinutes: Long = 60,
    val moveEnergyWh: Double = 0.0,
    val maxTiltAt: (Instant) -> Double? = { null },
)

data class TiltSlot(val start: Instant, val tiltDeg: Double, val pvKwh: Double, val valueKwh: Double, val windLimited: Boolean)

data class TiltSchedule(
    val objective: TiltObjective,
    val slots: List<TiltSlot>,
    /** Planned moves (time → new tilt), excluding the starting position. */
    val changes: List<Pair<Instant, Double>>,
    val valueKwh: Double,
    val energyKwh: Double,
    val moveCostKwh: Double,
    val staticBestTiltDeg: Double,
    val staticValueKwh: Double,
    /** Net gain of the schedule over the best fixed tilt (move cost included) [kWh] and [%]. */
    val gainKwh: Double,
    val gainPercent: Double,
    /** False when moving is not worth it (gain < 2 % or not above the move cost). */
    val worthIt: Boolean,
)

enum class Season(val label: String, val months: Set<Month>) {
    WINTER("Zima", setOf(Month.DECEMBER, Month.JANUARY, Month.FEBRUARY)),
    SPRING("Wiosna", setOf(Month.MARCH, Month.APRIL, Month.MAY)),
    SUMMER("Lato", setOf(Month.JUNE, Month.JULY, Month.AUGUST)),
    AUTUMN("Jesień", setOf(Month.SEPTEMBER, Month.OCTOBER, Month.NOVEMBER)),
}

/**
 * Dynamic tilt planning on the loss-chain model: an exact dynamic programme over time slots × allowed tilts that
 * respects the number of moves, the minimum time between moves, the energy cost of a move and wind limits.
 * Planning only – no actuator is driven. Frequent tracking is not assumed to pay off; the result says when not.
 */
object TiltScheduleOptimizer {
    private val engine = PvSimulationEngine()
    private val clearSky = ClearSkyModel()

    fun optimize(
        array: PvArrayConfig,
        losses: LossProfile,
        location: GeoLocation,
        date: LocalDate,
        zone: ZoneId,
        constraints: TiltConstraints,
        objective: TiltObjective = TiltObjective.ENERGY,
        conditions: (Instant, SolarPosition) -> PvConditions = { t, s -> PvConditions(clearSky.irradiance(s, t)) },
        loadKw: (Instant) -> Double = { 0.0 },
        chargeLimitKw: Double = 0.0,
        slotMinutes: Long = 30,
    ): TiltSchedule {
        require(slotMinutes in 5..120)
        require(constraints.maxChanges >= 0 && constraints.minIntervalMinutes >= 0)
        val m = constraints.mount
        val tilts = generateSequence(m.minTiltDeg) { it + m.stepDeg }.takeWhile { it <= m.maxTiltDeg + 1e-9 }.toList()
        val h = slotMinutes / 60.0
        val all = generateSequence(date.atStartOfDay(zone).toInstant()) { it.plus(Duration.ofMinutes(slotMinutes)) }
            .takeWhile { it.isBefore(date.plusDays(1).atStartOfDay(zone).toInstant()) }.toList()
        // pv[slot][tilt] in kWh, only daylight slots (night slots do not constrain moves).
        val slots = all.filter { t -> engine.simulate(array, losses, location, t.plusSeconds(slotMinutes * 30), { conditions(t, it) }).sun.isAboveHorizon }
        if (slots.isEmpty()) return empty(objective, tilts.first())
        val pv = slots.map { t ->
            val mid = t.plusSeconds(slotMinutes * 30)
            tilts.map { tilt -> engine.simulate(array.copy(tiltDeg = tilt), losses, location, mid, { conditions(mid, it) }).acPowerW / 1000 * h }
        }
        val value = slots.indices.map { s ->
            val cap = when (objective) {
                TiltObjective.ENERGY -> Double.MAX_VALUE
                TiltObjective.SELF_CONSUMPTION -> loadKw(slots[s]) * h
                TiltObjective.BATTERY -> (loadKw(slots[s]) + chargeLimitKw) * h
            }
            pv[s].map { minOf(it, cap) }
        }
        val limit = slots.map { constraints.maxTiltAt(it) }
        fun allowed(s: Int, ti: Int) = limit[s]?.let { tilts[ti] <= it + 1e-9 } ?: true
        // Fallback when even the lowest tilt exceeds the limit: the lowest tilt (stow) is always allowed.
        fun ok(s: Int, ti: Int) = allowed(s, ti) || ti == 0

        val n = slots.size; val k = tilts.size; val c = constraints.maxChanges
        val hold = ((constraints.minIntervalMinutes + slotMinutes - 1) / slotMinutes).toInt()
        val moveCost = constraints.moveEnergyWh / 1000
        val neg = Double.NEGATIVE_INFINITY
        // dp[tilt][changes][held] ; held = slots since the last move, capped at [hold].
        var dp = Array(k) { Array(c + 1) { DoubleArray(hold + 1) { neg } } }
        val parents = ArrayList<Array<Array<IntArray>>>(n)
        for (ti in 0 until k) if (ok(0, ti)) dp[ti][0][hold] = value[0][ti]
        parents += Array(k) { Array(c + 1) { IntArray(hold + 1) { -1 } } }
        for (s in 1 until n) {
            val next = Array(k) { Array(c + 1) { DoubleArray(hold + 1) { neg } } }
            val par = Array(k) { Array(c + 1) { IntArray(hold + 1) { -1 } } }
            for (ti in 0 until k) for (ch in 0..c) for (hd in 0..hold) {
                val v = dp[ti][ch][hd]; if (v == neg) continue
                // Stay.
                if (ok(s, ti)) {
                    val nh = minOf(hold, hd + 1)
                    val nv = v + value[s][ti]
                    if (nv > next[ti][ch][nh]) { next[ti][ch][nh] = nv; par[ti][ch][nh] = encode(ti, ch, hd, c, hold) }
                }
                // Move (needs a free change and the minimum interval; a forced safety move ignores both).
                for (tj in 0 until k) {
                    if (tj == ti || !ok(s, tj)) continue
                    val forced = !ok(s, ti)
                    if (!forced && (ch >= c || hd < hold)) continue
                    val nch = if (forced) ch else ch + 1
                    val nv = v + value[s][tj] - moveCost
                    if (nv > next[tj][nch][0]) { next[tj][nch][0] = nv; par[tj][nch][0] = encode(ti, ch, hd, c, hold) }
                }
            }
            dp = next; parents += par
        }
        var best = neg; var bt = 0; var bc = 0; var bh = 0
        for (ti in 0 until k) for (ch in 0..c) for (hd in 0..hold) if (dp[ti][ch][hd] > best) { best = dp[ti][ch][hd]; bt = ti; bc = ch; bh = hd }
        val path = IntArray(n)
        var ti = bt; var ch = bc; var hd = bh
        for (s in n - 1 downTo 0) {
            path[s] = ti
            if (s > 0) { val p = parents[s][ti][ch][hd]; ti = p / ((c + 1) * (hold + 1)); ch = (p / (hold + 1)) % (c + 1); hd = p % (hold + 1) }
        }
        val schedule = slots.indices.map { s -> TiltSlot(slots[s], tilts[path[s]], pv[s][path[s]], value[s][path[s]], limit[s] != null && tilts[path[s]] < tilts.last()) }
        val changes = (1 until n).filter { path[it] != path[it - 1] }.map { slots[it] to tilts[path[it]] }
        val moveTotal = changes.size * moveCost
        // Best fixed tilt under the same wind limits (forced to stow when needed, counted without move cost).
        val static = tilts.indices.map { tj -> tj to slots.indices.sumOf { s -> if (ok(s, tj)) value[s][tj] else value[s][0] } }.maxBy { it.second }
        val valueSum = schedule.sumOf { it.valueKwh } - moveTotal
        val gain = valueSum - static.second
        val gainPct = if (static.second > 0) gain / static.second * 100 else 0.0
        return TiltSchedule(objective, schedule, changes, valueSum, schedule.sumOf { it.pvKwh }, moveTotal, tilts[static.first], static.second,
            gain, gainPct, changes.isNotEmpty() && gain > 0 && gainPct >= 2.0)
    }

    /** Best tilt per season from the annual comparison (sum of the season's monthly yields). */
    fun seasonal(rec: TiltRecommendation): Map<Season, Double> = Season.entries.associateWith { season ->
        rec.yields.maxBy { y -> season.months.sumOf { y.monthlyKwh[it.ordinal] } }.tiltDeg
    }

    /** Back-pointer of a DP state; decoded in the backtracking loop. */
    private fun encode(ti: Int, ch: Int, hd: Int, c: Int, hold: Int) = (ti * (c + 1) + ch) * (hold + 1) + hd

    private fun empty(objective: TiltObjective, tilt: Double) =
        TiltSchedule(objective, emptyList(), emptyList(), 0.0, 0.0, 0.0, tilt, 0.0, 0.0, 0.0, false)
}
