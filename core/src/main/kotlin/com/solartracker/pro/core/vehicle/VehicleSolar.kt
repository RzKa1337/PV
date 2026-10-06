package com.solartracker.pro.core.vehicle

import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.pv.PvSimulationEngine
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.normalizeDegrees
import java.time.Duration
import java.time.Instant

/**
 * Vehicle / camper / boat mode. Panels are fixed to the vehicle, so their azimuth follows the heading.
 * Energy figures use the clear-sky model (upper bound) unless a clearness factor is supplied.
 */
/** Vehicle outline and the roof area that may carry panels. */
data class VehicleBody(val lengthM: Double, val widthM: Double, val roofUsableAreaM2: Double? = null)

/**
 * One panel (or a group mounted the same way) on the roof. Position [xM]/[yM] is the corner nearest to
 * the front-left of the roof, [lengthM] runs along the vehicle. Tilt and facing come from the user –
 * nothing is assumed.
 */
data class VehiclePanel(
    val name: String,
    val powerW: Double,
    val lengthM: Double,
    val widthM: Double,
    val xM: Double = 0.0,
    val yM: Double = 0.0,
    val tiltDeg: Double = 0.0,
    /** Facing of the tilted surface relative to the vehicle front (0 = forward, 90 = right side). */
    val relativeAzimuthDeg: Double = 0.0,
) {
    val areaM2: Double get() = lengthM * widthM
}

data class VehicleSolarConfig(
    val panelPowerW: Double,
    /** Panel tilt relative to the roof; 0 = flat. */
    val tiltDeg: Double = 0.0,
    /** Panel facing relative to the vehicle front (0 = forward, 180 = rearward). */
    val relativeAzimuthDeg: Double = 0.0,
    val losses: LossProfile = LossProfile(),
    /** Vehicle consumption [kWh/100 km]; null = no range estimate. */
    val consumptionKwhPer100Km: Double? = null,
    val body: VehicleBody? = null,
    /** Individual panels; when empty a single group of [panelPowerW] / [tiltDeg] / [relativeAzimuthDeg] is used. */
    val panels: List<VehiclePanel> = emptyList(),
) {
    val effectivePanels: List<VehiclePanel>
        get() = panels.ifEmpty { listOf(VehiclePanel("PV", panelPowerW, 0.0, 0.0, tiltDeg = tiltDeg, relativeAzimuthDeg = relativeAzimuthDeg)) }

    val totalPowerW: Double get() = effectivePanels.sumOf { it.powerW }

    /** Panels lying (nearly) flat: the parking heading hardly matters. */
    val effectivelyFlat: Boolean get() = effectivePanels.all { it.tiltDeg < 3.0 }

    fun validate(): List<String> = buildList {
        if (effectivePanels.any { it.powerW !in 1.0..2000.0 }) add("Moc panelu 1–2000 W")
        if (effectivePanels.any { it.tiltDeg !in 0.0..90.0 }) add("Kąt panelu 0–90°")
        val b = body ?: return@buildList
        if (b.lengthM !in 1.0..30.0 || b.widthM !in 0.5..4.0) add("Wymiary pojazdu: długość 1–30 m, szerokość 0,5–4 m")
        panels.forEach { p ->
            if (p.lengthM <= 0 || p.widthM <= 0) add("${p.name}: podaj wymiary panelu")
            if (p.xM < 0 || p.yM < 0 || p.xM + p.lengthM > b.lengthM + 1e-6 || p.yM + p.widthM > b.widthM + 1e-6) add("${p.name}: wystaje poza dach")
        }
        for (i in panels.indices) for (j in i + 1 until panels.size) {
            val a = panels[i]; val c = panels[j]
            val overlap = a.xM < c.xM + c.lengthM && c.xM < a.xM + a.lengthM && a.yM < c.yM + c.widthM && c.yM < a.yM + a.widthM
            if (overlap) add("${a.name} i ${c.name} nachodzą na siebie")
        }
        val area = panels.sumOf { it.areaM2 }
        b.roofUsableAreaM2?.let { if (area > it + 1e-6) add("Panele (${"%.1f".format(area)} m²) większe niż użyteczny dach (${"%.1f".format(it)} m²)") }
    }
}

/** Energy for one parking heading. */
data class HeadingEnergy(val headingDeg: Double, val energyKwh: Double)

/** Energy over all headings with the best one and how much the heading matters at all. */
data class HeadingProfile(val points: List<HeadingEnergy>, val best: HeadingEnergy, val worst: HeadingEnergy, val flat: Boolean) {
    /** (best − worst) / best: ~0 for flat panels. */
    val sensitivity: Double get() = if (best.energyKwh > 0) (best.energyKwh - worst.energyKwh) / best.energyKwh else 0.0
}

data class VehicleEstimate(
    val energyKwh: Double,
    val rangeKm: Double?,
    val headingDeg: Double,
    val clearSky: Boolean,
)

object VehicleSolarEstimator {
    private val engine = PvSimulationEngine()
    private val clearSky = ClearSkyModel()

    fun estimate(
        cfg: VehicleSolarConfig,
        location: GeoLocation,
        headingDeg: Double,
        from: Instant,
        to: Instant,
        clearnessFactor: Double? = null,
        stepMinutes: Long = 15,
    ): VehicleEstimate {
        val arrays = cfg.effectivePanels.map { PvArrayConfig(1, it.powerW, it.tiltDeg, normalizeDegrees(headingDeg + it.relativeAzimuthDeg)) }
        var wh = 0.0
        var t = from
        val step = Duration.ofMinutes(stepMinutes)
        while (t.isBefore(to)) {
            val h = minOf(step, Duration.between(t, to)).seconds / 3600.0
            val mid = t.plusSeconds((h * 1800).toLong())
            for (array in arrays) wh += engine.simulate(array, cfg.losses, location, mid, { PvConditions(clearSky.irradiance(it, mid)) }).acPowerW * h
            t = t.plus(step)
        }
        val kwh = wh / 1000.0 * (clearnessFactor?.coerceIn(0.0, 1.0) ?: 1.0)
        return VehicleEstimate(kwh, cfg.consumptionKwhPer100Km?.takeIf { it > 0 }?.let { kwh / it * 100.0 }, normalizeDegrees(headingDeg), clearnessFactor == null)
    }

    /** Parking heading that maximises energy in the window (relevant only for tilted panels). */
    fun bestHeading(cfg: VehicleSolarConfig, location: GeoLocation, from: Instant, to: Instant, stepDeg: Int = 15): VehicleEstimate =
        (0 until 360 step stepDeg).map { estimate(cfg, location, it.toDouble(), from, to, stepMinutes = 30) }.maxBy { it.energyKwh }

    /** Energy for every heading 0–359° (step [stepDeg]) in the time window. */
    fun headingProfile(cfg: VehicleSolarConfig, location: GeoLocation, from: Instant, to: Instant, stepDeg: Int = 5, clearnessFactor: Double? = null): HeadingProfile {
        require(stepDeg in 1..90)
        val points = (0 until 360 step stepDeg).map { h ->
            HeadingEnergy(h.toDouble(), estimate(cfg, location, h.toDouble(), from, to, clearnessFactor, stepMinutes = 20).energyKwh)
        }
        return HeadingProfile(points, points.maxBy { it.energyKwh }, points.minBy { it.energyKwh }, cfg.effectivelyFlat)
    }

    /**
     * Energy in the time window for each tilt (all panels set to that tilt, facing kept), for a given
     * parking heading – for vehicles with a tilting rack. Planning only; no actuator is controlled.
     */
    fun tiltComparison(
        cfg: VehicleSolarConfig, location: GeoLocation, headingDeg: Double, from: Instant, to: Instant,
        tilts: List<Double> = (0..90 step 5).map { it.toDouble() },
    ): List<Pair<Double, Double>> = tilts.map { tilt ->
        val tilted = cfg.copy(tiltDeg = tilt, panels = cfg.panels.map { it.copy(tiltDeg = tilt) })
        tilt to estimate(tilted, location, headingDeg, from, to, stepMinutes = 20).energyKwh
    }
}
