# Changelog

Wszystkie istotne zmiany w projekcie Solar Tracker PRO.
Format oparty na [Keep a Changelog](https://keepachangelog.com/), wersjonowanie [SemVer](https://semver.org/).

## [Unreleased]

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
