package com.solartracker.pro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.solartracker.pro.core.energy.BackupSource
import com.solartracker.pro.core.energy.BatteryStorage
import com.solartracker.pro.core.live.LiveSolarCalculator
import com.solartracker.pro.core.live.SecondTicker
import com.solartracker.pro.core.live.SunPath
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
import com.solartracker.pro.core.weather.MonthlyClimate
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
import com.solartracker.pro.core.weather.WeatherSource
import com.solartracker.pro.data.AppSettings
import com.solartracker.pro.data.ConsumptionSettings
import com.solartracker.pro.data.LocationProvider
import com.solartracker.pro.data.LocationRepository
import com.solartracker.pro.data.LocationSource
import com.solartracker.pro.data.SettingsRepository
import com.solartracker.pro.data.ThemeMode
import com.solartracker.pro.data.WeatherProvider
import com.solartracker.pro.data.WeatherRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.collectLatest
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
    val weather: WeatherNow? = null,
)

/** Weather data state: what was loaded and whether a refresh is running. */
data class WeatherState(
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val forecast: WeatherForecast? = null,
    val climate: MonthlyClimate? = null,
    val error: String? = null,
    val updatedAt: Instant? = null,
)

/** Weather shown on the dashboard for the current moment. */
data class WeatherNow(
    val state: WeatherState,
    /** Source used for the current hour. */
    val source: WeatherSource,
    val cloudCoverPercent: Double?,
    val temperatureC: Double?,
)

/** Short Polish description of the data behind the estimates. */
fun describeSources(state: WeatherState): String = when {
    !state.enabled -> "model bezchmurnego nieba (pogoda wyłączona)"
    state.forecast != null && state.climate != null -> "prognoza pogody na najbliższe dni + średnie klimatyczne"
    state.forecast != null -> "prognoza pogody (dalej: bezchmurne niebo)"
    state.climate != null -> "średnie klimatyczne (brak prognozy)"
    else -> "model bezchmurnego nieba (brak danych pogodowych)"
}

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
    val sourceDescription: String = "",
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
    val sourceDescription: String = "",
) {
    val best: TiltEstimate? get() = estimates.maxByOrNull { it.energyKwh }
}

data class MonthlyState(
    val year: Int,
    val system: PvSystem,
    val estimates: List<MonthlyEstimate>,
    val sourceDescription: String = "",
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
    private val weatherProvider: WeatherProvider? = null,
    private val zoneProvider: () -> ZoneId = { ZoneId.systemDefault() },
    private val clock: () -> Instant = { Instant.now() },
    /** Source of live ticks; by default one per wall-clock second (see [SecondTicker]). */
    private val liveTicker: (clock: () -> Instant) -> Flow<Instant> = { c -> SecondTicker(c).ticks() },
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

    private val _weather = MutableStateFlow(WeatherState())
    val weather: StateFlow<WeatherState> = _weather.asStateFlow()

    /** Shared PV estimator + energy simulator; rebuilt when the weather data changes. */
    private class Engine(val estimator: PvEstimator, val model: WeatherAwareIrradianceModel?, val weather: WeatherState) {
        val simulator = EnergyFlowSimulator(estimator)
    }

    private val engine: Flow<Engine> = combine(
        validSettings.map { it.location }.distinctUntilChanged(),
        _weather,
    ) { location, w ->
        if (!w.enabled) {
            Engine(PvEstimator(), null, w)
        } else {
            val model = WeatherAwareIrradianceModel(location, w.forecast, w.climate)
            model.monthlyClearnessFactor // precompute off the main thread
            Engine(PvEstimator(model), model, w)
        }
    }.flowOn(Dispatchers.Default)

    /** Settings and engine together, so every estimate follows the same weather. */
    private val inputs: Flow<Pair<AppSettings, Engine>> = combine(validSettings, engine) { s, e -> s to e }

    init {
        viewModelScope.launch {
            validSettings.map { it.location to it.weatherEnabled }.distinctUntilChanged().collectLatest { (location, enabled) ->
                if (!enabled || weatherProvider == null) {
                    _weather.value = WeatherState(enabled = false)
                    return@collectLatest
                }
                _weather.value = WeatherState(enabled = true, loading = true)
                while (true) {
                    loadWeather(location, force = false)
                    delay(WEATHER_REFRESH_MS)
                }
            }
        }
    }

    private suspend fun loadWeather(location: GeoLocation, force: Boolean) {
        val provider = weatherProvider ?: return
        _weather.value = _weather.value.copy(enabled = true, loading = true)
        val result = provider.load(location, force)
        _weather.value = WeatherState(
            enabled = true,
            loading = false,
            forecast = result.forecast,
            climate = result.climate,
            error = result.error,
            updatedAt = result.forecast?.fetchedAt ?: _weather.value.updatedAt,
        )
    }

    // --- Live Solar: local calculations every second, weather data only from the cache/engine ---

    private val _livePaused = MutableStateFlow(false)

    /** True when the user paused live tracking with the ⏸ button. */
    val livePaused: StateFlow<Boolean> = _livePaused.asStateFlow()

    private val _liveActive = MutableStateFlow(false)

    /** True while the 1-second ticker is running (screen visible, app in foreground, not paused). */
    val liveActive: StateFlow<Boolean> = _liveActive.asStateFlow()

    @Volatile
    private var liveDayCache: Pair<List<Any?>, SimulationResult?>? = null

    /**
     * Live state, recomputed every second. The ticker only runs while somebody collects this flow:
     * the Live screen collects it with collectAsStateWithLifecycle, so leaving the screen or moving
     * the app to the background cancels the ticker at once (WhileSubscribed(0)); returning starts
     * it again. No service, wake lock or network request is involved.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val live: StateFlow<LiveUiState?> = _livePaused
        .flatMapLatest { paused ->
            if (paused) {
                emptyFlow()
            } else {
                // One computation per tick, in order (no conflation of ticks); the latest settings and
                // weather engine are read from a state that follows `inputs`.
                channelFlow {
                    val latest = inputs.stateIn(this)
                    liveTicker(clock).collect { now ->
                        val (s, e) = latest.value
                        send(buildLive(now, s, e))
                    }
                }
                    .onStart { _liveActive.value = true }
                    .onCompletion { _liveActive.value = false }
            }
        }
        .flowOn(Dispatchers.Default)
        // Shared off the main thread: every second is published on time even when the UI thread
        // is briefly busy (e.g. first composition); same Job as viewModelScope, so it is still
        // cancelled with the ViewModel and stopped when the screen stops collecting.
        .stateIn(CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default), SharingStarted.WhileSubscribed(0), null)

    /** Today's sun path; recomputed when the date or location changes, not every second. */
    val liveSunPath: StateFlow<SunPathUi?> = combine(
        validSettings.map { it.location }.distinctUntilChanged(),
        today,
    ) { location, date -> SunPath.forDay(location, date, zoneProvider()).toUi(zoneProvider()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setLivePaused(paused: Boolean) {
        _livePaused.value = paused
    }

    private fun buildLive(now: Instant, s: AppSettings, e: Engine): LiveUiState {
        val zone = zoneProvider()
        val battery = s.activeBattery
        val consumption = s.consumption.profile()
        val soc = battery?.let { liveDaySimulation(s, e, battery, now.atZone(zone).toLocalDate(), zone)?.socAt(now) }
        val sourceAt: (Instant) -> WeatherSource = e.model?.let { m -> { t: Instant -> m.sourceAt(t) } } ?: { WeatherSource.CLEAR_SKY }
        val snapshot = LiveSolarCalculator(e.estimator, e.simulator, sourceAt)
            .snapshot(now, zone, s.location, s.system, consumption, battery, soc)
        return snapshot.toUi(
            locationName = s.locationName,
            weatherEnabled = e.weather.enabled,
            usableKwh = battery?.usableCapacityKwh,
            backupLabel = if (s.prices.backupSource == BackupSource.GENERATOR) "Agregat" else "Sieć",
        )
    }

    /** Today's 15-minute simulation for the live SOC, cached until any input changes. */
    private fun liveDaySimulation(s: AppSettings, e: Engine, battery: BatteryStorage, date: LocalDate, zone: ZoneId): SimulationResult? {
        val key = listOf(date, zone, s.system, s.location, battery, s.consumption, e)
        liveDayCache?.let { (k, v) -> if (k == key) return v }
        val result = e.simulator.simulate(s.system, s.location, date, 1, zone, s.consumption.profile(), battery)
        liveDayCache = key to result
        return result
    }

    /** Forces a fresh download of the forecast. */
    fun refreshWeather() {
        val s = settings.value ?: return
        if (!s.weatherEnabled || _weather.value.loading) return
        viewModelScope.launch { loadWeather(s.location, force = true) }
    }

    fun setWeatherEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setWeatherEnabled(enabled) }
    }

    val dashboard: StateFlow<DashboardState?> = combine(inputs, ticker) { (s, e), now -> buildDashboard(s, e, now) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val tiltComparison: StateFlow<TiltComparisonState?> = combine(inputs, today) { (s, e), date ->
        TiltComparisonState(
            date = date,
            system = s.system,
            estimates = e.estimator.compareTilts(s.system, s.location, date, zoneProvider()),
            sourceDescription = describeSources(e.weather),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val monthly: StateFlow<MonthlyState?> = combine(
        inputs,
        today.map { it.year }.distinctUntilChanged(),
    ) { (s, e), year ->
        MonthlyState(
            year = year,
            system = s.system,
            estimates = e.estimator.monthlyEnergy(s.system, s.location, year, zoneProvider()),
            sourceDescription = describeSources(e.weather),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _energyPeriod = MutableStateFlow(EnergyPeriod.TODAY)
    val energyPeriod: StateFlow<EnergyPeriod> = _energyPeriod.asStateFlow()

    val energy: StateFlow<EnergyState?> = combine(inputs, today, _energyPeriod) { (s, e), date, period ->
        buildEnergy(s, e, date, period)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Yearly costs; null when no backup energy price is set. */
    val costs: StateFlow<CostState?> = combine(
        inputs.map { (s, e) -> CostInputs(s, e) }.distinctUntilChanged(),
        today,
    ) { c, date -> buildCosts(c.settings, c.engine, date) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Settings that influence costs; theme or GPS status changes do not trigger a yearly re-run. */
    private class CostInputs(val settings: AppSettings, val engine: Engine) {
        override fun equals(other: Any?) = other is CostInputs && key() == other.key()
        override fun hashCode() = key().hashCode()
        private fun key() = listOf(
            settings.system, settings.location, settings.activeBattery, settings.consumption, settings.prices,
            engine.weather.enabled, engine.weather.forecast?.fetchedAt, engine.weather.climate,
        )
    }

    private val _gpsStatus = MutableStateFlow<GpsStatus>(GpsStatus.Idle)
    val gpsStatus: StateFlow<GpsStatus> = _gpsStatus.asStateFlow()

    private fun buildDashboard(s: AppSettings, e: Engine, now: Instant): DashboardState {
        val estimator = e.estimator
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
            battery = s.activeBattery?.let { batteryNow(s, e, it, date, zone, now) },
            weather = if (e.weather.enabled) {
                val hour = e.weather.forecast?.at(now)
                WeatherNow(
                    state = e.weather,
                    source = e.model?.sourceAt(now) ?: WeatherSource.CLEAR_SKY,
                    cloudCoverPercent = hour?.cloudCoverPercent,
                    temperatureC = hour?.temperatureC,
                )
            } else {
                null
            },
        )
    }

    private fun batteryNow(s: AppSettings, e: Engine, battery: BatteryStorage, date: LocalDate, zone: ZoneId, now: Instant): BatteryNow {
        val result = e.simulator.simulate(s.system, s.location, date, 1, zone, s.consumption.profile(), battery)
        val current = result.steps.lastOrNull { !it.start.isAfter(now) } ?: result.steps.first()
        return BatteryNow(
            socPercent = current.socPercent,
            storedKwh = battery.storedKwh(current.socPercent),
            usableKwh = battery.usableCapacityKwh,
            chargeKw = current.chargePowerKw,
            dischargeKw = current.dischargePowerKw,
        )
    }

    private fun buildEnergy(s: AppSettings, e: Engine, today: LocalDate, period: EnergyPeriod): EnergyState {
        val zone = zoneProvider()
        val simulator = e.simulator
        val series = simulator.pvSeries(s.system, s.location, today, period.simulatedDays, zone)
        val profile = s.consumption.profile()
        val battery = s.activeBattery
        val from = today.plusDays(period.firstShownDay.toLong())
        val to = today.plusDays(period.simulatedDays - 1L)
        val result = simulator.run(series, profile, battery).slice(from, to)
        val without = if (battery != null) simulator.run(series, profile, null).slice(from, to) else null
        return EnergyState(period, from, to, zone, s, result, without, describeSources(e.weather))
    }

    private fun buildCosts(s: AppSettings, e: Engine, today: LocalDate): CostState? {
        if (s.prices.backupPricePerKwh == null) return null
        val simulator = e.simulator
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

    fun setManualLocation(latitude: Double, longitude: Double, name: String, elevationM: Double = 0.0) {
        viewModelScope.launch {
            settingsRepository.setLocation(GeoLocation(latitude, longitude, elevationM), name, LocationSource.MANUAL)
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
        const val WEATHER_REFRESH_MS = 60 * 60_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        const val GPS_LOCATION_NAME = "GPS"

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY]!!
                MainViewModel(SettingsRepository(app), LocationRepository(app), WeatherRepository(app))
            }
        }
    }
}
