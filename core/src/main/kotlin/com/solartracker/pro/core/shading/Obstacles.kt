package com.solartracker.pro.core.shading

import com.solartracker.pro.core.quality.DataKind
import java.time.Instant
import java.time.Month

/** Where an obstacle height comes from – shown next to every value. */
enum class HeightSource(val label: String, val kind: DataKind, val weight: Double) {
    /** Measured on site by the user (laser, tape) and confirmed. */
    USER_CONFIRMED("Potwierdzona przez użytkownika", DataKind.MEASURED, 1.0),
    /** LiDAR/DSM survey data. */
    SURVEY("Dane pomiarowe (LiDAR/DSM)", DataKind.MEASURED, 0.95),
    /** Explicit height tag in map data (e.g. OSM height=). */
    MAP_TAG("Wysokość z danych mapowych", DataKind.CALCULATED, 0.8),
    /** Estimated from number of storeys × storey height. */
    FROM_LEVELS("Szacunek z liczby kondygnacji", DataKind.ESTIMATED, 0.55),
    /** Estimated by the user (no measurement). */
    USER_ESTIMATED("Szacunek użytkownika", DataKind.ESTIMATED, 0.5),
    /** No data – not used in calculations until the user provides a value. */
    UNKNOWN("Nieznana", DataKind.UNKNOWN, 0.0),
}

/** Height above the ground at the obstacle, with source and ± uncertainty. */
data class HeightValue(
    val meters: Double?,
    val source: HeightSource,
    val uncertaintyM: Double? = null,
    val note: String? = null,
) {
    init {
        require(meters == null || (meters.isFinite() && meters in 0.0..1000.0)) { "height out of range" }
        require((meters == null) == (source == HeightSource.UNKNOWN)) { "UNKNOWN height has no value" }
    }

    val known: Boolean get() = meters != null

    companion object {
        val UNKNOWN = HeightValue(null, HeightSource.UNKNOWN, note = "Brak danych o wysokości – podaj ręcznie")
        const val DEFAULT_STOREY_M = 3.0

        /** Storeys × storey height + roof; uncertainty grows with the number of storeys. */
        fun fromLevels(levels: Double, storeyHeightM: Double = DEFAULT_STOREY_M, roofM: Double = 0.0): HeightValue {
            require(levels > 0 && levels < 200)
            val h = levels * storeyHeightM + roofM
            return HeightValue(h, HeightSource.FROM_LEVELS, uncertaintyM = 1.0 + 0.5 * levels,
                note = "${fmt(levels)} kond. × ${fmt(storeyHeightM)} m${if (roofM > 0) " + dach ${fmt(roofM)} m" else ""}")
        }

        private fun fmt(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)
    }
}

enum class ObstacleType(val label: String, val defaultRadiusM: Double) {
    BUILDING("Budynek", 0.0),
    CHIMNEY("Komin", 0.4),
    TREE("Drzewo", 3.0),
    MAST("Maszt", 0.3),
    POLE("Słup", 0.15),
    FENCE("Ogrodzenie / mur", 0.0),
    OTHER("Inna przeszkoda", 0.5),
}

sealed interface ObstacleShape {
    data class Point(val at: LatLon, val radiusM: Double) : ObstacleShape
    data class Line(val points: List<LatLon>, val widthM: Double = 0.2) : ObstacleShape
    data class Polygon(val outline: List<LatLon>) : ObstacleShape

    /** Manual entry without map geometry: distance + azimuth range as seen from the panels. */
    data class Bearing(val distanceM: Double, val azimuthFromDeg: Double, val azimuthToDeg: Double) : ObstacleShape
}

/** Seasonal behaviour of tree crowns: light passing through the crown. */
data class Foliage(
    val leafMonths: Set<Month> = setOf(Month.MAY, Month.JUNE, Month.JULY, Month.AUGUST, Month.SEPTEMBER, Month.OCTOBER),
    /** 0 = opaque, 1 = transparent. */
    val transmittanceLeafOn: Double = 0.2,
    val transmittanceLeafOff: Double = 0.7,
    /** Height where the crown starts (trunk below lets light through). */
    val crownBaseM: Double? = null,
) {
    fun transmittance(month: Month) = if (month in leafMonths) transmittanceLeafOn else transmittanceLeafOff
}

/** A record in the change history of user-edited obstacles. */
data class ObstacleChange(val at: Instant, val description: String)

data class Obstacle(
    val id: String,
    val type: ObstacleType,
    val shape: ObstacleShape,
    val height: HeightValue,
    /** Terrain elevation at the obstacle's base [m ASL]; null = same as the installation. */
    val baseElevationM: Double? = null,
    val name: String? = null,
    val foliage: Foliage? = null,
    val enabled: Boolean = true,
    /** "OpenStreetMap", "Użytkownik", ... */
    val source: String,
    val sourceUpdated: Instant? = null,
    val userDefined: Boolean = false,
    /** Automatic obstacle this one replaces (user correction keeps the original's history). */
    val overridesId: String? = null,
    val history: List<ObstacleChange> = emptyList(),
) {
    val usable: Boolean get() = enabled && height.known
}

/**
 * Merges automatic and user obstacles: user data wins over the automatic entry it overrides,
 * disabled entries are kept (for "before/after" comparison) but not used.
 */
object ObstacleMerger {
    fun merge(automatic: List<Obstacle>, user: List<Obstacle>): List<Obstacle> {
        val overridden = user.mapNotNull { it.overridesId }.toSet()
        return automatic.filter { it.id !in overridden } + user
    }
}
