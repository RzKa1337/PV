package com.solartracker.pro.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.EnergyPrices
import com.solartracker.pro.data.ConsumptionSettings
import com.solartracker.pro.data.ThemeMode
import com.solartracker.pro.ui.screens.AngleComparisonScreen
import com.solartracker.pro.ui.screens.DashboardScreen
import com.solartracker.pro.ui.screens.EnergyScreen
import com.solartracker.pro.ui.screens.EnergySettingsActions
import com.solartracker.pro.ui.screens.LiveSolarScreen
import com.solartracker.pro.ui.screens.MonthlyScreen
import com.solartracker.pro.ui.screens.SettingsActions
import com.solartracker.pro.ui.screens.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Pulpit", Icons.Outlined.WbSunny),
    LIVE("Live", Icons.Outlined.Speed),
    ANGLES("Kąty", Icons.Outlined.Tune),
    MONTHLY("Miesiące", Icons.Outlined.BarChart),
    ENERGY("Energia", Icons.Outlined.BatteryChargingFull),
    SETTINGS("Ustawienia", Icons.Outlined.Settings),
}

@Composable
fun SolarTrackerApp(viewModel: MainViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(Tab.DASHBOARD.ordinal) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        if (result.values.any { it }) viewModel.locateWithGps() else viewModel.onGpsPermissionDenied()
    }
    val settingsActions = remember(viewModel) {
        object : SettingsActions {
            override fun setPeakPower(kwp: Double) = viewModel.setPeakPower(kwp)
            override fun setTilt(degrees: Double) = viewModel.setTilt(degrees)
            override fun setPanelAzimuth(degrees: Double) = viewModel.setPanelAzimuth(degrees)
            override fun setManualLocation(latitude: Double, longitude: Double, name: String, elevationM: Double) =
                viewModel.setManualLocation(latitude, longitude, name, elevationM)
            override fun setThemeMode(mode: ThemeMode) = viewModel.setThemeMode(mode)
            override fun setWeatherEnabled(enabled: Boolean) = viewModel.setWeatherEnabled(enabled)
            override fun refreshWeather() = viewModel.refreshWeather()
            override fun requestGpsLocation() {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }
    }

    val energyActions = remember(viewModel) {
        object : EnergySettingsActions {
            override fun setBatteryEnabled(enabled: Boolean) = viewModel.setBatteryEnabled(enabled)
            override suspend fun saveBattery(enabled: Boolean, battery: BatteryStorage) = viewModel.saveBattery(enabled, battery)
            override suspend fun saveConsumption(consumption: ConsumptionSettings) = viewModel.saveConsumption(consumption)
            override suspend fun savePrices(prices: EnergyPrices) = viewModel.savePrices(prices)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, maxLines = 1) },
                        alwaysShowLabel = false,
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.DASHBOARD -> {
                val state by viewModel.dashboard.collectAsStateWithLifecycle()
                DashboardScreen(state, viewModel::refreshWeather, contentModifier)
            }
            Tab.LIVE -> {
                // Collecting with lifecycle: the 1-second ticker runs only while this tab is shown
                // and the app is in the foreground.
                val state by viewModel.live.collectAsStateWithLifecycle()
                val path by viewModel.liveSunPath.collectAsStateWithLifecycle()
                val active by viewModel.liveActive.collectAsStateWithLifecycle()
                val paused by viewModel.livePaused.collectAsStateWithLifecycle()
                LiveSolarScreen(state, path, active, paused, viewModel::setLivePaused, contentModifier)
            }
            Tab.ANGLES -> {
                val state by viewModel.tiltComparison.collectAsStateWithLifecycle()
                AngleComparisonScreen(state, contentModifier)
            }
            Tab.MONTHLY -> {
                val state by viewModel.monthly.collectAsStateWithLifecycle()
                MonthlyScreen(state, contentModifier)
            }
            Tab.ENERGY -> {
                val state by viewModel.energy.collectAsStateWithLifecycle()
                val costs by viewModel.costs.collectAsStateWithLifecycle()
                val period by viewModel.energyPeriod.collectAsStateWithLifecycle()
                EnergyScreen(state, costs, period, viewModel::setEnergyPeriod, contentModifier)
            }
            Tab.SETTINGS -> {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
                val weather by viewModel.weather.collectAsStateWithLifecycle()
                SettingsScreen(settings, gpsStatus, weather, settingsActions, energyActions, contentModifier)
            }
        }
    }
}
