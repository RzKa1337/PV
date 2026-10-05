package com.solartracker.pro.core.shading

import kotlin.math.cos
import kotlin.math.sin

/**
 * Physical layout of the PV field, used to sample which part of each panel is in shadow.
 * Panels are laid out in [rows] × [columns] in the plane of the array, centred on [center]
 * (local metres from the installation point).
 */
data class PvArrayGeometry(
    val tiltDeg: Double,
    val azimuthDeg: Double,
    /** Height of the lower panel edge above the ground at the installation [m]. */
    val baseHeightM: Double = 0.5,
    val rows: Int = 1,
    val columns: Int = 4,
    val panelWidthM: Double = 1.13,
    val panelLengthM: Double = 1.72,
    val center: Local = Local(0.0, 0.0),
    /** Bypass diodes per panel (typically 3, each protecting a third along the long side). */
    val bypassDiodes: Int = 3,
    /** String index for each panel (row-major); null = one string with all panels. */
    val stringOfPanel: List<Int>? = null,
    /** MPPT index for each string; null = all strings on MPPT 1. */
    val mpptOfString: List<Int>? = null,
) {
    init {
        require(rows in 1..50 && columns in 1..100) { "panel layout out of range" }
        require(bypassDiodes in 1..6)
        require(stringOfPanel == null || stringOfPanel.size == rows * columns) { "string assignment must cover every panel" }
    }

    val panelCount: Int get() = rows * columns
    fun stringOf(panel: Int): Int = stringOfPanel?.get(panel) ?: 0
    val stringCount: Int get() = (stringOfPanel?.maxOrNull() ?: 0) + 1
    fun mpptOf(string: Int): Int = mpptOfString?.getOrNull(string) ?: 0

    /** A sample point on a panel: panel index, bypass group, local coordinates and height. */
    data class SamplePoint(val panel: Int, val group: Int, val east: Double, val north: Double, val up: Double)

    /** [perGroup] × [perGroup] points in each bypass group of each panel (portrait panels). */
    fun samplePoints(perGroup: Int = 2): List<SamplePoint> {
        val tilt = Math.toRadians(tiltDeg)
        val az = Math.toRadians(azimuthDeg)
        // Up-slope direction on the ground (towards the back of the panels) and the row direction.
        val slopeE = -sin(az)
        val slopeN = -cos(az)
        val rowE = cos(az)
        val rowN = -sin(az)
        val totalSlope = rows * panelLengthM
        val totalRow = columns * panelWidthM
        val points = mutableListOf<SamplePoint>()
        for (r in 0 until rows) for (c in 0 until columns) {
            val panel = r * columns + c
            for (g in 0 until bypassDiodes) for (i in 0 until perGroup) for (j in 0 until perGroup) {
                // Bypass groups split the panel along its long side (column direction across the width).
                val u = (c + (g + (i + 0.5) / perGroup) / bypassDiodes) * panelWidthM - totalRow / 2
                val v = (r + (j + 0.5) / perGroup) * panelLengthM // distance up the slope from the lower edge
                val horizontal = v * cos(tilt) - totalSlope * cos(tilt) / 2
                points += SamplePoint(
                    panel, g,
                    east = center.east + rowE * u + slopeE * horizontal,
                    north = center.north + rowN * u + slopeN * horizontal,
                    up = baseHeightM + v * sin(tilt),
                )
            }
        }
        return points
    }
}
