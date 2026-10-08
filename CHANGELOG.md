# Changelog

Wszystkie istotne zmiany w projekcie Solar Tracker PRO.
Format oparty na [Keep a Changelog](https://keepachangelog.com/), wersjonowanie [SemVer](https://semver.org/).

## [Unreleased]

## [v0.13.0] - 2026-10-08
### Added – Anenji Deep Analyzer (tylko odczyt)
- `core/anenji`: import logów CSV/JSON/TXT (`LogImporter` – format, kolumny, jednostki, znaczniki czasu, duplikaty, brakujące dane, podejrzenie kW), snapshot ustawień (`AnenjiSettingsSnapshot`, NOT_AVAILABLE bez zweryfikowanych rejestrów, UNVERIFIED z logu/aplikacji) i porównanie w czasie (`AnenjiSettingsDiff`), dziennik zdarzeń z analizą powtarzalności i przyczyn (`AnenjiEventLog`), komunikacja (`AnenjiCommunicationAnalyzer`), trendy 1 h…1 rok (`TrendAnalyzer`), doradca konfiguracji (`AnenjiConfigurationAdvisor`), „co się stało?” (`IncidentAnalyzer`), „dlaczego?” (`WhyAnalyzer`, deterministyczny), `SystemHealthEngine` (wyjaśnialne potrącenia), raport `ANENJI_FULL_DIAGNOSTIC_REPORT` (JSON/CSV/PDF).
- Ekran **Centrum → Analiza Anenji**; snapshoty przechowywane w telefonie; historia v5 oznacza wiersze z symulatora (dane REAL/SIMULATED rozdzielone w analizie).
### Fixed
- Wykrywanie separatora CSV liczyło przecinki w cudzysłowach (log rejestrów z nazwą „Kod błędu (u32, …)”).
### Added
- PV Reality & Diagnostics (`core/diagnostics`): `PvRealityEngine` (teoretyczna → straty → oczekiwana → rzeczywista + niewyjaśniona, pewność), `PvDiagnosticEngine` (PV Doctor: 14 typów diagnoz z ważnością, pewnością, dowodami, wpływem i zaleceniem), `SoilingDetector` (dni pogodne + poprawa po deszczu), `DegradationAnalyzer` (metoda rok-do-roku), `MpptAnalyzer` (porównanie z modelem i z pozostałymi MPPT), `ConversionEfficiency`.
- Łańcuch strat: krok AOI (ASHRAE IAM, b₀ = 0,05) i MPPT (`LossProfile.mpptEfficiency`).
- `DataKind.SIMULATED` – dane symulatora nie są już oznaczane jako pomiar.
- Jakość każdego rejestru (VERIFIED/UNVERIFIED/SUSPECTED/INVALID, zakresy fizyczne) i log surowych rejestrów z każdego odczytu (także przekroczeń czasu) z eksportem CSV/JSON – tylko odczyt.
- PV Radar (`PvRadarBuilder`): TERAZ, +5, +15, +30 min, +1, +2, +3, +6 h z pewnością, wpływem chmur i energią; wykrywanie spadków przez chmury (bez mylenia z zachodem), z jawną rozdzielczością godzinową.
- Misje energetyczne (`EnergyMissionPlanner`) na scenariuszach bezpieczeństwa energetycznego: przetrwaj noc, autokonsumpcja, ochrona baterii, chłodnia, agregat, koszt sieci.
- Rdzeń + testy (bez ekranu): optymalizator rozmieszczenia paneli na dachu (`PanelLayoutOptimizer`), harmonogram kąta z ograniczeniami (`TiltScheduleOptimizer`), bezpieczeństwo wiatrowe (`PvWindSafetyEngine`), symulator „co jeśli” (`WhatIfSimulator`); porywy wiatru z Open-Meteo.
- Ekran **Centrum → Diagnostyka PV**; baza historii v4 (miesięczny wskaźnik wydajności do analizy degradacji).
### Fixed
- `PvPerformanceAnalyzer`: małe straty (< 0,05%) były pomijane, przez co suma rozkładu nie równała się różnicy (test regresyjny).
- Reguła śniegu zduplikowana w `WeatherEffects` i `PvSimulationEngine` – jedna wspólna funkcja.

## [v0.12.0] - 2026-10-07
### Security
- Audyt bezpieczeństwa (`docs/SECURITY_AUDIT.md`): CI z minimalnymi uprawnieniami (zapis tylko w zadaniu `build`), akcja emulatora przypięta do SHA, generator klucza podpisu blokowany w publicznym repozytorium, test regresyjny odrzucania poleceń zapisu do falownika (Modbus i PI30).
### Changed
- Pulpit: przebudowana karta pogody – nagłówek z dużą temperaturą, kompaktowe „pigułki” (wiatr, wilgotność, opady, UV w kolorach WHO z nazwą poziomu), pasek „Słońce wg prognozy”, warstwy chmur i paski produkcji dziś/jutro względem czystego nieba; czas prognozy i atrybucja w jednej stopce.
- Pulpit: gdy falownik nie jest podłączony, zamiast sześciu pustych kafelków pokazywana jest jedna podpowiedź konfiguracji + szacunki modelu; karta bezpieczeństwa energetycznego tylko przy skonfigurowanej baterii lub falowniku.

## [v0.11.0] - 2026-10-07

### Added
- Dashboard: cloud impact on today's and tomorrow's production ("X kWh zamiast Y kWh przy czystym niebie, chmury −Z%")
- SOC estimated from the resting battery voltage (± uncertainty) when the inverter does not report SOC; used by energy security with reduced confidence
- Cold-room power schedule editor in Konfiguracja
- **English UI** (Ustawienia → Język / Language: Polski – default, English, Systemowy): bottom navigation, all screen titles, Pulpit, Live, Kąty, Miesiące, Energia, Centrum energii (main page, Analizy, Konfiguracja, main labels of Analiza zacienienia), Narzędzia, Ustawienia, Aktualizacje and the home-screen widget. Texts live in `res/values` (Polish) and `res/values-en`; numbers, months and dates follow the chosen language. The choice applies immediately (the screen is rebuilt)
- **System Back button no longer closes the app**: it first closes an open sub-page (Centrum → Zacienienie/Konfiguracja/Analizy, map drawing/picking, a non-default tool) and then returns to the previously visited tab; on Pulpit with no history a second press within 2 s exits

### Known gaps (English UI)
- Still Polish in English mode: texts generated in `:core` (Solar Advisor questions/answers, alerts and their details, health/EMS/daily-report explanations, forecast bases, telemetry issue and link-error names, inverter link/protocol names, feature-lock reasons, SOC milestone and 72 h day labels), the obstacle editor dialog, detailed data lines of Analiza zacienienia, notifications (updates, daily report) and the PDF/CSV export
- Currency stays "zł" (PLN)

### Changed
- CI runners pinned to ubuntu-24.04 (ubuntu-latest moves to Ubuntu 26 on 2026-10-19)

## [v0.10.0] - 2026-10-06

### Added
- **Anenji read-only telemetry validation**: physical ranges, P≈U×I consistency, SOC/voltage jump detection, zero PV in sunshine, stale and frozen data; rejected values shown as BŁĘDNE (INVALID) and kept out of history, calibration and forecasts; link error classes (timeout, CRC/frame, device rejected, connection), reconnects and read statistics; SMG register catalogue (REAL_DEVICE_VALIDATION_REQUIRED) with raw register checks
- **AutoCalibration 3.0**: learned per sky condition (clear/partly/overcast/rain/snow), sun elevation, hour and month with shrinkage and confidence; excludes faults, link loss, invalid data, clipping, PV off, low sun, heavy shading; MAE/RMSE/bias before vs after on held-out days; used by the PV forecast
- **Forecast vs actual**: 5 min / 15 min / 1 h / day-ahead and day/week/month aggregation, R², today's accuracy, live forecast/actual/error
- **Energy security** ("czy wystarczy energii?"): SOC at 21:00, 00:00, 03:00, 06:00, 08:00 with range, time to minimum SOC, security %, risk and shortage warning
- **72 h outlook**: today/tomorrow/+2/+3 with PV, load, battery, balance, SOC min/max, risk and confidence
- **Cold room** (cooling load) model with compressor duty cycle; added to the load forecast until measured history exists
- **Mobile PV**: vehicle dimensions, panel layout validation, energy for headings 0–359°, best parking heading, tilt × heading
- **Tilt optimizer** (Narzędzia → Kąt paneli): best static, daily and monthly tilt; movable-rack model (no actuator control)
- **PV performance**: expected vs actual with estimated loss shares
- **Predictive alerts**: battery depletion, over-temperature, communication loss, forecast deterioration, unexpected load, clipping, charging and voltage anomalies, unusually low PV
- **Daily report** (Analizy + evening notification) and **digital twin**
- Dashboard: energy security, PV now/today, load, SOC, forecast accuracy, Anenji status
- Synthetic fixtures for Anenji registers and day profiles

## [v0.9.0] - 2026-10-05

### Added
- EMS settings (Centrum → Konfiguracja): flexible loads (power, run time, time window, surplus only) and generator (power, start/stop SOC, minimum run, fuel use); Analizy shows start times per load and generator runs with fuel estimate. Recommendations only – nothing is switched
- Weather: wind, humidity, precipitation, snow depth and visibility from Open-Meteo; forecasts include wind cooling and drop to zero under forecast snow on the panels; dashboard shows the extra fields and a snow warning
- Forecast accuracy: hour-ahead and day-ahead PV forecasts are stored and compared with measured production (30 days: MAE, RMSE, MAPE, bias); hours with data gaps and night hours are excluded

## [v0.8.0] - 2026-10-05

### Added
- **PV loss chain** (`PvSimulationEngine`): POA (+bifacial rear), Faiman cell temperature with wind, snow cover, soiling, shading, mismatch, degradation, DC wiring, inverter efficiency/self-consumption, clipping, AC wiring; fixed, 1-axis and 2-axis trackers; performance ratio
- Forecast accuracy (MAE, RMSE, MAPE, bias) and **AutoCalibration 2.0** (yield, temperature coefficient, soiling trend, forecast bias, clipping) with change history and rollback
- **PV Health Score** 0–100 with deductions backed by evidence and an explicit "not assessed" list; **predictive warnings** (efficiency drop, PV voltage drift, inverter temperature, night load, recurring fault codes, link quality, string imbalance) labelled ANOMALY vs FAULT
- **Battery chemistry** (LiFePO4, Li-ion, AGM, GEL, lead acid, custom): resting-voltage SOC (ESTIMATED, UNKNOWN under load), Peukert and cold capacity, charge temperature limits, cycle life vs DoD, ageing/SOH, time to full/minimum, capacity estimate from history
- **Economics**: payback (simple and discounted), NPV, ROI, LCOE, storage cost, generator savings
- **EMS** (recommendations only, no device control): battery-first dispatch with grid/off-grid/generator, surplus and deficit windows, flexible load scheduling, rest-of-day and 7-day balance
- **Tools tab**: PV designer (string sizing from datasheet values), clear-sky yield and optimal tilt, location comparison, vehicle mode (energy, range, best parking heading)
- **Energy Center → Analizy**: health, warnings, EMS decisions, history day/week/month/year/lifetime with self-consumption, autarky and data completeness
- Export to CSV, JSON and PDF via the system file picker
- FREE/PRO feature access layer (`FeatureAccessManager`) separate from UI; no billing in this build, all features unlocked and stated as such
- Docs: ARCHITECTURE, CALCULATIONS, TESTING, ROADMAP, API

### Changed
- CI: GitHub Actions updated to Node 24 versions (checkout v6, setup-java v5, setup-gradle v5, upload-artifact v6)

## [v0.7.0] - 2026-10-05

### Added
- Home-screen widget: model PV power now (estimate/forecast label), today's expected and remaining production, last inverter reading (shown as current only when < 15 min old), sunrise/sunset; cached weather only, refresh every 30 min, when leaving the app and with ⟳

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
