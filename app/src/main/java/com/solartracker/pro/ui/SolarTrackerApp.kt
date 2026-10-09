package com.solartracker.pro.ui

import com.solartracker.pro.core.geo.SavedPlace
import androidx.compose.ui.res.stringResource
import com.solartracker.pro.R
import android.Manifest
import android.app.Activity
import android.os.SystemClock
import androidx.annotation.StringRes
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Hub
import com.solartracker.pro.core.access.FeatureAccessManager
import com.solartracker.pro.core.access.SubscriptionState
import com.solartracker.pro.ui.tools.ToolsScreen
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.EnergyPrices
import com.solartracker.pro.data.ConsumptionSettings
import com.solartracker.pro.data.AppLanguage
import com.solartracker.pro.data.ThemeMode
import com.solartracker.pro.ui.screens.AngleComparisonScreen
import com.solartracker.pro.ui.screens.DashboardScreen
import com.solartracker.pro.ui.screens.EnergyScreen
import com.solartracker.pro.ui.screens.EnergySettingsActions
import com.solartracker.pro.ui.screens.LiveSolarScreen
import com.solartracker.pro.ui.screens.MonthlyScreen
import com.solartracker.pro.ui.screens.SettingsActions
import com.solartracker.pro.ui.screens.SettingsScreen
import com.solartracker.pro.ui.screens.UpdateSection
import com.solartracker.pro.ui.energy.EnergyCenterScreen
import com.solartracker.pro.ui.radar.RadarScreen
import com.solartracker.pro.ui.energy.EnergyOverview
import com.solartracker.pro.energy.EnergyCenterViewModel
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    DASHBOARD(R.string.tab_dashboard, Icons.Outlined.WbSunny),
    LIVE(R.string.tab_live, Icons.Outlined.Speed),
    ANGLES(R.string.tab_angles, Icons.Outlined.Tune),
    MONTHLY(R.string.tab_monthly, Icons.Outlined.BarChart),
    ENERGY(R.string.tab_energy, Icons.Outlined.BatteryChargingFull),
    CENTER(R.string.tab_center, Icons.Outlined.Hub),
    RADAR(R.string.tab_radar, Icons.Outlined.Cloud),
    TOOLS(R.string.tab_tools, Icons.Outlined.Build),
    SETTINGS(R.string.tab_settings, Icons.Outlined.Settings),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SolarTrackerApp(viewModel: MainViewModel, openSettingsRequest: Int = 0) {
    var tab by rememberSaveable { mutableIntStateOf(Tab.DASHBOARD.ordinal) }
    // Previously visited tabs: the system Back button walks back through them instead of closing the app.
    var history by rememberSaveable(stateSaver = listSaver<List<Int>, Int>(save = { it }, restore = { it })) {
        mutableStateOf(emptyList<Int>())
    }
    fun select(next: Int) {
        history = TabHistory.push(history, tab, next)
        tab = next
    }
    // A tap on an update notification opens the settings tab (where the update section is).
    LaunchedEffect(openSettingsRequest) { if (openSettingsRequest > 0) select(Tab.SETTINGS.ordinal) }

    // Registered before the screens' own handlers, so a sub-page inside a tab closes first.
    val context = LocalContext.current
    var lastExitPress by remember { mutableLongStateOf(0L) }
    BackHandler {
        val previous = TabHistory.back(history, tab, Tab.DASHBOARD.ordinal)
        if (previous != null) {
            tab = previous.first
            history = previous.second
        } else {
            val now = SystemClock.elapsedRealtime()
            if (now - lastExitPress < EXIT_CONFIRM_MS) {
                (context as? Activity)?.finish()
            } else {
                lastExitPress = now
                Toast.makeText(context, context.getString(R.string.press_back_again), Toast.LENGTH_SHORT).show()
            }
        }
    }

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
            override fun setModuleAndInverter(temperatureCoefficient: Double, inverterLimitKw: Double?) =
                viewModel.setModuleAndInverter(temperatureCoefficient, inverterLimitKw)
            override fun setManualLocation(latitude: Double, longitude: Double, name: String, elevationM: Double) =
                viewModel.setManualLocation(latitude, longitude, name, elevationM)
            override fun selectPlace(place: SavedPlace) = viewModel.selectPlace(place)
            override fun forgetRecentPlace(place: SavedPlace) = viewModel.forgetRecentPlace(place)
            override fun setThemeMode(mode: ThemeMode) = viewModel.setThemeMode(mode)
            override fun setLanguage(language: AppLanguage) = viewModel.setLanguage(language)
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

    // No store billing in this build: the subscription state is fixed and shown to the user as such.
    val subscription = SubscriptionState.DEFAULT
    val featureAccess = remember { FeatureAccessManager { subscription } }

    Scaffold(
        // Expose test tags as resource ids so UI tests (UiAutomator) can read the live values.
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    val label = stringResource(t.label)
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { select(t.ordinal) },
                        icon = { Icon(t.icon, contentDescription = label) },
                        label = { Text(label, maxLines = 1) },
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
                val weather by viewModel.weather.collectAsStateWithLifecycle()
                val energyVm: EnergyCenterViewModel = viewModel()
                DashboardScreen(state, viewModel::refreshWeather, contentModifier) { EnergyOverview(energyVm, weather) }
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
            Tab.CENTER -> {
                val weather by viewModel.weather.collectAsStateWithLifecycle()
                EnergyCenterScreen(weather, contentModifier)
            }
            Tab.RADAR -> {
                val weather by viewModel.weather.collectAsStateWithLifecycle()
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val energyVm: EnergyCenterViewModel = viewModel()
                RadarScreen(energyVm, weather, settings?.locationName.orEmpty(), viewModel::refreshWeather, contentModifier)
            }
            Tab.TOOLS -> {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                settings?.let { ToolsScreen(it, featureAccess, subscription, contentModifier) }
            }
            Tab.SETTINGS -> {
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val gpsStatus by viewModel.gpsStatus.collectAsStateWithLifecycle()
                val weather by viewModel.weather.collectAsStateWithLifecycle()
                SettingsScreen(settings, gpsStatus, weather, settingsActions, energyActions, contentModifier) { UpdateSection() }
            }
        }
    }
}

private const val EXIT_CONFIRM_MS = 2_000L
