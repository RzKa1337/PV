package com.solartracker.pro.core.analytics

import kotlin.math.abs

/** Context needed to explain a difference between modelled and measured PV power. */
data class ComparisonContext(
    val sunElevationDeg: Double,
    /** Expected power without shading [kW] and the computed shading loss [kW]. */
    val modelUnshadedKw: Double,
    val shadingLossKw: Double = 0.0,
    val cloudCoverPercent: Double? = null,
    val cellTemperatureC: Double? = null,
    val inverterRatedKw: Double? = null,
    /** PV charge limit of the inverter's MPPT charger [kW], if known. */
    val mpptLimitKw: Double? = null,
    val batterySocPercent: Double? = null,
    val batteryMaxSocPercent: Double? = null,
    val gridExportAllowed: Boolean? = null,
    val loadKw: Double? = null,
    val batteryChargeKw: Double? = null,
    val calibrationFactor: Double? = null,
)

enum class DeviationCause(val label: String) {
    CLOUDS("zachmurzenie"),
    TEMPERATURE("wysoka temperatura paneli"),
    SHADING("zacienienie (obliczone)"),
    ORIENTATION("orientacja / niska wysokość Słońca"),
    SOILING("zabrudzenie lub degradacja paneli"),
    CLIPPING("clipping – limit mocy falownika"),
    MPPT_LIMIT("ograniczenie MPPT/ładowarki"),
    BATTERY_FULL("bateria pełna – brak odbioru energii"),
    LOAD_LIMITED("produkcja ograniczona przez odbiory (praca wyspowa)"),
    MODEL_UNDERESTIMATES("model zaniża – możliwe odbicia/chłodne panele lub zbyt niska moc w ustawieniach"),
}

data class LikelyCause(val cause: DeviationCause, val explanation: String, val weight: Double)

data class ModelComparisonResult(
    val modelKw: Double,
    val realKw: Double,
    /** (real − model) / model in %, null when the model expects ~0 (night). */
    val differencePercent: Double?,
    val causes: List<LikelyCause>,
    /** True when the real production is limited by the system, not by the sun (excluded from calibration). */
    val curtailed: Boolean,
)

/** Compares the PV model (incl. shading) with the measured Anenji PV power and explains the gap. */
object ModelComparison {

    fun compare(modelKw: Double, realKw: Double, ctx: ComparisonContext): ModelComparisonResult {
        val diff = if (modelKw >= MIN_MODEL_KW) (realKw - modelKw) / modelKw * 100.0 else null
        val causes = mutableListOf<LikelyCause>()
        val nearCap = { limit: Double? -> limit != null && realKw >= limit * 0.97 && modelKw > limit }

        val clipping = nearCap(ctx.inverterRatedKw)
        if (clipping) causes += LikelyCause(DeviationCause.CLIPPING, "PV ≈ ${fmt(realKw)} kW przy limicie ${fmt(ctx.inverterRatedKw!!)} kW", 1.0)
        val mppt = nearCap(ctx.mpptLimitKw)
        if (mppt) causes += LikelyCause(DeviationCause.MPPT_LIMIT, "PV ≈ limitowi MPPT ${fmt(ctx.mpptLimitKw!!)} kW", 0.9)

        val full = ctx.batterySocPercent != null && ctx.batteryMaxSocPercent != null &&
            ctx.batterySocPercent >= ctx.batteryMaxSocPercent - 1.0 && (ctx.batteryChargeKw ?: 0.0) < 0.1
        val loadLimited = ctx.gridExportAllowed == false && ctx.loadKw != null &&
            abs(realKw - (ctx.loadKw + (ctx.batteryChargeKw ?: 0.0))) < 0.15 && realKw < modelKw * 0.9
        if (full && ctx.gridExportAllowed != true) causes += LikelyCause(DeviationCause.BATTERY_FULL, "SOC ${fmt(ctx.batterySocPercent!!)}% i brak ładowania – falownik ogranicza PV", 0.95)
        else if (loadLimited) causes += LikelyCause(DeviationCause.LOAD_LIMITED, "PV ≈ zużycie + ładowanie (${fmt(ctx.loadKw!! + (ctx.batteryChargeKw ?: 0.0))} kW)", 0.9)
        val curtailed = clipping || mppt || (full && ctx.gridExportAllowed != true) || loadLimited

        if (diff != null && diff < -5.0 && !curtailed) {
            if (ctx.shadingLossKw > 0.05) {
                causes += LikelyCause(DeviationCause.SHADING, "obliczone zacienienie −${fmt(ctx.shadingLossKw)} kW (uwzględnione w modelu)", 0.6)
            }
            val clouds = ctx.cloudCoverPercent
            if (clouds != null && clouds >= 30) causes += LikelyCause(DeviationCause.CLOUDS, "zachmurzenie ${clouds.toInt()}%", (clouds / 100.0).coerceIn(0.3, 0.9))
            val cell = ctx.cellTemperatureC
            if (cell != null && cell > 45) causes += LikelyCause(DeviationCause.TEMPERATURE, "temperatura ogniw ≈ ${cell.toInt()}°C (−${((cell - 25) * 0.4).toInt()}%)", 0.4)
            if (ctx.sunElevationDeg < 15) causes += LikelyCause(DeviationCause.ORIENTATION, "Słońce nisko (${ctx.sunElevationDeg.toInt()}°) – duży wpływ geometrii i odbić", 0.4)
            if ((clouds == null || clouds < 30) && diff < -15) {
                causes += LikelyCause(DeviationCause.SOILING, "przy czystym niebie produkcja niższa o ${(-diff).toInt()}% – sprawdź zabrudzenie/przeszkody", 0.5)
            }
        }
        if (diff != null && diff > 10.0) causes += LikelyCause(DeviationCause.MODEL_UNDERESTIMATES, "pomiar wyższy o ${diff.toInt()}%", 0.5)
        return ModelComparisonResult(modelKw, realKw, diff, causes.sortedByDescending { it.weight }, curtailed)
    }

    const val MIN_MODEL_KW = 0.05
    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)
}
