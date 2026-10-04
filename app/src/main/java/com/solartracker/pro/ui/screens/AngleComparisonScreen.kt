package com.solartracker.pro.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.pv.TiltEstimate
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.TiltComparisonState
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.ScreenTitle
import com.solartracker.pro.ui.components.SectionCard
import java.time.format.DateTimeFormatter
import kotlin.math.abs

@Composable
fun AngleComparisonScreen(state: TiltComparisonState?, modifier: Modifier = Modifier) {
    if (state == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val maxEnergy = state.estimates.maxOfOrNull { it.energyKwh }?.coerceAtLeast(1e-6) ?: 1e-6
    val best = state.best
    val dateText = state.date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Format.locale))

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenTitle(
                "Porównaj kąty",
                "Prognoza na $dateText · ${Format.decimal(state.system.peakPowerKw)} kWp · " +
                    "azymut ${Format.degrees(state.system.azimuthDeg)} ${Format.compass(state.system.azimuthDeg)}",
            )
        }
        if (best != null) {
            item {
                SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Najlepszy kąt dziś: ${Format.degrees(best.tiltDeg)} → ${Format.kwh(best.energyKwh)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        EstimateBadge()
                    }
                }
            }
        }
        items(state.estimates, key = { it.tiltDeg }) { estimate ->
            TiltRow(
                estimate = estimate,
                fraction = (estimate.energyKwh / maxEnergy).toFloat(),
                isBest = estimate == best,
                isCurrent = abs(estimate.tiltDeg - state.system.tiltDeg) < 0.5,
            )
        }
        item {
            Text(
                "Obliczone z modelu bezchmurnego nieba dla bieżącej lokalizacji i azymutu paneli.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TiltRow(estimate: TiltEstimate, fraction: Float, isBest: Boolean, isCurrent: Boolean) {
    val barColor = if (isBest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                Format.degrees(estimate.tiltDeg),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(48.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .background(barColor),
                )
            }
            Text(
                Format.kwh(estimate.energyKwh),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp).width(84.dp),
            )
        }
        if (isBest || isCurrent) {
            Text(
                listOfNotNull(if (isBest) "★ najlepszy" else null, if (isCurrent) "• Twój kąt" else null).joinToString("  "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
