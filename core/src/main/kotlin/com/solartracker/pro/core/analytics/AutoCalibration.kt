package com.solartracker.pro.core.analytics


import java.time.Instant
import kotlin.math.abs


/** Sky situation of a sample or forecast hour; calibration is learned separately for each. */
enum class SkyCondition(val label: String) {
    CLEAR("bezchmurnie"), PARTLY_CLOUDY("częściowe zachmurzenie"), OVERCAST("pochmurno"), RAIN("deszcz"), SNOW("śnieg"), UNKNOWN("nieznane"),
}

object SkyClassifier {
    /**
     * @param clearSkyIndex measured or forecast GHI / clear-sky GHI (null when unknown)
     */
    fun classify(cloudCoverPercent: Double?, precipitationMm: Double?, snowDepthM: Double?, temperatureC: Double?, clearSkyIndex: Double?): SkyCondition = when {
        (snowDepthM ?: 0.0) >= 0.02 || ((precipitationMm ?: 0.0) >= 0.2 && (temperatureC ?: 99.0) <= 0.5) -> SkyCondition.SNOW
        (precipitationMm ?: 0.0) >= 0.2 -> SkyCondition.RAIN
        clearSkyIndex != null && clearSkyIndex >= 0.8 -> SkyCondition.CLEAR
        clearSkyIndex != null && clearSkyIndex < 0.35 -> SkyCondition.OVERCAST
        clearSkyIndex != null -> SkyCondition.PARTLY_CLOUDY
        cloudCoverPercent == null -> SkyCondition.UNKNOWN
        cloudCoverPercent < 20 -> SkyCondition.CLEAR
        cloudCoverPercent > 75 -> SkyCondition.OVERCAST
        else -> SkyCondition.PARTLY_CLOUDY
    }
}

/** One sample used for calibration, with the context needed to separate effects. */
data class CalibrationObservation(
    val time: Instant,
    val realKw: Double,
    val modelKw: Double,
    val cellTemperatureC: Double?,
    /** Model output was near the inverter/MPPT limit (excluded from temperature/soiling analysis). */
    val nearLimit: Boolean = false,
    val sunElevationDeg: Double? = null,
    val sunAzimuthDeg: Double? = null,
    val cloudCoverPercent: Double? = null,
    /** Irradiance used by the model (POA) [W/m²]. */
    val irradianceWm2: Double? = null,
    val clearSkyIndex: Double? = null,
    val condition: SkyCondition = SkyCondition.UNKNOWN,
    /** Shading power factor applied by the model (1 = unshaded). */
    val shadingFactor: Double? = null,
    /** Inverter reported a fault at this time. */
    val fault: Boolean = false,
    /** Communication was healthy (fresh, validated reading). */
    val linkOk: Boolean = true,
    /** Telemetry validation rejected a value of this reading. */
    val invalidTelemetry: Boolean = false,
) {
    val ratio: Double get() = realKw / modelKw
}

/** Situation for which a calibrated factor is requested (a forecast point). */
data class CalibrationContext(val time: Instant, val sunElevationDeg: Double?, val condition: SkyCondition)

/** Learned factor for one bucket (condition, sun elevation band, hour or month). */
data class CalibrationBucket(val dimension: String, val key: String, val factor: Double, val samples: Int)

/** Error of the model on held-out days, before and after calibration [kW]. */
data class CalibrationMetrics(val samples: Int, val maeBefore: Double, val maeAfter: Double, val rmseBefore: Double, val rmseAfter: Double, val biasBefore: Double, val biasAfter: Double)

/**
 * Conditional calibration (AutoCalibration 3.0): a global factor plus multiplicative adjustments per sky
 * condition, sun-elevation band, hour of day and month, each shrunk towards the global value by its
 * sample count. The applied correction is further scaled by [confidence], so little data barely changes
 * the forecast.
 */
data class CalibrationModel(
    val globalFactor: Double,
    val buckets: List<CalibrationBucket>,
    val confidence: Double,
    val sampleCount: Int,
    val trainingDays: Int,
    val excluded: Map<String, Int>,
    /** Validation on the most recent days (not used for training); null with too few days. */
    val validation: CalibrationMetrics?,
    val ready: Boolean,
    private val zone: java.time.ZoneId,
) {
    private val index = buckets.associateBy { it.dimension to it.key }

    /** Correction factor for a point; 1.0 when not ready. Bounded to 0.5–1.3. */
    fun factor(ctx: CalibrationContext): Double {
        if (!ready) return 1.0
        var f = globalFactor
        for ((dim, key) in keys(ctx.time, ctx.sunElevationDeg, ctx.condition, zone)) {
            // Hour and month are correlated with sun elevation: they get half weight (square root).
            val weight = if (dim == "hour" || dim == "month") 0.5 else 1.0
            index[dim to key]?.let { f *= Math.pow(it.factor / globalFactor, weight) }
        }
        val raw = f.coerceIn(0.5, 1.3)
        return 1.0 + (raw - 1.0) * confidence
    }

    fun describe(): String = when {
        !ready -> "Uczenie: $sampleCount próbek z $trainingDays dni (potrzeba więcej danych)"
        else -> "Kalibracja z $sampleCount próbek / $trainingDays dni, pewność ${(confidence * 100).toInt()}%" +
            (validation?.let { " · MAE ${"%.2f".format(it.maeBefore)} → ${"%.2f".format(it.maeAfter)} kW" } ?: "")
    }

    companion object {
        fun elevationBand(deg: Double?): String? = deg?.let {
            when {
                it < 15 -> null
                it < 30 -> "15-30"
                it < 45 -> "30-45"
                it < 60 -> "45-60"
                else -> "60+"
            }
        }

        internal fun keys(time: Instant, elevation: Double?, condition: SkyCondition, zone: java.time.ZoneId): List<Pair<String, String>> {
            val z = time.atZone(zone)
            return listOfNotNull(
                ("condition" to condition.name).takeIf { condition != SkyCondition.UNKNOWN },
                elevationBand(elevation)?.let { "elevation" to it },
                "hour" to z.hour.toString(),
                "month" to z.monthValue.toString(),
            )
        }

        fun untrained(zone: java.time.ZoneId) = CalibrationModel(1.0, emptyList(), 0.0, 0, 0, emptyMap(), null, false, zone)
    }
}

enum class CorrectionKind(val label: String) {
    YIELD("Ogólny uzysk instalacji (real / model)"),
    TEMPERATURE_COEFFICIENT("Korekta współczynnika temperaturowego"),
    SOILING_TREND("Trend spadku uzysku (możliwe zabrudzenie / degradacja)"),
    FORECAST_BIAS("Korekta błędu systematycznego prognozy"),
    CLIPPING("Udział czasu z ograniczeniem mocy falownika"),
}

/** A learned correction with full provenance. Only [enabled] and [ready] corrections are applied. */
data class Correction(
    val kind: CorrectionKind,
    val value: Double,
    val unit: String,
    val source: String,
    val periodStart: Instant?,
    val periodEnd: Instant?,
    val samples: Int,
    val confidence: Double,
    val ready: Boolean,
    val enabled: Boolean,
    val note: String,
)

data class CorrectionChange(val time: Instant, val kind: CorrectionKind, val from: Double?, val to: Double, val reason: String)

/**
 * Statistical (no ML) self-calibration from MODEL vs REAL vs FORECAST:
 * - yield factor: median ratio with MAD outlier rejection (via [CalibrationEngine]);
 * - temperature coefficient: least-squares slope of ratio vs cell temperature (needs ≥ 10 °C spread);
 * - soiling/degradation trend: slope of daily median ratio over ≥ 14 days (reported, not auto-applied);
 * - forecast bias: from [ForecastAccuracy];
 * - clipping share: fraction of samples at the inverter limit (reported).
 * Each correction keeps source, period, sample count, confidence, enabled flag and change history.
 */
class AutoCalibrationEngine(
    private val disabled: Set<CorrectionKind> = emptySet(),
    private val minSamples: Int = 60,
) {
    private val history = mutableListOf<CorrectionChange>()
    private val last = mutableMapOf<CorrectionKind, Double>()

    fun history(): List<CorrectionChange> = history.toList()

    fun restoreHistory(changes: List<CorrectionChange>) {
        history.clear(); history += changes
        changes.forEach { last[it.kind] = it.to }
    }

    /** Why a sample must not be learned from (null = usable). */
    fun exclusionReason(o: CalibrationObservation): String? = when {
        !o.linkOk -> "utrata komunikacji / dane nieaktualne"
        o.invalidTelemetry -> "błędne dane z falownika"
        o.fault -> "awaria falownika"
        o.nearLimit -> "ograniczenie mocy (clipping)"
        !o.realKw.isFinite() || !o.modelKw.isFinite() || o.realKw < 0 -> "nieprawidłowe wartości"
        o.modelKw < 0.05 -> "model bliski zeru"
        (o.sunElevationDeg ?: 90.0) < 10 -> "słońce zbyt nisko"
        o.realKw == 0.0 && o.modelKw > 0.3 -> "PV wyłączone lub awaria (0 W)"
        (o.shadingFactor ?: 1.0) < 0.5 -> "silne zacienienie (niepewny model cienia)"
        else -> null
    }

    /**
     * Builds the conditional model. The most recent ~20% of days (at least 2) are held out to measure
     * MAE/RMSE/bias before vs after calibration when there are ≥ 7 training days.
     */
    fun buildModel(observations: List<CalibrationObservation>, zone: java.time.ZoneId, shrinkage: Double = 30.0, minDays: Int = 3): CalibrationModel {
        val excluded = mutableMapOf<String, Int>()
        val usable = observations.filter { o ->
            val r = exclusionReason(o)
            if (r != null) excluded[r] = (excluded[r] ?: 0) + 1
            r == null
        }
        val days = usable.map { it.time.atZone(zone).toLocalDate() }.distinct().sorted()
        if (usable.isEmpty()) return CalibrationModel.untrained(zone).copy(excluded = excluded)
        val holdoutDays = if (days.size >= 7) days.takeLast(maxOf(2, days.size / 5)).toSet() else emptySet()
        val train = usable.filter { it.time.atZone(zone).toLocalDate() !in holdoutDays }
        val test = usable.filter { it.time.atZone(zone).toLocalDate() in holdoutDays }
        val model = fit(train, zone, shrinkage, minDays, excluded)
        val metrics = test.takeIf { it.size >= 20 }?.let { t ->
            fun stats(pred: (CalibrationObservation) -> Double): Triple<Double, Double, Double> {
                val e = t.map { pred(it) - it.realKw }
                return Triple(e.map { abs(it) }.average(), kotlin.math.sqrt(e.map { it * it }.average()), e.average())
            }
            val before = stats { it.modelKw }
            val after = stats { it.modelKw * model.factor(CalibrationContext(it.time, it.sunElevationDeg, it.condition)) }
            CalibrationMetrics(t.size, before.first, after.first, before.second, after.second, before.third, after.third)
        }
        // Final model uses all usable days; the held-out metrics describe how it generalises.
        return fit(usable, zone, shrinkage, minDays, excluded).copy(validation = metrics)
    }

    private fun fit(obs: List<CalibrationObservation>, zone: java.time.ZoneId, k: Double, minDays: Int, excluded: Map<String, Int>): CalibrationModel {
        if (obs.isEmpty()) return CalibrationModel.untrained(zone).copy(excluded = excluded)
        val ratios = obs.map { it.ratio }
        val med = CalibrationEngine.median(ratios)
        val mad = CalibrationEngine.median(ratios.map { abs(it - med) }).coerceAtLeast(0.01)
        val kept = obs.filter { abs(it.ratio - med) <= 3.0 * 1.4826 * mad }
        val global = CalibrationEngine.median(kept.map { it.ratio }).coerceIn(0.4, 1.3)
        val buckets = kept.flatMap { o -> CalibrationModel.keys(o.time, o.sunElevationDeg, o.condition, zone).map { it to o.ratio } }
            .groupBy({ it.first }, { it.second })
            .map { (key, values) ->
                val n = values.size
                val raw = CalibrationEngine.median(values)
                CalibrationBucket(key.first, key.second, global + (raw - global) * n / (n + k), n)
            }
        val days = kept.map { it.time.atZone(zone).toLocalDate() }.distinct().size
        val spread = CalibrationEngine.median(kept.map { abs(it.ratio - global) }) * 1.4826 / global
        val confidence = ((kept.size.toDouble() / minSamples).coerceAtMost(1.0) * (days.toDouble() / 14).coerceAtMost(1.0) *
            (1.0 - spread * 2).coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
        return CalibrationModel(global, buckets, confidence, kept.size, days, excluded, null, kept.size >= minSamples && days >= minDays, zone)
    }

    fun evaluate(observations: List<CalibrationObservation>, forecast: AccuracyReport?, now: Instant): List<Correction> {
        val obs = observations.filter { it.modelKw > 0.05 && it.realKw >= 0 && it.ratio.isFinite() }
        val start = obs.minOfOrNull { it.time }
        val end = obs.maxOfOrNull { it.time }
        val days = obs.map { it.time.epochSecond / 86_400 }.toSet().size
        val out = mutableListOf<Correction>()

        // Yield
        val unclipped = obs.filter { !it.nearLimit }
        val engine = CalibrationEngine(minSamples = minSamples, minDays = 3).apply {
            load(unclipped.map { CalibrationSample(it.time, it.realKw, it.modelKw) })
        }
        val y = engine.result()
        out += correction(CorrectionKind.YIELD, y.factor, "×", y.samples, y.confidence, y.ready, start, end, y.reason, now)

        // Temperature coefficient (slope of ratio vs cell temperature, normalised by the median ratio).
        val withTemp = unclipped.filter { it.cellTemperatureC != null }
        val spread = (withTemp.maxOfOrNull { it.cellTemperatureC!! } ?: 0.0) - (withTemp.minOfOrNull { it.cellTemperatureC!! } ?: 0.0)
        if (withTemp.size >= 10) {
            val xs = withTemp.map { it.cellTemperatureC!! - 25.0 }
            val ys = withTemp.map { it.ratio }
            val fit = linearFit(xs, ys)
            val median = CalibrationEngine.median(ys).coerceAtLeast(0.1)
            val slopePerC = fit.slope / median // relative change per °C beyond the model's coefficient
            val ready = withTemp.size >= minSamples && spread >= 10.0 && fit.r2 >= 0.2
            val conf = ((withTemp.size.toDouble() / minSamples).coerceAtMost(1.0) * (spread / 20.0).coerceAtMost(1.0) * fit.r2).coerceIn(0.0, 1.0)
            out += correction(CorrectionKind.TEMPERATURE_COEFFICIENT, slopePerC * 100, "%/°C", withTemp.size, conf, ready, start, end,
                if (ready) "Dodatkowy wpływ temperatury ${"%+.2f".format(slopePerC * 100)} %/°C (R²=${"%.2f".format(fit.r2)})"
                else "Za mało danych: ${withTemp.size}/$minSamples próbek, rozrzut ${spread.toInt()}/10 °C", now)
        }

        // Trend of daily median ratios (soiling / degradation).
        val daily = unclipped.groupBy { it.time.epochSecond / 86_400 }.filterValues { it.size >= 5 }
            .map { (day, list) -> day.toDouble() to CalibrationEngine.median(list.map { it.ratio }) }.sortedBy { it.first }
        if (daily.size >= 3) {
            val fit = linearFit(daily.map { it.first }, daily.map { it.second })
            val perMonth = fit.slope * 30 * 100
            val ready = daily.size >= 14 && fit.r2 >= 0.3 && perMonth < -0.5
            out += correction(CorrectionKind.SOILING_TREND, perMonth, "%/30 dni", daily.size, (daily.size / 30.0).coerceAtMost(1.0) * fit.r2, ready,
                start, end, if (ready) "Uzysk spada o ${"%.1f".format(-perMonth)}% na miesiąc – sprawdź zabrudzenie paneli" else "Brak istotnego trendu (dni: ${daily.size})", now,
                autoApply = false)
        }

        // Forecast bias
        if (forecast != null && forecast.biasPercent != null && forecast.count > 0) {
            val ready = forecast.count >= 48
            val factor = 1.0 / (1.0 + forecast.biasPercent / 100.0)
            out += correction(CorrectionKind.FORECAST_BIAS, factor, "×", forecast.count, (forecast.count / 168.0).coerceAtMost(1.0), ready, start, end,
                forecast.describe(), now)
        }

        // Clipping share
        if (obs.isNotEmpty()) {
            val share = obs.count { it.nearLimit }.toDouble() / obs.size * 100
            out += correction(CorrectionKind.CLIPPING, share, "% próbek", obs.size, (obs.size / 200.0).coerceAtMost(1.0), obs.size >= 50, start, end,
                if (share > 5) "Falownik ogranicza moc przez ${"%.0f".format(share)}% czasu produkcji" else "Clipping marginalny", now, autoApply = false)
        }
        if (days == 0) return out
        return out
    }

    private fun correction(
        kind: CorrectionKind, value: Double, unit: String, samples: Int, confidence: Double, ready: Boolean,
        start: Instant?, end: Instant?, note: String, now: Instant, autoApply: Boolean = true,
    ): Correction {
        val enabled = kind !in disabled && autoApply
        if (ready && enabled) {
            val prev = last[kind]
            if (prev == null || abs(prev - value) > abs(prev) * 0.01 + 1e-6) {
                history += CorrectionChange(now, kind, prev, value, note)
                last[kind] = value
            }
        }
        return Correction(kind, value, unit, "pomiary falownika vs model", start, end, samples, confidence.coerceIn(0.0, 1.0), ready, enabled, note)
    }

    data class Fit(val slope: Double, val intercept: Double, val r2: Double)

    companion object {
        fun linearFit(xs: List<Double>, ys: List<Double>): Fit {
            require(xs.size == ys.size && xs.size >= 2)
            val mx = xs.average()
            val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            if (sxx == 0.0) return Fit(0.0, my, 0.0)
            val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
            val slope = sxy / sxx
            val intercept = my - slope * mx
            val ssTot = ys.sumOf { (it - my) * (it - my) }
            val ssRes = xs.indices.sumOf { val e = ys[it] - (intercept + slope * xs[it]); e * e }
            val r2 = if (ssTot == 0.0) 0.0 else (1 - ssRes / ssTot).coerceIn(0.0, 1.0)
            return Fit(slope, intercept, r2)
        }

    }
}

