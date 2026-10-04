package com.solartracker.pro.data

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
) {
    companion object {
        val DEFAULT_LOCATION = GeoLocation(52.2297, 21.0122)
        const val DEFAULT_LOCATION_NAME = "Warszawa"
    }
}
