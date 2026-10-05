# Changelog

Wszystkie istotne zmiany w projekcie Solar Tracker PRO.
Format oparty na [Keep a Changelog](https://keepachangelog.com/), wersjonowanie [SemVer](https://semver.org/).

## [Unreleased]

## [v0.3.0] - 2026-10-05

### Added
- Weather: hourly Open-Meteo forecast (16 days: direct/diffuse irradiance, temperature, cloud cover) and monthly climate from the Open-Meteo archive (last 3 years), cached for offline use; built-in approximate climate for Poland as offline fallback
- `WeatherAwareIrradianceModel` used by the shared PV estimator: forecast → climate (two-state clear/overcast sky) → clear sky
- Panel temperature losses from air temperature (NOCT model, −0.4%/°C)
- Weather card on the dashboard, "Pogoda" settings section (on/off, refresh, status), data source shown under every estimate

### Changed
- All estimates (dashboard, angles, monthly, energy balance, battery, costs) now follow the weather data when enabled

## [v0.2.0] - 2026-10-04

### Added
- Battery energy storage: capacity, usable %, initial/min/max SOC, charge/discharge power and efficiency, battery type (LiFePO4, Li-ion, AGM, GEL, other) with validation
- Consumption profile: constant load or hourly periods
- `EnergyFlowSimulator` (15-minute steps) using the existing PV estimator: PV → loads → battery → grid/generator, surplus and losses
- Multi-day simulation (today, tomorrow, 7 days, month, year) carrying SOC over to the next day
- "Energia" tab: energy balance, with/without battery comparison, flow chart, SOC chart, battery statistics, autonomy calculator, yearly costs and payback
- Battery card on the dashboard (SOC, stored energy, charge/discharge power)
- Optional energy prices (grid, generator, feed-in, battery cost)

## [v0.1.0] - 2026-10-04

- Created Android project (Kotlin, Jetpack Compose, Material 3)
- Added pure Kotlin `:core` module for calculations
- Added solar position calculations (elevation, azimuth, sunrise, sunset, day length)
- Added PV production estimator (clear-sky model), angle comparison and monthly estimates
- Added settings storage (DataStore): kWp, tilt 0–90°, azimuth 0–359°, location, theme
- Added manual location and optional GPS location (no GPS required)
- Added main dashboard: sun elevation/azimuth, sunrise/sunset, day length, PV power, energy today, daily forecast
- Added hour → PV power chart
- Added panel angle comparison (0–90°)
- Added monthly production for 0°, 30°, 45°, 60°, 90°
- Added Material 3 light/dark theme
- Added unit tests (solar position, sunrise/sunset, PV production, angles, locations, extreme values, settings, ViewModel)
- Added GitHub Actions CI (unit tests, lint, debug APK, APK attached to releases)

### Fixed
- Avoid `LocalDate.ofInstant` (API 34) – would crash on Android 8–13
