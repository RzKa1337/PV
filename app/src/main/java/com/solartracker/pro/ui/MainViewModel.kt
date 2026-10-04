package com.solartracker.pro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.energy.CostComparison
import com.solartracker.pro.core.energy.EnergyFlowSimulator
import com.solartracker.pro.core.energy.EnergyPrices
import com.solartracker.pro.core.energy.SimulationResult
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
import com.solartracker.pro.data.ConsumptionSettings
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
    /** Simulated battery state now, null without a battery. */
    val battery: BatteryNow? = null,
)

/** Battery state at the current moment, from today's energy-flow simulation. */
data class BatteryNow(
    val socPercent: Double,
    val storedKwh: Double,
    val usableKwh: Double,
    val chargeKw: Double,
    val dischargeKw: Double,
)

/** Simulation horizons. Every horizon starts today with the configured initial SOC. */
enum class EnergyPeriod(val label: String, val simulatedDays: Int, val firstShownDay: Int) {
    TODAY("Dziś", 1, 0),
    TOMORROW("Jutro", 2, 1),
    WEEK("7 dni", 7, 0),
    MONTH("Miesiąc", 30, 0),
    YEAR("Rok", 365, 0),
    ;

    val shownDays: Int get() = simulatedDays - firstShownDay
}

data class EnergyState(
    val period: EnergyPeriod,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val zone: ZoneId,
    val settings: AppSettings,
    /** Simulation with the configured battery (or without, if none). */
    val result: SimulationResult,
    /** Same period without a battery; null when no battery is configured. */
    val withoutBattery: SimulationResult?,
)

/** Yearly cost comparison (365 days from today). */
data class CostState(
    val prices: EnergyPrices,
    val comparison: CostComparison,
    val hasBattery: Boolean,
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
    private val simulator: EnergyFlowSimulator = EnergyFlowSimulator(estimator),
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

    private val _energyPeriod = MutableStateFlow(EnergyPeriod.TODAY)
    val energyPeriod: StateFlow<EnergyPeriod> = _energyPeriod.asStateFlow()

    val energy: StateFlow<EnergyState?> = combine(validSettings, today, _energyPeriod) { s, date, period ->
        buildEnergy(s, date, period)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Yearly costs; null when no backup energy price is set. */
    val costs: StateFlow<CostState?> = combine(
        validSettings.map { CostInputs(it) }.distinctUntilChanged(),
        today,
    ) { inputs, date -> buildCosts(inputs.settings, date) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Settings that influence costs; theme or GPS status changes do not trigger a yearly re-run. */
    private data class CostInputs(val settings: AppSettings) {
        override fun equals(other: Any?) = other is CostInputs && key(settings) == key(other.settings)
        override fun hashCode() = key(settings).hashCode()
        private fun key(s: AppSettings) = listOf(s.system, s.location, s.activeBattery, s.consumption, s.prices)
    }

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
            battery = s.activeBattery?.let { batteryNow(s, it, date, zone, now) },
        )
    }

    private fun batteryNow(s: AppSettings, battery: BatteryStorage, date: LocalDate, zone: ZoneId, now: Instant): BatteryNow {
        val result = simulator.simulate(s.system, s.location, date, 1, zone, s.consumption.profile(), battery)
        val current = result.steps.lastOrNull { !it.start.isAfter(now) } ?: result.steps.first()
        return BatteryNow(
            socPercent = current.socPercent,
            storedKwh = battery.storedKwh(current.socPercent),
            usableKwh = battery.usableCapacityKwh,
            chargeKw = current.chargePowerKw,
            dischargeKw = current.dischargePowerKw,
        )
    }

    private fun buildEnergy(s: AppSettings, today: LocalDate, period: EnergyPeriod): EnergyState {
        val zone = zoneProvider()
        val series = simulator.pvSeries(s.system, s.location, today, period.simulatedDays, zone)
        val profile = s.consumption.profile()
        val battery = s.activeBattery
        val from = today.plusDays(period.firstShownDay.toLong())
        val to = today.plusDays(period.simulatedDays - 1L)
        val result = simulator.run(series, profile, battery).slice(from, to)
        val without = if (battery != null) simulator.run(series, profile, null).slice(from, to) else null
        return EnergyState(period, from, to, zone, s, result, without)
    }

    private fun buildCosts(s: AppSettings, today: LocalDate): CostState? {
        if (s.prices.backupPricePerKwh == null) return null
        val series = simulator.pvSeries(s.system, s.location, today, EnergyPeriod.YEAR.simulatedDays, zoneProvider())
        val profile = s.consumption.profile()
        val without = simulator.run(series, profile, null)
        val with = s.activeBattery?.let { simulator.run(series, profile, it) } ?: without
        val comparison = CostComparison.of(without, with, s.prices) ?: return null
        return CostState(s.prices, comparison, s.activeBattery != null)
    }

    fun setEnergyPeriod(period: EnergyPeriod) {
        _energyPeriod.value = period
    }

    /** Saves the battery; returns false (and saves nothing) for an invalid configuration. */
    suspend fun saveBattery(enabled: Boolean, battery: BatteryStorage): Boolean =
        settingsRepository.setBattery(enabled, battery)

    fun setBatteryEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setBatteryEnabled(enabled) }
    }

    suspend fun saveConsumption(consumption: ConsumptionSettings): Boolean =
        settingsRepository.setConsumption(consumption)

    suspend fun savePrices(prices: EnergyPrices): Boolean = settingsRepository.setPrices(prices)

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
