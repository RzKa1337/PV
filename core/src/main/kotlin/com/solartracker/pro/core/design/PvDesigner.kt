package com.solartracker.pro.core.design

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/** Panel datasheet values (STC). All fields come from the datasheet — nothing is assumed. */
data class PanelSpec(
    val powerW: Double,
    val vocV: Double,
    val vmpV: Double,
    val iscA: Double,
    val impA: Double,
    /** Voc temperature coefficient [1/°C], e.g. −0.0027. */
    val vocTempCoeff: Double,
    /** Pmax/Vmp temperature coefficient [1/°C], e.g. −0.0035. */
    val pmaxTempCoeff: Double,
    val lengthM: Double,
    val widthM: Double,
) {
    val areaM2: Double get() = lengthM * widthM
}

/** Inverter / charge-controller DC input limits from its datasheet. */
data class MpptSpec(
    val trackers: Int,
    val minMpptV: Double,
    val maxMpptV: Double,
    val maxInputV: Double,
    /** Max operating current per tracker [A]. */
    val maxCurrentA: Double,
    /** Max short-circuit current per tracker [A]; null = not specified. */
    val maxIscA: Double? = null,
    /** Max PV power on all trackers [W]; null = not specified. */
    val maxPvPowerW: Double? = null,
    val ratedAcW: Double,
)

data class DesignInput(
    val panel: PanelSpec,
    val mppt: MpptSpec,
    /** Target DC power [W]; null = fill the area. */
    val targetPowerW: Double? = null,
    /** Usable roof/ground area [m²]; null = unlimited. */
    val areaM2: Double? = null,
    /** Area use factor (gaps, walkways). */
    val packingFactor: Double = 0.85,
    /** Record low ambient temperature at the site [°C] (worst-case Voc). */
    val minAmbientC: Double = -25.0,
    /** Hot cell temperature [°C] (lowest Vmp). */
    val maxCellC: Double = 70.0,
    val costPerWp: Double? = null,
)

data class StringLayout(val panelsPerString: Int, val stringsPerTracker: Int, val trackersUsed: Int) {
    val panels: Int get() = panelsPerString * stringsPerTracker * trackersUsed
}

data class DesignResult(
    val layout: StringLayout?,
    val minSeries: Int,
    val maxSeries: Int,
    val maxParallel: Int,
    val dcPowerW: Double,
    val areaM2: Double,
    val vocColdV: Double,
    val vmpHotV: Double,
    val vmpColdV: Double,
    val trackerCurrentA: Double,
    val dcAcRatio: Double,
    val investment: Double?,
    val warnings: List<String>,
)

/** String sizing: voltage window across temperatures, current per tracker, area and target power. */
object PvDesigner {
    fun design(input: DesignInput): DesignResult {
        val p = input.panel
        val m = input.mppt
        val vocCold = p.vocV * (1 + p.vocTempCoeff * (input.minAmbientC - 25.0))
        val vmpHot = p.vmpV * (1 + p.pmaxTempCoeff * (input.maxCellC - 25.0))
        val vmpCold = p.vmpV * (1 + p.vocTempCoeff * (input.minAmbientC - 25.0))
        val maxSeries = min(floor(m.maxInputV / vocCold), floor(m.maxMpptV / vmpCold)).toInt()
        val minSeries = ceil(m.minMpptV / vmpHot).toInt().coerceAtLeast(1)
        val maxParallel = floor(m.maxCurrentA / p.impA).toInt().let { byImp ->
            m.maxIscA?.let { min(byImp, floor(it / p.iscA).toInt()) } ?: byImp
        }
        val areaLimit = input.areaM2?.let { floor(it * input.packingFactor / p.areaM2).toInt() } ?: Int.MAX_VALUE
        val powerLimit = m.maxPvPowerW?.let { floor(it / p.powerW).toInt() } ?: Int.MAX_VALUE
        val wanted = input.targetPowerW?.let { ceil(it / p.powerW).toInt() } ?: Int.MAX_VALUE
        val cap = minOf(areaLimit, powerLimit)
        val warnings = mutableListOf<String>()
        if (maxSeries < minSeries) warnings += "Brak poprawnej liczby paneli w stringu: okno MPPT ${m.minMpptV.toInt()}–${m.maxMpptV.toInt()} V nie mieści napięć panelu w zakresie temperatur"
        if (maxParallel < 1) warnings += "Prąd panelu (${p.impA} A) przekracza limit wejścia MPPT (${m.maxCurrentA} A)"

        var best: StringLayout? = null
        if (maxSeries >= minSeries && maxParallel >= 1) {
            for (n in minSeries..maxSeries) for (s in 1..maxParallel) for (t in 1..m.trackers) {
                val l = StringLayout(n, s, t)
                if (l.panels > cap) continue
                val b = best
                val better = when {
                    b == null -> true
                    wanted == Int.MAX_VALUE -> l.panels > b.panels
                    // Closest to the target from below wins; otherwise the smallest overshoot.
                    else -> score(l.panels, wanted) < score(b.panels, wanted) ||
                        (score(l.panels, wanted) == score(b.panels, wanted) && l.stringsPerTracker * l.trackersUsed < b.stringsPerTracker * b.trackersUsed)
                }
                if (better) best = l
            }
            if (best == null) warnings += "Za mało miejsca / limit mocy na najkrótszy string ($minSeries paneli)"
        }
        val layout = best
        val dc = (layout?.panels ?: 0) * p.powerW
        val ratio = if (m.ratedAcW > 0) dc / m.ratedAcW else 0.0
        if (layout != null) {
            if (input.targetPowerW != null && dc < input.targetPowerW * 0.95) warnings += "Moc ${"%.2f".format(dc / 1000)} kWp poniżej celu — ograniczenie: powierzchnia, limity MPPT lub moc PV"
            if (ratio > 1.4) warnings += "Stosunek DC/AC ${"%.2f".format(ratio)} — możliwe obcinanie mocy w słoneczne dni"
            if (m.maxIscA == null && p.iscA * layout.stringsPerTracker > m.maxCurrentA * 1.25) warnings += "Sprawdź prąd zwarciowy wejścia (brak danych Isc max w specyfikacji)"
        }
        return DesignResult(
            layout, minSeries, maxSeries, maxParallel, dc, (layout?.panels ?: 0) * p.areaM2, vocCold * (layout?.panelsPerString ?: 0),
            vmpHot * (layout?.panelsPerString ?: 0), vmpCold * (layout?.panelsPerString ?: 0), p.impA * (layout?.stringsPerTracker ?: 0),
            ratio, input.costPerWp?.let { it * dc }, warnings,
        )
    }

    private fun score(panels: Int, wanted: Int): Int = if (panels <= wanted) (wanted - panels) * 2 else (panels - wanted) * 2 + 1
}
