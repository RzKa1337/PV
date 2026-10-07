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
- `EnergyCenterInstrumentedTest`: simulator shown as SYMULATOR and ONLINE, advisor, Diagnostyka PV (status, recommendation, simulator has no registers), Keystore encryption, SQLite history.
- `WidgetInstrumentedTest`, `ToolsInstrumentedTest` (designer validation, location comparison, analyses page).

## What is NOT covered by tests
- A real Anenji inverter: register map and PI30 commands need **REAL DEVICE VALIDATION** (record the register log in Diagnostyka PV).
- Lint (`./gradlew lint`) and the Android build run only in CI (no Android SDK in the development sandbox).
- Real weather accuracy and real-world shading measurements.
