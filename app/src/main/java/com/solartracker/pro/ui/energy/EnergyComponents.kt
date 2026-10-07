package com.solartracker.pro.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.solartracker.pro.core.inverter.Freshness
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.ui.uiLabel
import java.util.Locale

/** Label showing what kind of value is displayed (measurement, calculation, estimate, ...). */
@Composable
fun KindBadge(kind: DataKind, modifier: Modifier = Modifier) {
    val (bg, fg) = kindColors(kind)
    Surface(modifier = modifier, shape = RoundedCornerShape(6.dp), color = bg, contentColor = fg) {
        Text(kind.uiLabel, Modifier.padding(horizontal = 6.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun kindColors(kind: DataKind): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (kind) {
        DataKind.MEASURED -> c.primary.copy(alpha = 0.18f) to c.primary
        DataKind.CALCULATED -> c.secondary.copy(alpha = 0.18f) to c.secondary
        DataKind.ESTIMATED, DataKind.FORECAST -> c.tertiary.copy(alpha = 0.18f) to c.tertiary
        DataKind.SIMULATED -> c.outline.copy(alpha = 0.25f) to c.onSurfaceVariant
        DataKind.STALE, DataKind.LAST_KNOWN, DataKind.INVALID -> c.error.copy(alpha = 0.15f) to c.error
        DataKind.UNAVAILABLE, DataKind.UNKNOWN -> c.outline.copy(alpha = 0.18f) to c.outline
    }
}

/** Data kind of a live value; simulator data is never labelled as a measurement. */
fun freshnessKind(f: Freshness, simulated: Boolean = false): DataKind = when (f) {
    Freshness.LIVE -> if (simulated) DataKind.SIMULATED else DataKind.MEASURED
    Freshness.STALE -> DataKind.STALE
    Freshness.LAST_KNOWN -> DataKind.LAST_KNOWN
    Freshness.NONE -> DataKind.UNKNOWN
}

/** One metric: caption, value (or N/A with the reason) and its data kind. */
@Composable
fun MetricRow(caption: String, value: String?, kind: DataKind, naReason: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(caption, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (value == null) {
            Column(horizontalAlignment = Alignment.End) {
                Text("N/A", fontWeight = FontWeight.SemiBold)
                naReason?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } else {
            Text(value, fontWeight = FontWeight.SemiBold)
            KindBadge(kind)
        }
    }
}

@Composable
fun BigMetric(caption: String, value: String, kind: DataKind?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        kind?.let { KindBadge(it) }
    }
}

object Fmt {
    fun kw(w: Double?): String? = w?.let { String.format(Locale.ROOT, "%.2f kW", it / 1000.0) }
    fun kwFromKw(kw: Double?, decimals: Int = 2): String? = kw?.let { String.format(Locale.ROOT, "%.${decimals}f kW", it) }
    fun signedKw(w: Double?): String? = w?.let { String.format(Locale.ROOT, "%+.2f kW", it / 1000.0) }
    fun kwh(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.2f kWh", it) }
    fun v(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.1f V", it) }
    fun a(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.1f A", it) }
    fun hz(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.2f Hz", it) }
    fun c(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.0f °C", it) }
    fun pct(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.0f%%", it) }
    fun conf(v: Double?): String = v?.let { "${(it * 100).toInt()}%" } ?: "—"
    fun signedPct(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%+.1f%%", it) }
    fun m(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.1f m", it) }
    fun deg(v: Double?): String? = v?.let { String.format(Locale.ROOT, "%.0f°", it) }
}
