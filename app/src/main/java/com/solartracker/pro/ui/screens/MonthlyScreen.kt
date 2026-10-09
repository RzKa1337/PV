package com.solartracker.pro.ui.screens

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.pv.MonthlyEstimate
import com.solartracker.pro.core.pv.MonthlyTiltPlan
import com.solartracker.pro.core.pv.TrackerComparison
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.MonthlyState
import com.solartracker.pro.ui.components.BarSeries
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.GroupedBarChart
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SunnyScene
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.theme.ChartColors
import java.time.Month

@Composable
fun MonthlyScreen(state: MonthlyState?, modifier: Modifier = Modifier) {
    if (state == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var selected by rememberSaveable { mutableStateOf(listOf(0.0, 30.0, 60.0)) }
    val colorOf = { tilt: Double ->
        val index = state.estimates.indexOfFirst { it.tiltDeg == tilt }.coerceAtLeast(0)
        ChartColors.tiltSeries[index % ChartColors.tiltSeries.size]
    }
    val visible: List<MonthlyEstimate> = state.estimates.filter { it.tiltDeg in selected }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle(
                stringResource(R.string.monthly_title, state.year.toString()),
                stringResource(R.string.monthly_subtitle, Format.decimal(state.system.peakPowerKw), Format.degrees(state.system.azimuthDeg), Format.compass(state.system.azimuthDeg)),
                scene = SunnyScene.PROVENCE,
            )

            state.tiltPlan?.let { MonthlyTiltCard(it) }
            state.trackers?.let { TrackerCard(it, state.tiltPlan) }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.estimates.forEach { estimate ->
                    val tilt = estimate.tiltDeg
                    val isSelected = tilt in selected
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selected = if (isSelected) {
                                (selected - tilt).ifEmpty { selected }
                            } else {
                                (selected + tilt).sorted()
                            }
                        },
                        label = { Text(Format.degrees(tilt)) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = colorOf(tilt).copy(alpha = 0.3f),
                        ),
                    )
                }
            }

            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.monthly_chart_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    EstimateBadge()
                }
                GroupedBarChart(
                    categories = Month.entries.map { Format.monthShort(it).take(3) },
                    series = visible.map { e ->
                        BarSeries(Format.degrees(e.tiltDeg), colorOf(e.tiltDeg), Month.entries.map { e.energyByMonthKwh.getValue(it) })
                    },
                    contentDescription = stringResource(R.string.monthly_chart_desc) +
                        visible.joinToString { Format.degrees(it.tiltDeg) },
                )
            }

            SectionCard {
                MonthlyTable(visible, colorOf)
            }

            Text(
                stringResource(R.string.monthly_footer, state.sourceDescription),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthlyTable(estimates: List<MonthlyEstimate>, colorOf: (Double) -> Color) {
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.month), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(104.dp))
        estimates.forEach {
            Text(
                Format.degrees(it.tiltDeg),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = colorOf(it.tiltDeg),
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
    HorizontalDivider()
    Month.entries.forEach { month ->
        Row(Modifier.fillMaxWidth()) {
            Text(Format.monthName(month), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp))
            estimates.forEach {
                Text(
                    Format.decimal(it.energyByMonthKwh.getValue(month), 0),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.year), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(104.dp))
        estimates.forEach {
            Text(
                Format.decimal(it.yearlyKwh, 0),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Change the angle every month": best angle per month, energy and the gain over a fixed angle. */
@Composable
private fun MonthlyTiltCard(plan: MonthlyTiltPlan) {
    SectionCard(Modifier.testTag("monthly_tilt_plan")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.mtilt_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            EstimateBadge()
        }
        Text(stringResource(R.string.mtilt_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Summary: monthly change vs current angle vs best fixed angle.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanTile(stringResource(R.string.mtilt_monthly), Format.decimal(plan.monthlyAdjustedKwh, 0) + " kWh", null, ChartColors.pv, Modifier.weight(1f))
            PlanTile(stringResource(R.string.mtilt_current, Format.degrees(plan.currentTiltDeg)), Format.decimal(plan.currentFixedKwh, 0) + " kWh",
                plan.gainPercent(plan.currentFixedKwh)?.let { "+" + Format.decimal(plan.gainVsCurrentKwh, 0) + " kWh (+" + Format.decimal(it, 1) + "%)" }, null, Modifier.weight(1f))
        }
        PlanTile(stringResource(R.string.mtilt_best_fixed, Format.degrees(plan.bestFixedTiltDeg)), Format.decimal(plan.bestFixedKwh, 0) + " kWh",
            plan.gainPercent(plan.bestFixedKwh)?.let { stringResource(R.string.mtilt_gain_vs_fixed, Format.decimal(plan.gainVsBestFixedKwh, 0), Format.decimal(it, 1)) }, null, Modifier.fillMaxWidth())

        // Angle per month as bars (0–90°).
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
            plan.months.forEach { m ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Text("${m.bestTiltDeg.toInt()}°", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Box(Modifier.fillMaxWidth().fillMaxHeight((m.bestTiltDeg / 90.0).toFloat().coerceIn(0.03f, 0.75f))
                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(ChartColors.pv))
                    Text(Format.monthShort(m.month).take(3), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }

        // Table: month, angle, energy, gain vs the current angle.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(R.string.month), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(104.dp))
            Text(stringResource(R.string.mtilt_angle), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text("kWh", style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.mtilt_vs_current), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
        }
        HorizontalDivider()
        plan.months.forEach { m ->
            Row(Modifier.fillMaxWidth()) {
                Text(Format.monthName(m.month), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(104.dp))
                Text(Format.degrees(m.bestTiltDeg), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text(Format.decimal(m.bestKwh, 0), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                val g = if (m.currentTiltKwh > 0) (m.bestKwh / m.currentTiltKwh - 1) * 100 else null
                Text(g?.let { "+" + Format.decimal(it, 1) + "%" } ?: "—", style = MaterialTheme.typography.bodyMedium,
                    color = if ((g ?: 0.0) >= 5) ChartColors.charge else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.year), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(104.dp))
            Spacer(Modifier.weight(1f))
            Text(Format.decimal(plan.monthlyAdjustedKwh, 0), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(plan.gainPercent(plan.currentFixedKwh)?.let { "+" + Format.decimal(it, 1) + "%" } ?: "—", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1.2f))
        }
        Text(stringResource(R.string.mtilt_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "What if a solar tracker were installed": fixed panels vs monthly re-tilt vs single-/dual-axis tracker. */
@Composable
private fun TrackerCard(c: TrackerComparison, plan: MonthlyTiltPlan?) {
    SectionCard(Modifier.testTag("tracker_comparison")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.trk_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            EstimateBadge()
        }
        Text(stringResource(R.string.trk_subtitle, Format.degrees(c.fixedTiltDeg), Format.compass(c.fixedAzimuthDeg)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        fun gain(kwh: Double) = c.gainPercent(kwh)?.let {
            (if (kwh >= c.fixedKwh) "+" else "") + Format.decimal(kwh - c.fixedKwh, 0) + " kWh (" + (if (it >= 0) "+" else "") + Format.decimal(it, 1) + "%)"
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanTile(stringResource(R.string.trk_fixed), Format.decimal(c.fixedKwh, 0) + " kWh", null, null, Modifier.weight(1f))
            if (plan != null) {
                PlanTile(stringResource(R.string.mtilt_monthly), Format.decimal(plan.monthlyAdjustedKwh, 0) + " kWh", gain(plan.monthlyAdjustedKwh), null, Modifier.weight(1f))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlanTile(stringResource(R.string.trk_single, Format.degrees(c.maxRotationDeg)), Format.decimal(c.singleAxisKwh, 0) + " kWh", gain(c.singleAxisKwh), ChartColors.pv, Modifier.weight(1f))
            PlanTile(stringResource(R.string.trk_dual), Format.decimal(c.dualAxisKwh, 0) + " kWh", gain(c.dualAxisKwh), ChartColors.pv, Modifier.weight(1f))
        }

        // Per month: three thin bars (fixed / 1-axis / 2-axis) on one scale.
        val max = c.months.maxOf { maxOf(it.fixedKwh, it.singleAxisKwh, it.dualAxisKwh) }.coerceAtLeast(1e-9)
        val colors = listOf(MaterialTheme.colorScheme.outline, ChartColors.pv.copy(alpha = 0.55f), ChartColors.pv)
        Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
            c.months.forEach { m ->
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(1.dp), verticalAlignment = Alignment.Bottom) {
                        listOf(m.fixedKwh, m.singleAxisKwh, m.dualAxisKwh).forEachIndexed { i, v ->
                            Box(Modifier.weight(1f).fillMaxHeight((v / max).toFloat().coerceIn(0.01f, 1f))
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)).background(colors[i]))
                        }
                    }
                    Text(Format.monthShort(m.month).take(3), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(stringResource(R.string.trk_fixed_short), stringResource(R.string.trk_single_short), stringResource(R.string.trk_dual_short)).forEachIndexed { i, label ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(2.dp)).background(colors[i]))
                    Text(" $label", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Table: month, fixed kWh, 1-axis gain, 2-axis gain.
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(stringResource(R.string.month), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(96.dp))
            Text(stringResource(R.string.trk_fixed_short), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.trk_single_short), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(stringResource(R.string.trk_dual_short), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        c.months.forEach { m ->
            Row(Modifier.fillMaxWidth()) {
                Text(Format.monthName(m.month), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
                Text(Format.decimal(m.fixedKwh, 0), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                GainCell(m.singleAxisKwh, m.fixedKwh, false, Modifier.weight(1f))
                GainCell(m.dualAxisKwh, m.fixedKwh, false, Modifier.weight(1f))
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.year), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(96.dp))
            Text(Format.decimal(c.fixedKwh, 0), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            GainCell(c.singleAxisKwh, c.fixedKwh, true, Modifier.weight(1f))
            GainCell(c.dualAxisKwh, c.fixedKwh, true, Modifier.weight(1f))
        }
        Text(stringResource(R.string.trk_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Gain of [kwh] over [base] in percent; green from +10 %. */
@Composable
private fun GainCell(kwh: Double, base: Double, bold: Boolean, modifier: Modifier) {
    val g = if (base > 0) (kwh / base - 1) * 100 else null
    Text(g?.let { (if (it >= 0) "+" else "") + Format.decimal(it, 0) + "%" } ?: "—", style = MaterialTheme.typography.bodyMedium,
        fontWeight = if (bold) FontWeight.Bold else null, textAlign = TextAlign.End,
        color = if ((g ?: 0.0) >= 10) ChartColors.charge else MaterialTheme.colorScheme.onSurface, modifier = modifier)
}

@Composable
private fun PlanTile(label: String, value: String, foot: String?, accent: Color?, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent ?: MaterialTheme.colorScheme.onSurface)
        foot?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = ChartColors.charge, fontWeight = FontWeight.SemiBold) }
    }
}
