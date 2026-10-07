# Core API (selected entry points)

All in `core/src/main/kotlin/com/solartracker/pro/core`.

| Entry point | Input | Output |
|---|---|---|
| `pv.PvSimulationEngine.simulate / daily` | `PvArrayConfig`, `LossProfile`, location, time, conditions | `PvSimulationPoint` / `PvLossBreakdown` (waterfall) |
| `forecast.EnergyForecastEngine` | PV engine, load forecaster, battery predictor | day/short-term forecasts, SOC prediction, timeline |
| `analytics.ForecastAccuracy.evaluate` | forecast/actual pairs | MAE, RMSE, MAPE, bias |
| `analytics.AutoCalibrationEngine.evaluate` | observations | corrections with history |
| `analytics.HistoryPeriods.totals` | `HistorySample`s, period, zone | `PeriodTotals` |
| `health.PvHealthEngine.assess` | `HealthInput` | `HealthReport` (score or null, deductions, unknowns) |
| `health.PredictiveFaultEngine.analyze` | `DayStats` | `FaultWarning`s |
| `energy.BatteryChemistry` / `BatteryAgingModel` / `BatteryTiming` / `BatteryCapacityEstimator` | chemistry, voltages, powers | SOC estimate, capacity, cycles, SOH, times |
| `economics.EconomicsEngine.evaluate` | `SystemCosts`, `TariffAssumptions`, `EnergyYear` | `EconomicsResult` |
| `ems.EnergyOptimizationEngine.plan` | `EmsInput` (slots, battery, loads, generator) | `EmsPlan` (simulation, windows, recommendations, decisions) |
| `design.PvDesigner.design` | `DesignInput` (datasheets) | `DesignResult` (layout, voltages, warnings) |
| `design.YieldEstimator.annual / optimalTilt` | array, losses, location | `AnnualYield` (clear-sky upper bound) |
| `design.LocationComparison.compare` | named locations | `SiteYield`s |
| `vehicle.VehicleSolarEstimator` | `VehicleSolarConfig`, heading, period | `VehicleEstimate` |
| `export.HistoryExport` | samples, totals | CSV / JSON text |
| `access.FeatureAccessManager` | `SubscriptionState` | enabled / reason per `Feature` |

External network APIs used by the app (all optional, cached): Open-Meteo (forecast, climate, geocoding, elevation), Nominatim (address search), Overpass (OSM buildings/trees), GitHub Releases (updates). No API keys are embedded.

## PV Reality & Diagnostics (0.13.0)

| Entry point | Input | Output |
|---|---|---|
| `diagnostics.PvRealityEngine.assess` | array, losses, location, time, conditions, measured `Quantity`, `IrradianceBasis`, shading/calibration | `PvReality` (theoretical, `LossItem`s, expected, actual, unexplained, uncertainty, confidence, status) |
| `diagnostics.PvDiagnosticEngine.diagnose` | `DiagnosticInput` (reality, recent samples, link, validation, snow, soiling, degradation, MPPT, flow) | `PvDiagnosis` (status, primary + ranked `Diagnosis` with severity, confidence, evidence, impact, recommendation) |
| `diagnostics.SoilingDetector.assess` | `DailyPerformance` list | `SoilingAssessment` (NONE / SUSPECTED / CONFIRMED / INSUFFICIENT_DATA, loss, rate, rain recovery) |
| `diagnostics.DegradationAnalyzer.assess` | `MonthlyPerformance` list, nameplate | `DegradationAssessment` (%/year, trend, confidence, capacity projection) |
| `diagnostics.PerformanceHistory.daily / monthly` | stored `CalibrationObservation`s, rain | daily / monthly clear-sky performance index |
| `diagnostics.MpptAnalyzer.analyze` | `MpptReading`s, `MpptConfig`s, expected per MPPT | `MpptAnalysis` (deviation vs model and peers, mismatch, possible causes) |
| `diagnostics.ConversionEfficiency.estimate / median` | telemetry | PV → loads + battery efficiency (ESTIMATED) |
| `inverter.RegisterDiagnostics.smgSamples / quality` | raw SMG blocks, validation issues | `RegisterSample`s with `RegisterQuality` |
| `inverter.InverterConnectionManager.registerLog` | – | `RegisterLogRecord` per poll (incl. timeouts) |
| `export.RegisterLogExport.csv / json` | `RegisterLogRecord`s | CSV / JSON text |
| `forecast.PvRadarBuilder.build` | PV engine, clear-sky engine, nowcast | `PvRadar` (NOW…+6 h, cloud impact, energy, `CloudEvent`) |
| `forecast.EnergyMissionPlanner.evaluate` | `MissionRequest`, SOC, PV/load forecast | `MissionResult` (achievable, probability estimate, milestones, margin, deficit, metrics) |
| `vehicle.PanelLayoutOptimizer.optimize` | `RoofArea`, `PanelType`s | `LayoutResult` (placements, Wp, utilisation, mass, centre of gravity) |
| `design.TiltScheduleOptimizer.optimize / seasonal` | array, mount constraints, objective, conditions, load | `TiltSchedule` (moves, gain vs static, worth it) |
| `design.PvWindSafetyEngine.assess / worstAhead` | `WindPanelSetup`, gust/mean wind, forecast | `WindAssessment` (SAFE … STOW_IMMEDIATELY, recommended tilt) |
| `economics.WhatIfSimulator.compare` | `WhatIfBase`, `WhatIfChange`, tariff | `WhatIfResult` (annual PV, autarky, backup energy, cost delta, payback) |

## Anenji Deep Analyzer (read-only)

| Entry point | Input | Output |
|---|---|---|
| `anenji.LogImporter.import` | text, zone, file name | `ImportedLog` (format, layout, columns, samples, comm, settings, issues, origin) |
| `anenji.AnenjiSettingsSnapshot.build / format` | device registers (verified only), imported/app values | snapshot with VERIFIED / UNVERIFIED / NOT_AVAILABLE per `SettingKey` |
| `anenji.AnenjiSettingsDiff.between / timeline` | snapshots | changes with time window, or "Nie wykryto zmian konfiguracji" |
| `anenji.SnapshotCodec` | snapshots / imported settings | JSON storage, snapshot timeline from a log |
| `anenji.AnenjiEventLog.build / patterns` | samples, comm, setting changes | `AnenjiEvent`s; `EventPattern`s (frequency, duration, dominant window, preceding conditions, likely cause, confidence) |
| `anenji.AnenjiCommunicationAnalyzer.analyze` | samples, `CommRecord`s | `CommunicationReport` (score with penalties, timeouts, CRC, frames, gaps, longest outage) |
| `anenji.TrendAnalyzer.analyze` | samples, now | `ChannelStats` per channel × window (min, max, avg, median, P95, slope, direction, robust anomalies) |
| `anenji.AnenjiConfigurationAdvisor.analyze` | samples, snapshot, battery, PV limit, expected PV | `Finding`s (reason, evidence, confidence, impact) – never writes |
| `anenji.IncidentAnalyzer.analyze` | moment, samples, events, context | `IncidentReport` (timeline, conclusion, evidence, confidence) |
| `anenji.WhyAnalyzer.parse / answer` | question text or `WhyQuestion`, date | `WhyAnswer` (findings, conclusion, confidence) |
| `anenji.SystemHealthEngine.assess` | `HealthEvidence` | `SystemHealth` (overall + 8 categories with deductions; N/A when not assessable) |
| `anenji.AnenjiDeepAnalyzer.analyze` | `DeepAnalysisInput` | `DeepAnalysisReport` (REAL/SIMULATED separated) |
| `export.DeepReportExport.json / csv / lines` | report | `ANENJI_FULL_DIAGNOSTIC_REPORT` |
