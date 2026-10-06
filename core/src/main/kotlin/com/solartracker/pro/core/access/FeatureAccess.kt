package com.solartracker.pro.core.access

/**
 * Feature gating, kept apart from UI: screens ask [FeatureAccessManager.isEnabled] and never check plans
 * themselves. There is no payment integration in this build; the plan comes from [SubscriptionState].
 */
enum class Plan { FREE, PRO }

enum class Feature(val label: String, val minPlan: Plan) {
    LIVE_SOLAR("Słońce live", Plan.FREE),
    BASIC_FORECAST("Prognoza PV", Plan.FREE),
    INVERTER_MONITORING("Monitoring falownika", Plan.FREE),
    SHADING_ANALYSIS("Analiza zacienienia", Plan.PRO),
    EMS("Optymalizacja energii (EMS)", Plan.PRO),
    HEALTH("Zdrowie instalacji i predykcja usterek", Plan.PRO),
    DESIGNER("Projektant PV", Plan.PRO),
    ECONOMICS("Ekonomia i ROI", Plan.PRO),
    LOCATION_COMPARISON("Porównanie lokalizacji", Plan.PRO),
    VEHICLE("Tryb pojazdu", Plan.PRO),
    TILT_OPTIMIZER("Optymalizator kąta paneli", Plan.PRO),
    EXPORT("Eksport CSV/JSON/PDF", Plan.PRO),
}

enum class SubscriptionSource(val label: String) {
    /** No store billing in this build: everything is unlocked and stated as such. */
    DEVELOPMENT_BUILD("Wersja bez sklepu — wszystkie funkcje odblokowane"),
    LOCAL_FREE("Plan FREE ustawiony lokalnie"),
    STORE_VERIFIED("Subskrypcja zweryfikowana przez sklep"),
}

data class SubscriptionState(val plan: Plan, val source: SubscriptionSource) {
    companion object {
        val DEFAULT = SubscriptionState(Plan.PRO, SubscriptionSource.DEVELOPMENT_BUILD)
    }
}

class FeatureAccessManager(private val state: () -> SubscriptionState) {
    fun isEnabled(feature: Feature): Boolean = state().plan.ordinal >= feature.minPlan.ordinal

    fun locked(): List<Feature> = Feature.entries.filterNot { isEnabled(it) }

    fun reason(feature: Feature): String? =
        if (isEnabled(feature)) null else "${feature.label} wymaga planu ${feature.minPlan.name}"
}
