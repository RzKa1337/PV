package com.solartracker.pro.core.shading

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Ground shadow outlines for the map (local metres). */
object ShadowFootprint {
    /** Max drawn shadow length – a sun near the horizon would give kilometre-long shadows. */
    const val MAX_LENGTH_M = 300.0

    /**
     * Shadow of the obstacle on flat ground at the installation level: convex hull of the footprint and
     * the footprint shifted away from the sun by top / tan(elevation). Null when the sun is down or the
     * height is unknown.
     */
    fun of(g: ObstacleGeometry, sunAzimuthDeg: Double, sunElevationDeg: Double): List<Local>? {
        if (sunElevationDeg <= 0.5 || !g.usable) return null
        val top = g.local.topM
        if (top <= 0) return null
        val length = (top / tan(Math.toRadians(sunElevationDeg))).coerceAtMost(MAX_LENGTH_M)
        val dx = -sin(Math.toRadians(sunAzimuthDeg)) * length
        val dy = -cos(Math.toRadians(sunAzimuthDeg)) * length
        val base: List<Local> = when (val s = g.local) {
            is LocalShape.Prism -> s.footprint
            is LocalShape.Cylinder -> (0 until 12).map { i ->
                val a = 2 * Math.PI * i / 12
                Local(s.centre.east + s.radiusM * sin(a), s.centre.north + s.radiusM * cos(a))
            }
        }
        val shifted = base.map { Local(it.east + dx, it.north + dy) }
        return ObstacleGeometryService.convexHull(base + shifted)
    }
}
