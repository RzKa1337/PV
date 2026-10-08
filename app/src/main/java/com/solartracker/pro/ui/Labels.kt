package com.solartracker.pro.ui

import com.solartracker.pro.core.access.SubscriptionSource
import com.solartracker.pro.core.forecast.EnergyRisk
import com.solartracker.pro.core.health.LossCause
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.shading.LocationAccuracy
import com.solartracker.pro.core.twin.TwinElement
import com.solartracker.pro.core.twin.TwinHealth
import com.solartracker.pro.core.weather.UvLevel
import com.solartracker.pro.i18n.tr

/*
 * UI names of `:core` enums in the app language. `:core` keeps its Polish labels (they are also used in
 * reports and tests); English versions live here so the engines stay platform independent.
 */

fun dataKindLabel(kind: DataKind): String = when (kind) {
    DataKind.MEASURED -> tr(kind.label, "MEASURED")
    DataKind.CALCULATED -> tr(kind.label, "CALCULATED")
    DataKind.ESTIMATED -> tr(kind.label, "ESTIMATE")
    DataKind.FORECAST -> tr(kind.label, "FORECAST")
    DataKind.SIMULATED -> tr(kind.label, "SIMULATED")
    DataKind.STALE -> tr(kind.label, "STALE")
    DataKind.LAST_KNOWN -> tr(kind.label, "LAST KNOWN")
    DataKind.INVALID -> tr(kind.label, "INVALID")
    DataKind.UNAVAILABLE -> kind.label
    DataKind.UNKNOWN -> tr(kind.label, "UNKNOWN")
}

val DataKind.uiLabel: String get() = dataKindLabel(this)

val EnergyRisk.uiLabel: String
    get() = when (this) {
        EnergyRisk.LOW -> tr(label, "LOW")
        EnergyRisk.MEDIUM -> tr(label, "MEDIUM")
        EnergyRisk.HIGH -> tr(label, "HIGH")
        EnergyRisk.UNKNOWN -> tr(label, "UNKNOWN")
    }

val TwinElement.uiLabel: String
    get() = when (this) {
        TwinElement.SUN -> tr(label, "Sun")
        TwinElement.PV_ARRAY -> tr(label, "PV panels")
        TwinElement.INVERTER -> tr(label, "Inverter")
        TwinElement.BATTERY -> tr(label, "Battery")
        TwinElement.LOADS -> tr(label, "Loads")
    }

val TwinHealth.uiLabel: String
    get() = when (this) {
        TwinHealth.OK -> label
        TwinHealth.WARNING -> tr(label, "WARNING")
        TwinHealth.FAULT -> tr(label, "PROBLEM")
        TwinHealth.UNKNOWN -> tr(label, "UNKNOWN")
    }

val UvLevel.uiLabel: String
    get() = when (this) {
        UvLevel.LOW -> tr(label, "low")
        UvLevel.MODERATE -> tr(label, "moderate")
        UvLevel.HIGH -> tr(label, "high")
        UvLevel.VERY_HIGH -> tr(label, "very high")
        UvLevel.EXTREME -> tr(label, "extreme")
    }

val UvLevel.uiAdvice: String
    get() = when (this) {
        UvLevel.LOW -> tr(advice, "Protection usually not needed")
        UvLevel.MODERATE -> tr(advice, "Sunglasses and sunscreen for longer time in the sun")
        UvLevel.HIGH -> tr(advice, "SPF 30+ sunscreen, a hat, seek shade around noon")
        UvLevel.VERY_HIGH -> tr(advice, "Avoid the sun 11–16, SPF 50 sunscreen, protective clothing")
        UvLevel.EXTREME -> tr(advice, "Avoid being in the sun, full protection")
    }

val LocationAccuracy.uiLabel: String
    get() = when (this) {
        LocationAccuracy.MAP_POINT -> tr(label, "Point picked on the map")
        LocationAccuracy.GPS -> label
        LocationAccuracy.ADDRESS -> tr(label, "Address (geocoding)")
        LocationAccuracy.POSTCODE -> tr(label, "Postcode (approximate)")
        LocationAccuracy.CITY -> tr(label, "City (approximate)")
    }

val SubscriptionSource.uiLabel: String
    get() = when (this) {
        SubscriptionSource.DEVELOPMENT_BUILD -> tr(label, "Build without a store — all features unlocked")
        SubscriptionSource.LOCAL_FREE -> tr(label, "FREE plan set locally")
        SubscriptionSource.STORE_VERIFIED -> tr(label, "Subscription verified by the store")
    }

val LossCause.uiLabel: String
    get() = when (this) {
        LossCause.AOI -> tr(label, "Angle of incidence (AOI)")
        LossCause.TEMPERATURE -> tr(label, "Temperature")
        LossCause.SHADING -> tr(label, "Shading")
        LossCause.SOILING -> tr(label, "Soiling")
        LossCause.MISMATCH -> label
        LossCause.SNOW -> tr(label, "Snow")
        LossCause.DEGRADATION -> tr(label, "Degradation")
        LossCause.WIRING -> tr(label, "Wiring")
        LossCause.MPPT -> label
        LossCause.INVERTER -> tr(label, "Inverter (efficiency)")
        LossCause.CLIPPING -> tr(label, "Power limiting (clipping)")
        LossCause.UNKNOWN -> tr(label, "Unknown")
    }

val com.solartracker.pro.core.analytics.HistoryPeriod.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.analytics.HistoryPeriod.DAY -> tr(label, "Day")
        com.solartracker.pro.core.analytics.HistoryPeriod.WEEK -> tr(label, "Week")
        com.solartracker.pro.core.analytics.HistoryPeriod.MONTH -> tr(label, "Month")
        com.solartracker.pro.core.analytics.HistoryPeriod.YEAR -> tr(label, "Year")
        com.solartracker.pro.core.analytics.HistoryPeriod.LIFETIME -> tr(label, "Lifetime")
    }

val com.solartracker.pro.core.analytics.SkyCondition.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.analytics.SkyCondition.CLEAR -> tr(label, "clear")
        com.solartracker.pro.core.analytics.SkyCondition.PARTLY_CLOUDY -> tr(label, "partly cloudy")
        com.solartracker.pro.core.analytics.SkyCondition.OVERCAST -> tr(label, "overcast")
        com.solartracker.pro.core.analytics.SkyCondition.RAIN -> tr(label, "rain")
        com.solartracker.pro.core.analytics.SkyCondition.SNOW -> tr(label, "snow")
        com.solartracker.pro.core.analytics.SkyCondition.UNKNOWN -> tr(label, "unknown")
    }

val com.solartracker.pro.core.analytics.AccuracyPeriod.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.analytics.AccuracyPeriod.DAY -> tr(label, "day")
        com.solartracker.pro.core.analytics.AccuracyPeriod.WEEK -> tr(label, "week")
        com.solartracker.pro.core.analytics.AccuracyPeriod.MONTH -> tr(label, "month")
    }

val com.solartracker.pro.core.diagnostics.DiagnosisType.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.diagnostics.DiagnosisType.NORMAL -> tr(label, "Installation works normally")
        com.solartracker.pro.core.diagnostics.DiagnosisType.CURTAILMENT_BATTERY_FULL -> tr(label, "Output limited – battery full")
        com.solartracker.pro.core.diagnostics.DiagnosisType.SOILING_SUSPECTED -> tr(label, "Soiling suspected")
        com.solartracker.pro.core.diagnostics.DiagnosisType.SNOW_SUSPECTED -> tr(label, "Snow on panels suspected")
        com.solartracker.pro.core.diagnostics.DiagnosisType.SHADING_SUSPECTED -> tr(label, "Unmodelled shading suspected")
        com.solartracker.pro.core.diagnostics.DiagnosisType.TEMPERATURE_LOSS -> tr(label, "High temperature loss")
        com.solartracker.pro.core.diagnostics.DiagnosisType.MPPT_ANOMALY -> tr(label, "MPPT anomaly")
        com.solartracker.pro.core.diagnostics.DiagnosisType.INVERTER_EFFICIENCY_ANOMALY -> tr(label, "Low conversion efficiency")
        com.solartracker.pro.core.diagnostics.DiagnosisType.STRING_MISMATCH -> tr(label, "String mismatch")
        com.solartracker.pro.core.diagnostics.DiagnosisType.SENSOR_ANOMALY -> tr(label, "Reading / sensor anomaly")
        com.solartracker.pro.core.diagnostics.DiagnosisType.COMMUNICATION_PROBLEM -> tr(label, "Communication problem")
        com.solartracker.pro.core.diagnostics.DiagnosisType.PV_DEGRADATION -> tr(label, "Module degradation")
        com.solartracker.pro.core.diagnostics.DiagnosisType.UNEXPECTED_LOW_OUTPUT -> tr(label, "Unexpectedly low output")
        com.solartracker.pro.core.diagnostics.DiagnosisType.UNEXPECTED_HIGH_OUTPUT -> tr(label, "Unexpectedly high output")
    }

val com.solartracker.pro.core.diagnostics.LossStep.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.diagnostics.LossStep.AOI -> tr(label, "Angle of incidence (AOI)")
        com.solartracker.pro.core.diagnostics.LossStep.TEMPERATURE -> tr(label, "Temperature")
        com.solartracker.pro.core.diagnostics.LossStep.SPECTRAL -> tr(label, "Spectrum")
        com.solartracker.pro.core.diagnostics.LossStep.SNOW -> tr(label, "Snow")
        com.solartracker.pro.core.diagnostics.LossStep.SOILING -> tr(label, "Soiling")
        com.solartracker.pro.core.diagnostics.LossStep.SHADING -> tr(label, "Shading")
        com.solartracker.pro.core.diagnostics.LossStep.MISMATCH -> label
        com.solartracker.pro.core.diagnostics.LossStep.DEGRADATION -> tr(label, "Degradation")
        com.solartracker.pro.core.diagnostics.LossStep.DC_WIRING -> tr(label, "DC wiring")
        com.solartracker.pro.core.diagnostics.LossStep.MPPT -> label
        com.solartracker.pro.core.diagnostics.LossStep.INVERTER -> tr(label, "Inverter")
        com.solartracker.pro.core.diagnostics.LossStep.CLIPPING -> label
        com.solartracker.pro.core.diagnostics.LossStep.AC_WIRING -> tr(label, "AC wiring")
        com.solartracker.pro.core.diagnostics.LossStep.CALIBRATION -> tr(label, "Calibration correction")
        com.solartracker.pro.core.diagnostics.LossStep.UNEXPLAINED -> tr(label, "Unexplained")
    }

val com.solartracker.pro.core.diagnostics.PvHealthStatus.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.diagnostics.PvHealthStatus.OK -> label
        com.solartracker.pro.core.diagnostics.PvHealthStatus.ATTENTION -> tr(label, "ATTENTION")
        com.solartracker.pro.core.diagnostics.PvHealthStatus.PROBLEM -> label
        com.solartracker.pro.core.diagnostics.PvHealthStatus.UNKNOWN -> tr(label, "NOT ASSESSED")
    }

val com.solartracker.pro.core.diagnostics.RealityStatus.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.diagnostics.RealityStatus.OK -> tr(label, "Output matches the expectation")
        com.solartracker.pro.core.diagnostics.RealityStatus.BELOW -> tr(label, "Output below the expectation")
        com.solartracker.pro.core.diagnostics.RealityStatus.ABOVE -> tr(label, "Output above the expectation")
        com.solartracker.pro.core.diagnostics.RealityStatus.NO_MEASUREMENT -> tr(label, "No current measurement")
        com.solartracker.pro.core.diagnostics.RealityStatus.LOW_LIGHT -> tr(label, "Too little light to judge")
        com.solartracker.pro.core.diagnostics.RealityStatus.NIGHT -> tr(label, "Night")
    }

val com.solartracker.pro.core.diagnostics.IrradianceBasis.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.diagnostics.IrradianceBasis.SENSOR -> tr(label, "irradiance sensor")
        com.solartracker.pro.core.diagnostics.IrradianceBasis.FORECAST -> tr(label, "weather forecast (Open-Meteo)")
        com.solartracker.pro.core.diagnostics.IrradianceBasis.CLIMATE -> tr(label, "climate averages")
        com.solartracker.pro.core.diagnostics.IrradianceBasis.CLEAR_SKY -> tr(label, "clear-sky model")
    }

val com.solartracker.pro.core.forecast.MissionGoal.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.forecast.MissionGoal.SURVIVE_NIGHT -> tr(label, "Survive the night")
        com.solartracker.pro.core.forecast.MissionGoal.MAXIMIZE_SELF_CONSUMPTION -> tr(label, "Max self-consumption")
        com.solartracker.pro.core.forecast.MissionGoal.PROTECT_BATTERY -> tr(label, "Protect battery")
        com.solartracker.pro.core.forecast.MissionGoal.RUN_COLD_ROOM -> tr(label, "Keep the cold room")
        com.solartracker.pro.core.forecast.MissionGoal.MINIMIZE_GENERATOR -> tr(label, "Min generator")
        com.solartracker.pro.core.forecast.MissionGoal.MINIMIZE_GRID_COST -> tr(label, "Min grid cost")
    }

val com.solartracker.pro.core.inverter.RegisterQuality.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.inverter.RegisterQuality.VERIFIED -> tr(label, "verified")
        com.solartracker.pro.core.inverter.RegisterQuality.UNVERIFIED -> tr(label, "unverified")
        com.solartracker.pro.core.inverter.RegisterQuality.SUSPECTED -> tr(label, "suspected")
        com.solartracker.pro.core.inverter.RegisterQuality.INVALID -> tr(label, "invalid")
        com.solartracker.pro.core.inverter.RegisterQuality.NOT_AVAILABLE -> tr(label, "not available")
    }

val com.solartracker.pro.core.diagnostics.SoilingState.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.diagnostics.SoilingState.INSUFFICIENT_DATA -> tr(label, "Not enough clear days to judge")
        com.solartracker.pro.core.diagnostics.SoilingState.NONE -> tr(label, "No signs of soiling")
        com.solartracker.pro.core.diagnostics.SoilingState.SUSPECTED -> tr(label, "Soiling suspected")
        com.solartracker.pro.core.diagnostics.SoilingState.CONFIRMED -> tr(label, "Soiling confirmed (recovery after rain)")
    }

val com.solartracker.pro.core.diagnostics.DegradationTrend.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.diagnostics.DegradationTrend.INSUFFICIENT_DATA -> tr(label, "History too short")
        com.solartracker.pro.core.diagnostics.DegradationTrend.STABLE -> tr(label, "Stable")
        com.solartracker.pro.core.diagnostics.DegradationTrend.NORMAL -> tr(label, "Typical degradation")
        com.solartracker.pro.core.diagnostics.DegradationTrend.ELEVATED -> tr(label, "Elevated degradation")
    }

val com.solartracker.pro.core.diagnostics.MpptFlag.uiLabel: String
    get() = when (this) {
        com.solartracker.pro.core.diagnostics.MpptFlag.NORMAL -> label
        com.solartracker.pro.core.diagnostics.MpptFlag.ABNORMAL -> tr(label, "Abnormal")
        com.solartracker.pro.core.diagnostics.MpptFlag.NO_DATA -> tr(label, "No data")
        com.solartracker.pro.core.diagnostics.MpptFlag.LOW_LIGHT -> tr(label, "Too little light")
    }
