package com.solartracker.pro.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.solartracker.pro.core.solar.DayType
import com.solartracker.pro.data.LocationSource
import com.solartracker.pro.ui.BatteryNow
import com.solartracker.pro.ui.DashboardState
import com.solartracker.pro.ui.WeatherNow
import com.solartracker.pro.ui.describeSources
import com.solartracker.pro.core.weather.OpenMeteo
import com.solartracker.pro.core.weather.WeatherSource
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.IconButton
import com.solartracker.pro.ui.theme.ChartColors
import com.solartracker.pro.ui.Format
import com.solartracker.pro.ui.components.EstimateBadge
import com.solartracker.pro.ui.components.PowerChart
import com.solartracker.pro.ui.components.SectionCard
import com.solartracker.pro.ui.components.StatTile

@Composable
fun DashboardScreen(
    state: DashboardState?,
    onRefreshWeather: () -> Unit,
    modifier: Modifier = Modifier,
    energyOverview: @Composable () -> Unit = {},
) {
    if (state == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val s = state.settings
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LocationHeader(state)
        energyOverview()
        state.weather?.let { WeatherCard(it, onRefreshWeather) }
        SunCard(state)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "PV",
                value = "${Format.decimal(s.system.peakPowerKw)} kWp",
                footnote = "Kąt ${Format.degrees(s.system.tiltDeg)} · azymut ${Format.degrees(s.system.azimuthDeg)} " +
                    Format.compass(s.system.azimuthDeg),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "AKTUALNA MOC",
                value = Format.kw(state.currentPowerKw),
                estimate = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = "DZISIAJ",
                value = Format.kwh(state.energySoFarKwh),
                footnote = "od północy do teraz",
                estimate = true,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = "PROGNOZA DNIA",
                value = Format.kwh(state.energyTodayKwh),
                footnote = "cały dzień",
                estimate = true,
                modifier = Modifier.weight(1f),
            )
        }

        state.battery?.let { BatteryCard(it) }

        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Przewidywana moc PV [kW]",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                EstimateBadge()
            }
            PowerChart(points = state.profile, now = state.now, peakPowerKw = s.system.peakPowerKw)
        }

        Text(
            "Wartości produkcji to szacunek, a nie pomiar. Źródło: " +
                (state.weather?.state?.let(::describeSources) ?: "model bezchmurnego nieba (pogoda wyłączona)") +
                ". Model nie uwzględnia zacienienia ani śniegu na panelach.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationHeader(state: DashboardState) {
    val s = state.settings
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(6.dp))
        Column {
            val source = if (s.locationSource == LocationSource.GPS) "GPS" else "ręcznie"
            Text(
                text = s.locationName.ifBlank { "Lokalizacja" } + " · $source",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = Format.coordinates(s.location.latitude, s.location.longitude),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SunCard(state: DashboardState) {
    SectionCard(containerColor = MaterialTheme.colorScheme.primaryContainer) {
        Text(
            "☀️ SŁOŃCE",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                Format.degrees(state.sun.elevationDeg),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.padding(bottom = 10.dp)) {
                Text(
                    "wysokość",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "azymut ${Format.degrees(state.sun.azimuthDeg)} ${Format.compass(state.sun.azimuthDeg)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (!state.sun.isAboveHorizon) {
            Text(
                "Słońce pod horyzontem",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        val times = state.sunTimes
        when (times.dayType) {
            DayType.NORMAL -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SunEvent("Wschód", Format.time(times.sunrise, state.zone))
                SunEvent("Południe", Format.time(times.solarNoon, state.zone))
                SunEvent("Zachód", Format.time(times.sunset, state.zone))
            }
            DayType.POLAR_DAY -> SunEvent("Dzień polarny", "słońce nie zachodzi")
            DayType.POLAR_NIGHT -> SunEvent("Noc polarna", "słońce nie wschodzi")
        }
        Text(
            "Długość dnia: ${Format.duration(times.dayLength)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun SunEvent(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun BatteryCard(b: BatteryNow) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "🔋 Magazyn energii",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            EstimateBadge()
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("SOC ${Format.percent(b.socPercent)}", fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.size(12.dp))
            Text(
                "${Format.decimal(b.storedKwh, 1)} / ${Format.decimal(b.usableKwh, 1)} kWh",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        LinearProgressIndicator(
            progress = { (b.socPercent / 100.0).toFloat().coerceIn(0f, 1f) },
            color = ChartColors.soc,
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Ładowanie: +${Format.kw(b.chargeKw)}",
                color = if (b.chargeKw > 0.0) ChartColors.charge else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Rozładowanie: ${Format.kw(b.dischargeKw)}",
                color = if (b.dischargeKw > 0.0) ChartColors.discharge else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            "Symulacja od północy z początkowym SOC z ustawień.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeatherCard(w: WeatherNow, onRefresh: () -> Unit) {
    val st = w.state
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val icon = when {
                    w.source != WeatherSource.FORECAST -> "📊"
                    (w.cloudCoverPercent ?: 0.0) >= 70 -> "☁️"
                    (w.cloudCoverPercent ?: 0.0) >= 30 -> "⛅"
                    else -> "☀️"
                }
                Text("$icon POGODA", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                Text(
                    when (w.source) {
                        WeatherSource.FORECAST -> listOfNotNull(
                            w.temperatureC?.let { "${Format.decimal(it, 0)}°C" },
                            w.cloudCoverPercent?.let { "zachmurzenie ${Format.percent(it)}" },
                        ).joinToString(" · ").ifEmpty { "prognoza" }
                        WeatherSource.CLIMATE -> "średnie klimatyczne (brak prognozy na tę godzinę)"
                        WeatherSource.CLEAR_SKY -> if (st.loading) "pobieranie danych…" else "brak danych – bezchmurne niebo"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                w.hour?.takeIf { w.source == WeatherSource.FORECAST }?.let { h ->
                    val details = listOfNotNull(
                        h.windSpeedMs?.let { "wiatr ${Format.decimal(it, 1)} m/s" },
                        h.relativeHumidityPercent?.let { "wilgotność ${Format.percent(it)}" },
                        h.precipitationMm?.takeIf { it > 0 }?.let { "opady ${Format.decimal(it, 1)} mm" },
                        h.snowDepthM?.takeIf { it > 0 }?.let { "śnieg ${Format.decimal(it * 100, 0)} cm" },
                        h.visibilityM?.takeIf { it < 5000 }?.let { "widoczność ${Format.decimal(it / 1000, 1)} km" },
                    )
                    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (w.snowOnPanels) Text("Prawdopodobnie śnieg na panelach – produkcja może być bliska zera (szacunek pulpitu tego nie uwzględnia).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (st.loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, contentDescription = "Odśwież pogodę") }
            }
        }
        st.updatedAt?.let {
            Text(
                "Prognoza z ${Format.time(it, java.time.ZoneId.systemDefault())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        st.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        Text(
            OpenMeteo.ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
