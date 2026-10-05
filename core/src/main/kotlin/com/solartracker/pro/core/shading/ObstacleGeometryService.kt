package com.solartracker.pro.core.shading

import kotlin.math.max


/** Geometry of an obstacle as seen from the PV array (one row of the obstacle table). */
data class ObstacleGeometry(
    val obstacle: Obstacle,
    val distanceM: Double,
    /** Azimuth of the nearest point / centre [°]. */
    val azimuthDeg: Double,
    val azimuthFromDeg: Double,
    val azimuthToDeg: Double,
    /** Obstacle top above the panel reference height [m]; null when the height is unknown. */
    val relativeHeightM: Double?,
    /** Elevation angle of the obstacle top above the horizon seen from the panels [°]. */
    val topElevationAngleDeg: Double,
    /** Absolute top elevation [m ASL]. */
    val topElevationM: Double?,
    val local: LocalShape,
) {
    val usable: Boolean get() = obstacle.usable && relativeHeightM != null
}

/** Obstacle footprint in local metres plus vertical extent (ground-relative to the installation). */
sealed interface LocalShape {
    val bottomM: Double
    val topM: Double

    data class Prism(val footprint: List<Local>, override val bottomM: Double, override val topM: Double) : LocalShape
    data class Cylinder(val centre: Local, val radiusM: Double, override val bottomM: Double, override val topM: Double) : LocalShape
}

/**
 * Converts obstacles to local geometry relative to the installation: distance, azimuth, angular
 * extent, relative height (incl. terrain difference) and elevation angle.
 */
class ObstacleGeometryService(
    private val frame: LocalFrame,
    /** Terrain elevation at the installation [m ASL]. */
    private val siteElevationM: Double,
    /** Height of the panels' centre above the ground [m]. */
    private val panelHeightM: Double,
    private val arrayCentre: Local = Local(0.0, 0.0),
) {
    fun geometry(o: Obstacle): ObstacleGeometry {
        val baseOffset = (o.baseElevationM ?: siteElevationM) - siteElevationM
        val heightM = o.height.meters
        val top = heightM?.let { baseOffset + it }
        val bottom = if (o.type == ObstacleType.TREE) baseOffset + (o.foliage?.crownBaseM ?: ((heightM ?: 0.0) * 0.3)) else baseOffset
        val local: LocalShape = when (val s = o.shape) {
            is ObstacleShape.Point -> LocalShape.Cylinder(frame.toLocal(s.at), s.radiusM, bottom, top ?: bottom)
            is ObstacleShape.Polygon -> LocalShape.Prism(s.outline.map(frame::toLocal), bottom, top ?: bottom)
            is ObstacleShape.Line -> {
                val pts = s.points.map(frame::toLocal)
                // A thick polyline approximated by the hull of its segment rectangles.
                LocalShape.Prism(pts.zipWithNext { a, b -> Geo2.segmentPolygon(a, b, s.widthM) }.flatten().let(::convexHull), bottom, top ?: bottom)
            }
            is ObstacleShape.Bearing -> {
                val a1 = Math.toRadians(s.azimuthFromDeg)
                val a2 = Math.toRadians(if (s.azimuthToDeg < s.azimuthFromDeg) s.azimuthToDeg + 360 else s.azimuthToDeg)
                val d = s.distanceM
                val depth = 1.0
                val pts = listOf(a1, a2).flatMap { a -> listOf(d, d + depth).map { r -> Local(arrayCentre.east + r * kotlin.math.sin(a), arrayCentre.north + r * kotlin.math.cos(a)) } }
                LocalShape.Prism(listOf(pts[0], pts[2], pts[3], pts[1]), bottom, top ?: bottom)
            }
        }
        val corners: List<Local> = when (local) {
            is LocalShape.Prism -> local.footprint
            is LocalShape.Cylinder -> (0 until 16).map { i ->
                val a = 2 * Math.PI * i / 16
                Local(local.centre.east + local.radiusM * kotlin.math.sin(a), local.centre.north + local.radiusM * kotlin.math.cos(a))
            }
        }
        val rel = corners.map { Local(it.east - arrayCentre.east, it.north - arrayCentre.north) }
        val azimuths = rel.map { it.azimuthDeg }
        val (from, to) = angularRange(azimuths)
        val nearest = nearestPoint(rel, local is LocalShape.Prism)
        val centre = Local(rel.map { it.east }.average(), rel.map { it.north }.average())
        val relHeight = top?.let { it - panelHeightM }
        return ObstacleGeometry(
            obstacle = o,
            distanceM = nearest.distance,
            azimuthDeg = centre.azimuthDeg,
            azimuthFromDeg = from,
            azimuthToDeg = to,
            relativeHeightM = relHeight,
            topElevationAngleDeg = relHeight?.let { SolarHorizonProfile.elevationAngle(it, max(nearest.distance, 0.1)) } ?: 0.0,
            topElevationM = top?.let { siteElevationM + it },
            local = local,
        )
    }

    companion object {
        /** Smallest azimuth range [from, to] covering all directions (may wrap over north: to < from). */
        fun angularRange(azimuths: List<Double>): Pair<Double, Double> {
            if (azimuths.isEmpty()) return 0.0 to 0.0
            val sorted = azimuths.map { ((it % 360) + 360) % 360 }.sorted()
            var bestGap = -1.0
            var gapEnd = 0
            for (i in sorted.indices) {
                val next = if (i + 1 < sorted.size) sorted[i + 1] else sorted[0] + 360
                val gap = next - sorted[i]
                if (gap > bestGap) { bestGap = gap; gapEnd = (i + 1) % sorted.size }
            }
            val from = sorted[gapEnd]
            val to = sorted[(gapEnd - 1 + sorted.size) % sorted.size]
            return from to to
        }

        /** Nearest point of the outline (edges for polygons) to the array centre at the origin. */
        fun nearestPoint(outline: List<Local>, polygon: Boolean): Local {
            if (outline.isEmpty()) return Local(0.0, 0.0)
            if (polygon && outline.size >= 3 && Geo2.contains(0.0, 0.0, outline)) return Local(0.0, 0.0)
            var best = outline.minBy { it.distance }
            if (polygon) for (i in outline.indices) {
                val a = outline[i]
                val b = outline[(i + 1) % outline.size]
                val dx = b.east - a.east
                val dy = b.north - a.north
                val len2 = dx * dx + dy * dy
                if (len2 == 0.0) continue
                val t = (-(a.east * dx + a.north * dy) / len2).coerceIn(0.0, 1.0)
                val p = Local(a.east + t * dx, a.north + t * dy)
                if (p.distance < best.distance) best = p
            }
            return best
        }

        fun convexHull(points: List<Local>): List<Local> {
            val p = points.distinct().sortedWith(compareBy({ it.east }, { it.north }))
            if (p.size < 3) return p
            fun cross(o: Local, a: Local, b: Local) = (a.east - o.east) * (b.north - o.north) - (a.north - o.north) * (b.east - o.east)
            val lower = mutableListOf<Local>()
            for (pt in p) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), pt) <= 0) lower.removeAt(lower.size - 1); lower += pt }
            val upper = mutableListOf<Local>()
            for (pt in p.reversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), pt) <= 0) upper.removeAt(upper.size - 1); upper += pt }
            return lower.dropLast(1) + upper.dropLast(1)
        }
    }

}
