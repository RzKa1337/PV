package com.solartracker.pro.energy

import com.solartracker.pro.core.anenji.AnalysisContext
import com.solartracker.pro.core.anenji.AnenjiSettingsDiff
import com.solartracker.pro.core.anenji.ConfigImpact
import com.solartracker.pro.core.anenji.ConfigurationForensics
import com.solartracker.pro.core.anenji.ForensicContext
import com.solartracker.pro.core.anenji.ForensicPeriod
import com.solartracker.pro.core.anenji.ForensicPeriodAnalyzer
import com.solartracker.pro.core.anenji.IncidentReconstruction
import com.solartracker.pro.core.anenji.IncidentReconstructor
import com.solartracker.pro.core.anenji.LongTrend
import com.solartracker.pro.core.anenji.LongTrendAnalyzer
import com.solartracker.pro.core.anenji.PeriodReport
import com.solartracker.pro.core.anenji.SystemBaselineEngine
import com.solartracker.pro.core.export.ForensicPackageExport
import com.solartracker.pro.core.inverter.ManualReference
import com.solartracker.pro.core.inverter.ReferenceCodec
import com.solartracker.pro.core.inverter.RegisterEvidence
import com.solartracker.pro.core.inverter.SmgRegisters
import com.solartracker.pro.core.inverter.ValidationMode
import com.solartracker.pro.core.anenji.AnalyzerSample
import com.solartracker.pro.core.anenji.AnalyzerSamples
import com.solartracker.pro.core.anenji.AnenjiDeepAnalyzer
import com.solartracker.pro.core.anenji.AnenjiEvent
import com.solartracker.pro.core.anenji.AnenjiSettingsSnapshot
import com.solartracker.pro.core.anenji.BatteryContext
import com.solartracker.pro.core.anenji.CommError
import com.solartracker.pro.core.anenji.CommRecord
import com.solartracker.pro.core.anenji.ContextProvider
import com.solartracker.pro.core.anenji.DataOrigin
import com.solartracker.pro.core.anenji.DeepAnalysisInput
import com.solartracker.pro.core.anenji.DeepAnalysisReport
import com.solartracker.pro.core.anenji.ImportedLog
import com.solartracker.pro.core.anenji.IncidentAnalyzer
import com.solartracker.pro.core.anenji.IncidentReport
import com.solartracker.pro.core.anenji.LogImporter
import com.solartracker.pro.core.anenji.SettingKey
import com.solartracker.pro.core.anenji.SnapshotCodec
import com.solartracker.pro.core.anenji.WhyAnalyzer
import com.solartracker.pro.core.anenji.WhyAnswer
import com.solartracker.pro.core.anenji.WhyQuestion
import com.solartracker.pro.core.export.DeepReportExport
import com.solartracker.pro.core.pv.Irradiance
import com.solartracker.pro.core.pv.PvPointEstimate
import com.solartracker.pro.core.weather.HourlyWeather
import java.time.YearMonth
import com.solartracker.pro.core.diagnostics.ConversionEfficiency
import com.solartracker.pro.core.diagnostics.DegradationAnalyzer
import com.solartracker.pro.core.diagnostics.DegradationAssessment
import com.solartracker.pro.core.diagnostics.DiagnosticInput
import com.solartracker.pro.core.diagnostics.FlowContext
import com.solartracker.pro.core.diagnostics.IrradianceBasis
import com.solartracker.pro.core.diagnostics.MpptAnalysis
import com.solartracker.pro.core.diagnostics.MpptAnalyzer
import com.solartracker.pro.core.diagnostics.MpptConfig
import com.solartracker.pro.core.diagnostics.PerformanceHistory
import com.solartracker.pro.core.diagnostics.PvDiagnosis
import com.solartracker.pro.core.diagnostics.PvDiagnosticEngine
import com.solartracker.pro.core.diagnostics.PvReality
import com.solartracker.pro.core.diagnostics.PvRealityEngine
import com.solartracker.pro.core.diagnostics.RealitySample
import com.solartracker.pro.core.diagnostics.SoilingAssessment
import com.solartracker.pro.core.diagnostics.SoilingDetector
import com.solartracker.pro.core.export.RegisterLogExport
import com.solartracker.pro.core.forecast.EnergyMissionPlanner
import com.solartracker.pro.core.forecast.MissionGoal
import com.solartracker.pro.core.forecast.MissionRequest
import com.solartracker.pro.core.forecast.MissionResult
import com.solartracker.pro.core.forecast.PvRadar
import com.solartracker.pro.core.forecast.PvRadarBuilder
import com.solartracker.pro.core.inverter.RegisterDiagnostics
import com.solartracker.pro.core.inverter.RegisterLogRecord
import com.solartracker.pro.core.inverter.RegisterQuality
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.solartracker.pro.BuildConfig
import com.solartracker.pro.core.analytics.AccuracyPeriod
import com.solartracker.pro.core.analytics.AccuracyReport
import com.solartracker.pro.core.analytics.ForecastComparison
import com.solartracker.pro.core.forecast.DayOutlook
import com.solartracker.pro.core.forecast.EnergySecurity
import com.solartracker.pro.core.forecast.EnergySecurityAnalyzer
import com.solartracker.pro.core.analytics.ForecastAccuracy
import com.solartracker.pro.core.analytics.ForecastHorizon
import com.solartracker.pro.core.analytics.HistoryPeriod
import com.solartracker.pro.core.analytics.HistoryPeriods
import com.solartracker.pro.core.analytics.PeriodTotals
import com.solartracker.pro.core.ems.DayBalance
import com.solartracker.pro.core.ems.EmsInput
import com.solartracker.pro.core.ems.EmsPlan
import com.solartracker.pro.core.ems.EnergyOptimizationEngine
import com.solartracker.pro.core.ems.EnergySlot
import com.solartracker.pro.core.energy.CoolingLoadProfile
import com.solartracker.pro.core.ems.FlexibleLoad
import com.solartracker.pro.core.ems.GeneratorConfig
import com.solartracker.pro.core.ems.PeriodBalance
import com.solartracker.pro.core.energy.BackupSource
import com.solartracker.pro.core.export.HistoryExport
import com.solartracker.pro.core.health.DayStatsBuilder
import com.solartracker.pro.core.health.ExpectedPower
import com.solartracker.pro.core.health.FaultWarning
import com.solartracker.pro.core.health.HealthInput
import com.solartracker.pro.core.health.HealthReport
import com.solartracker.pro.core.health.PredictiveFaultEngine
import com.solartracker.pro.core.health.PvHealthEngine
import com.solartracker.pro.core.analytics.DailyEnergyReport
import com.solartracker.pro.core.analytics.DailyReportBuilder
import com.solartracker.pro.core.health.PerformanceReport
import com.solartracker.pro.core.health.PredictiveAlertInput
import com.solartracker.pro.core.health.PvPerformanceAnalyzer
import com.solartracker.pro.core.pv.ClearSkyModel
import com.solartracker.pro.core.pv.LossProfile
import com.solartracker.pro.core.pv.PvArrayConfig
import com.solartracker.pro.core.pv.PvConditions
import com.solartracker.pro.core.twin.DigitalTwin
import com.solartracker.pro.core.twin.DigitalTwinBuilder
import com.solartracker.pro.core.twin.TwinInput
import com.solartracker.pro.core.analytics.Alert
import com.solartracker.pro.core.analytics.AutoCalibrationEngine
import com.solartracker.pro.core.analytics.CalibrationModel
import com.solartracker.pro.core.analytics.CalibrationObservation
import com.solartracker.pro.core.weather.WeatherEffects
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
import com.solartracker.pro.core.forecast.CoolingAwareLoad
import com.solartracker.pro.core.forecast.LoadForecaster
import com.solartracker.pro.core.forecast.LoadModel
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
import com.solartracker.pro.core.inverter.PlausibilityLimits
import com.solartracker.pro.core.inverter.TelemetryValidation
import com.solartracker.pro.core.inverter.TelemetryValidator
import com.solartracker.pro.core.pv.PvEstimator
import com.solartracker.pro.core.quality.DataKind
import com.solartracker.pro.core.quality.Quantity
import com.solartracker.pro.core.energy.SocEstimator
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
import com.solartracker.pro.core.weather.WeatherSource
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
import kotlinx.coroutines.flow.first
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
import java.time.temporal.ChronoUnit

/** Live part of the Energy Center. */
data class LiveState(
    val info: InverterInfo? = null,
    val capabilities: Set<TelemetryField> = emptySet(),
    val connection: ConnectionState = ConnectionState(),
    val telemetry: InverterTelemetry? = null,
    val freshness: Freshness = Freshness.NONE,
    val flow: RealEnergyFlow? = null,
    val now: Instant = Instant.now(),
    /** Validation of the newest reading (rejected values, issues). */
    val validation: TelemetryValidation? = null,
    /** Protocol diagnostics of the last read (e.g. unsupported registers). */
    val diagnostics: List<String> = emptyList(),
    /** SOC from the inverter (MEASURED) or from the resting voltage (ESTIMATED), else UNKNOWN. */
    val soc: Quantity? = null,
)

data class ModelState(
    val modelKw: Double? = null,
    val unshadedKw: Double? = null,
    val comparison: ModelComparisonResult? = null,
    val calibration: CalibrationResult? = null,
    /** AutoCalibration 3.0 model (per sky condition, sun elevation, hour, month). */
    val calibrationModel: CalibrationModel? = null,
    /** Expected vs actual power with estimated loss shares. */
    val performance: PerformanceReport? = null,
)

data class ForecastState(
    val today: DayProductionForecast? = null,
    val tomorrow: DayProductionForecast? = null,
    val shortTerm: List<ShortTermForecast> = emptyList(),
    val battery: BatteryPrediction? = null,
    val timeline: List<EnergyForecastRow> = emptyList(),
    val producedTodayKwh: Double? = null,
    val updatedAt: Instant? = null,
    /** Forecast PV power for this moment (with calibration and nowcast) [kW]. */
    val nowForecastKw: Double? = null,
    /** "Will there be enough energy?" (SOC milestones, time to minimum, risk). */
    val security: EnergySecurity? = null,
    /** Today, tomorrow, +2, +3 days. */
    val outlook: List<DayOutlook> = emptyList(),
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

/** Derived insights: health, early warnings, EMS decisions, multi-day balance, history totals. */
data class InsightsState(
    val health: HealthReport? = null,
    val healthReason: String? = null,
    val faults: List<FaultWarning> = emptyList(),
    val ems: EmsPlan? = null,
    val periods: List<PeriodBalance> = emptyList(),
    val week: List<DayBalance> = emptyList(),
    val totals: Map<HistoryPeriod, List<PeriodTotals>> = emptyMap(),
    /** Stored PV forecasts vs measured energy over the last 30 days. */
    val accuracy: Map<ForecastHorizon, AccuracyReport> = emptyMap(),
    /** Day-ahead forecast summed per day / week / month. */
    val periodAccuracy: Map<AccuracyPeriod, AccuracyReport> = emptyMap(),
    /** Today's complete hours (day-ahead forecast, else hour-ahead). */
    val todayAccuracy: AccuracyReport? = null,
    val dailyToday: DailyEnergyReport? = null,
    val dailyYesterday: DailyEnergyReport? = null,
    /** Sun → PV → inverter → battery → loads. */
    val twin: DigitalTwin? = null,
)

/** PV Reality & Diagnostics (Centrum → Diagnostyka PV). */
data class DiagnosticsState(
    val reality: PvReality? = null,
    val diagnosis: PvDiagnosis? = null,
    val radar: PvRadar? = null,
    val missionGoal: MissionGoal = MissionGoal.SURVIVE_NIGHT,
    val mission: MissionResult? = null,
    val soiling: SoilingAssessment? = null,
    val degradation: DegradationAssessment? = null,
    val mppt: MpptAnalysis? = null,
    /** Raw register log (read-only) – recorded only while [registerRecording] is on. */
    val registerRecording: Boolean = false,
    val registerRecords: Int = 0,
    val registerSummary: Map<RegisterQuality, Int> = emptyMap(),
    val lastRegisters: RegisterLogRecord? = null,
)

/** Anenji Deep Analyzer (Centrum → Analiza Anenji). Read-only. */
data class AnalyzerState(
    val running: Boolean = false,
    val source: String? = null,
    val report: DeepAnalysisReport? = null,
    val importSummary: String? = null,
    val importIssues: List<String> = emptyList(),
    val snapshots: List<AnenjiSettingsSnapshot> = emptyList(),
    val incident: IncidentReport? = null,
    val why: WhyAnswer? = null,
    val error: String? = null,
    // Forensic analyzer ("CO SIĘ STAŁO?")
    val forensicRunning: Boolean = false,
    val forensicPeriod: ForensicPeriod = ForensicPeriod.D7,
    val forensic: PeriodReport? = null,
    val trends90: List<LongTrend> = emptyList(),
    val configImpacts: List<ConfigImpact> = emptyList(),
    val reconstruction: IncidentReconstruction? = null,
    // Validation mode: values read from the inverter display vs decoded registers.
    val references: List<ManualReference> = emptyList(),
    val validation: List<RegisterEvidence> = emptyList(),
)

/** About 3 h of 5-second polls. */
private const val MAX_REGISTER_RECORDS = 2000

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
    val flexibleLoads: StateFlow<List<FlexibleLoad>> = store.flexibleLoads.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val generator: StateFlow<GeneratorConfig?> = store.generator.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val cooling: StateFlow<CoolingLoadProfile?> = store.cooling.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
    private val _insights = MutableStateFlow(InsightsState())
    val insights: StateFlow<InsightsState> = _insights.asStateFlow()
    private val _diagnostics = MutableStateFlow(DiagnosticsState())
    val diagnostics: StateFlow<DiagnosticsState> = _diagnostics.asStateFlow()
    private val realitySamples = ArrayDeque<RealitySample>()
    private var lastRealitySampleAt: Instant? = null
    private val registerLog = ArrayDeque<RegisterLogRecord>()
    private val _analyzer = MutableStateFlow(AnalyzerState())
    val analyzer: StateFlow<AnalyzerState> = _analyzer.asStateFlow()
    private val commLog = ArrayDeque<CommRecord>()
    private var lastImport: ImportedLog? = null
    private var analyzerSamples: List<AnalyzerSample> = emptyList()
    private var analyzerEvents: List<AnenjiEvent> = emptyList()
    private var analyzerComm: List<CommRecord> = emptyList()
    private var analyzerSnapshots: List<AnenjiSettingsSnapshot> = emptyList()
    private var analyzerNow: Instant = Instant.now()
    /** Built once per analysed data set (the baseline index is the expensive part); reports cached per period. */
    private var forensicBase: ForensicContext? = null
    private var forensicTrends: List<LongTrend>? = null
    private val forensicCache = HashMap<Pair<Instant, Instant>, PeriodReport>()
    @Volatile private var verifiedRegisters: Set<Int> = emptySet()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var manager: InverterConnectionManager? = null
    private var monitorJob: Job? = null
    private var monitoring = false
    private val aggregator = TelemetryAggregator(Duration.ofSeconds(30))
    private val calibration = CalibrationEngine()
    private val autoCalibration = AutoCalibrationEngine()
    private var calibrationModel: CalibrationModel? = null
    private var calibrationModelAt: Instant? = null
    private var lastObservationAt: Instant? = null
    private var outlookComputedAt: Instant? = null
    private val recentTelemetry = ArrayDeque<InverterTelemetry>()
    private var offlineSince: Instant? = null
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
            validator = TelemetryValidator(PlausibilityLimits(
                batteryNominalV = siteConfig.value?.batteryVoltage ?: 48.0,
                ratedPowerW = config.ratedPowerW,
            )),
            expectedPvW = { time -> expectedPvW(time) },
        )
        manager = m
        _live.value = LiveState(info = provider.info, capabilities = provider.capabilities)
        monitorJob = viewModelScope.launch {
            launch {
                m.state.collect { s ->
                    _live.value = _live.value.copy(connection = s)
                    if (s.status == LinkStatus.OFFLINE) {
                        if (offlineSince == null) offlineSince = Instant.now()
                        onTelemetryMissing()
                        rediagnose()
                    } else if (s.status == LinkStatus.ONLINE) {
                        offlineSince = null
                    }
                }
            }
            launch { m.validation.collect { v -> _live.value = _live.value.copy(validation = v, diagnostics = provider.diagnostics) } }
            launch { m.registerLog.collect { r -> if (r != null) onRegisterRecord(r) } }
            m.run()
        }
    }

    /** Model PV expectation [W] (weather, shading, calibration) used by the zero-PV check. */
    private fun expectedPvW(time: Instant): Double? {
        val s = settings.value ?: return null
        val weather = WeatherAwareIrradianceModel(s.location, _weather.value.first, _weather.value.second)
        val e = PvEstimator(weather).pointEstimate(s.system, s.location, time)
        val shade = _shading.value.engine?.snapshot(s.system, time, e)?.powerFactor ?: 1.0
        return e.powerKw * 1000 * shade * calibration.appliedFactor()
    }

    fun refreshNow() {
        viewModelScope.launch { manager?.pollOnce() }
    }

    private suspend fun onTelemetry(t: InverterTelemetry) = lock.withLock {
        val flow = RealEnergyFlow.from(t)
        val socQuantity = settings.value?.let { st ->
            SocEstimator.estimate(t, synchronized(recentTelemetry) { recentTelemetry.toList() }, st.battery.type, siteConfig.value?.batteryVoltage ?: 48.0)
        }
        _live.value = _live.value.copy(telemetry = t, flow = flow, soc = socQuantity)
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
                    cloudCoverPercent = _weather.value.first?.at(t.timestamp)?.cloudCoverPercent,
                    clearSkyIndex = WeatherEffects.clearSkyIndex(_weather.value.first?.at(t.timestamp), estimate.sun.position, t.timestamp),
                    cellTemperatureC = estimate.cellTemperatureC,
                    inverterRatedKw = inv?.ratedPowerW?.div(1000.0), batterySocPercent = t.battery.socPercent,
                    batteryMaxSocPercent = battery?.maxSocPercent ?: 100.0, gridExportAllowed = site?.gridExportAllowed,
                    loadKw = t.load.powerW?.div(1000.0), batteryChargeKw = t.battery.chargePowerW?.div(1000.0),
                ),
            )
        }
        if (realKw != null && comparison != null && estimate.sun.elevationDeg > 5 && modelKw > 0.05) {
            val usable = calibration.offer(t.timestamp, realKw, modelKw, s.system.peakPowerKw, estimate.sun.elevationDeg, comparison.curtailed)
            val last = lastObservationAt
            // Usable samples are kept as before; others once a minute so exclusions can be counted.
            if (usable || last == null || Duration.between(last, t.timestamp) >= Duration.ofMinutes(1)) {
                lastObservationAt = t.timestamp
                val hour = _weather.value.first?.at(t.timestamp)
                val position = estimate.sun.position
                val observation = CalibrationObservation(
                    time = t.timestamp, realKw = realKw, modelKw = modelKw, cellTemperatureC = estimate.cellTemperatureC,
                    nearLimit = comparison.curtailed, sunElevationDeg = position.elevationDeg, sunAzimuthDeg = position.azimuthDeg,
                    cloudCoverPercent = hour?.cloudCoverPercent, irradianceWm2 = estimate.poa,
                    clearSkyIndex = WeatherEffects.clearSkyIndex(hour, position, t.timestamp),
                    condition = WeatherEffects.skyCondition(hour, position, t.timestamp), shadingFactor = shadeFactor,
                    fault = t.inverter.faults.isNotEmpty(), linkOk = _live.value.connection.status == LinkStatus.ONLINE,
                    invalidTelemetry = manager?.validation?.value?.invalidFields?.isNotEmpty() == true,
                )
                withContext(Dispatchers.IO) { runCatching { db.addObservation(observation, usable) } }
            }
        }
        val hourNow = _weather.value.first?.at(t.timestamp)
        val irradiance = weather.irradiance(estimate.sun.position, t.timestamp)
        val performance = runCatching {
            PvPerformanceAnalyzer.analyze(
                PvArrayConfig(1, s.system.peakPowerKw * 1000, s.system.tiltDeg, s.system.azimuthDeg),
                LossProfile(inverterLimitW = inv?.takeIf { it.enabled }?.ratedPowerW, inverterRatedW = inv?.takeIf { it.enabled }?.ratedPowerW),
                s.location, t.timestamp,
                PvConditions(irradiance, irradiance.ambientTemperatureC, hourNow?.windSpeedMs, hourNow?.snowDepthM),
                actualW = t.pv.powerW, shadingFactor = shadeFactor,
                fresh = _live.value.connection.status == LinkStatus.ONLINE,
                clearSkyConditions = PvConditions(ClearSkyModel().irradiance(estimate.sun.position, t.timestamp), irradiance.ambientTemperatureC, hourNow?.windSpeedMs),
            )
        }.getOrNull()
        synchronized(recentTelemetry) {
            recentTelemetry.addLast(t)
            while (recentTelemetry.size > 40) recentTelemetry.removeFirst()
        }
        _model.value = ModelState(modelKw, estimate.powerKw, comparison, calibration.result(), calibrationModel, performance)
        runCatching { updateReality(s, t, weather, estimate, hourNow, irradiance, shadeFactor) }

        val anomalies = AnomalyDetector.detect(
            AnomalyInput(
                now = t.timestamp, telemetry = t, link = _live.value.connection.status,
                expectedUnshadedKw = estimate.powerKw * calibration.appliedFactor(), expectedShadedKw = modelKw * calibration.appliedFactor(),
                poaWm2 = estimate.poa, curtailed = comparison?.curtailed ?: false, batteryMinSocPercent = battery?.minSocPercent,
                batteryNominalVoltage = site?.batteryVoltage ?: 48.0, typicalLoadKw = loadForecaster?.typicalKw(t.timestamp),
                previous = previousTelemetry, batteryCapacityKwh = battery?.usableCapacityKwh,
                validationIssues = manager?.validation?.value?.issues.orEmpty(),
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

    private fun persist(row: HistorySample) {
        val sample = row.copy(simulated = _live.value.info?.simulated == true)
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
        val model = currentCalibrationModel(now)
        val pv = PredictivePvEngine(
            s.location, s.system, weather, _shading.value.engine,
            calibrationFactor = calibration.appliedFactor(), calibrationConfidence = if (cal.ready) cal.confidence else 0.0,
            inverterLimitKw = inv?.takeIf { it.enabled }?.ratedPowerW?.div(1000.0),
            calibrationModel = model,
        )
        val base = LoadForecaster(history, zone, s.consumption.profile(), now).also { loadForecaster = it }
        // The cold room is added only while there is no measured load history (history already contains it).
        val coolingProfile = store.cooling.first()?.takeIf { !base.usesHistory }
        val forecastWeather = _weather.value.first
        val load = if (coolingProfile == null) base else CoolingAwareLoad(base, coolingProfile, zone) { t -> forecastWeather?.at(t)?.temperatureC }
        val battery = s.activeBattery?.let { BatteryPredictor(it, zone) }
        val engine = EnergyForecastEngine(pv, load, battery, zone)
        val live = _live.value
        val fresh = live.freshness == Freshness.LIVE
        val nowcast = _model.value.comparison?.takeIf { fresh && !it.curtailed && it.modelKw > 0.2 }?.let { it.realKw / it.modelKw }
        // Inverter SOC when reported, otherwise the resting-voltage estimate (never a guess under load).
        val socQ = live.soc
        val soc = socQ?.value
        val socKind = when {
            soc == null -> DataKind.UNKNOWN
            socQ?.kind == DataKind.ESTIMATED -> DataKind.ESTIMATED
            fresh && live.info?.simulated == true -> DataKind.SIMULATED
            fresh -> DataKind.MEASURED
            else -> DataKind.LAST_KNOWN
        }
        val gridAvailable = s.prices.backupSource == BackupSource.GRID
        val security = EnergySecurityAnalyzer(s.activeBattery, zone)
        val pvAt = { t: Instant -> pv.at(t, now, nowcast) }
        val loadAt = { t: Instant -> load.at(t) }
        val securityNow = withContext(Dispatchers.Default) { security.analyze(now, soc, socKind, pvAt, loadAt, gridAvailable) }
        val outlookAt = outlookComputedAt
        val outlook = if (outlookAt == null || Duration.between(outlookAt, now) >= Duration.ofMinutes(10) || _forecast.value.outlook.isEmpty()) {
            outlookComputedAt = now
            withContext(Dispatchers.Default) { security.outlook(now, soc, socKind, pvAt, loadAt) }
        } else {
            _forecast.value.outlook
        }
        _forecast.value = ForecastState(
            nowForecastKw = pvAt(now).expectedKw,
            security = securityNow,
            outlook = outlook,
            today = engine.day(today, now, producedToday, nowcast),
            tomorrow = engine.day(today.plusDays(1), now, null),
            shortTerm = engine.shortTerm(now, nowcast),
            battery = soc?.let { engine.battery(now, it, socKind, nowcast) },
            timeline = engine.timeline(now, 24, nowcast),
            producedTodayKwh = producedToday,
            updatedAt = now,
        )
        runCatching {
            // Radar: the same engine on a clear sky (same calibration/shading/limits) isolates the cloud impact.
            val clear = PredictivePvEngine(
                s.location, s.system, WeatherAwareIrradianceModel(s.location), _shading.value.engine,
                calibrationFactor = calibration.appliedFactor(), calibrationConfidence = if (cal.ready) cal.confidence else 0.0,
                inverterLimitKw = inv?.takeIf { it.enabled }?.ratedPowerW?.div(1000.0), calibrationModel = model,
            )
            val currentKw = live.telemetry?.pv?.powerW?.takeIf { fresh }?.div(1000.0)
            val radar = withContext(Dispatchers.Default) { PvRadarBuilder(pv, clear, s.system.peakPowerKw).build(now, nowcast, currentKw) }
            val goal = _diagnostics.value.missionGoal
            val mission = withContext(Dispatchers.Default) {
                EnergyMissionPlanner(security, zone).evaluate(
                    MissionRequest(goal, gridPricePerKwh = s.prices.gridPricePerKwh, generatorPricePerKwh = s.prices.generatorPricePerKwh,
                        coldRoomKw = coolingProfile?.averagePowerKw(now, zone, null)),
                    now, soc, socKind, pvAt, loadAt,
                )
            }
            _diagnostics.value = _diagnostics.value.copy(radar = radar, mission = mission)
        }
        storeForecasts(pv, now, nowcast)
        recomputeInsights(s, pv, load, nowcast, soc, now)
    }

    /** AutoCalibration 3.0 model from the last 60 days, rebuilt at most once an hour. */
    private suspend fun currentCalibrationModel(now: Instant): CalibrationModel? {
        val at = calibrationModelAt
        if (calibrationModel != null && at != null && Duration.between(at, now) < Duration.ofHours(1)) return calibrationModel
        val observations = withContext(Dispatchers.IO) { runCatching { db.observations(now.minus(Duration.ofDays(60))) }.getOrDefault(emptyList()) }
        val built = withContext(Dispatchers.Default) { autoCalibration.buildModel(observations, zone) }
        runCatching { updateLongTerm(observations, now) }
        calibrationModel = built
        calibrationModelAt = now
        _model.value = _model.value.copy(calibrationModel = built)
        return built
    }

    /** Hour-ahead (next hour) and day-ahead (all of tomorrow) PV energy, kept for accuracy tracking. */
    private suspend fun storeForecasts(pv: PredictivePvEngine, now: Instant, nowcast: Double?) = withContext(Dispatchers.IO) {
        fun hourKwh(start: Instant, ratio: Double?) = (0 until 6).sumOf { pv.at(start.plusSeconds(300L + it * 600L), now, ratio).expectedKw } / 6.0
        runCatching {
            // Point forecasts on a 5-minute grid, 5 and 15 minutes ahead (the last issue before the target is kept).
            for ((horizon, minutes) in listOf(ForecastHorizon.MIN5 to 5L, ForecastHorizon.MIN15 to 15L)) {
                val ahead = now.plus(Duration.ofMinutes(minutes)).epochSecond
                val target = Instant.ofEpochSecond((ahead + 299) / 300 * 300)
                db.putForecast(target, horizon, pv.at(target, now, nowcast).expectedKw, now)
            }
            val next = now.truncatedTo(ChronoUnit.HOURS).plus(Duration.ofHours(1))
            db.putForecast(next, ForecastHorizon.HOUR_AHEAD, hourKwh(next, nowcast), now)
            val tomorrow = now.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
            for (h in 0 until 24) {
                val start = tomorrow.plus(Duration.ofHours(h.toLong()))
                db.putForecast(start, ForecastHorizon.DAY_AHEAD, hourKwh(start, null), now)
            }
        }
    }

    private suspend fun recomputeInsights(s: AppSettings, pv: PredictivePvEngine, load: LoadModel, nowcast: Double?, soc: Double?, now: Instant) {
        val week = withContext(Dispatchers.IO) { runCatching { db.history(now.minus(Duration.ofDays(7)), now) }.getOrDefault(emptyList()) }
        val all = withContext(Dispatchers.IO) { runCatching { db.history(Instant.EPOCH, now, summary = true) }.getOrDefault(emptyList()) }
        val monthAgo = now.minus(Duration.ofDays(30))
        val stored = withContext(Dispatchers.IO) {
            ForecastHorizon.entries.associateWith { h -> runCatching { db.forecasts(h, monthAgo, now.minus(Duration.ofHours(1))) }.getOrDefault(emptyMap()) }
        }
        val telemetry = _live.value.telemetry
        val inv = inverterConfig.value
        val shading = _shading.value
        // Read the store directly: right after a save the StateFlows may not have caught up yet.
        val loads = store.flexibleLoads.first()
        val gen = store.generator.first()
        val securityNow = _forecast.value.security
        val performance = _model.value.performance
        val recent = synchronized(recentTelemetry) { recentTelemetry.toList() }
        val clippingShare = withContext(Dispatchers.IO) {
            runCatching {
                db.observations(now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()).filter { (it.sunElevationDeg ?: 0.0) > 15 }
                    .takeIf { it.size >= 20 }?.let { list -> list.count { it.nearLimit }.toDouble() / list.size }
            }.getOrNull()
        }
        _insights.value = withContext(Dispatchers.Default) {
            val slots = (0 until 24 * 7).map { i ->
                val t = now.plus(Duration.ofHours(i.toLong()))
                val p = pv.at(t, now, nowcast)
                EnergySlot(t, 1.0, p.expectedKw, load.at(t).kw, p.minKw, p.confidence)
            }
            val ems = EnergyOptimizationEngine.plan(
                EmsInput(
                    slots.take(36), s.activeBattery, soc, loads = loads,
                    generator = gen.takeIf { s.prices.backupSource == BackupSource.GENERATOR },
                    gridAvailable = s.prices.backupSource == BackupSource.GRID, zone = zone,
                ),
            )
            val ratedW = inv?.takeIf { it.enabled }?.ratedPowerW
            val assessment: Triple<HealthReport?, String?, List<FaultWarning>> = if (week.isEmpty() || ratedW == null) {
                Triple(null, "Brak historii pomiarów z falownika — ocena zdrowia wymaga co najmniej 2 dni danych", emptyList())
            } else {
                val weather = WeatherAwareIrradianceModel(s.location, _weather.value.first, _weather.value.second)
                val estimator = PvEstimator(weather)
                val expected = ExpectedPower { t ->
                    val e = estimator.pointEstimate(s.system, s.location, t)
                    e.powerKw * 1000 * (shading.engine?.snapshot(s.system, t, e)?.powerFactor ?: 1.0)
                }
                val days = DayStatsBuilder.build(week, zone, expected, s.system.peakPowerKw * 1000, ratedW)
                val report = PvHealthEngine.assess(HealthInput(
                    days, null, shading.confidence?.score, null, null,
                    telemetry?.inverter?.faults?.size ?: 0, telemetry?.inverter?.warnings?.size ?: 0, null,
                ))
                Triple(report, null, PredictiveFaultEngine.analyze(days, now))
            }
            val (health, reason, trendFaults) = assessment
            val hourAhead = ForecastAccuracy.pairHourly(stored[ForecastHorizon.HOUR_AHEAD].orEmpty(), all.filter { it.start >= now.minus(Duration.ofDays(14)) })
                .filter { it.forecast > 0.01 || it.actual > 0.01 }
            val weekAgo = now.minus(Duration.ofDays(7))
            val predictive = PredictiveFaultEngine.predictive(PredictiveAlertInput(
                now = now, security = securityNow, telemetry = telemetry, recent = recent,
                batteryType = s.activeBattery?.type, batteryNominalVoltage = siteConfig.value?.batteryVoltage ?: 48.0,
                inverterMaxTempC = 75.0, linkFailures = _live.value.connection.consecutiveFailures, linkOfflineSince = offlineSince,
                accuracyRecent = ForecastAccuracy.evaluate(hourAhead.filter { it.time >= weekAgo }),
                accuracyPrevious = ForecastAccuracy.evaluate(hourAhead.filter { it.time < weekAgo }),
                typicalLoadKw = loadForecaster?.typicalKw(now), clippingShare = clippingShare, performance = performance,
            ))
            val faults = (predictive + trendFaults).distinctBy { it.id }
            val today = now.atZone(zone).toLocalDate()
            val todayAccuracy = run {
                val dayStart = today.atStartOfDay(zone).toInstant()
                val rows = all.filter { it.start >= dayStart }
                listOf(ForecastHorizon.DAY_AHEAD, ForecastHorizon.HOUR_AHEAD).map { h ->
                    ForecastAccuracy.evaluate(ForecastAccuracy.pairHourly(stored[h].orEmpty().filterKeys { it >= dayStart }, rows)
                        .filter { it.forecast > 0.01 || it.actual > 0.01 })
                }.firstOrNull { it.count > 0 }
            }
            val recentRows = all.filter { it.start >= today.minusDays(9).atStartOfDay(zone).toInstant() }
            val weatherModel = WeatherAwareIrradianceModel(s.location, _weather.value.first, _weather.value.second)
            val twin = DigitalTwinBuilder.build(TwinInput(
                time = now, sun = SolarCalculator.position(s.location, now),
                poaWm2 = PvEstimator(weatherModel).pointEstimate(s.system, s.location, now).poa,
                weatherSource = when (weatherModel.sourceAt(now)) {
                    WeatherSource.FORECAST -> "prognoza pogody"
                    WeatherSource.CLIMATE -> "średnie klimatyczne"
                    WeatherSource.CLEAR_SKY -> "bezchmurne niebo (górna granica)"
                },
                telemetry = telemetry, fresh = _live.value.freshness == Freshness.LIVE, link = _live.value.connection.status.takeIf { telemetry != null },
                modelPvKw = _model.value.modelKw, forecastPvKw = pv.at(now, now, nowcast).expectedKw, performance = performance,
                security = securityNow, loadForecastKw = load.at(now).kw, warnings = faults,
            ))
            InsightsState(
                health = health, healthReason = reason, faults = faults, ems = ems,
                dailyToday = DailyReportBuilder.build(today, recentRows, zone, todayAccuracy, health?.score, _alerts.value.size),
                dailyYesterday = DailyReportBuilder.build(today.minusDays(1), recentRows, zone, null, null, 0),
                twin = twin,
                periods = EnergyOptimizationEngine.periods(slots.takeWhile { it.start.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate() }, zone),
                week = EnergyOptimizationEngine.daily(slots, zone),
                totals = HistoryPeriod.entries.associateWith { HistoryPeriods.totals(all, it, zone, now) },
                accuracy = stored.mapValues { (h, f) ->
                    // Night (0 vs 0) would flatter the score: compare only points with production expected or measured.
                    val pairs = if (h.power) ForecastComparison.pairPower(f.filterKeys { it >= now.minus(Duration.ofDays(7)) }, week)
                    else ForecastAccuracy.pairHourly(f, all.filter { it.start >= monthAgo })
                    ForecastAccuracy.evaluate(pairs.filter { it.forecast > 0.01 || it.actual > 0.01 })
                },
                periodAccuracy = run {
                    val hourly = ForecastAccuracy.pairHourly(stored[ForecastHorizon.DAY_AHEAD].orEmpty(), all.filter { it.start >= monthAgo })
                    AccuracyPeriod.entries.associateWith { ForecastAccuracy.evaluate(ForecastComparison.aggregate(hourly, it, zone)) }
                },
                todayAccuracy = todayAccuracy,
            )
        }
    }

    /** PDF report: health, EMS decisions, monthly and daily totals from recorded history. */
    suspend fun exportPdf(out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val rows = db.history(Instant.EPOCH, now, summary = true)
        val i = _insights.value
        val lines = buildList {
            add("Wygenerowano: ${now.atZone(zone).toLocalDateTime().withNano(0)} · wersja ${BuildConfig.VERSION_NAME}")
            add(settings.value?.let { "Instalacja: ${it.system.peakPowerKw} kWp, ${it.system.tiltDeg}°, azymut ${it.system.azimuthDeg}°, ${it.locationName}" } ?: "")
            add("")
            i.dailyToday?.let { add(""); addAll(it.text().lines()); add("") }
            add("ZDROWIE: " + (i.health?.explanation() ?: i.healthReason ?: "brak danych"))
            i.faults.forEach { add("OSTRZEŻENIE [${it.level.label}] ${it.title}: ${it.reason}") }
            add("")
            add("EMS (prognoza, tylko zalecenia):")
            i.ems?.decisions?.forEach { add("  • $it") }
            add("")
            add("MIESIĄCE (pomiary)          PV kWh   zużycie kWh   import   eksport")
            HistoryPeriods.totals(rows, HistoryPeriod.MONTH, zone, now).forEach {
                add(String.format(java.util.Locale.ROOT, "%-24s %9.2f %13.2f %8.2f %9.2f", it.periodStart, it.pvKwh, it.loadKwh, it.gridImportKwh, it.gridExportKwh))
            }
            add("")
            add("DNI (pomiary)               PV kWh   zużycie kWh   kompletność")
            HistoryPeriods.totals(rows, HistoryPeriod.DAY, zone, now).takeLast(62).forEach {
                add(String.format(java.util.Locale.ROOT, "%-24s %9.2f %13.2f %12.0f%%", it.periodStart, it.pvKwh, it.loadKwh, it.coverage * 100))
            }
            if (rows.isEmpty()) add("Brak zapisanej historii z falownika.")
        }
        PdfReport.write("Solar Tracker PRO — raport", lines, out)
    }

    fun saveFlexibleLoads(loads: List<FlexibleLoad>) {
        viewModelScope.launch { store.setFlexibleLoads(loads); recomputeForecast() }
    }

    fun saveCooling(c: CoolingLoadProfile?) {
        viewModelScope.launch { store.setCooling(c); recomputeForecast() }
    }

    fun saveGenerator(g: GeneratorConfig?) {
        viewModelScope.launch { store.setGenerator(g); recomputeForecast() }
    }

    /** Export of recorded history (summary rows) as CSV or JSON text. */
    // ---- PV Reality & Diagnostics -------------------------------------------------------------

    private fun updateReality(
        s: AppSettings, t: InverterTelemetry, weather: WeatherAwareIrradianceModel, estimate: PvPointEstimate,
        hourNow: HourlyWeather?, irradiance: Irradiance, shadeFactor: Double,
    ) {
        val inv = inverterConfig.value?.takeIf { it.enabled }
        val simulated = _live.value.info?.simulated == true
        val position = estimate.sun.position
        val basis = when (weather.sourceAt(t.timestamp)) {
            WeatherSource.FORECAST -> IrradianceBasis.FORECAST
            WeatherSource.CLIMATE -> IrradianceBasis.CLIMATE
            WeatherSource.CLEAR_SKY -> IrradianceBasis.CLEAR_SKY
        }
        val csi = WeatherEffects.clearSkyIndex(hourNow, position, t.timestamp)
        // The learned calibration belongs to the PR model, not to the loss chain, so it is not applied here.
        val reality = PvRealityEngine.assess(
            PvArrayConfig(1, s.system.peakPowerKw * 1000, s.system.tiltDeg, s.system.azimuthDeg),
            LossProfile(inverterLimitW = inv?.ratedPowerW, inverterRatedW = inv?.ratedPowerW),
            s.location, t.timestamp,
            PvConditions(irradiance, irradiance.ambientTemperatureC, hourNow?.windSpeedMs, hourNow?.snowDepthM),
            t.pv.powerW?.let { Quantity(it, "W", if (simulated) DataKind.SIMULATED else DataKind.MEASURED, _live.value.info?.manufacturer ?: "falownik", t.timestamp) },
            basis, shadingFactor = shadeFactor, shadingConfidence = _shading.value.confidence?.score ?: 0.5, clearSkyIndex = csi,
        )
        if (reality.deviationPercent != null) {
            val last = lastRealitySampleAt
            if (last == null || Duration.between(last, t.timestamp) >= Duration.ofMinutes(1)) {
                lastRealitySampleAt = t.timestamp
                synchronized(realitySamples) {
                    realitySamples.addLast(RealitySample(t.timestamp, reality.expectedW, reality.actualW!!, csi, WeatherEffects.snowCovered(hourNow, s.system.tiltDeg)))
                    while (realitySamples.size > 3 * 24 * 60) realitySamples.removeFirst()
                }
            }
        }
        val mppt = t.mppts.takeIf { it.isNotEmpty() }?.let { readings ->
            // Without per-MPPT configuration the array is assumed split evenly (stated in the UI).
            val configs = readings.map { MpptConfig(it.index, s.system.peakPowerKw * 1000 / readings.size) }
            MpptAnalyzer.analyze(readings, configs, MpptAnalyzer.splitByPeak(reality.breakdown.dcInputW, configs))
        }
        _diagnostics.value = _diagnostics.value.copy(reality = reality, mppt = mppt)
        rediagnose(t)
    }

    /** Re-runs PV Doctor with the newest reality check (also when the link drops). */
    private fun rediagnose(t: InverterTelemetry? = _live.value.telemetry) {
        val s = settings.value ?: return
        val d = _diagnostics.value
        val live = _live.value
        val battery = s.activeBattery
        val (eff, n) = synchronized(recentTelemetry) { ConversionEfficiency.median(recentTelemetry.toList()) }
        val input = DiagnosticInput(
            now = Instant.now(), zone = zone, peakW = s.system.peakPowerKw * 1000, reality = d.reality,
            recent = synchronized(realitySamples) { realitySamples.toList() },
            link = live.connection.status, freshness = manager?.freshness(Instant.now()) ?: live.freshness,
            linkFailures = live.connection.consecutiveFailures,
            validationIssues = manager?.validation?.value?.issues.orEmpty(),
            simulated = live.info?.simulated == true,
            snowExpected = WeatherEffects.snowCovered(_weather.value.first?.at(Instant.now()), s.system.tiltDeg),
            soiling = d.soiling, degradation = d.degradation, mppt = d.mppt,
            flow = t?.let { FlowContext(it.battery.socPercent, battery?.maxSocPercent ?: 100.0, it.load.powerW, it.battery.powerW, siteConfig.value?.gridExportAllowed == true) },
            conversionEfficiency = eff, conversionSamples = n,
        )
        _diagnostics.value = _diagnostics.value.copy(diagnosis = PvDiagnosticEngine.diagnose(input))
    }

    /** Soiling (last 60 days) and degradation (monthly index kept for years) from the stored observations. */
    private suspend fun updateLongTerm(observations: List<CalibrationObservation>, now: Instant) {
        val s = settings.value ?: return
        val forecast = _weather.value.first
        val rain = forecast?.hours?.filter { it.precipitationMm != null }?.groupBy { it.startTime.atZone(zone).toLocalDate() }
            ?.filterValues { it.size >= 20 }?.mapValues { (_, h) -> h.sumOf { it.precipitationMm!! } } ?: emptyMap()
        val snow = forecast?.hours?.filter { WeatherEffects.snowCovered(it, s.system.tiltDeg) }?.map { it.startTime.atZone(zone).toLocalDate() }?.toSet() ?: emptySet()
        val days = PerformanceHistory.daily(observations, zone, rain, snow)
        val months = PerformanceHistory.monthly(days)
        val stored = withContext(Dispatchers.IO) {
            runCatching {
                // Months fully inside the observation window are final; the current one is refreshed each time.
                val windowStart = YearMonth.from(now.minus(Duration.ofDays(60)).atZone(zone)).plusMonths(1)
                months.filter { !it.month.isBefore(windowStart) }.forEach { db.putMonthlyPerformance(it) }
                db.monthlyPerformance()
            }.getOrDefault(months)
        }
        val year = now.atZone(zone).year
        val soiling = SoilingDetector.assess(days)
        val degradation = DegradationAnalyzer.assess(stored, s.system.peakPowerKw, null, listOf(year, year + 4, year + 9))
        _diagnostics.value = _diagnostics.value.copy(soiling = soiling, degradation = degradation)
    }

    private fun onRegisterRecord(r: RegisterLogRecord) {
        synchronized(commLog) {
            commLog.addLast(CommRecord(r.timestamp, r.communicationOk, if (r.communicationOk) null else CommError.classify(r.communication), detail = r.communication.takeIf { !r.communicationOk }))
            while (commLog.size > 20_000) commLog.removeFirst()
        }
        // Registers confirmed in validation mode (≥ 3 real-device matches) are shown as VERIFIED; physics checks still apply first.
        val verified = verifiedRegisters
        val r = if (verified.isEmpty() || r.simulated) r else r.copy(samples = r.samples.map {
            if (it.address in verified && it.quality == RegisterQuality.UNVERIFIED) it.copy(quality = RegisterQuality.VERIFIED) else it
        })
        val d = _diagnostics.value
        if (!d.registerRecording) {
            _diagnostics.value = d.copy(lastRegisters = r)
            return
        }
        val count = synchronized(registerLog) {
            registerLog.addLast(r)
            while (registerLog.size > MAX_REGISTER_RECORDS) registerLog.removeFirst()
            registerLog.size
        }
        val summary = synchronized(registerLog) { RegisterDiagnostics.summary(registerLog.toList()) }
        _diagnostics.value = d.copy(lastRegisters = r, registerRecords = count, registerSummary = summary)
    }

    fun setRegisterRecording(on: Boolean) {
        _diagnostics.value = _diagnostics.value.copy(registerRecording = on)
    }

    fun clearRegisterLog() {
        synchronized(registerLog) { registerLog.clear() }
        _diagnostics.value = _diagnostics.value.copy(registerRecords = 0, registerSummary = emptyMap())
    }

    fun setMissionGoal(goal: MissionGoal) {
        _diagnostics.value = _diagnostics.value.copy(missionGoal = goal)
        viewModelScope.launch(Dispatchers.Default) { recomputeForecast() }
    }

    suspend fun exportRegisterLog(json: Boolean): String = withContext(Dispatchers.IO) {
        val records = synchronized(registerLog) { registerLog.toList() }
        if (json) RegisterLogExport.json(records, Instant.now(), BuildConfig.VERSION_NAME, _live.value.info?.let { "${it.manufacturer} ${it.model} · ${it.protocol}" } ?: "?")
        else RegisterLogExport.csv(records)
    }

    // ---- Anenji Deep Analyzer (read-only) ---------------------------------------------------------

    private val snapshotFile get() = java.io.File(context.filesDir, "anenji/snapshots.json")

    private fun loadSnapshots(): List<AnenjiSettingsSnapshot> = runCatching { SnapshotCodec.decode(snapshotFile.readText()) }.getOrDefault(emptyList())

    private fun saveSnapshots(list: List<AnenjiSettingsSnapshot>) {
        runCatching { snapshotFile.parentFile?.mkdirs(); snapshotFile.writeText(SnapshotCodec.encode(list.takeLast(200))) }
    }

    private fun deviceName(): String = _live.value.info?.let { "${it.manufacturer} ${it.model} · ${it.protocol}" } ?: "Anenji (nie podłączony)"

    private fun batteryContext(): BatteryContext? {
        val s = settings.value ?: return null
        val b = s.activeBattery ?: return null
        val v = siteConfig.value?.batteryVoltage ?: 48.0
        return BatteryContext(b.type, v, b.nominalCapacityKwh * 1000 / v)
    }

    /** Snapshot of everything known now: device registers (none verified → NOT_AVAILABLE) and app settings (UNVERIFIED). */
    fun takeSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            // Inverter settings are not read: no settings register is verified for this model (→ NOT_AVAILABLE).
            val b = batteryContext()
            val app = buildMap {
                b?.let { put(SettingKey.BATTERY_NOMINAL_VOLTAGE, it.nominalVoltage); it.capacityAh?.let { ah -> put(SettingKey.BATTERY_CAPACITY, ah) } }
            }
            val appText = buildMap { b?.let { put(SettingKey.BATTERY_TYPE, it.type.name) } }
            val snap = AnenjiSettingsSnapshot.build(Instant.now(), deviceName(), app = app, appText = appText)
            val list = (loadSnapshots() + snap).sortedBy { it.timestamp }
            saveSnapshots(list)
            _analyzer.value = _analyzer.value.copy(snapshots = list)
        }
    }

    fun loadAnalyzer() {
        viewModelScope.launch(Dispatchers.IO) {
            val refs = loadReferences()
            _analyzer.value = _analyzer.value.copy(snapshots = loadSnapshots(), references = refs, validation = validation(refs))
        }
    }

    /** Imports a CSV/JSON/TXT log and analyses it. */
    fun importLog(text: String, fileName: String?) {
        viewModelScope.launch(Dispatchers.Default) {
            val log = runCatching { LogImporter.import(text, zone, fileName) }.getOrElse {
                _analyzer.value = _analyzer.value.copy(error = "Import: ${it.message}"); return@launch
            }
            lastImport = log
            val recognized = log.columns.count { it.recognized }
            _analyzer.value = _analyzer.value.copy(
                importSummary = "${fileName ?: "log"} · ${log.format} · ${log.samples.size}/${log.rowsTotal} wierszy · kolumny rozpoznane $recognized/${log.columns.size}" +
                    (log.from?.let { f -> " · ${f.atZone(zone).toLocalDate()} – ${log.to?.atZone(zone)?.toLocalDate()}" } ?: ""),
                importIssues = log.issues, error = null,
            )
            runAnalysis(useImport = true)
        }
    }

    /** Runs the deep analysis on the imported log or on the stored device history (simulator rows kept apart). */
    fun runAnalysis(useImport: Boolean = false) {
        viewModelScope.launch(Dispatchers.Default) {
            _analyzer.value = _analyzer.value.copy(running = true, error = null)
            val result = runCatching {
                val s = settings.value
                val now = Instant.now()
                val import = lastImport.takeIf { useImport }
                val samples: List<AnalyzerSample>
                val comm: List<CommRecord>
                val snaps: List<AnenjiSettingsSnapshot>
                if (import != null) {
                    samples = import.samples
                    comm = import.comm
                    snaps = SnapshotCodec.fromImport(deviceName(), import.settings, import.settingsText)
                } else {
                    val rows = withContext(Dispatchers.IO) {
                        val recent = db.history(now.minus(Duration.ofDays(HistoryDatabase.HISTORY_DAYS)), now)
                        val older = db.history(now.minus(Duration.ofDays(365)), recent.firstOrNull()?.start ?: now, summary = true)
                        older + recent
                    }
                    samples = rows.map { AnalyzerSamples.fromHistory(it, if (it.simulated) DataOrigin.SIMULATOR else DataOrigin.DEVICE) }
                    comm = synchronized(commLog) { commLog.toList() }
                    snaps = loadSnapshots()
                }
                val ctx = s?.let { st -> analysisContext(st) }
                val inv = inverterConfig.value?.takeIf { it.enabled }
                val report = AnenjiDeepAnalyzer.analyze(DeepAnalysisInput(
                    device = if (import != null) "Import: ${_analyzer.value.importSummary?.substringBefore(" ·") ?: "log"}" else deviceName(),
                    samples = samples, comm = comm, snapshots = snaps, battery = batteryContext(), pvLimitW = inv?.ratedPowerW,
                    zone = zone, now = if (import != null) import.to ?: now else now, context = ctx,
                    limits = PlausibilityLimits(batteryNominalV = siteConfig.value?.batteryVoltage ?: 48.0, ratedPowerW = inv?.ratedPowerW ?: 6200.0),
                    forecastAccuracyPercent = _insights.value.periodAccuracy[AccuracyPeriod.DAY]?.accuracyPercent,
                    mppt = _diagnostics.value.mppt, soiling = _diagnostics.value.soiling, degradation = _diagnostics.value.degradation,
                    importIssues = import?.issues.orEmpty(),
                ))
                analyzerSamples = samples.filter { report.simulated || it.origin != DataOrigin.SIMULATOR }
                analyzerEvents = report.events
                analyzerComm = comm
                analyzerSnapshots = snaps
                analyzerNow = if (import != null) import.to ?: now else now
                synchronized(forensicCache) { forensicBase = null; forensicTrends = null; forensicCache.clear() }
                Triple(report, if (import != null) "import" else "historia", snaps)
            }
            _analyzer.value = result.fold(
                { (r, src, snaps) -> _analyzer.value.copy(running = false, report = r, source = src, snapshots = if (src == "import") snaps else loadSnapshots(), incident = null, why = null,
                    forensic = null, reconstruction = null) },
                { _analyzer.value.copy(running = false, error = it.message ?: it.javaClass.simpleName) },
            )
            if (result.isSuccess) runForensics(_analyzer.value.forensicPeriod)
        }
    }

    // ---- Forensic analyzer ("CO SIĘ STAŁO?") – read-only, on the data set of the last analysis ------------------------

    private fun forensicContext(): ForensicContext? = synchronized(forensicCache) {
        forensicBase ?: run {
            if (analyzerSamples.isEmpty() && analyzerComm.isEmpty()) return null
            val ctx = settings.value?.let { analysisContext(it) }
            ForensicContext(analyzerSamples, analyzerEvents, analyzerComm, zone, ctx, SystemBaselineEngine(analyzerSamples, zone, ctx), analyzerSnapshots.lastOrNull(),
                batteryContext(), inverterConfig.value?.takeIf { it.enabled }?.ratedPowerW, settings.value?.prices?.backupPricePerKwh).also { forensicBase = it }
        }
    }

    /** Diagnoses of a period (Today/Yesterday/7/30/90 days/custom); computed in the background and cached per range. */
    fun runForensics(period: ForensicPeriod, custom: Pair<Instant, Instant>? = null) {
        viewModelScope.launch(Dispatchers.Default) {
            _analyzer.value = _analyzer.value.copy(forensicRunning = true, forensicPeriod = period, error = null)
            val result = runCatching {
                val f = forensicContext() ?: error("Najpierw uruchom analizę historii lub zaimportuj log")
                val range = ForensicPeriodAnalyzer.range(period, analyzerNow, zone, custom)
                val report = synchronized(forensicCache) { forensicCache[range] } ?: ForensicPeriodAnalyzer.analyze(f, period, range.first, range.second, analyzerNow)
                    .also { r -> synchronized(forensicCache) { forensicCache[range] = r } }
                val trends = forensicTrends ?: LongTrendAnalyzer.analyze(f, analyzerNow).also { forensicTrends = it }
                Triple(report, trends, ConfigurationForensics.analyze(AnenjiSettingsDiff.timeline(analyzerSnapshots).changes, f, report.diagnoses))
            }
            _analyzer.value = result.fold(
                { (r, t, c) -> _analyzer.value.copy(forensicRunning = false, forensic = r, trends90 = t, configImpacts = c) },
                { _analyzer.value.copy(forensicRunning = false, error = it.message ?: it.javaClass.simpleName) },
            )
        }
    }

    /** State at −60…+60 min around [at] with the diagnoses that overlap it. */
    fun reconstructIncident(at: Instant) {
        viewModelScope.launch(Dispatchers.Default) {
            val f = forensicContext() ?: return@launch
            val diagnoses = _analyzer.value.forensic?.diagnoses?.takeIf { d -> d.any { !it.anomaly.end.isBefore(at.minusSeconds(3600)) && !it.anomaly.start.isAfter(at.plusSeconds(3600)) } }
                ?: ForensicPeriodAnalyzer.analyze(f, ForensicPeriod.CUSTOM, at.minusSeconds(3600), at.plusSeconds(3600), analyzerNow).diagnoses
            _analyzer.value = _analyzer.value.copy(reconstruction = IncidentReconstructor.reconstruct(at, f, diagnoses, analyzerNow))
        }
    }

    /** ANENJI_FORENSIC_PACKAGE.zip: report.pdf + JSON/CSV with evidence and raw sources. */
    suspend fun exportForensicPackage(out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val a = _analyzer.value
        val report = a.forensic ?: error("Najpierw uruchom analizę")
        val period = analyzerSamples.filter { !it.time.isBefore(report.from.minusSeconds(3600)) && !it.time.isAfter(report.to) }
        val input = ForensicPackageExport.Input(report, period, analyzerEvents.filter { !it.start.isBefore(report.from) && !it.start.isAfter(report.to) }, analyzerSnapshots,
            synchronized(registerLog) { registerLog.toList() }.filter { !it.timestamp.isBefore(report.from) && !it.timestamp.isAfter(report.to) },
            a.references, a.trends90, a.configImpacts, BuildConfig.VERSION_NAME, deviceName(), zone, Instant.now())
        val pdf = java.io.ByteArrayOutputStream().also { PdfReport.write(ForensicPackageExport.NAME, ForensicPackageExport.lines(input), it) }.toByteArray()
        val files = LinkedHashMap<String, ByteArray>().apply { put("report.pdf", pdf); putAll(ForensicPackageExport.files(input)) }
        out.write(ForensicPackageExport.zip(files))
    }

    // ---- Validation mode: compare decoded registers with the inverter display (references stay on the phone) ----------

    private val referenceFile get() = java.io.File(context.filesDir, "anenji/manual_references.json")
    private fun loadReferences(): List<ManualReference> = runCatching { ReferenceCodec.decode(referenceFile.readText()) }.getOrDefault(emptyList())
        .also { verifiedRegisters = ValidationMode.verifiedAddresses(it) }

    private fun validation(refs: List<ManualReference>): List<RegisterEvidence> =
        SmgRegisters.LIVE.filter { spec -> refs.any { it.address == spec.address } }.map { ValidationMode.evidence(it, refs) }

    /**
     * Stores what the inverter display shows for [address] next to the last raw register word. Returns an error text or null.
     * Simulator readings are stored as such and never count towards VERIFIED.
     */
    fun addReference(address: Int, displayValue: Double, unit: String): String? {
        val rec = _diagnostics.value.lastRegisters ?: return "Brak odczytu rejestrów – połącz falownik"
        if (!rec.communicationOk) return "Ostatni odczyt nieudany – odczekaj na poprawny"
        val sample = rec.samples.firstOrNull { it.address == address } ?: return "Rejestr nie był odczytany"
        val decoded = sample.decoded ?: return "Wartość rejestru nieprawidłowa (nie zdekodowano)"
        val ref = ManualReference(address, sample.raw, decoded, decoded, displayValue, unit.trim(), Instant.now(), fromRealDevice = !rec.simulated)
        viewModelScope.launch(Dispatchers.IO) {
            val list = (loadReferences() + ref).takeLast(500)
            runCatching { referenceFile.parentFile?.mkdirs(); referenceFile.writeText(ReferenceCodec.encode(list)) }
            verifiedRegisters = ValidationMode.verifiedAddresses(list)
            _analyzer.value = _analyzer.value.copy(references = list, validation = validation(list))
        }
        return null
    }

    fun clearReferences() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { referenceFile.delete() }
            verifiedRegisters = emptySet()
            _analyzer.value = _analyzer.value.copy(references = emptyList(), validation = emptyList())
        }
    }

    /** Model expectation and weather for correlation; cached per 5 minutes. Unknown = null (never assumed). */
    private fun analysisContext(s: AppSettings): ContextProvider {
        val weather = WeatherAwareIrradianceModel(s.location, _weather.value.first, _weather.value.second)
        val estimator = PvEstimator(weather)
        val forecast = _weather.value.first
        val cache = HashMap<Long, AnalysisContext?>()
        return ContextProvider { t ->
            synchronized(cache) {
                cache.getOrPut(t.epochSecond / 300) {
                    runCatching {
                        val e = estimator.pointEstimate(s.system, s.location, t)
                        val hour = forecast?.at(t)
                        AnalysisContext(
                            expectedPvW = if (weather.sourceAt(t) == WeatherSource.FORECAST) e.powerKw * 1000 else null,
                            cloudCoverPercent = hour?.cloudCoverPercent, clearSkyIndex = WeatherEffects.clearSkyIndex(hour, e.sun.position, t),
                            ambientC = hour?.temperatureC, sunElevationDeg = e.sun.elevationDeg,
                        )
                    }.getOrNull()
                }
            }
        }
    }

    fun analyzeIncident(at: Instant) {
        viewModelScope.launch(Dispatchers.Default) {
            val s = settings.value
            val r = IncidentAnalyzer.analyze(at, analyzerSamples, analyzerEvents, zone, s?.let { analysisContext(it) })
            _analyzer.value = _analyzer.value.copy(incident = r)
        }
    }

    /** "Dlaczego…?" – free text or a fixed question type; answered deterministically from the data. */
    fun ask(text: String?, question: WhyQuestion? = null) {
        viewModelScope.launch(Dispatchers.Default) {
            val ref = _analyzer.value.report?.to?.atZone(zone)?.toLocalDate() ?: java.time.LocalDate.now(zone)
            val parsed = question?.let { it to ref } ?: text?.let { WhyAnalyzer.parse(it, ref) }
            if (parsed == null) {
                _analyzer.value = _analyzer.value.copy(error = "Nie rozpoznano pytania – wybierz jedno z gotowych pytań")
                return@launch
            }
            val s = settings.value
            val a = WhyAnalyzer.answer(parsed.first, parsed.second, analyzerSamples, analyzerEvents, zone, s?.let { analysisContext(it) },
                inverterConfig.value?.takeIf { it.enabled }?.ratedPowerW)
            _analyzer.value = _analyzer.value.copy(why = a, error = null)
        }
    }

    suspend fun exportAnalyzerReport(format: String, out: java.io.OutputStream) = withContext(Dispatchers.IO) {
        val r = _analyzer.value.report ?: error("Najpierw uruchom analizę")
        when (format) {
            "json" -> out.write(DeepReportExport.json(r, BuildConfig.VERSION_NAME).toByteArray())
            "csv" -> out.write(DeepReportExport.csv(r, zone).toByteArray())
            else -> PdfReport.write(DeepReportExport.NAME, DeepReportExport.lines(r, zone), out)
        }
    }

    suspend fun exportHistory(json: Boolean): String = withContext(Dispatchers.IO) {
        val now = Instant.now()
        val rows = db.history(Instant.EPOCH, now, summary = true)
        val totals = HistoryPeriods.totals(rows, HistoryPeriod.DAY, zone, now)
        if (json) HistoryExport.json(rows, totals, now, BuildConfig.VERSION_NAME) else HistoryExport.samplesCsv(rows)
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
