package com.solartracker.pro.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
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
import com.solartracker.pro.data.ThemeMode
import com.solartracker.pro.ui.screens.AngleComparisonScreen
import com.solartracker.pro.ui.screens.DashboardScreen
import com.solartracker.pro.ui.screens.MonthlyScreen
import com.solartracker.pro.ui.screens.SettingsActions
import com.solartracker.pro.ui.screens.SettingsScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    DASHBOARD("Pulpit", Icons.Outlined.WbSunny),
    ANGLES("Kąty", Icons.Outlined.Tune),
    MONTHLY("Miesiące", Icons.Outlined.BarChart),
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
            override fun setManualLocation(latitude: Double, longitude: Double, name: String) =
                viewModel.setManualLocation(latitude, longitude, name)
            override fun setThemeMode(mode: ThemeMode) = viewModel.setThemeMode(mode)
            override fun requestGpsLocation() {
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        val contentModifier = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.DASHBOARD -> {
                val state by viewModel.dashboard.collectAsStateWithLifecycle()
                DashboardScreen(state, contentModifier)
            }
            Tab.ANGLES -> {
                val state by viewModel.tiltComparison.collectAsStateWithLifecycle()
                AngleComparisonScreen(state, contentModifier)
            }
            Tab.MONTHLY -> {
                val state by viewModel.monthly.collectAsStateWithLifecycle()
                MonthlyScreen(state, contentModifier)
            }
            Tab.SETTINGS -> {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
                SettingsScreen(settings, gpsStatus, settingsActions, contentModifier)
            }
        }
    }
}
