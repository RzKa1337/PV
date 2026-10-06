# Testing

## Core unit tests (JVM)
`./gradlew :core:test` — calculations, protocols and decision logic. Highlights:
- `pv/PvSimulationEngineTest`: loss order, STC, temperature/wind, snow, clipping, trackers, bifacial.
- `scenarios/RealWorldScenariosTest`: 1 / 2.09 / 5 / 10 kWp scaling, flat / 30° / 60° / 1-axis / 2-axis, clear / cloudy / hot / cold / snow, AGM vs LiFePO4, grid vs off-grid (plausibility, not measured reference data).
- `analytics/*`: flow, history integration, calibration, anomalies, forecast accuracy, AutoCalibration, period totals.
- `inverter/*`: Modbus CRC/framing, SMG register mapping, PI30, connection manager (freshness, back-off, out-of-order rejection), simulator.
- `shading/*`: geometry, horizon, obstacle shadows, forecasts — with fake map/terrain/building providers.
- `health/*`, `energy/BatteryChemistryTest`, `economics/EconomicsTest`, `ems/EnergyOptimizationEngineTest` (incl. energy conservation per slot), `design/PvDesignerTest`, `vehicle/VehicleSolarTest`, `export/HistoryExportTest`, `access/FeatureAccessTest`.

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
- `EnergyCenterInstrumentedTest`: simulator shown as SYMULATOR and ONLINE, advisor, Keystore encryption, SQLite history.
- `WidgetInstrumentedTest`, `ToolsInstrumentedTest` (designer validation, location comparison, analyses page).

## What is NOT covered by tests
- A real Anenji inverter: register map and PI30 commands need **REAL DEVICE VALIDATION**.
- Real weather accuracy and real-world shading measurements.
