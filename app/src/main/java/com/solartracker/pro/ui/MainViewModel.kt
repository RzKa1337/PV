package com.solartracker.pro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.solartracker.pro.core.pv.MonthlyEstimate
import com.solartracker.pro.core.pv.PowerPoint
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.pv.PvSystem
import com.solartracker.pro.core.pv.TiltEstimate
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.solar.SolarPosition
import com.solartracker.pro.core.solar.SunTimes
import com.solartracker.pro.data.AppSettings
import com.solartracker.pro.data.LocationProvider
import com.solartracker.pro.data.LocationRepository
import com.solartracker.pro.data.LocationSource
import com.solartracker.pro.data.SettingsRepository
import com.solartracker.pro.data.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Snapshot shown on the dashboard. All PV values are estimates. */
data class DashboardState(
    val now: Instant,
    val zone: ZoneId,
    val settings: AppSettings,
    val sun: SolarPosition,
    val sunTimes: SunTimes,
    val currentPowerKw: Double,
    val energySoFarKwh: Double,
    val energyTodayKwh: Double,
    val profile: List<PowerPoint>,
)

data class TiltComparisonState(
    val date: LocalDate,
    val system: PvSystem,
    val estimates: List<TiltEstimate>,
) {
    val best: TiltEstimate? get() = estimates.maxByOrNull { it.energyKwh }
}

data class MonthlyState(
    val year: Int,
    val system: PvSystem,
    val estimates: List<MonthlyEstimate>,
)

sealed interface GpsStatus {
    data object Idle : GpsStatus
    data object Locating : GpsStatus
    data object Success : GpsStatus
    data class Error(val message: String) : GpsStatus
}

class MainViewModel(
    private val settingsRepository: SettingsRepository,
    private val locationRepository: LocationProvider,
    private val estimator: PvEstimator = PvEstimator(),
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Instant = { Instant.now() },
) : ViewModel() {

    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Emits the current time periodically while the UI is subscribed. */
    private val ticker: Flow<Instant> = flow {
        while (true) {
            emit(clock())
            delay(REFRESH_INTERVAL_MS)
        }
    }

    private val today: Flow<LocalDate> = ticker
        .map { it.atZone(zoneProvider()).toLocalDate() }
        .distinctUntilChanged()

    private val validSettings: Flow<AppSettings> = settingsRepository.settings.distinctUntilChanged()

    val dashboard: StateFlow<DashboardState?> = combine(validSettings, ticker) { s, now -> buildDashboard(s, now) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val tiltComparison: StateFlow<TiltComparisonState?> = combine(validSettings, today) { s, date ->
        TiltComparisonState(
            date = date,
            system = s.system,
            estimates = estimator.compareTilts(s.system, s.location, date, zoneProvider()),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val monthly: StateFlow<MonthlyState?> = combine(
        validSettings,
        today.map { it.year }.distinctUntilChanged(),
    ) { s, year ->
        MonthlyState(
            year = year,
            system = s.system,
            estimates = estimator.monthlyEnergy(s.system, s.location, year, zoneProvider()),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _gpsStatus = MutableStateFlow<GpsStatus>(GpsStatus.Idle)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private fun buildDashboard(s: AppSettings, now: Instant): DashboardState {
        val zone = zoneProvider()
        val date = now.atZone(zone).toLocalDate()
        val dayStart = date.atStartOfDay(zone).toInstant()
        return DashboardState(
            now = now,
            zone = zone,
            settings = s,
            sun = SolarCalculator.position(s.location, now),
            sunTimes = SolarCalculator.sunTimes(s.location, date),
            currentPowerKw = estimator.powerKw(s.system, s.location, now),
            energySoFarKwh = estimator.energyKwh(s.system, s.location, dayStart, now),
            energyTodayKwh = estimator.dailyEnergyKwh(s.system, s.location, date, zone),
            profile = estimator.dailyProfile(s.system, s.location, date, zone, stepMinutes = 10),
        )
    }

    fun setPeakPower(kwp: Double) = updateSystem { it.copy(peakPowerKw = kwp) }

    fun setTilt(degrees: Double) = updateSystem { it.copy(tiltDeg = degrees) }

    fun setPanelAzimuth(degrees: Double) = updateSystem { it.copy(azimuthDeg = degrees) }

    private fun updateSystem(transform: (PvSystem) -> PvSystem) {
        viewModelScope.launch { settingsRepository.updateSystem(transform) }
    }

    fun setManualLocation(latitude: Double, longitude: Double, name: String) {
        viewModelScope.launch {
            settingsRepository.setLocation(GeoLocation(latitude, longitude), name, LocationSource.MANUAL)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsRepository.setThemeMode(mode) }
    }

    /** Call after the location permission has been granted. */
    fun locateWithGps() {
        if (_gpsStatus.value == GpsStatus.Locating) return
        viewModelScope.launch {
            if (!locationRepository.hasPermission()) {
                _gpsStatus.value = GpsStatus.Error("Brak uprawnień do lokalizacji")
                return@launch
            }
            if (!locationRepository.isLocationEnabled()) {
                _gpsStatus.value = GpsStatus.Error("Lokalizacja w telefonie jest wyłączona")
                return@launch
            }
            _gpsStatus.value = GpsStatus.Locating
            val location = locationRepository.currentLocation()
            if (location == null) {
                _gpsStatus.value = GpsStatus.Error("Nie udało się ustalić lokalizacji. Wpisz ją ręcznie.")
            } else {
                settingsRepository.setLocation(location, GPS_LOCATION_NAME, LocationSource.GPS)
                _gpsStatus.value = GpsStatus.Success
            }
        }
    }

    fun onGpsPermissionDenied() {
        _gpsStatus.value = GpsStatus.Error("Odmówiono dostępu do lokalizacji. Możesz wpisać ją ręcznie.")
    }

    companion object {
        const val REFRESH_INTERVAL_MS = 30_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        const val GPS_LOCATION_NAME = "GPS"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY]!!
                MainViewModel(SettingsRepository(app), LocationRepository(app))
            }
        }
    }
}
