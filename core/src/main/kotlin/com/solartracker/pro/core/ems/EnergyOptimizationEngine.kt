package com.solartracker.pro.core.ems

import com.solartracker.pro.core.energy.BatteryStorage
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Energy management decisions (EMS). This module only DECIDES and EXPLAINS: it never talks to a device.
 * Any execution (relays, inverter settings) must live in a separate, explicitly enabled control layer.
 *
 * Inputs are plain forecast slots, so the engine can be fed from [com.solartracker.pro.core.forecast.EnergyForecastEngine]
 * or from history in tests. All outputs derived from forecasts are FORECAST/ESTIMATED by nature.
 */
data class EnergySlot(
    val start: Instant,
    val hours: Double,
    val pvKw: Double,
    val loadKw: Double,
    /** Pessimistic PV (lower band); defaults to [pvKw]. */
    val pvMinKw: Double = pvKw,
    val confidence: Double = 0.5,
) {
    val end: Instant get() = start.plusSeconds((hours * 3600).toLong())
}

data class FlexibleLoad(
    val id: String,
    val name: String,
    val powerKw: Double,
    val hours: Double,
    val earliest: LocalTime? = null,
    val latest: LocalTime? = null,
    /** Higher = scheduled first. */
    val priority: Int = 0,
    /** If true the load is only recommended when PV surplus covers most of it. */
    val surplusOnly: Boolean = true,
)

data class GeneratorConfig(
    val ratedKw: Double,
    val startSocPercent: Double = 25.0,
    val stopSocPercent: Double = 80.0,
    val minRunHours: Double = 1.0,
    /** Fuel use [l/kWh] if known; null = UNKNOWN. */
    val litersPerKwh: Double? = null,
)

data class EmsInput(
    val slots: List<EnergySlot>,
    val battery: BatteryStorage?,
    val socPercent: Double?,
    val loads: List<FlexibleLoad> = emptyList(),
    val generator: GeneratorConfig? = null,
    val gridAvailable: Boolean = true,
    val zone: ZoneId = ZoneId.systemDefault(),
    /** Minimum PV surplus treated as useful [kW]. */
    val surplusThresholdKw: Double = 0.1,
    /** Share of a load's energy that must come from surplus to recommend it. */
    val minCoverage: Double = 0.7,
)

enum class WindowKind { SURPLUS, DEFICIT }

data class EnergyWindow(val kind: WindowKind, val start: Instant, val end: Instant, val energyKwh: Double, val peakKw: Double, val confidence: Double)

enum class RecommendationKind { RUN, NOT_RECOMMENDED }

data class LoadRecommendation(
    val load: FlexibleLoad,
    val kind: RecommendationKind,
    val start: Instant?,
    val end: Instant?,
    /** Fraction of the load's energy covered by forecast surplus. */
    val surplusCoverage: Double,
    val reason: String,
)

data class GeneratorAdvice(val start: Instant, val hours: Double, val energyKwh: Double, val fuelLiters: Double?, val reason: String)

data class SlotResult(
    val slot: EnergySlot,
    val socPercent: Double?,
    val batteryKw: Double,
    val gridImportKw: Double,
    val exportOrCurtailKw: Double,
    val generatorKw: Double,
    val unservedKw: Double,
)

data class PeriodBalance(val label: String, val pvKwh: Double, val loadKwh: Double) {
    val balanceKwh: Double get() = pvKwh - loadKwh
}

data class DayBalance(val date: LocalDate, val pvKwh: Double, val loadKwh: Double, val confidence: Double) {
    val balanceKwh: Double get() = pvKwh - loadKwh
    val surplus: Boolean get() = balanceKwh >= 0
}

data class EmsPlan(
    val simulation: List<SlotResult>,
    val windows: List<EnergyWindow>,
    val recommendations: List<LoadRecommendation>,
    val generator: GeneratorAdvice?,
    val minSocPercent: Double?,
    val minSocAt: Instant?,
    val batteryFullAt: Instant?,
    val batteryEmptyAt: Instant?,
    val gridImportKwh: Double,
    val exportKwh: Double,
    val unservedKwh: Double,
    val decisions: List<String>,
)

object EnergyOptimizationEngine {

    fun plan(input: EmsInput): EmsPlan {
        val baseSim = simulate(input, input.slots.map { it.loadKw })
        val recommendations = scheduleLoads(input, baseSim)
        val extra = DoubleArray(input.slots.size)
        recommendations.filter { it.kind == RecommendationKind.RUN && it.start != null }.forEach { r ->
            input.slots.forEachIndexed { i, s -> extra[i] += r.load.powerKw * overlapHours(s, r.start!!, r.end!!) / s.hours }
        }
        val sim = simulate(input, input.slots.mapIndexed { i, s -> s.loadKw + extra[i] })
        val windows = windows(input.slots, input.surplusThresholdKw)
        val minRow = sim.filter { it.socPercent != null }.minByOrNull { it.socPercent!! }
        val battery = input.battery
        val full = battery?.let { b -> sim.firstOrNull { (it.socPercent ?: 0.0) >= b.maxSocPercent - 0.5 }?.slot?.end }
        val empty = battery?.let { b -> sim.firstOrNull { it.socPercent != null && it.socPercent <= b.minSocPercent + 0.5 && it.batteryKw < 0 }?.slot?.end }
        val gen = generatorAdvice(input, sim)
        val plan = EmsPlan(
            simulation = sim,
            windows = windows,
            recommendations = recommendations,
            generator = gen,
            minSocPercent = minRow?.socPercent,
            minSocAt = minRow?.slot?.end,
            batteryFullAt = full,
            batteryEmptyAt = empty,
            gridImportKwh = sim.sumOf { it.gridImportKw * it.slot.hours },
            exportKwh = sim.sumOf { it.exportOrCurtailKw * it.slot.hours },
            unservedKwh = sim.sumOf { it.unservedKw * it.slot.hours },
            decisions = emptyList(),
        )
        return plan.copy(decisions = explain(input, plan))
    }

    /** Battery-first dispatch: surplus charges, deficit discharges to min SOC, then grid / generator. */
    fun simulate(input: EmsInput, loads: List<Double>): List<SlotResult> {
        val b = input.battery
        var soc = input.socPercent
        var genOn = false
        return input.slots.mapIndexed { i, s ->
            val net = s.pvKw - loads[i]
            var batteryKw = 0.0
            var grid = 0.0
            var export = 0.0
            var gen = 0.0
            var unserved = 0.0
            val g = input.generator
            if (b != null && soc != null && g != null && !input.gridAvailable) {
                // Look ahead: start when this slot would push SOC to the start threshold.
                val projected = soc + net * s.hours / b.usableCapacityKwh * 100.0
                if (soc <= g.startSocPercent || projected <= g.startSocPercent) genOn = true
                if (soc >= g.stopSocPercent) genOn = false
            }
            if (net >= 0) {
                if (b != null && soc != null) {
                    val roomKwh = (b.maxSocPercent - soc) / 100.0 * b.usableCapacityKwh / b.chargeEfficiency
                    batteryKw = minOf(net, b.maxChargePowerKw, max(0.0, roomKwh / s.hours))
                }
                export = net - batteryKw
            } else {
                var deficit = -net
                if (b != null && soc != null) {
                    val availKwh = (soc - b.minSocPercent) / 100.0 * b.usableCapacityKwh * b.dischargeEfficiency
                    val d = minOf(deficit, b.maxDischargePowerKw, max(0.0, availKwh / s.hours))
                    batteryKw = -d
                    deficit -= d
                }
                if (input.gridAvailable) grid = deficit else unserved = deficit
            }
            if (genOn && g != null && b != null && soc != null) {
                // Generator covers what the battery is short of and charges with the rest of its rating.
                gen = g.ratedKw
                unserved = 0.0
                val toBattery = gen - (-net).coerceAtLeast(0.0)
                batteryKw = if (net >= 0) min(b.maxChargePowerKw, batteryKw + gen) else min(b.maxChargePowerKw, toBattery)
            }
            if (b != null && soc != null) {
                val stored = if (batteryKw >= 0) batteryKw * b.chargeEfficiency else batteryKw / b.dischargeEfficiency
                soc = (soc + stored * s.hours / b.usableCapacityKwh * 100.0).coerceIn(0.0, 100.0)
            }
            SlotResult(s, soc, batteryKw, grid, export, gen, unserved)
        }
    }

    fun windows(slots: List<EnergySlot>, thresholdKw: Double): List<EnergyWindow> {
        val out = mutableListOf<EnergyWindow>()
        var cur: MutableList<EnergySlot>? = null
        var kind: WindowKind? = null
        fun flush() {
            val c = cur ?: return
            val k = kind ?: return
            val values = c.map { if (k == WindowKind.SURPLUS) it.pvKw - it.loadKw else it.loadKw - it.pvKw }
            out += EnergyWindow(k, c.first().start, c.last().end, c.indices.sumOf { values[it] * c[it].hours }, values.max(), c.map { it.confidence }.average())
        }
        for (s in slots) {
            val net = s.pvKw - s.loadKw
            val k = when { net >= thresholdKw -> WindowKind.SURPLUS; net <= -thresholdKw -> WindowKind.DEFICIT; else -> null }
            if (k != kind) { flush(); cur = if (k != null) mutableListOf() else null; kind = k }
            cur?.add(s)
        }
        flush()
        return out
    }

    private fun scheduleLoads(input: EmsInput, sim: List<SlotResult>): List<LoadRecommendation> {
        val residual = sim.map { it.exportOrCurtailKw }.toMutableList()
        return input.loads.sortedByDescending { it.priority }.map { load ->
            val needKwh = load.powerKw * load.hours
            var best: Triple<Int, Double, Double>? = null // start index, coverage, mean confidence
            var bestMargin = Double.NEGATIVE_INFINITY
            for (i in input.slots.indices) {
                val start = input.slots[i].start
                val end = start.plusSeconds((load.hours * 3600).toLong())
                if (end.isAfter(input.slots.last().end)) break
                if (!allowed(load, start, end, input.zone)) continue
                var covered = 0.0
                var conf = 0.0
                var margin = Double.POSITIVE_INFINITY
                input.slots.forEachIndexed { j, s ->
                    val h = overlapHours(s, start, end)
                    if (h > 0) {
                        covered += min(load.powerKw, residual[j]) * h
                        conf += s.confidence * h
                        margin = min(margin, residual[j] - load.powerKw)
                    }
                }
                val coverage = if (needKwh > 0) covered / needKwh else 0.0
                // Highest coverage wins; ties go to the window with the largest spare surplus (robust to forecast error).
                val better = best == null || coverage > best.second + 1e-6 || (coverage > best.second - 1e-6 && margin > bestMargin)
                if (better) { best = Triple(i, coverage, conf / load.hours); bestMargin = margin }
            }
            if (best == null) {
                LoadRecommendation(load, RecommendationKind.NOT_RECOMMENDED, null, null, 0.0, "Brak okna czasowego w horyzoncie prognozy spełniającego ograniczenia")
            } else {
                val start = input.slots[best.first].start
                val end = start.plusSeconds((load.hours * 3600).toLong())
                val ok = !load.surplusOnly || best.second >= input.minCoverage
                if (ok) input.slots.forEachIndexed { j, s ->
                    val h = overlapHours(s, start, end)
                    if (h > 0) residual[j] = max(0.0, residual[j] - load.powerKw * h / s.hours)
                }
                val pct = (best.second * 100).toInt()
                val reason = if (ok) "Prognozowana nadwyżka PV pokrywa ok. $pct% energii (${"%.1f".format(needKwh)} kWh), pewność ${(best.third * 100).toInt()}%"
                else "Najlepsze okno pokrywa tylko $pct% z nadwyżki PV (próg ${(input.minCoverage * 100).toInt()}%) — uruchomienie pobierze energię z baterii/sieci"
                LoadRecommendation(load, if (ok) RecommendationKind.RUN else RecommendationKind.NOT_RECOMMENDED, start, end, best.second, reason)
            }
        }
    }

    private fun allowed(load: FlexibleLoad, start: Instant, end: Instant, zone: ZoneId): Boolean {
        val s = start.atZone(zone).toLocalTime()
        val e = end.atZone(zone).toLocalTime()
        if (load.earliest != null && s.isBefore(load.earliest)) return false
        if (load.latest != null && (e.isAfter(load.latest) || e.isBefore(s))) return false
        return true
    }

    private fun overlapHours(s: EnergySlot, start: Instant, end: Instant): Double {
        val a = maxOf(s.start, start)
        val b = minOf(s.end, end)
        return if (b.isAfter(a)) Duration.between(a, b).seconds / 3600.0 else 0.0
    }

    private fun generatorAdvice(input: EmsInput, sim: List<SlotResult>): GeneratorAdvice? {
        val g = input.generator ?: return null
        val running = sim.filter { it.generatorKw > 0 }
        if (running.isEmpty()) return null
        val hours = max(g.minRunHours, running.sumOf { it.slot.hours })
        val kwh = running.sumOf { it.generatorKw * it.slot.hours }
        return GeneratorAdvice(running.first().slot.start, hours, kwh, g.litersPerKwh?.let { it * kwh },
            "SOC spadnie do ${g.startSocPercent.toInt()}% — agregat ładuje do ${g.stopSocPercent.toInt()}%")
    }

    private fun explain(input: EmsInput, p: EmsPlan): List<String> {
        val out = mutableListOf<String>()
        val fmt = { t: Instant -> t.atZone(input.zone).toLocalTime().withSecond(0).withNano(0).toString() }
        p.windows.filter { it.kind == WindowKind.SURPLUS }.maxByOrNull { it.energyKwh }?.let {
            out += "Największa nadwyżka PV: ${fmt(it.start)}–${fmt(it.end)}, ok. ${"%.1f".format(it.energyKwh)} kWh (prognoza)"
        }
        p.recommendations.forEach { r ->
            out += if (r.kind == RecommendationKind.RUN) "Uruchom ${r.load.name} o ${fmt(r.start!!)}: ${r.reason}" else "${r.load.name}: nie zalecane — ${r.reason}"
        }
        if (p.batteryEmptyAt != null) out += "Bateria osiągnie minimum ok. ${fmt(p.batteryEmptyAt)} — ogranicz zużycie wieczorem"
        p.generator?.let { out += "Agregat: start ok. ${fmt(it.start)}, ${"%.1f".format(it.hours)} h — ${it.reason}" }
        if (p.unservedKwh > 0.05) out += "Ryzyko niedoboru: ${"%.1f".format(p.unservedKwh)} kWh nie zostanie pokryte"
        if (out.isEmpty()) out += "Brak działań do zalecenia w horyzoncie prognozy"
        return out
    }

    /** Balance of the rest of today split into day / evening (17–22) / night (22–06). */
    fun periods(slots: List<EnergySlot>, zone: ZoneId): List<PeriodBalance> {
        fun label(t: Instant): String = when (t.atZone(zone).hour) { in 6..16 -> "Dzień"; in 17..21 -> "Wieczór"; else -> "Noc" }
        return listOf("Dzień", "Wieczór", "Noc").map { l ->
            val sel = slots.filter { label(it.start) == l }
            PeriodBalance(l, sel.sumOf { it.pvKw * it.hours }, sel.sumOf { it.loadKw * it.hours })
        }
    }

    /** Daily energy balance per local date (e.g. 7-day outlook). */
    fun daily(slots: List<EnergySlot>, zone: ZoneId): List<DayBalance> =
        slots.groupBy { it.start.atZone(zone).toLocalDate() }.toSortedMap().map { (d, s) ->
            DayBalance(d, s.sumOf { it.pvKw * it.hours }, s.sumOf { it.loadKw * it.hours }, s.map { it.confidence }.average())
        }
}
