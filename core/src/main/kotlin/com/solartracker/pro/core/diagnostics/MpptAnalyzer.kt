package com.solartracker.pro.core.diagnostics

import com.solartracker.pro.core.analytics.Stats
import com.solartracker.pro.core.inverter.MpptReading

/**
 * Configured string on one MPPT input. [modules] is the number of modules in series (for voltage
 * comparison); null = unknown. Orientation differences between MPPTs are handled by the caller passing
 * per-MPPT expected power.
 */
data class MpptConfig(val index: Int, val peakW: Double, val modules: Int? = null, val label: String = "MPPT $index")

enum class MpptFlag(val label: String) { NORMAL("OK"), ABNORMAL("Nieprawidłowy"), NO_DATA("Brak danych"), LOW_LIGHT("Za mało światła") }

data class MpptStatus(
    val index: Int,
    val label: String,
    val voltageV: Double?,
    val currentA: Double?,
    val powerW: Double?,
    val expectedW: Double?,
    /** (actual − expected) / expected [%]. */
    val deviationPercent: Double?,
    /** Deviation relative to the other MPPTs (removes common irradiance error) [%]; null with one MPPT. */
    val peerDeviationPercent: Double?,
    val energyTodayKwh: Double?,
    val flag: MpptFlag,
)

data class MpptAnalysis(
    /** False when the inverter does not report per-MPPT values (shown as N/A, never estimated). */
    val available: Boolean,
    val statuses: List<MpptStatus>,
    val abnormal: List<Int>,
    /** Voltage spread between MPPTs with the same module count [%]; null when not comparable. */
    val voltageSpreadPercent: Double?,
    val stringMismatch: Boolean,
    val possibleCauses: List<String>,
    val confidence: Double,
    val note: String,
)

/**
 * Compares MPPT inputs with their expectation and with each other. Comparing with peers is more reliable
 * than with the model: a forecast error affects all inputs equally. Never claims a specific module is
 * damaged – only lists possible causes.
 */
object MpptAnalyzer {
    const val ABNORMAL_PERCENT = 15.0
    const val MIN_SHARE_OF_PEAK = 0.10
    const val VOLTAGE_SPREAD_PERCENT = 10.0

    val CAUSES = listOf(
        "zacienienie części stringu",
        "niedopasowanie modułów w stringu (mismatch)",
        "luźne / skorodowane złącze lub bezpiecznik",
        "uszkodzony moduł lub dioda bocznikująca",
        "błędna konfiguracja (liczba modułów, przypisanie do MPPT)",
    )

    /**
     * @param expectedW expected DC power per MPPT index (from the loss chain); missing = only peer comparison
     * @param energyTodayKwh optional per-MPPT energy (when the protocol provides it or history integrates it)
     */
    fun analyze(
        readings: List<MpptReading>,
        configs: List<MpptConfig>,
        expectedW: Map<Int, Double> = emptyMap(),
        energyTodayKwh: Map<Int, Double> = emptyMap(),
    ): MpptAnalysis {
        if (readings.isEmpty()) {
            return MpptAnalysis(false, emptyList(), emptyList(), null, false, emptyList(), 0.0,
                "Falownik nie raportuje danych poszczególnych MPPT – N/A")
        }
        val cfg = configs.associateBy { it.index }
        // Peer comparison: power per configured Wp, relative to the median of the inputs with light.
        val specific = readings.mapNotNull { r ->
            val peak = cfg[r.index]?.peakW?.takeIf { it > 0 } ?: return@mapNotNull null
            val p = r.powerW ?: return@mapNotNull null
            r.index to p / peak
        }.toMap()
        // Reference for each input = median of the OTHER inputs (an outlier must not pull its own reference).
        fun peerRef(index: Int): Double? = Stats.median(specific.filterKeys { it != index }.values.toList())
        val allRef = Stats.median(specific.values.toList())
        val statuses = readings.sortedBy { it.index }.map { r ->
            val c = cfg[r.index]
            val exp = expectedW[r.index]
            val dev = if (exp != null && exp > 0 && r.powerW != null) (r.powerW - exp) / exp * 100 else null
            val ref = peerRef(r.index)
            val peer = if (specific.size >= 2 && ref != null && ref > 0) specific[r.index]?.let { (it / ref - 1) * 100 } else null
            val lowLight = (exp != null && c != null && exp < c.peakW * MIN_SHARE_OF_PEAK) ||
                (exp == null && allRef != null && c != null && allRef < MIN_SHARE_OF_PEAK)
            val flag = when {
                r.powerW == null -> MpptFlag.NO_DATA
                lowLight -> MpptFlag.LOW_LIGHT
                // With peers: must be low against the model AND against the peers (or peers alone when no model).
                peer != null && peer < -ABNORMAL_PERCENT && (dev == null || dev < -ABNORMAL_PERCENT) -> MpptFlag.ABNORMAL
                peer == null && dev != null && dev < -ABNORMAL_PERCENT * 1.5 -> MpptFlag.ABNORMAL
                else -> MpptFlag.NORMAL
            }
            MpptStatus(r.index, c?.label ?: "MPPT ${r.index}", r.voltageV, r.currentA, r.powerW, exp, dev, peer, energyTodayKwh[r.index], flag)
        }
        val abnormal = statuses.filter { it.flag == MpptFlag.ABNORMAL }.map { it.index }

        // String mismatch: same module count but clearly different MPP voltage while producing.
        val comparable = statuses.filter { s -> s.flag != MpptFlag.LOW_LIGHT && s.voltageV != null && (s.powerW ?: 0.0) > 0 && cfg[s.index]?.modules != null }
            .groupBy { cfg[it.index]!!.modules!! }.values.filter { it.size >= 2 }
        val spread = comparable.mapNotNull { g ->
            val v = g.mapNotNull { it.voltageV }
            val mean = v.average()
            if (mean > 0) (v.max() - v.min()) / mean * 100 else null
        }.maxOrNull()
        val mismatch = spread != null && spread > VOLTAGE_SPREAD_PERCENT
        val withPeers = specific.size >= 2
        val confidence = when {
            abnormal.isEmpty() && !mismatch -> if (withPeers) 0.8 else 0.5
            withPeers -> (0.55 + 0.1 * statuses.count { it.flag == MpptFlag.NORMAL }).coerceAtMost(0.85)
            else -> 0.4
        }
        val note = when {
            abnormal.isNotEmpty() -> "Nieprawidłowe: ${statuses.filter { it.index in abnormal }.joinToString { it.label }} – możliwe przyczyny poniżej (bez wskazywania konkretnego modułu)"
            mismatch -> "Różne napięcia MPP przy tej samej liczbie modułów (${"%.0f".format(spread)}%)"
            !withPeers -> "Jeden MPPT – porównanie tylko z modelem (mniejsza pewność)"
            else -> "Wszystkie MPPT pracują podobnie"
        }
        return MpptAnalysis(true, statuses, abnormal, spread, mismatch, if (abnormal.isNotEmpty() || mismatch) CAUSES else emptyList(), confidence, note)
    }

    /** Expected DC power split by configured Wp when all MPPTs share one orientation. */
    fun splitByPeak(totalExpectedW: Double, configs: List<MpptConfig>): Map<Int, Double> {
        val total = configs.sumOf { it.peakW }
        return if (total <= 0) emptyMap() else configs.associate { it.index to totalExpectedW * it.peakW / total }
    }
}
