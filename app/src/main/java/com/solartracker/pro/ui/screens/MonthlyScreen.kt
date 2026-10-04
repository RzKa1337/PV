package com.solartracker.pro.ui.screens

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
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.MonthlyState
import com.solartracker.pro.ui.components.BarSeries
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.GroupedBarChart
import com.solartracker.pro.ui.components.ScreenTitle
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
                "Produkcja miesięczna ${state.year}",
                "${Format.decimal(state.system.peakPowerKw)} kWp · azymut ${Format.degrees(state.system.azimuthDeg)} " +
                    Format.compass(state.system.azimuthDeg),
            )

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
                        "Energia [kWh] wg miesięcy",
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
                    contentDescription = "Wykres miesięcznej produkcji dla kątów " +
                        visible.joinToString { Format.degrees(it.tiltDeg) },
                )
            }

            SectionCard {
                MonthlyTable(visible, colorOf)
            }

            Text(
                "Szacunek dla bezchmurnego nieba – górna granica produkcji. " +
                    "W polskim klimacie rzeczywista produkcja, zwłaszcza zimą, jest wyraźnie niższa.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthlyTable(estimates: List<MonthlyEstimate>, colorOf: (Double) -> Color) {
    Row(Modifier.fillMaxWidth()) {
        Text("Miesiąc", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(104.dp))
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
        Text("Rok", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, modifier = Modifier.width(104.dp))
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
