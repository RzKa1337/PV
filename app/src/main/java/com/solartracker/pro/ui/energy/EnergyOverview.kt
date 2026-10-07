package com.solartracker.pro.ui.energy

import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.inverter.Freshness
import com.solartracker.pro.core.inverter.LinkStatus
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.ui.WeatherState
import com.solartracker.pro.energy.EnergyCenterViewModel
import com.solartracker.pro.ui.components.SectionCard

@Composable
private fun Tile(label: String, value: String, kind: DataKind?, modifier: Modifier = Modifier, note: String? = null) {
    SectionCard(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        kind?.let { KindBadge(it) }
    }
}

/** Dashboard summary from the Energy Center: energy security first, then live and today's values. */
@Composable
fun EnergyOverview(vm: EnergyCenterViewModel, weather: WeatherState, modifier: Modifier = Modifier) {
    MonitorWhileVisible(vm, weather)
    val live by vm.live.collectAsStateWithLifecycle()
    val forecast by vm.forecast.collectAsStateWithLifecycle()
    val insights by vm.insights.collectAsStateWithLifecycle()
    val config by vm.inverterConfig.collectAsStateWithLifecycle()
    val kind = freshnessKind(live.freshness)
    val t = live.telemetry
    Column(modifier.testTag("energy_overview"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        EnergySecurityCard(forecast.security, compact = true)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(stringResource(R.string.ov_pv_now), Fmt.kw(t?.pv?.powerW) ?: "—", if (t == null) null else kind, Modifier.weight(1f),
                forecast.nowForecastKw?.let { stringResource(R.string.ov_forecast_value, Fmt.kwFromKw(it)) })
            Tile(stringResource(R.string.ov_pv_today), Fmt.kwh(forecast.producedTodayKwh) ?: "—", if (forecast.producedTodayKwh == null) null else DataKind.CALCULATED, Modifier.weight(1f),
                forecast.today?.let { stringResource(R.string.ov_expected, Fmt.kwh(it.expectedKwh)) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(stringResource(R.string.ov_load_now), Fmt.kw(t?.load?.powerW) ?: "—", if (t == null) null else kind, Modifier.weight(1f))
            Tile(stringResource(R.string.ov_battery_soc), Fmt.pct(t?.battery?.socPercent) ?: "—", if (t == null) null else kind, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(stringResource(R.string.ov_accuracy), insights.todayAccuracy?.accuracyPercent?.let { "${it.toInt()}%" } ?: "—",
                if (insights.todayAccuracy == null) null else DataKind.CALCULATED, Modifier.weight(1f), stringResource(R.string.ov_accuracy_note))
            val status = when {
                config?.enabled != true -> stringResource(R.string.ov_not_configured)
                live.info?.simulated == true -> stringResource(R.string.simulator_upper)
                live.connection.status == LinkStatus.OFFLINE -> "OFFLINE"
                live.freshness == Freshness.STALE -> stringResource(R.string.ov_stale)
                live.connection.status == LinkStatus.ONLINE -> "ONLINE"
                else -> live.connection.status.name
            }
            Tile("ANENJI", status, null, Modifier.weight(1f), live.connection.quality?.let { stringResource(R.string.ov_link_quality, Fmt.conf(it)) })
        }
    }
}
