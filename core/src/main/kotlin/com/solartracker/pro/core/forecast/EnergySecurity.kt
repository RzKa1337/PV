package com.solartracker.pro.core.forecast

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.quality.DataKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

enum class EnergyRisk(val label: String) { LOW("NISKIE"), MEDIUM("ŚREDNIE"), HIGH("WYSOKIE"), UNKNOWN("NIEZNANE") }

/** Predicted SOC at a clock time, with the pessimistic/optimistic band (whole percent, no false precision). */
data class SocMilestone(val label: String, val time: Instant, val socPercent: Int, val lowPercent: Int, val highPercent: Int)

/**
 * "Will there be enough energy?" — SOC trajectory with uncertainty and a security score.
 *
 * [securityPercent] = usable stored energy / energy the battery must deliver before PV covers the load
 * again (expected scenario), capped at 100%. [risk] uses the pessimistic scenario too.
 */
data class EnergySecurity(
    val socNow: Double?,
    val socKind: DataKind,
    val minSocPercent: Double?,
    val milestones: List<SocMilestone>,
    /** First time the expected trajectory reaches the minimum SOC (null = not within the horizon). */
    val minSocAt: Instant?,
    val timeToMinSoc: Duration?,
    /** Same for the pessimistic scenario. */
    val minSocAtPessimistic: Instant?,
    val predictedMinSoc: Int?,
    val securityPercent: Int?,
    val risk: EnergyRisk,
    val shortageExpected: Boolean,
    val confidence: Double,
    val gridBackup: Boolean,
    val explanation: String,
)

/** One day of the multi-day outlook (energies from the expected scenario). */
data class DayOutlook(
    val date: LocalDate,
    val label: String,
    val pvKwh: Double,
    val loadKwh: Double,
    val batteryChargeKwh: Double,
    val batteryDischargeKwh: Double,
    val gridImportKwh: Double,
    val socMin: Int?,
    val socMax: Int?,
    /** Lowest SOC in the pessimistic scenario. */
    val socMinPessimistic: Int?,
    val securityPercent: Int?,
    val risk: EnergyRisk,
    val confidence: Double,
    /** True when the period covers only part of the day (today: from now). */
    val partial: Boolean,
) {
    val balanceKwh: Double get() = pvKwh - loadKwh
}

/**
 * Builds the energy security view from the existing [BatteryPredictor] (same limits and efficiencies),
 * run three times: expected, pessimistic (lower PV band, higher load) and optimistic.
 */
class EnergySecurityAnalyzer(
    private val battery: BatteryStorage?,
    private val zone: ZoneId,
    private val inverterEfficiency: Double = 0.94,
) {
    private val predictor = battery?.takeIf { it.isValid }?.let { BatteryPredictor(it, zone, inverterEfficiency) }

    fun analyze(
        now: Instant,
        socNow: Double?,
        socKind: DataKind,
        pv: (Instant) -> PvForecastPoint,
        load: (Instant) -> LoadForecastPoint,
        gridAvailable: Boolean,
        horizon: Duration = Duration.ofHours(30),
        step: Duration = Duration.ofMinutes(15),
    ): EnergySecurity {
        val b = battery
        if (predictor == null || b == null || socNow == null) {
            return EnergySecurity(socNow, socKind, b?.minSocPercent, emptyList(), null, null, null, null, null,
                if (gridAvailable) EnergyRisk.LOW else EnergyRisk.UNKNOWN, false, 0.0, gridAvailable,
                when {
                    b == null -> if (gridAvailable) "Brak baterii – zasilanie z PV i sieci" else "Brak skonfigurowanej baterii"
                    else -> "Brak aktualnego SOC z falownika – nie da się przewidzieć baterii"
                })
        }
        val (expected, pess, opt) = scenarios(now, socNow, socKind, pv, load, horizon, step)
        val milestones = milestoneTimes(now, horizon).mapNotNull { (label, t) ->
            val e = socAt(expected, t) ?: return@mapNotNull null
            val lo = socAt(pess, t) ?: e
            val hi = socAt(opt, t) ?: e
            SocMilestone(label, t, e.roundToInt(), minOf(lo, e).roundToInt(), maxOf(hi, e).roundToInt())
        }
        val needed = energyNeededFromBattery(expected, step)
        val available = (b.storedKwh(socNow) - b.storedKwh(b.minSocPercent)).coerceAtLeast(0.0) * b.dischargeEfficiency
        val security = if (needed <= 0.01) 100 else (available / needed * 100).coerceIn(0.0, 100.0).roundToInt()
        val minSoc = expected.rows.mapNotNull { it.socPercent }.minOrNull()
        val risk = when {
            expected.emptyAt != null -> EnergyRisk.HIGH
            pess.emptyAt != null -> EnergyRisk.MEDIUM
            else -> EnergyRisk.LOW
        }
        val shortage = expected.emptyAt != null
        val fmt = { t: Instant -> t.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString() }
        val explanation = when {
            shortage && gridAvailable -> "Bateria osiągnie minimum ok. ${fmt(expected.emptyAt!!)} – dalej energia z sieci"
            shortage -> "⚠️ PRZEWIDYWANY BRAK ENERGII: minimum SOC ok. ${fmt(expected.emptyAt!!)}"
            pess.emptyAt != null -> "Przy gorszej pogodzie lub większym zużyciu minimum SOC ok. ${fmt(pess.emptyAt)}"
            else -> "Energii wystarczy do ponownego ładowania z PV"
        }
        return EnergySecurity(
            socNow, socKind, b.minSocPercent, milestones, expected.emptyAt, expected.emptyAt?.let { Duration.between(now, it) },
            pess.emptyAt, minSoc?.roundToInt(), security, risk, shortage,
            expected.confidence.coerceIn(0.0, 1.0), gridAvailable, explanation,
        )
    }

    /** Today (from now), tomorrow, +2 and +3 days. Without a battery only PV/load energies are given. */
    fun outlook(
        now: Instant,
        socNow: Double?,
        socKind: DataKind,
        pv: (Instant) -> PvForecastPoint,
        load: (Instant) -> LoadForecastPoint,
        days: Int = 4,
        step: Duration = Duration.ofMinutes(30),
    ): List<DayOutlook> {
        val today = now.atZone(zone).toLocalDate()
        val end = today.plusDays(days.toLong()).atStartOfDay(zone).toInstant()
        val horizon = Duration.between(now, end)
        val h = step.seconds / 3600.0
        val labels = listOf("DZIŚ", "JUTRO", "POJUTRZE", "ZA 3 DNI")
        if (predictor == null || socNow == null) {
            // Midpoint rule per local day (energy = Σ kW · hours over exact slices, the first one starting at `now`).
            return generateSequence(today) { it.plusDays(1) }.take(days).toList().mapIndexed { i, date ->
                val from = maxOf(now, date.atStartOfDay(zone).toInstant())
                val slices = midpointSlices(from, date.plusDays(1).atStartOfDay(zone).toInstant(), step)
                val p = slices.map { (mid, hours) -> hours to pv(mid) }
                val pvKwh = p.sumOf { (hours, f) -> f.expectedKw * hours }
                val loadKwh = slices.sumOf { (mid, hours) -> load(mid).kw * hours }
                DayOutlook(date, labels.getOrElse(i) { date.toString() }, pvKwh, loadKwh,
                    0.0, 0.0, 0.0, null, null, null, null, EnergyRisk.UNKNOWN, p.map { it.second.confidence }.average().takeIf { !it.isNaN() } ?: 0.0, date == today)
            }
        }
        val (expected, pess, _) = scenarios(now, socNow, socKind, pv, load, horizon, step)
        val pessByDay = pess.rows.groupBy { it.time.atZone(zone).toLocalDate() }
        return expected.rows.filter { it.time.isBefore(end) }.groupBy { it.time.atZone(zone).toLocalDate() }.toSortedMap().entries.mapIndexed { i, (date, rows) ->
            val socs = rows.mapNotNull { it.socPercent }
            val pessMin = pessByDay[date]?.mapNotNull { it.socPercent }?.minOrNull()
            val needed = energyNeeded(rows, h)
            val startSoc = socs.firstOrNull()
            val available = startSoc?.let { s -> (battery!!.storedKwh(s) - battery.storedKwh(battery.minSocPercent)).coerceAtLeast(0.0) * battery.dischargeEfficiency }
            val minHit = socs.any { it <= battery!!.minSocPercent + 0.5 }
            val pessHit = pessMin != null && pessMin <= battery!!.minSocPercent + 0.5
            DayOutlook(
                date, labels.getOrElse(i) { date.toString() },
                rows.sumOf { it.pvKw } * h, rows.sumOf { it.loadKw } * h,
                rows.sumOf { it.batteryKw.coerceAtLeast(0.0) } * h, rows.sumOf { (-it.batteryKw).coerceAtLeast(0.0) } * h,
                rows.sumOf { it.gridKw.coerceAtLeast(0.0) } * h,
                socs.minOrNull()?.roundToInt(), socs.maxOrNull()?.roundToInt(), pessMin?.roundToInt(),
                if (needed <= 0.01 || available == null) 100 else (available / needed * 100).coerceIn(0.0, 100.0).roundToInt(),
                when { minHit -> EnergyRisk.HIGH; pessHit -> EnergyRisk.MEDIUM; else -> EnergyRisk.LOW },
                (expected.confidence * (1.0 - 0.15 * i)).coerceIn(0.05, 1.0), date == today,
            )
        }
    }

    /** The three SOC trajectories behind every result of this analyzer (null without battery/SOC). */
    data class Scenarios(val expected: BatteryPrediction, val pessimistic: BatteryPrediction, val optimistic: BatteryPrediction)

    /** Same scenarios as [analyze] uses, for layers built on top of it (e.g. energy missions). */
    fun scenarioSet(
        now: Instant, socNow: Double?, socKind: DataKind, pv: (Instant) -> PvForecastPoint, load: (Instant) -> LoadForecastPoint,
        horizon: Duration, step: Duration = Duration.ofMinutes(15),
    ): Scenarios? = if (predictor == null || socNow == null) null else scenarios(now, socNow, socKind, pv, load, horizon, step)

    val batteryConfig: BatteryStorage? get() = battery?.takeIf { predictor != null }

    private fun scenarios(
        now: Instant, soc: Double, kind: DataKind, pv: (Instant) -> PvForecastPoint, load: (Instant) -> LoadForecastPoint,
        horizon: Duration, step: Duration,
    ): Scenarios {
        val p = predictor!!
        fun spread(l: LoadForecastPoint) = 0.10 + (1 - l.confidence.coerceIn(0.0, 1.0)) * 0.25
        val expected = p.predict(now, soc, kind, pv, load, horizon, step)
        val pess = p.predict(now, soc, kind, { t -> pv(t).let { it.copy(expectedKw = it.minKw) } },
            { t -> load(t).let { it.copy(kw = it.kw * (1 + spread(it))) } }, horizon, step)
        val opt = p.predict(now, soc, kind, { t -> pv(t).let { it.copy(expectedKw = it.maxKw) } },
            { t -> load(t).let { it.copy(kw = it.kw * (1 - spread(it))) } }, horizon, step)
        return Scenarios(expected, pess, opt)
    }

    private fun socAt(p: BatteryPrediction, t: Instant): Double? =
        p.rows.lastOrNull { !it.time.isAfter(t) }?.takeIf { Duration.between(it.time, t).toMinutes() <= 60 }?.socPercent

    private fun milestoneTimes(now: Instant, horizon: Duration): List<Pair<String, Instant>> {
        val date = now.atZone(zone).toLocalDate()
        return listOf(21, 0, 3, 6, 8).map { hour ->
            var t = date.atTime(LocalTime.of(hour, 0)).atZone(zone).toInstant()
            while (!t.isAfter(now)) t = t.plus(Duration.ofDays(1))
            String.format("%02d:00", hour) to t
        }.filter { !it.second.isAfter(now.plus(horizon)) }.sortedBy { it.second }
    }

    /** Energy the battery must deliver before PV covers the load again (max cumulative deficit) [kWh]. */
    private fun energyNeededFromBattery(p: BatteryPrediction, step: Duration) = energyNeeded(p.rows, step.seconds / 3600.0)

    private fun energyNeeded(rows: List<EnergyForecastRow>, h: Double): Double {
        val b = battery ?: return 0.0
        var cum = 0.0
        var worst = 0.0
        for (r in rows) {
            val net = r.pvKw - r.loadKw / inverterEfficiency
            cum += if (net >= 0) net * b.chargeEfficiency * h else net / b.dischargeEfficiency * h
            cum = cum.coerceAtMost(0.0) // a full recharge resets the requirement
            worst = minOf(worst, cum)
        }
        return -worst
    }
}
