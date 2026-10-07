package com.solartracker.pro.core.vehicle

/** Rectangular roof (or any mounting surface). x runs along the length (from the front), y across the width. */
data class RoofArea(
    val lengthM: Double,
    val widthM: Double,
    /** Free border kept at every edge (fixings, rails, roof curvature) [m]. */
    val edgeMarginM: Double = 0.0,
    /** Minimum gap between panels (clamps, thermal expansion) [m]. */
    val gapM: Double = 0.02,
    /** Allowed extra roof load [kg]; null = not checked. */
    val maxLoadKg: Double? = null,
) {
    fun validate(): List<String> = buildList {
        if (lengthM !in 0.3..30.0 || widthM !in 0.3..10.0) add("Wymiary dachu: długość 0,3–30 m, szerokość 0,3–10 m")
        if (edgeMarginM < 0 || gapM < 0) add("Margines i odstęp nie mogą być ujemne")
        if (2 * edgeMarginM >= minOf(lengthM, widthM)) add("Margines większy niż dach")
    }
}

/** A panel model with how many pieces are available. Nothing is assumed – every dimension comes from the user. */
data class PanelType(
    val name: String,
    val powerW: Double,
    val lengthM: Double,
    val widthM: Double,
    val weightKg: Double? = null,
    val available: Int = 1,
) {
    fun validate(): List<String> = buildList {
        if (powerW !in 1.0..2000.0) add("$name: moc 1–2000 W")
        if (lengthM <= 0 || widthM <= 0) add("$name: podaj wymiary")
        if (available !in 0..20) add("$name: liczba sztuk 0–20")
        if (weightKg != null && weightKg <= 0) add("$name: masa musi być dodatnia")
    }
}

data class PanelPlacement(val type: PanelType, val xM: Double, val yM: Double, val rotated: Boolean) {
    /** Size along the roof length / across the roof width. */
    val alongM: Double get() = if (rotated) type.widthM else type.lengthM
    val acrossM: Double get() = if (rotated) type.lengthM else type.widthM
    val centerX: Double get() = xM + alongM / 2
    val centerY: Double get() = yM + acrossM / 2
}

data class LayoutResult(
    val placements: List<PanelPlacement>,
    val totalPowerW: Double,
    val panelAreaM2: Double,
    val roofAreaM2: Double,
    /** Covered share of the roof [%]. */
    val utilizationPercent: Double,
    /** Total mass [kg]; null when any panel has no weight. */
    val massKg: Double?,
    /** Centre of gravity of the panels from the front-left corner [m]; null without weights. */
    val centerOfGravity: Pair<Double, Double>?,
    /** Offset of that centre from the roof centre (+x = rearward, +y = right) [m]. */
    val cogOffset: Pair<Double, Double>?,
    /** Panels that did not fit, by type name. */
    val leftOver: Map<String, Int>,
) {
    /** The same layout in the vehicle model (for energy estimates and overlap validation). */
    fun toVehiclePanels(tiltDeg: Double = 0.0, relativeAzimuthDeg: Double = 0.0): List<VehiclePanel> = placements.mapIndexed { i, p ->
        VehiclePanel("${p.type.name} #${i + 1}", p.type.powerW, p.alongM, p.acrossM, p.xM, p.yM, tiltDeg, relativeAzimuthDeg)
    }
}

/**
 * Finds the panel set and placement with the highest total Wp that physically fits the roof: inside the margins,
 * no overlap, the minimum gap kept, both orientations tried, within the allowed load. Exact search (bottom-left
 * candidate positions with backtracking) – meant for the small panel counts of vehicles and small roofs.
 */
object PanelLayoutOptimizer {
    const val MAX_PANELS = 16
    private const val EPS = 1e-9

    fun optimize(roof: RoofArea, types: List<PanelType>, nodeBudget: Int = 200_000): LayoutResult {
        require(roof.validate().isEmpty()) { roof.validate().joinToString() }
        types.forEach { t -> require(t.validate().isEmpty()) { t.validate().joinToString() } }
        val usable = types.filter { it.available > 0 }
        require(usable.sumOf { it.available } <= MAX_PANELS) { "Maksymalnie $MAX_PANELS paneli" }

        // Every combination of counts, best total power first (ties: lighter, then smaller area).
        val combos = counts(usable.map { it.available }).map { c -> c to usable.indices.sumOf { usable[it].powerW * c[it] } }
            .sortedWith(compareByDescending<Pair<IntArray, Double>> { it.second }
                .thenBy { (c, _) -> usable.indices.sumOf { (usable[it].weightKg ?: 0.0) * c[it] } }
                .thenBy { (c, _) -> usable.indices.sumOf { usable[it].lengthM * usable[it].widthM * c[it] } })
        var budget = nodeBudget
        for ((c, _) in combos) {
            val panels = usable.indices.flatMap { i -> List(c[i]) { usable[i] } }
            // Load limit only when every weight is known (unknown weight = not checked, never guessed).
            if (roof.maxLoadKg != null && panels.all { it.weightKg != null } && panels.sumOf { it.weightKg!! } > roof.maxLoadKg + EPS) continue
            if (panels.sumOf { it.lengthM * it.widthM } > (roof.lengthM - 2 * roof.edgeMarginM) * (roof.widthM - 2 * roof.edgeMarginM) + EPS) continue
            val placed = place(roof, panels.sortedByDescending { it.lengthM * it.widthM }) { budget-- > 0 } ?: continue
            return result(roof, placed, usable, c)
        }
        return result(roof, emptyList(), usable, IntArray(usable.size))
    }

    private fun counts(max: List<Int>): List<IntArray> {
        val out = mutableListOf<IntArray>()
        fun rec(i: Int, acc: IntArray) {
            if (i == max.size) { out += acc.copyOf(); return }
            for (n in 0..max[i]) { acc[i] = n; rec(i + 1, acc) }
        }
        rec(0, IntArray(max.size))
        return out
    }

    /** Backtracking placement; null when the set does not fit (or the search budget ran out). */
    private fun place(roof: RoofArea, panels: List<PanelType>, step: () -> Boolean): List<PanelPlacement>? {
        val placed = ArrayList<PanelPlacement>()
        fun fits(p: PanelPlacement): Boolean {
            if (p.xM < roof.edgeMarginM - EPS || p.yM < roof.edgeMarginM - EPS) return false
            if (p.xM + p.alongM > roof.lengthM - roof.edgeMarginM + EPS || p.yM + p.acrossM > roof.widthM - roof.edgeMarginM + EPS) return false
            return placed.none { q ->
                p.xM < q.xM + q.alongM + roof.gapM - EPS && q.xM < p.xM + p.alongM + roof.gapM - EPS &&
                    p.yM < q.yM + q.acrossM + roof.gapM - EPS && q.yM < p.yM + p.acrossM + roof.gapM - EPS
            }
        }
        fun rec(i: Int): Boolean {
            if (i == panels.size) return true
            if (!step()) return false
            val xs = (listOf(roof.edgeMarginM) + placed.map { it.xM + it.alongM + roof.gapM }).distinct().sorted()
            val ys = (listOf(roof.edgeMarginM) + placed.map { it.yM + it.acrossM + roof.gapM }).distinct().sorted()
            val t = panels[i]
            val orientations = if (kotlin.math.abs(t.lengthM - t.widthM) < EPS) listOf(false) else listOf(false, true)
            for (x in xs) for (y in ys) for (rot in orientations) {
                val p = PanelPlacement(t, x, y, rot)
                if (fits(p)) {
                    placed += p
                    if (rec(i + 1)) return true
                    placed.removeAt(placed.lastIndex)
                }
            }
            return false
        }
        return if (rec(0)) placed.toList() else null
    }

    private fun result(roof: RoofArea, placed: List<PanelPlacement>, types: List<PanelType>, used: IntArray): LayoutResult {
        val area = placed.sumOf { it.type.lengthM * it.type.widthM }
        val weighed = placed.isNotEmpty() && placed.all { it.type.weightKg != null }
        val mass = if (weighed) placed.sumOf { it.type.weightKg!! } else null
        val cog = mass?.takeIf { it > 0 }?.let { m -> placed.sumOf { it.centerX * it.type.weightKg!! } / m to placed.sumOf { it.centerY * it.type.weightKg!! } / m }
        return LayoutResult(
            placements = placed,
            totalPowerW = placed.sumOf { it.type.powerW },
            panelAreaM2 = area,
            roofAreaM2 = roof.lengthM * roof.widthM,
            utilizationPercent = area / (roof.lengthM * roof.widthM) * 100,
            massKg = mass,
            centerOfGravity = cog,
            cogOffset = cog?.let { (x, y) -> x - roof.lengthM / 2 to y - roof.widthM / 2 },
            leftOver = types.indices.associate { types[it].name to types[it].available - used[it] }.filterValues { it > 0 },
        )
    }
}
