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
        LossCause.TEMPERATURE -> tr(label, "Temperature")
        LossCause.SHADING -> tr(label, "Shading")
        LossCause.SOILING -> tr(label, "Soiling")
        LossCause.MISMATCH -> label
        LossCause.SNOW -> tr(label, "Snow")
        LossCause.DEGRADATION -> tr(label, "Degradation")
        LossCause.WIRING -> tr(label, "Wiring")
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
