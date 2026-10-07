package com.solartracker.pro.core.analytics

/** Small robust statistics used by the diagnostics (no external dependencies). */
object Stats {
    fun median(values: List<Double>): Double? = percentile(values, 50.0)

    /** Linear-interpolated percentile [0..100]; null for an empty list. */
    fun percentile(values: List<Double>, p: Double): Double? {
        val v = values.filter { it.isFinite() }.sorted()
        if (v.isEmpty()) return null
        if (v.size == 1) return v[0]
        val rank = (p.coerceIn(0.0, 100.0) / 100.0) * (v.size - 1)
        val lo = rank.toInt()
        val hi = minOf(lo + 1, v.size - 1)
        return v[lo] + (v[hi] - v[lo]) * (rank - lo)
    }

    /** Interquartile range (P75 − P25); null for an empty list. */
    fun iqr(values: List<Double>): Double? {
        val q1 = percentile(values, 25.0) ?: return null
        return percentile(values, 75.0)!! - q1
    }

    /** Least-squares slope of y over x; null with fewer than 2 distinct x values. */
    fun slope(xs: List<Double>, ys: List<Double>): Double? {
        require(xs.size == ys.size)
        if (xs.size < 2) return null
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
    }
}
