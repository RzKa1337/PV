# Changelog

Wszystkie istotne zmiany w projekcie Solar Tracker PRO.
Format oparty na [Keep a Changelog](https://keepachangelog.com/), wersjonowanie [SemVer](https://semver.org/).

## [Unreleased]

## [v0.6.1] - 2026-10-05

### Added
- Settings → Lokalizacja: search a city, address or postcode and pick the result (coordinates and elevation filled in automatically)

## [v0.6.0] - 2026-10-05

### Added
- **Anenji 6.2 kW 48 V integration** (ANJ-6200W-48V, RS232): Modbus RTU with the community-documented SMG register map and the PI30 ASCII variant, over a TCP RS232 bridge, a Modbus TCP gateway or a USB-RS232 cable (OTG); simulator clearly labelled as test data. REAL DEVICE VALIDATION REQUIRED – see `ANENJI_INTEGRATION.md`
- Brand-independent inverter architecture: `InverterProvider`, `InverterRepository`, `InverterConnectionManager` (polling, back-off, OFFLINE after failures, STALE/LAST KNOWN data never shown as live, duplicate/out-of-order rejection), `InverterDataMapper`, `InverterTelemetry`, read-only `InverterCommandService`
- Data quality labels everywhere: MEASURED, CALCULATED, ESTIMATED, FORECAST, STALE, LAST KNOWN, N/A (with reason), UNKNOWN
- **Energy Center** tab: connection status, LIVE telemetry, real energy flows (PV→load/battery/grid, battery→load, grid→load/battery), production vs model with likely causes, gradual model calibration (median, outlier rejection, confidence), short-term (+5 min … +6 h) and today/tomorrow forecasts with ranges, SOC prediction (1 h, 3 h, 21:00, midnight, 07:00, charging start/end, minimum SOC time), shading summary, grouped alerts with notifications, rule-based Solar Advisor answering only from data
- Local telemetry history (SQLite): 30 s rows, 15 min summaries, calibration samples, automatic pruning; energy integrated from power without bridging link gaps
- **Shading analysis** (`SHADING_ANALYSIS.md`): location by city/address/postcode or map tap with accuracy and confirmation; OpenStreetMap buildings/trees/chimneys/masts/walls (Overpass) with height source (map tag, storeys estimate, user measured/estimated, UNKNOWN – never invented); Copernicus DEM terrain horizon; ray-traced shade per panel and bypass group with string/MPPT model; shadow start/end, obstacle, panels, energy loss; 360° horizon chart, hourly chart, map layers with current and selected-time shadows, time slider, obstacle editor with history, monthly/yearly losses, confidence score and missing-data list; offline cache
- Shading is included in the PV model, forecasts, model comparison and anomaly detection (PV underperformance, possible PV fault, expected shading, unexpected load, abnormal SOC, inverter offline, battery/inverter limits, inverter faults)

### Changed
- Update check: HTTP 403/429 now shows GitHub's reason (e.g. token without Contents permission, with instructions) instead of a generic message
- The updater's GitHub token is stored encrypted with an Android Keystore key (plaintext from v0.5.0 is migrated)

## [v0.5.0] - 2026-10-05

### Added
- Automatic updates from GitHub releases (Settings → Aktualizacje): configurable repository, stable/beta channel, check interval (6 h – weekly or manual), auto-download, Wi-Fi only, optional auto-install (Android 12+), optional read-only token for private repositories (stored only on the phone, excluded from backups)
- Downloads resume after interruptions (HTTP Range), show progress and retry with exponential back-off; can be cancelled, snoozed (24 h) or skipped
- Verification before installation: SHA-256 from the release's `SHA256SUMS`, package name, newer versionCode, version matching the release and signing certificate identical to the installed app; unverified files are deleted
- Installation through the system PackageInstaller (atomic), restart notification after the update
- Settings backup before installing; restored automatically when the new version crash-loops or loses them; such versions are marked bad and not offered again
- Persistent update log viewable in the app
- CI: release APK signed with a key from GitHub Secrets (`docs/RELEASE_SIGNING.md`, one-time key generation workflow); releases publish `SolarTrackerPRO-<tag>-universal.apk`, `SHA256SUMS` and the certificate digest

### Fixed
- Live Solar emulator test no longer fails on a single late tick of a busy emulator (all seconds are still required)

## [v0.4.0] - 2026-10-05

### Added
- Live Solar tab: sun position and all dependent values recomputed locally every second while the screen is visible (clock synchronised to the system second, no drift)
- Azimuth, elevation, zenith, hour angle, declination, air mass (elevation-corrected), angle of incidence, geometric utilisation, POA/GHI/DNI/DHI, cell temperature, time to sunrise/sunset
- Modelled PV power ("Szacowana moc PV", never presented as a measurement), irradiance source label
- Sun position compass, today's sun path chart, LIVE card, "Energia teraz" with battery flow/SOC and animated energy flow
- Optional elevation above sea level (GPS altitude or manual)
- Instrumented test watching the Live screen for 3 minutes on an Android emulator in CI (state stream + screen via UiAutomator)
- `SecondTicker` back-fills seconds missed during a short stall (≤ 5 s)

### Changed
- `SolarCalculator.position` now derives from `details`; clear-sky air mass and flow-step rules shared with the live calculations

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
