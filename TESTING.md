# Testing

## Core unit tests (JVM)
`./gradlew :core:test` — calculations, protocols and decision logic. Highlights:
- `pv/PvSimulationEngineTest`: loss order, STC, temperature/wind, snow, clipping, trackers, bifacial.
- `scenarios/RealWorldScenariosTest`: 1 / 2.09 / 5 / 10 kWp scaling, flat / 30° / 60° / 1-axis / 2-axis, clear / cloudy / hot / cold / snow, AGM vs LiFePO4, grid vs off-grid (plausibility, not measured reference data).
- `analytics/*`: flow, history integration, calibration, anomalies, forecast accuracy, AutoCalibration, period totals.
- `inverter/*`: Modbus CRC/framing, SMG register mapping, PI30, connection manager (freshness, back-off, out-of-order rejection), simulator.
- `shading/*`: geometry, horizon, obstacle shadows, forecasts — with fake map/terrain/building providers.
- `health/*`, `energy/BatteryChemistryTest`, `economics/EconomicsTest`, `ems/EnergyOptimizationEngineTest` (incl. energy conservation per slot), `design/PvDesignerTest`, `vehicle/VehicleSolarTest`, `export/HistoryExportTest`, `access/FeatureAccessTest`.

- `diagnostics/*` (0.13.0): reality breakdown adds up, night/sunrise/zero irradiance/no or stale measurement, simulator confidence, heat/cold, snow, shading, clipping, calibration line, AOI; PV Doctor (normal, sustained low/high, communication timeout, missing data, battery full vs empty curtailment, snow, temperature, sensor anomaly, simulator, shading pattern, soiling/degradation/MPPT, conversion efficiency); soiling with rain recovery, build-up, cloudy/snowy/insufficient, shading change; YoY degradation and projection; MPPT example and edge cases; performance history filtering.
- `inverter/RegisterDiagnosticsTest`: register quality (UNVERIFIED / INVALID markers and ranges / SUSPECTED by validation / VERIFIED), CSV/JSON log incl. timeouts, manager log with simulator label; `InverterProtocolTest.modbusWriteFunctionsAreRefused`.
- `forecast/RadarAndMissionTest`: radar horizons, cumulative energy, cloud event vs sunset, missions (survive, empty battery, protect, self-consumption, cost, generator, cold room, no battery/SOC).
- `design/MobileAndTiltTest`: 4290 × 2290 mm roof with 2 × 590 W + 2 × 455 W, margins/gaps/load, rotation, invalid input; tilt schedule (moves, interval, cost, objectives, wind stow), wind levels, seasonal tilts, what-if.

- `anenji/LogImporterTest` (parser): CSV with units and local time, semicolon + decimal comma + epoch + duplicates + bad time, JSON array and our history export, TXT key=value with inline units and whitespace tables, our register log CSV/JSON round trip incl. timeouts and simulator flag, unknown format, missing time column, kW suspicion.
- `anenji/DeepAnalyzerTest`: event log and low-battery pattern with cause, communication score and gaps, settings snapshot/diff/NOT_AVAILABLE, trends and coverage, advisor (clipping energy, capacity ≈ 230 Ah, charge voltage, wrong capacity), incident reconstruction, why-questions (parse + answers), full report with REAL/SIMULATED separation and JSON/CSV/text export, simulator-only and empty input, missing data and impossible values, MPPT health, snapshot storage.
- `fixtures/AnalyzerFixtures`: synthetic 30-day history with known ground truth (not a device recording).
- `anenji/ForensicDataTest`: raw line/field/register provenance through the importer, register quality never VERIFIED without device evidence, validation mode verdicts and ×10/sign hints, VERIFIED only with ≥ 3 real matches at different values, time series without interpolation.
- `anenji/ForensicEngineTest`: baseline in similar conditions only (INSUFFICIENT DATA at night / without a model), injected low PV found but not on cloudy days, weather and shading eliminated, dirt left possible, lost energy and cost, trace down to the raw file, N/A impact without a model, communication downtime, explicit confidence.
- `anenji/ForensicScenarioTest` + `fixtures/ForensicScenarios`: **SIMULATED** scenarios SCENARIO_NORMAL, LOW_PV, MPPT_FAULT, LOW_BATTERY, HIGH_LOAD, GRID_OUTAGE, COMMUNICATION_FAILURE, INVERTER_RESTART, CONFIGURATION_CHANGE with expected diagnoses; incident reconstruction with NO DATA (no interpolation), period ranking, 90-day trends (apparent capacity ≈ 11 kWh, insufficient with < 3 weeks), configuration forensics, WHY restart/consumption, forensic ZIP package contents and traceability, 30-day analysis time.

- `forecast/RadarForecastTest`: dew point (Magnus: normal, high/low humidity, frost, invalid input), Open-Meteo new fields + location zone, DST day with 25 local hours, no forecast, PV forecast (clear, clouds, heat, night, sunrise, peak, best/worst window), rain approaching with PV impact / night rain without, wind and heat alerts with documented thresholds, expected vs actual (exact, +10 %, −10 %, partial, simulator never actual, missing), PV-below-forecast alert, confidence (history, horizon, stale cache, broken clouds, no weather), RainViewer radar (valid, stale, cached, unavailable, malformed/insecure data).

## Fixtures (no physical inverter needed)
- `fixtures/AnenjiFixtures`: SMG register snapshots – clear summer, cloudy summer, winter, zero PV, battery low, battery full, high load, garbage. Synthetic, built with the community register map.
- `fixtures/EnergyFixtures`: day profiles (PV and load) – clear/cloudy/partly cloudy summer, winter, zero PV, high load, cooling load.
- Used by `TelemetryValidationTest`, `EnergySecurityTest`, `PredictiveAlertsTest`, `DigitalTwinTest`.

## App unit tests
`./gradlew :app:testDebugUnitTest` — settings store, update recovery.

## Instrumented tests (emulator, CI job `live-ui-test`)
`scripts/run-live-ui-test.sh` runs `connectedDebugAndroidTest` on an API 30 emulator:
- `LiveSolarInstrumentedTest`: 1-second live values, UI lag ≤ 3 s for ≥ 95 % of seconds.
- `UpdateVerificationInstrumentedTest`: APK identity/signature checks.
- `EnergyCenterInstrumentedTest`: simulator shown as SYMULATOR and ONLINE, advisor, Diagnostyka PV (status, recommendation, simulator has no registers), Analiza Anenji (analysis on history shows SYSTEM HEALTH), Co się stało? (data status always shown, validation mode present), Keystore encryption, SQLite history.
- `RadarInstrumentedTest`: Radar tab shows a radar status (LIVE/STALE/CACHED/UNAVAILABLE – also offline), today's forecast, chart and hourly list.
- `WidgetInstrumentedTest`, `ToolsInstrumentedTest` (designer validation, location comparison, analyses page).

## What is NOT covered by tests
- A real Anenji inverter: register map and PI30 commands need **REAL DEVICE VALIDATION** (record the register log in Diagnostyka PV); procedure with acceptance criteria: [ANENJI_REAL_DEVICE_VALIDATION.md](ANENJI_REAL_DEVICE_VALIDATION.md). All forensic scenarios are SIMULATED.
- Lint (`./gradlew lint`) and the Android build run only in CI (no Android SDK in the development sandbox).
- Real weather accuracy and real-world shading measurements.
