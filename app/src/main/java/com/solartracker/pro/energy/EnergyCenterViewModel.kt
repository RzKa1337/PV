package com.solartracker.pro.energy

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solartracker.pro.core.analytics.Alert
import com.solartracker.pro.core.analytics.AlertManager
import com.solartracker.pro.core.analytics.AnomalyDetector
import com.solartracker.pro.core.analytics.AnomalyInput
import com.solartracker.pro.core.analytics.CalibrationEngine
import com.solartracker.pro.core.analytics.CalibrationResult
import com.solartracker.pro.core.analytics.ComparisonContext
import com.solartracker.pro.core.analytics.HistorySample
import com.solartracker.pro.core.analytics.ModelComparison
import com.solartracker.pro.core.analytics.ModelComparisonResult
import com.solartracker.pro.core.analytics.RealEnergyFlow
import com.solartracker.pro.core.analytics.TelemetryAggregator
import com.solartracker.pro.core.forecast.AdvisorAnswer
import com.solartracker.pro.core.forecast.AdvisorContext
import com.solartracker.pro.core.forecast.AdvisorQuestion
import com.solartracker.pro.core.forecast.BatteryPrediction
import com.solartracker.pro.core.forecast.BatteryPredictor
import com.solartracker.pro.core.forecast.DayProductionForecast
import com.solartracker.pro.core.forecast.EnergyForecastEngine
import com.solartracker.pro.core.forecast.EnergyForecastRow
import com.solartracker.pro.core.forecast.LoadForecaster
import com.solartracker.pro.core.forecast.PredictivePvEngine
import com.solartracker.pro.core.forecast.ShortTermForecast
import com.solartracker.pro.core.forecast.SolarAdvisor
import com.solartracker.pro.core.inverter.ConnectionState
import com.solartracker.pro.core.inverter.Freshness
import com.solartracker.pro.core.inverter.InverterConfig
import com.solartracker.pro.core.inverter.InverterConnectionManager
import com.solartracker.pro.core.inverter.InverterInfo
import com.solartracker.pro.core.inverter.InverterRepository
import com.solartracker.pro.core.inverter.InverterTelemetry
import com.solartracker.pro.core.inverter.LinkStatus
import com.solartracker.pro.core.inverter.TelemetryField
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.shading.DayShading
import com.solartracker.pro.core.shading.GeocodeResult
import com.solartracker.pro.core.shading.LatLon
import com.solartracker.pro.core.shading.LocalFrame
import com.solartracker.pro.core.shading.LocationAccuracy
import com.solartracker.pro.core.shading.MonthShading
import com.solartracker.pro.core.shading.Obstacle
import com.solartracker.pro.core.shading.ObstacleMerger
import com.solartracker.pro.core.shading.PvArrayGeometry
import com.solartracker.pro.core.shading.ShadeSnapshot
import com.solartracker.pro.core.shading.ShadingAnalysisEngine
import com.solartracker.pro.core.shading.ShadingConfidence
import com.solartracker.pro.core.shading.ShadingForecastService
import com.solartracker.pro.core.shading.ShadingSite
import com.solartracker.pro.core.shading.ShadowForecast
import com.solartracker.pro.core.solar.GeoLocation
import com.solartracker.pro.core.solar.SolarCalculator
import com.solartracker.pro.core.weather.MonthlyClimate
import com.solartracker.pro.core.weather.WeatherAwareIrradianceModel
import com.solartracker.pro.core.weather.WeatherForecast
import com.solartracker.pro.data.AppSettings
import com.solartracker.pro.data.LocationSource
import com.solartracker.pro.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Live part of the Energy Center. */
data class LiveState(
    val info: InverterInfo? = null,
    val capabilities: Set<TelemetryField> = emptySet(),
    val connection: ConnectionState = ConnectionState(),
    val telemetry: InverterTelemetry? = null,
    val freshness: Freshness = Freshness.NONE,
    val flow: RealEnergyFlow? = null,
    val now: Instant = Instant.now(),
)

data class ModelState(
    val modelKw: Double? = null,
    val unshadedKw: Double? = null,
    val comparison: ModelComparisonResult? = null,
    val calibration: CalibrationResult? = null,
)

data class ForecastState(
    val today: DayProductionForecast? = null,
    val tomorrow: DayProductionForecast? = null,
    val shortTerm: List<ShortTermForecast> = emptyList(),
    val battery: BatteryPrediction? = null,
    val timeline: List<EnergyForecastRow> = emptyList(),
    val producedTodayKwh: Double? = null,
    val updatedAt: Instant? = null,
)

data class ShadingState(
    val ready: Boolean = false,
    val reason: String? = null,
    val engine: ShadingAnalysisEngine? = null,
    val current: ShadeSnapshot? = null,
    val today: DayShading? = null,
    val todayEvents: List<ShadowForecast> = emptyList(),
    val tomorrow: DayShading? = null,
    val next: ShadowForecast? = null,
    val year: List<MonthShading> = emptyList(),
    val confidence: ShadingConfidence? = null,
    val computing: Boolean = false,
)

class EnergyCenterViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val settingsRepo = SettingsRepository(context)
    val store = EnergySettingsStore(context)
    private val shadingRepo = ShadingRepository(context)
    private val db by lazy { HistoryDatabase(context) }
    private val repository = InverterRepository(usbTransportFactory = { UsbSerialTransport(context) })

    val settings: StateFlow<AppSettings?> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val inverterConfig: StateFlow<InverterConfig?> = store.inverter.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val siteConfig: StateFlow<SiteConfig?> = store.site.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _mapData = MutableStateFlow<MapDataCache?>(null)
    val mapData: StateFlow<MapDataCache?> = _mapData.asStateFlow()

    private val _weather = MutableStateFlow<Pair<WeatherForecast?, MonthlyClimate?>>(null to null)

    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()
    private val _model = MutableStateFlow(ModelState())
    val model: StateFlow<ModelState> = _model.asStateFlow()
    private val _forecast = MutableStateFlow(ForecastState())
    val forecast: StateFlow<ForecastState> = _forecast.asStateFlow()
    private val _shading = MutableStateFlow(ShadingState())
    val shading: StateFlow<ShadingState> = _shading.asStateFlow()
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _search = MutableStateFlow<List<GeocodeResult>>(emptyList())
    val searchResults: StateFlow<List<GeocodeResult>> = _search.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var manager: InverterConnectionManager? = null
    private var monitorJob: Job? = null
    private var monitoring = false
    private val aggregator = TelemetryAggregator(Duration.ofSeconds(30))
    private val calibration = CalibrationEngine()
    private val alertManager = AlertManager()
    private val lock = Mutex()
    private var previousTelemetry: InverterTelemetry? = null
    private var loadForecaster: LoadForecaster? = null

    init {
        viewModelScope.launch { _mapData.value = shadingRepo.load() }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { calibration.load(db.calibration(Instant.now().minus(Duration.ofDays(30)))); db.prune(Instant.now()) }
            _model.value = _model.value.copy(calibration = calibration.result())
        }
        // Rebuild the shading model when its inputs change.
        viewModelScope.launch {
            combine(settings, siteConfig, mapData) { s, site, map -> Triple(s, site, map) }
                .distinctUntilChanged()
                .collectLatest { (s, site, map) -> if (s != null && site != null && map != null) rebuildShading(s, site, map) }
        }
        // Restart monitoring when the inverter configuration changes.
        viewModelScope.launch { inverterConfig.collect { if (monitoring) restartMonitoring() } }
        // Clock for freshness labels and periodic forecasts.
        viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val now = Instant.now()
                _live.value = _live.value.copy(now = now, freshness = manager?.freshness(now) ?: if (_live.value.telemetry != null) Freshness.LAST_KNOWN else Freshness.NONE)
                if (tick % 60 == 0) launch(Dispatchers.Default) { recomputeForecast() }
                tick++
                delay(1000)
            }
        }
    }

    fun setWeather(forecast: WeatherForecast?, climate: MonthlyClimate?) {
        if (_weather.value != (forecast to climate)) {
            _weather.value = forecast to climate
            viewModelScope.launch(Dispatchers.Default) { recomputeForecast() }
        }
    }

    /** Called by the screen: monitoring runs only while the Energy Center is visible (battery friendly). */
    fun setMonitoring(active: Boolean) {
        if (active == monitoring) return
        monitoring = active
        if (active) restartMonitoring() else stopMonitoring()
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        aggregator.flush()?.let { s -> viewModelScope.launch(Dispatchers.IO) { persist(s) } }
    }

    private fun restartMonitoring() {
        monitorJob?.cancel()
        val config = inverterConfig.value ?: return
        if (!config.enabled) {
            manager = null
            _live.value = LiveState()
            return
        }
        val provider = runCatching { repository.createProvider(config) }.getOrElse {
            _message.value = "Konfiguracja falownika: ${it.message}"
            return
        }
        val m = InverterConnectionManager(
            provider, config.pollSettings,
            onTelemetry = { t -> viewModelScope.launch(Dispatchers.Default) { onTelemetry(t) } },
            onEvent = { e -> _events.value = (listOf("${java.time.LocalTime.now().withNano(0)} $e") + _events.value).take(50) },
        )
        manager = m
        _live.value = LiveState(info = provider.info, capabilities = provider.capabilities)
        monitorJob = viewModelScope.launch {
            launch { m.state.collect { s -> _live.value = _live.value.copy(connection = s); if (s.status == LinkStatus.OFFLINE) onTelemetryMissing() } }
            m.run()
        }
    }

    fun refreshNow() {
        viewModelScope.launch { manager?.pollOnce() }
    }

    private suspend fun onTelemetry(t: InverterTelemetry) = lock.withLock {
        val flow = RealEnergyFlow.from(t)
        _live.value = _live.value.copy(telemetry = t, flow = flow)
        aggregator.add(t).forEach { withContext(Dispatchers.IO) { persist(it) } }

        val s = settings.value ?: return@withLock
        val site = siteConfig.value
        val inv = inverterConfig.value
        val weather = WeatherAwareIrradianceModel(s.location, _weather.value.first, _weather.value.second)
        val estimate = PvEstimator(weather).pointEstimate(s.system, s.location, t.timestamp)
        val shadeFactor = _shading.value.engine?.snapshot(s.system, t.timestamp, estimate)?.powerFactor ?: 1.0
        val modelKw = estimate.powerKw * shadeFactor
        val realKw = t.pv.powerW?.div(1000.0)
        val battery = s.activeBattery
        val comparison = realKw?.let {
            ModelComparison.compare(
                modelKw, it,
                ComparisonContext(
                    sunElevationDeg = estimate.sun.elevationDeg, modelUnshadedKw = estimate.powerKw, shadingLossKw = estimate.powerKw - modelKw,
                    cloudCoverPercent = _weather.value.first?.at(t.timestamp)?.cloudCoverPercent, cellTemperatureC = estimate.cellTemperatureC,
                    inverterRatedKw = inv?.ratedPowerW?.div(1000.0), batterySocPercent = t.battery.socPercent,
                    batteryMaxSocPercent = battery?.maxSocPercent ?: 100.0, gridExportAllowed = site?.gridExportAllowed,
                    loadKw = t.load.powerW?.div(1000.0), batteryChargeKw = t.battery.chargePowerW?.div(1000.0),
                ),
            )
        }
        if (realKw != null && comparison != null &&
            calibration.offer(t.timestamp, realKw, modelKw, s.system.peakPowerKw, estimate.sun.elevationDeg, comparison.curtailed)
        ) {
            withContext(Dispatchers.IO) { runCatching { db.addCalibration(calibration.samples().last()) } }
        }
        _model.value = ModelState(modelKw, estimate.powerKw, comparison, calibration.result())

        val anomalies = AnomalyDetector.detect(
            AnomalyInput(
                now = t.timestamp, telemetry = t, link = _live.value.connection.status,
                expectedUnshadedKw = estimate.powerKw * calibration.appliedFactor(), expectedShadedKw = modelKw * calibration.appliedFactor(),
                poaWm2 = estimate.poa, curtailed = comparison?.curtailed ?: false, batteryMinSocPercent = battery?.minSocPercent,
                batteryNominalVoltage = site?.batteryVoltage ?: 48.0, typicalLoadKw = loadForecaster?.typicalKw(t.timestamp),
                previous = previousTelemetry, batteryCapacityKwh = battery?.usableCapacityKwh,
            ),
        ) + shadingAnomalies()
        previousTelemetry = t
        publishAlerts(alertManager.update(t.timestamp, anomalies))
    }

    private fun onTelemetryMissing() {
        val now = Instant.now()
        publishAlerts(alertManager.update(now, AnomalyDetector.detect(
            AnomalyInput(now, null, LinkStatus.OFFLINE, null, null, null, false, null, typicalLoadKw = null, previous = null, batteryCapacityKwh = null),
        )))
    }

    private fun shadingAnomalies() = buildList {
        val c = _shading.value.confidence ?: return@buildList
        if (c.missing.any { it.startsWith("Brak wysokości") }) add(com.solartracker.pro.core.analytics.Anomaly(com.solartracker.pro.core.analytics.AnomalyType.MISSING_BUILDING_HEIGHT, c.missing.first { it.startsWith("Brak wysokości") }))
        if (c.score < 0.4) add(com.solartracker.pro.core.analytics.Anomaly(com.solartracker.pro.core.analytics.AnomalyType.LOW_CONFIDENCE_SHADING, "Pewność ${(c.score * 100).toInt()}%"))
    }

    private fun publishAlerts(list: List<Alert>) {
        _alerts.value = list
        list.filter { it.notify }.forEach { EnergyNotifications.alert(context, it) }
    }

    private fun persist(sample: HistorySample) {
        runCatching {
            db.insert(sample)
            val period = Duration.ofMinutes(15)
            val start = Instant.ofEpochSecond(sample.start.epochSecond - Math.floorMod(sample.start.epochSecond, period.seconds))
            TelemetryAggregator.summarize(db.history(start, start.plus(period)), period).forEach { db.insert(it, summary = true) }
        }
    }

    /** Recomputes forecasts (every minute and when inputs change). */
    private suspend fun recomputeForecast() {
        val s = settings.value ?: return
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        val history = withContext(Dispatchers.IO) { runCatching { db.history(now.minus(Duration.ofDays(28)), now, summary = true) }.getOrDefault(emptyList()) }
        val producedToday = withContext(Dispatchers.IO) {
            runCatching { db.history(today.atStartOfDay(zone).toInstant(), now).takeIf { it.isNotEmpty() }?.sumOf { it.pvEnergyKwh } }.getOrNull()
        }
        val weather = WeatherAwareIrradianceModel(s.location, _weather.value.first, _weather.value.second)
        val cal = calibration.result()
        val inv = inverterConfig.value
        val pv = PredictivePvEngine(
            s.location, s.system, weather, _shading.value.engine,
            calibrationFactor = calibration.appliedFactor(), calibrationConfidence = if (cal.ready) cal.confidence else 0.0,
            inverterLimitKw = inv?.takeIf { it.enabled }?.ratedPowerW?.div(1000.0),
        )
        val load = LoadForecaster(history, zone, s.consumption.profile(), now).also { loadForecaster = it }
        val battery = s.activeBattery?.let { BatteryPredictor(it, zone) }
        val engine = EnergyForecastEngine(pv, load, battery, zone)
        val live = _live.value
        val fresh = live.freshness == Freshness.LIVE
        val nowcast = _model.value.comparison?.takeIf { fresh && !it.curtailed && it.modelKw > 0.2 }?.let { it.realKw / it.modelKw }
        val soc = live.telemetry?.battery?.socPercent
        val socKind = when {
            soc == null -> DataKind.UNKNOWN
            fresh -> DataKind.MEASURED
            else -> DataKind.LAST_KNOWN
        }
        _forecast.value = ForecastState(
            today = engine.day(today, now, producedToday, nowcast),
            tomorrow = engine.day(today.plusDays(1), now, null),
            shortTerm = engine.shortTerm(now, nowcast),
            battery = soc?.let { engine.battery(now, it, socKind, nowcast) },
            timeline = engine.timeline(now, 24, nowcast),
            producedTodayKwh = producedToday,
            updatedAt = now,
        )
    }

    private suspend fun rebuildShading(s: AppSettings, site: SiteConfig, map: MapDataCache) {
        if (!site.locationConfirmed) {
            _shading.value = ShadingState(reason = "Potwierdź lokalizację instalacji, aby obliczyć zacienienie")
            return
        }
        _shading.value = _shading.value.copy(computing = true)
        val result = withContext(Dispatchers.Default) {
            val center = LatLon(s.location.latitude, s.location.longitude)
            val nearCache = map.center?.let { LocalFrame(center).toLocal(it).distance < 200 } ?: false
            val strings = site.strings.coerceAtMost(site.panelCount)
            val geometry = PvArrayGeometry(
                tiltDeg = s.system.tiltDeg, azimuthDeg = s.system.azimuthDeg, baseHeightM = site.panelBaseHeightM,
                rows = site.rows, columns = site.columns, panelWidthM = site.panelWidthM, panelLengthM = site.panelLengthM,
                bypassDiodes = site.bypassDiodes,
                stringOfPanel = if (strings > 1) (0 until site.rows * site.columns).map { (it * strings / (site.rows * site.columns)).coerceAtMost(strings - 1) } else null,
                mpptOfString = (0 until strings).map { it % site.mpptCount },
            )
            val obstacles = ObstacleMerger.merge(if (nearCache) map.automatic else emptyList(), map.user)
            val shadingSite = ShadingSite(s.location, site.locationAccuracy, true, geometry, obstacles, map.terrain.takeIf { nearCache }, map.loaded && nearCache)
            val engine = ShadingAnalysisEngine(shadingSite)
            val service = ShadingForecastService(engine, s.system, zone)
            val now = Instant.now()
            val today = now.atZone(zone).toLocalDate()
            val (day, events) = service.forDay(today)
            ShadingState(
                ready = true, engine = engine, current = service.current(now), today = day, todayEvents = events,
                tomorrow = engine.day(s.system, today.plusDays(1), zone), next = service.nextEvent(now),
                year = service.year(today.year), confidence = engine.confidence(), computing = false,
                reason = if (!nearCache) "Brak danych mapowych dla tej lokalizacji – pobierz budynki i teren" else null,
            )
        }
        _shading.value = result
        recomputeForecast()
    }

    /** Shading for any chosen date (on demand). */
    suspend fun shadingFor(date: LocalDate): Pair<DayShading, List<ShadowForecast>>? {
        val engine = _shading.value.engine ?: return null
        val s = settings.value ?: return null
        return withContext(Dispatchers.Default) { ShadingForecastService(engine, s.system, zone).forDay(date) }
    }

    fun snapshotAt(instant: Instant): ShadeSnapshot? {
        val engine = _shading.value.engine ?: return null
        val s = settings.value ?: return null
        return engine.snapshot(s.system, instant)
    }

    // ---- configuration actions ----

    fun saveInverter(config: InverterConfig): List<String> {
        val errors = config.validate()
        if (errors.isEmpty()) viewModelScope.launch { store.setInverter(config) }
        return errors
    }

    fun saveSite(config: SiteConfig): List<String> {
        val errors = config.validate()
        if (errors.isEmpty()) viewModelScope.launch { store.setSite(config) }
        return errors
    }

    fun search(query: String) {
        if (query.trim().length < 2) return
        viewModelScope.launch {
            _busy.value = true
            _search.value = runCatching { shadingRepo.search(query) }.getOrElse {
                _message.value = "Wyszukiwanie: ${it.message ?: "brak połączenia"}"
                emptyList()
            }
            if (_search.value.isEmpty() && _message.value == null) _message.value = "Nie znaleziono: $query"
            _busy.value = false
        }
    }

    /** Sets the installation point (from search or map); needs confirmation before shading is computed. */
    fun setLocation(point: LatLon, name: String, accuracy: LocationAccuracy, elevationM: Double? = null) {
        viewModelScope.launch {
            val current = settings.value?.location
            settingsRepo.setLocation(GeoLocation(point.lat, point.lon, elevationM ?: current?.elevationM ?: 0.0), name, LocationSource.MANUAL)
            val site = siteConfig.value ?: SiteConfig()
            store.setSite(site.copy(locationAccuracy = accuracy, locationConfirmed = false))
            _search.value = emptyList()
        }
    }

    fun confirmLocation() {
        viewModelScope.launch { store.setSite((siteConfig.value ?: SiteConfig()).copy(locationConfirmed = true)) }
    }

    fun downloadMapData() {
        val s = settings.value ?: return
        val site = siteConfig.value ?: return
        if (!site.locationConfirmed) {
            _message.value = "Najpierw potwierdź lokalizację"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            runCatching { shadingRepo.download(LatLon(s.location.latitude, s.location.longitude), site.obstacleRadiusM) }
                .onSuccess {
                    _mapData.value = it
                    val unknown = it.automatic.count { o -> !o.height.known }
                    _message.value = "Pobrano ${it.automatic.size} obiektów" + (if (unknown > 0) ", $unknown bez wysokości – uzupełnij" else "") +
                        (if (it.terrain == null) ". Teren: brak danych" else ". Teren: ${it.terrain.source}")
                }
                .onFailure { _message.value = "Pobieranie danych mapowych nie powiodło się: ${it.message}" }
            _busy.value = false
        }
    }

    fun saveObstacle(o: Obstacle, change: String) {
        viewModelScope.launch { _mapData.value = shadingRepo.saveUserObstacle(o, change) }
    }

    fun deleteObstacle(id: String) {
        viewModelScope.launch { _mapData.value = shadingRepo.deleteUserObstacle(id) }
    }

    fun clearMapData() {
        viewModelScope.launch { _mapData.value = shadingRepo.clearDownloaded() }
    }

    fun dismissMessage() {
        _message.value = null
    }

    fun ask(question: AdvisorQuestion): AdvisorAnswer {
        val l = _live.value
        val s = settings.value
        val sh = _shading.value
        val ctx = AdvisorContext(
            now = Instant.now(), zone = zone, telemetry = l.telemetry, freshness = l.freshness, simulated = l.info?.simulated == true,
            flow = l.flow, today = _forecast.value.today, battery = _forecast.value.battery, batteryMinSoc = s?.activeBattery?.minSocPercent,
            sunsetAt = s?.let { SolarCalculator.sunTimes(it.location, LocalDate.now(zone)).sunset },
            comparison = _model.value.comparison, shadingToday = sh.todayEvents, nextShadow = sh.next, shadingConfidence = sh.confidence,
            dailyShadingLossKwh = sh.today?.lossKwh, alerts = _alerts.value,
        )
        return SolarAdvisor.answer(question, ctx)
    }

    override fun onCleared() {
        stopMonitoring()
        runCatching { db.close() }
    }
}
