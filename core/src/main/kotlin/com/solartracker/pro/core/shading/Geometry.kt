package com.solartracker.pro.core.shading

import com.solartracker.pro.core.solar.GeoLocation
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sqrt

/** A WGS84 point. */
data class LatLon(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { "latitude out of range" }
        require(lon.isFinite() && lon in -180.0..180.0) { "longitude out of range" }
    }
}

/** Local east/north metres around an origin (equirectangular – accurate to < 0.1% within a few km). */
data class Local(val east: Double, val north: Double) {
    val distance: Double get() = hypot(east, north)

    /** Compass azimuth from the origin, 0 = north, 90 = east. */
    val azimuthDeg: Double get() = (Math.toDegrees(atan2(east, north)) + 360.0) % 360.0
}

class LocalFrame(val origin: LatLon) {
    private val mPerDegLat = 111_132.954 - 559.822 * cos(2 * Math.toRadians(origin.lat)) + 1.175 * cos(4 * Math.toRadians(origin.lat))
    private val mPerDegLon = 111_412.84 * cos(Math.toRadians(origin.lat)) - 93.5 * cos(3 * Math.toRadians(origin.lat))

    fun toLocal(p: LatLon) = Local((p.lon - origin.lon) * mPerDegLon, (p.lat - origin.lat) * mPerDegLat)
    fun toLatLon(l: Local) = LatLon(origin.lat + l.north / mPerDegLat, origin.lon + l.east / mPerDegLon)

    companion object {
        fun of(location: GeoLocation) = LocalFrame(LatLon(location.latitude, location.longitude))
    }
}

/** Unit vector pointing to the sun (east, north, up). */
data class SunVector(val east: Double, val north: Double, val up: Double) {
    companion object {
        fun of(azimuthDeg: Double, elevationDeg: Double): SunVector {
            val az = Math.toRadians(azimuthDeg)
            val el = Math.toRadians(elevationDeg)
            return SunVector(kotlin.math.sin(az) * cos(el), cos(az) * cos(el), kotlin.math.sin(el))
        }
    }
}

internal object Geo2 {
    /**
     * Parameter intervals t ≥ 0 where the ray p + t·d (2D, d normalized) is inside the polygon.
     * Uses even-odd crossings; returns sorted (entry, exit) pairs.
     */
    fun rayPolygonIntervals(px: Double, py: Double, dx: Double, dy: Double, poly: List<Local>): List<Pair<Double, Double>> {
        if (poly.size < 3) return emptyList()
        val ts = mutableListOf<Double>()
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            val ex = b.east - a.east
            val ey = b.north - a.north
            val den = dx * ey - dy * ex
            if (kotlin.math.abs(den) < 1e-12) continue
            val wx = a.east - px
            val wy = a.north - py
            val t = (wx * ey - wy * ex) / den
            val u = (wx * dy - wy * dx) / den
            if (u >= 0.0 && u < 1.0 && t >= 0.0) ts += t
        }
        ts.sort()
        val inside0 = contains(px, py, poly)
        val bounds = if (inside0) listOf(0.0) + ts else ts
        return bounds.chunked(2).filter { it.size == 2 }.map { it[0] to it[1] }
    }

    fun contains(x: Double, y: Double, poly: List<Local>): Boolean {
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[j]
            if ((a.north > y) != (b.north > y) && x < (b.east - a.east) * (y - a.north) / (b.north - a.north) + a.east) inside = !inside
            j = i
        }
        return inside
    }

    /** Interval of t where the ray passes within [radius] of the circle centre c. */
    fun rayCircleInterval(px: Double, py: Double, dx: Double, dy: Double, c: Local, radius: Double): Pair<Double, Double>? {
        val fx = px - c.east
        val fy = py - c.north
        val b = fx * dx + fy * dy
        val cc = fx * fx + fy * fy - radius * radius
        val disc = b * b - cc
        if (disc < 0) return null
        val s = sqrt(disc)
        val t1 = -b - s
        val t2 = -b + s
        if (t2 < 0) return null
        return t1.coerceAtLeast(0.0) to t2
    }

    /** Thin rectangle (width [w]) around a polyline segment, for fences and walls. */
    fun segmentPolygon(a: Local, b: Local, w: Double): List<Local> {
        val dx = b.east - a.east
        val dy = b.north - a.north
        val len = hypot(dx, dy).coerceAtLeast(1e-6)
        val nx = -dy / len * w / 2
        val ny = dx / len * w / 2
        return listOf(Local(a.east + nx, a.north + ny), Local(b.east + nx, b.north + ny), Local(b.east - nx, b.north - ny), Local(a.east - nx, a.north - ny))
    }
}
