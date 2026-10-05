package com.solartracker.pro.data

import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.ConsumptionPeriod
import com.solartracker.pro.core.energy.ConsumptionProfile
import com.solartracker.pro.core.energy.EnergyPrices
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.solar.GeoLocation

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LocationSource { MANUAL, GPS }

/** Everything the user can configure, persisted locally in DataStore. */
data class AppSettings(
    val system: PvSystem = PvSystem(),
    val location: GeoLocation = DEFAULT_LOCATION,
    val locationName: String = DEFAULT_LOCATION_NAME,
    val locationSource: LocationSource = LocationSource.MANUAL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val batteryEnabled: Boolean = false,
    val battery: BatteryStorage = BatteryStorage(),
    val consumption: ConsumptionSettings = ConsumptionSettings(),
    val prices: EnergyPrices = EnergyPrices(),
    /** Use the Open-Meteo forecast and climate data instead of a clear sky. */
    val weatherEnabled: Boolean = true,
) {
    /** The battery used in simulations, or null when the user has none. */
    val activeBattery: BatteryStorage? get() = battery.takeIf { batteryEnabled && it.isValid }

    companion object {
        val DEFAULT_LOCATION = GeoLocation(52.2297, 21.0122, elevationM = 100.0)
        const val DEFAULT_LOCATION_NAME = "Warszawa"
    }
}

enum class ConsumptionMode { CONSTANT, HOURLY }

/** User consumption settings: a constant load or hourly periods. */
data class ConsumptionSettings(
    val mode: ConsumptionMode = ConsumptionMode.CONSTANT,
    val constantKw: Double = DEFAULT_CONSTANT_KW,
    val periods: List<ConsumptionPeriod> = DEFAULT_PERIODS,
) {
    fun validate(): List<String> = when (mode) {
        ConsumptionMode.CONSTANT ->
            if (constantKw.isFinite() && constantKw >= 0.0 && constantKw <= MAX_KW) emptyList()
            else listOf("Zużycie musi wynosić 0–${MAX_KW.toInt()} kW")
        ConsumptionMode.HOURLY -> ConsumptionProfile.validatePeriods(periods) +
            if (periods.any { it.powerKw > MAX_KW }) listOf("Moc przedziału maks. ${MAX_KW.toInt()} kW") else emptyList()
    }

    fun profile(): ConsumptionProfile = when (mode) {
        ConsumptionMode.CONSTANT -> ConsumptionProfile.constant(constantKw)
        ConsumptionMode.HOURLY -> ConsumptionProfile.fromPeriods(periods)
    }

    companion object {
        const val DEFAULT_CONSTANT_KW = 0.25
        const val MAX_KW = 1000.0
        val DEFAULT_PERIODS = listOf(
            ConsumptionPeriod(0, 6, 0.5),
            ConsumptionPeriod(6, 10, 1.0),
            ConsumptionPeriod(10, 18, 2.0),
            ConsumptionPeriod(18, 24, 1.0),
        )
    }
}
