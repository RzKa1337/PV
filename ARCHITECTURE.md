# Architecture

Solar Tracker PRO has two Gradle modules.

| Module | Type | Contents |
|---|---|---|
| `:core` | Kotlin/JVM, no Android | All calculations, models, protocols and decision logic. Unit tested on the JVM. |
| `:app` | Android (Compose, Material 3) | UI, persistence (DataStore, SQLite, Keystore), transports (TCP, USB OTG), notifications, widget, updater. |

The rule is simple: **`:core` decides, `:app` shows and stores.** Anything that can be computed without Android lives in `:core`, so it can be tested quickly and deterministically.

## Core packages

| Package | Responsibility |
|---|---|
| `solar` | Sun position, sunrise/sunset, air mass (NOAA algorithm) |
| `pv` | Irradiance models (clear sky, POA), `PvEstimator`, `PvSimulationEngine` (full loss chain, trackers) |
| `weather` | Open-Meteo forecast/climate parsing, weather-aware irradiance |
| `energy` | Battery storage model, chemistry profiles, consumption profiles, flow simulation, costs |
| `inverter` | Brand-independent inverter layer: telemetry model, Modbus RTU/TCP codec, Anenji SMG map, PI30, transports, connection manager, simulator |
| `analytics` | Real energy flow, history aggregation, model comparison, calibration, anomalies/alerts, forecast accuracy, AutoCalibration, period totals |
| `shading` | Obstacles, horizon, geometry, ray-traced shading per panel/bypass group, shading forecasts, map/terrain providers |
| `forecast` | Predictive PV, load forecast, battery SOC prediction, energy forecast, rule-based advisor |
| `health` | Daily statistics, PV Health Score, predictive fault warnings, PV performance (loss shares) |
| `diagnostics` | PV Reality engine, PV Doctor, soiling and degradation detection, MPPT analysis, performance history |
| `ems` | `EnergyOptimizationEngine`: dispatch simulation, windows, load scheduling, explanations (no device control) |
| `economics` | Payback, NPV, ROI, LCOE, storage cost, what-if simulator |
| `design` | PV designer (string sizing), yield estimator, location comparison, tilt optimizer and dynamic tilt schedule, wind safety |
| `vehicle` | Vehicle/camper solar estimates, panel layout optimizer |
| `export` | CSV/JSON export of history |
| `access` | FREE/PRO feature access (`FeatureAccessManager`) |
| `quality` | `DataKind` labels (MEASURED, CALCULATED, ESTIMATED, FORECAST, SIMULATED, STALE, LAST KNOWN, INVALID, N/A, UNKNOWN) |
| `update` | GitHub release check, download with resume, SHA-256 and signer verification |
| `widget`, `live` | Widget summary, 1-second live ticker |

## Data flow (Energy Center)

```
Transport (TCP / Modbus TCP / USB / simulator)
  → InverterProvider (protocol mapping) → InverterConnectionManager (polling, back-off, freshness)
  → EnergyCenterViewModel.onTelemetry
       ├─ RealEnergyFlow, ModelComparison (+ shading), CalibrationEngine, AnomalyDetector → alerts
       ├─ PvRealityEngine → PvDiagnosticEngine (PV Doctor) → DiagnosticsState
       ├─ registerLog (raw registers + quality, read-only) → register log export
       ├─ TelemetryAggregator → HistoryDatabase (30 s rows, 15 min summaries, monthly performance index)
       └─ recomputeForecast → PredictivePvEngine + LoadForecaster + BatteryPredictor, PvRadar, EnergyMission
             └─ recomputeInsights → PvHealthEngine, PredictiveFaultEngine, EnergyOptimizationEngine, HistoryPeriods
```

## Principles

- **No invented data.** Missing values are UNKNOWN/N/A with a reason; estimates and forecasts are labelled.
- **Decision vs control.** EMS only recommends. There is no write path to the inverter (`InverterCommandService` is read-only).
- **Offline first.** Models work without network; weather, map and terrain are cached.
- **Secrets.** Tokens are encrypted with an Android Keystore AES-GCM key and never logged.
- **Feature access** is decided in `core/access`, never inside composables.
