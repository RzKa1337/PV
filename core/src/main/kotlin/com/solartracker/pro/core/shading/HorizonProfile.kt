package com.solartracker.pro.core.shading

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max

/**
 * 360° horizon: for every whole-degree azimuth the elevation angle [°] of the highest obstruction
 * (terrain and obstacles) seen from the panels, and which obstacle causes it.
 */
class SolarHorizonProfile(
    val elevationDeg: DoubleArray,
    val causeIds: Array<String?>,
) {
    init {
        require(elevationDeg.size == 360 && causeIds.size == 360)
    }

    fun elevationAt(azimuthDeg: Double): Double {
        val a = ((azimuthDeg % 360.0) + 360.0) % 360.0
        val i = a.toInt() % 360
        val j = (i + 1) % 360
        val f = a - a.toInt()
        return elevationDeg[i] * (1 - f) + elevationDeg[j] * f
    }

    /** True when the sun is hidden behind the horizon profile (centre of the array). */
    fun blocks(azimuthDeg: Double, elevationDeg: Double) = elevationDeg <= elevationAt(azimuthDeg)

    /**
     * Isotropic sky-view factor for a horizontal surface: share of diffuse light reaching the
     * panels (1 = open horizon). Used to reduce diffuse irradiance.
     */
    val skyViewFactor: Double
        get() = elevationDeg.map { cos(Math.toRadians(max(0.0, it))).let { c -> c * c } }.average()

    companion object {
        fun flat() = SolarHorizonProfile(DoubleArray(360), arrayOfNulls(360))

        const val EARTH_RADIUS_M = 6_371_000.0

        /** Apparent elevation [°] of a point [heightDiffM] above the observer at [distanceM] (curvature + refraction). */
        fun elevationAngle(heightDiffM: Double, distanceM: Double): Double {
            if (distanceM <= 0.0) return 90.0
            val curvature = distanceM * distanceM / (2 * EARTH_RADIUS_M) * (1 - 0.13)
            return Math.toDegrees(atan2(heightDiffM - curvature, distanceM))
        }
    }
}

/** Terrain heights around the installation on a polar grid (azimuth × distance). */
data class TerrainSamples(
    val originElevationM: Double,
    val azimuthsDeg: List<Double>,
    val distancesM: List<Double>,
    /** [azimuth index][distance index] → elevation ASL, null where unknown. */
    val elevations: List<List<Double?>>,
    val source: String,
    val resolutionM: Double?,
)

/** Builds a [SolarHorizonProfile] from terrain samples and obstacle geometry. */
object HorizonBuilder {

    fun build(
        terrain: TerrainSamples?,
        obstacles: List<ObstacleGeometry>,
        panelTopElevationM: Double,
    ): SolarHorizonProfile {
        val elev = DoubleArray(360) { 0.0 }
        val cause = arrayOfNulls<String>(360)
        if (terrain != null) {
            terrain.azimuthsDeg.forEachIndexed { ai, az ->
                var best = 0.0
                terrain.distancesM.forEachIndexed { di, d ->
                    val h = terrain.elevations.getOrNull(ai)?.getOrNull(di) ?: return@forEachIndexed
                    best = max(best, SolarHorizonProfile.elevationAngle(h - panelTopElevationM, d))
                }
                // Spread each terrain azimuth over its sector.
                val half = 180.0 / terrain.azimuthsDeg.size
                var a = (az - half).toInt()
                while (a <= az + half) {
                    val idx = ((a % 360) + 360) % 360
                    if (best > elev[idx]) { elev[idx] = best; cause[idx] = TERRAIN_ID }
                    a++
                }
            }
        }
        for (g in obstacles) {
            if (!g.usable) continue
            for (a in g.azimuthFromDeg.toInt()..g.azimuthToDeg.toInt().let { if (it < g.azimuthFromDeg.toInt()) it + 360 else it }) {
                val idx = ((a % 360) + 360) % 360
                if (g.topElevationAngleDeg > elev[idx]) { elev[idx] = g.topElevationAngleDeg; cause[idx] = g.obstacle.id }
            }
        }
        return SolarHorizonProfile(elev, cause)
    }

    const val TERRAIN_ID = "terrain"
}
