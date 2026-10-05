package com.solartracker.pro.core.shading

import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvPointEstimate
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.pv.cosIncidence
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.solar.GeoLocation
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.tan

/** How precise the installation location is (affects the confidence of the shading result). */
enum class LocationAccuracy(val label: String, val factor: Double) {
    MAP_POINT("Punkt wskazany na mapie", 1.0),
    GPS("GPS", 0.95),
    ADDRESS("Adres (geokodowanie)", 0.85),
    POSTCODE("Kod pocztowy (przybliżona)", 0.5),
    CITY("Miasto (przybliżona)", 0.3),
}

/** Everything the engine needs about the site. Built once, reused for many time steps. */
class ShadingSite(
    val location: GeoLocation,
    val locationAccuracy: LocationAccuracy,
    /** User confirmed the location – required before shading is computed. */
    val locationConfirmed: Boolean,
    val array: PvArrayGeometry,
    obstacles: List<Obstacle>,
    val terrain: TerrainSamples?,
    /** True when building data for the surroundings was loaded (even if the list is empty). */
    val buildingDataLoaded: Boolean,
) {
    val frame = LocalFrame.of(location)
    private val service = ObstacleGeometryService(frame, terrain?.originElevationM ?: location.elevationM, array.baseHeightM + 1.0, array.center)
    val allObstacles: List<ObstacleGeometry> = obstacles.map(service::geometry)
    val usableObstacles: List<ObstacleGeometry> = allObstacles.filter { it.usable }
    val terrainHorizon: SolarHorizonProfile = HorizonBuilder.build(terrain, emptyList(), (terrain?.originElevationM ?: location.elevationM) + array.baseHeightM + 1.0)
    val horizon: SolarHorizonProfile = HorizonBuilder.build(terrain, usableObstacles, (terrain?.originElevationM ?: location.elevationM) + array.baseHeightM + 1.0)
    val samplePoints = array.samplePoints()
}

/** Shading of the array at one moment. */
data class ShadeSnapshot(
    val time: Instant,
    val sunAzimuthDeg: Double,
    val sunElevationDeg: Double,
    /** Share of the array area in (geometric) shadow, 0..1. */
    val shadedAreaFraction: Double,
    /** Shaded share per panel (row-major). */
    val panelShadedFraction: List<Double>,
    /** Power relative to no shading per string, after bypass/MPPT effects, 0..1. */
    val stringPowerFactor: List<Double>,
    /** Whole array power relative to no shading, 0..1 (incl. diffuse sky-view reduction). */
    val powerFactor: Double,
    val blockingObstacleIds: List<String>,
    val terrainBlocked: Boolean,
    val unshadedKw: Double,
    val shadedKw: Double,
) {
    val lossKw: Double get() = unshadedKw - shadedKw
    val partial: Boolean get() = shadedAreaFraction in 0.001..0.999
    val total: Boolean get() = shadedAreaFraction >= 0.999
}

data class ShadowEvent(
    val obstacleId: String,
    val start: Instant,
    val end: Instant,
    val maxShadedFraction: Double,
    val panels: Set<Int>,
    val energyLossKwh: Double,
) {
    val duration: Duration get() = Duration.between(start, end)
}

data class DayShading(
    val date: LocalDate,
    val steps: List<ShadeSnapshot>,
    val events: List<ShadowEvent>,
    val unshadedKwh: Double,
    val shadedKwh: Double,
) {
    val lossKwh: Double get() = unshadedKwh - shadedKwh
    val lossPercent: Double get() = if (unshadedKwh > 0) lossKwh / unshadedKwh * 100 else 0.0
}

data class ShadingConfidence(val score: Double, val missing: List<String>, val kind: DataKind)

/**
 * Geometric shading model: for sample points on every panel (per bypass group) a ray towards the sun
 * is tested against every obstacle volume (prism footprints, cylinders, tree crowns with seasonal
 * transmittance) and the terrain horizon. Strings are evaluated with bypass diodes and MPPT choice;
 * diffuse light is reduced by the sky-view factor of the horizon profile.
 */
class ShadingAnalysisEngine(
    val site: ShadingSite,
    private val estimator: PvEstimator = PvEstimator(),
) {
    private val array = site.array

    fun snapshot(system: PvSystem, instant: Instant, estimate: PvPointEstimate = estimator.pointEstimate(system, site.location, instant)): ShadeSnapshot {
        val sun = estimate.sun
        val az = sun.azimuthDeg
        val el = sun.elevationDeg
        if (el <= 0.0) {
            return ShadeSnapshot(instant, az, el, 0.0, List(array.panelCount) { 0.0 }, List(array.stringCount) { 1.0 }, 1.0, emptyList(), false, 0.0, 0.0)
        }
        val terrainBlocked = site.terrainHorizon.blocks(az, el)
        val month = instant.atZone(ZoneId.of("UTC")).month
        val dir = SunVector.of(az, el)
        val horizontal = kotlin.math.hypot(dir.east, dir.north)
        val dx = dir.east / horizontal
        val dy = dir.north / horizontal
        val tanEl = tan(Math.toRadians(el))
        val candidates = site.usableObstacles.filter { couldShade(it, az, el) }
        val blocking = mutableSetOf<String>()

        val pointShade = site.samplePoints.map { p ->
            if (terrainBlocked) return@map 1.0
            var transmitted = 1.0
            for (g in candidates) {
                val hit = blockedBy(g, p, dx, dy, tanEl) ?: continue
                if (!hit) continue
                blocking += g.obstacle.id
                val t = g.obstacle.foliage?.transmittance(month) ?: if (g.obstacle.type == ObstacleType.TREE) Foliage().transmittance(month) else 0.0
                transmitted *= t
                if (transmitted <= 0.0) break
            }
            1.0 - transmitted
        }

        val groups = site.samplePoints.indices.groupBy { site.samplePoints[it].panel to site.samplePoints[it].group }
        val groupShade = groups.mapValues { (_, idx) -> idx.map { pointShade[it] }.average() }
        val panelShade = (0 until array.panelCount).map { p -> (0 until array.bypassDiodes).map { groupShade[p to it] ?: 0.0 }.average() }

        // Irradiance split on the plane: beam vs diffuse(+reflected).
        val beam = estimate.irradiance.dni * max(0.0, cosIncidence(sun.position, system.tiltDeg, system.azimuthDeg))
        val total = estimate.poa
        // Diffuse light is reduced by the part of the sky hidden by the horizon (terrain + obstacles).
        val diffuse = (total - beam).coerceAtLeast(0.0) * site.horizon.skyViewFactor.coerceIn(0.0, 1.0)
        val stringFactors = (0 until array.stringCount).map { s ->
            val levels = (0 until array.panelCount).filter { array.stringOf(it) == s }
                .flatMap { p -> (0 until array.bypassDiodes).map { g -> beam * (1 - (groupShade[p to g] ?: 0.0)) + diffuse } }
            stringFactor(levels, beam + (total - beam).coerceAtLeast(0.0))
        }
        val panelsPerString = (0 until array.stringCount).map { s -> (0 until array.panelCount).count { array.stringOf(it) == s }.toDouble() }
        val powerFactor = if (total <= 0.0) 1.0 else stringFactors.zip(panelsPerString).sumOf { it.first * it.second } / panelsPerString.sum()
        return ShadeSnapshot(
            time = instant, sunAzimuthDeg = az, sunElevationDeg = el,
            shadedAreaFraction = pointShade.average(),
            panelShadedFraction = panelShade,
            stringPowerFactor = stringFactors,
            powerFactor = powerFactor.coerceIn(0.0, 1.0),
            blockingObstacleIds = blocking.toList(),
            terrainBlocked = terrainBlocked,
            unshadedKw = estimate.powerKw,
            shadedKw = estimate.powerKw * powerFactor.coerceIn(0.0, 1.0),
        )
    }

    /**
     * Series string of bypass groups with irradiance [levels]: the MPPT picks the current that maximises
     * power; groups receiving less than that current are bypassed (contribute nothing).
     */
    private fun stringFactor(levels: List<Double>, unshadedLevel: Double): Double {
        if (levels.isEmpty() || unshadedLevel <= 0.0) return 1.0
        val sorted = levels.sortedDescending()
        var best = 0.0
        sorted.forEachIndexed { i, current -> best = max(best, current * (i + 1)) }
        return (best / (unshadedLevel * levels.size)).coerceIn(0.0, 1.0)
    }

    private fun couldShade(g: ObstacleGeometry, az: Double, el: Double): Boolean {
        // Angles are computed from the array centre; near obstacles look different from each panel,
        // so they are always tested exactly.
        if (g.distanceM < NEAR_M) return true
        if (g.topElevationAngleDeg + ANGLE_MARGIN_DEG < el) return false
        return azimuthWithin(az, g.azimuthFromDeg, g.azimuthToDeg, ANGLE_MARGIN_DEG)
    }

    /** null = no intersection in plan view; true = ray passes through the obstacle volume. */
    private fun blockedBy(g: ObstacleGeometry, p: PvArrayGeometry.SamplePoint, dx: Double, dy: Double, tanEl: Double): Boolean? {
        val intervals = when (val s = g.local) {
            is LocalShape.Prism -> Geo2.rayPolygonIntervals(p.east, p.north, dx, dy, s.footprint)
            is LocalShape.Cylinder -> listOfNotNull(Geo2.rayCircleInterval(p.east, p.north, dx, dy, s.centre, s.radiusM))
        }
        if (intervals.isEmpty()) return null
        val shape = g.local
        return intervals.any { (t1, t2) ->
            val zIn = p.up + t1 * tanEl
            val zOut = p.up + t2 * tanEl
            // The ray is inside the volume where its height overlaps [bottom, top].
            zIn < shape.topM && zOut > shape.bottomM
        }
    }

    /** Shading over a local day, every [stepMinutes]. Energy integrates powers over the steps. */
    fun day(system: PvSystem, date: LocalDate, zone: ZoneId, stepMinutes: Int = 5): DayShading {
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val steps = generateSequence(start) { it.plusSeconds(stepMinutes * 60L) }.takeWhile { it.isBefore(end) }
            .map { snapshot(system, it) }.toList()
        val h = stepMinutes / 60.0
        val events = mutableListOf<ShadowEvent>()
        val open = mutableMapOf<String, MutableList<ShadeSnapshot>>()
        fun close(id: String) {
            val run = open.remove(id) ?: return
            val panels = run.flatMap { s -> s.panelShadedFraction.withIndex().filter { it.value > 0.001 }.map { it.index } }.toSet()
            events += ShadowEvent(id, run.first().time, run.last().time.plusSeconds(stepMinutes * 60L),
                run.maxOf { it.shadedAreaFraction }, panels, run.sumOf { it.lossKw } * h)
        }
        for (s in steps) {
            val ids = s.blockingObstacleIds + if (s.terrainBlocked) listOf(HorizonBuilder.TERRAIN_ID) else emptyList()
            open.keys.filter { it !in ids }.forEach(::close)
            ids.forEach { open.getOrPut(it) { mutableListOf() } += s }
        }
        open.keys.toList().forEach(::close)
        return DayShading(date, steps, events.sortedBy { it.start }, steps.sumOf { it.unshadedKw } * h, steps.sumOf { it.shadedKw } * h)
    }

    /** Monthly losses from the 15th of each month scaled by days in the month [kWh]. */
    fun year(system: PvSystem, year: Int, zone: ZoneId, stepMinutes: Int = 10): Map<Month, DayShading> =
        Month.entries.associateWith { m -> day(system, LocalDate.of(year, m, 15), zone, stepMinutes) }

    fun annualLossKwh(yearResult: Map<Month, DayShading>, year: Int): Pair<Double, Double> {
        var loss = 0.0
        var unshaded = 0.0
        yearResult.forEach { (m, d) ->
            val days = YearMonth.of(year, m).lengthOfMonth()
            loss += d.lossKwh * days
            unshaded += d.unshadedKwh * days
        }
        return loss to unshaded
    }

    /** Confidence of the shading result and the list of missing data. */
    fun confidence(): ShadingConfidence {
        val missing = mutableListOf<String>()
        if (!site.locationConfirmed) missing += "Potwierdź lokalizację instalacji"
        if (site.locationAccuracy.factor < 0.8) missing += "Lokalizacja przybliżona (${site.locationAccuracy.label}) – wskaż punkt na mapie"
        if (site.terrain == null) missing += "Brak danych o ukształtowaniu terenu"
        if (!site.buildingDataLoaded) missing += "Nie pobrano danych o budynkach w okolicy"
        val unknown = site.allObstacles.filter { it.obstacle.enabled && !it.obstacle.height.known }
        unknown.forEach { missing += "Brak wysokości: ${it.obstacle.name ?: it.obstacle.type.label} (${it.distanceM.toInt()} m, ${it.azimuthDeg.toInt()}°)" }
        if (site.array.stringOfPanel == null) missing += "Nie podano przypisania paneli do stringów (przyjęto jeden string)"

        // Relevant obstacles: those that can rise above the sun path (> 3° above horizon, within 300 m).
        val relevant = site.allObstacles.filter { it.obstacle.enabled && it.distanceM < 300 && (it.topElevationAngleDeg > 3 || !it.obstacle.height.known) }
        val heightScore = if (relevant.isEmpty()) 1.0 else relevant.map { max(0.2, it.obstacle.height.source.weight) }.average()
        val score = (site.locationAccuracy.factor * (if (site.terrain != null) 1.0 else 0.85) *
            (if (site.buildingDataLoaded) 1.0 else 0.6) * heightScore * (if (site.locationConfirmed) 1.0 else 0.5)).coerceIn(0.0, 1.0)
        val kind = when {
            unknown.isNotEmpty() || relevant.any { it.obstacle.height.source.kind == DataKind.ESTIMATED } || site.locationAccuracy.factor < 0.8 -> DataKind.ESTIMATED
            else -> DataKind.CALCULATED
        }
        return ShadingConfidence(score, missing, kind)
    }

    companion object {
        const val ANGLE_MARGIN_DEG = 3.0
        const val NEAR_M = 30.0

        /** True when [az] lies in the clockwise range [from, to] (which may wrap over north) ± [margin]. */
        fun azimuthWithin(az: Double, from: Double, to: Double, margin: Double = 0.0): Boolean {
            val span = ((to - from) % 360 + 360) % 360
            val d = ((az - from) % 360 + 360) % 360
            return d <= span + margin || d >= 360 - margin
        }
    }
}
