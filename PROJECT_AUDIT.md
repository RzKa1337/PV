# PROJECT AUDIT — Solar Tracker PRO

Data audytu: 2026-10-05 · gałąź `ccr-81f74041-r3omte` · wersja v0.7.0 (versionCode 8) · 46 commitów.
Kod: `:core` (czysty Kotlin/JVM, ~6,3 tys. linii, 188 testów JVM) + `:app` (Android/Compose, ~8,3 tys. linii,
testy JVM aplikacji + 7 testów instrumentalnych na emulatorze w CI). CI: GitHub Actions (testy, lint, debug + release
APK podpisany kluczem z Secrets, emulator API 30, wydanie z `SHA256SUMS` przez `workflow_dispatch release_tag`).

Legenda: ✅ REAL (działa na prawdziwych danych / obliczeniach) · 🟡 częściowo · 🧪 tylko symulator/test ·
❌ brak · ⚠️ wymaga walidacji w terenie.

## Moduły docelowe → stan kodu

| Moduł docelowy | Istniejący kod | Stan |
|---|---|---|
| AstronomyEngine | `core/solar/SolarCalculator` (NOAA/Meeus, refrakcja, wschód/zachód, noc/dzień polarny) | ✅ testy NREL/NOAA |
| PVSimulationEngine | `core/pv/PvEstimator` (POA izotropowy + albedo 0,2, NOCT, wsp. temp. −0,4%/°C, PR 0,80) | 🟡 brak jawnego łańcucha strat (zabrudzenie, mismatch, DC/AC, sprawność falownika, clipping, degradacja, bifacial, śnieg) |
| PredictivePVEngine | `core/forecast/PredictivePvEngine` | ✅ (pogoda → klimat → bezchmurnie, cień, kalibracja, nowcast, limit falownika) |
| EnergyForecastEngine | `core/forecast/EnergyForecastEngine` | 🟡 +5 min…+6 h, dziś/jutro; brak wieczór/noc/7 dni, nadwyżka/deficyt |
| BatteryEngine | `core/energy/BatteryStorage`, `EnergyFlowSimulator`, `forecast/BatteryPredictor` | 🟡 SOC, sprawności, limity; brak chemii (krzywe napięcia AGM/GEL/kwas), degradacji, cykli, czasu do pełna |
| LoadPredictionEngine | `core/forecast/LoadForecaster` | ✅ profile godzinowe, dni robocze/weekend, wagi wieku, odrzucanie anomalii |
| EnergyFlowEngine | `energy/EnergyFlowSimulator` (model), `analytics/RealEnergyFlow` (pomiar) | 🟡 brak generatora i baterii→sieć w rozkładzie pomiarów |
| AutoCalibrationEngine | `analytics/CalibrationEngine` (jeden współczynnik, mediana+MAD, pewność) | 🟡 brak rozbicia na straty, historii korekt, wyłączania |
| Forecast accuracy (MAE/RMSE/MAPE/bias) | — | ❌ |
| PVHealthEngine | — | ❌ |
| PredictiveFaultEngine | `analytics/AnomalyDetector`, `AlertManager` | 🟡 brak dowodów/pewności/rekomendacji, rozróżnienia anomalia vs awaria |
| ShadingEngine, SolarHorizonProfile, ObstacleGeometryService, Map/Terrain/Building providers | `core/shading/*` | ✅ ⚠️ dane mapowe wymagają walidacji |
| WeatherProvider | `app/data/WeatherRepository` (Open-Meteo: GHI/DNI/DHI, temp., zachmurzenie; cache, offline) | 🟡 brak wiatru, wilgotności, opadów, śniegu, mgły, widoczności; brak abstrakcji w core |
| InverterProvider / AnenjiProvider | `core/inverter/*` (Modbus SMG, PI30, TCP/USB, symulator) | ✅ 🧪 ⚠️ REAL DEVICE VALIDATION REQUIRED |
| EnergyOptimizationEngine, EMSController | — | ❌ |
| EconomicsEngine | `energy/EnergyCost` (koszt dnia/okresu, porównanie z/bez baterii) | 🟡 brak ROI, okresu zwrotu, LCOE, kosztu magazynowania |
| AIAdvisor | `forecast/SolarAdvisor` (reguły, tylko dane z aplikacji) | ✅ do rozszerzenia o nowe pytania |
| UpdateManager | `core/update/*`, `app/update/*` | ✅ (GitHub Releases, kanały, SHA-256, certyfikat, PackageInstaller, kopia ustawień, dziennik) |
| SubscriptionManager, FeatureAccessManager | — | ❌ |
| World PV map / porównanie lokalizacji | — | ❌ (jest porównanie kątów i miesięcy dla jednej lokalizacji) |
| PV Designer | — | ❌ |
| Vehicle / mobile mode | — | ❌ |
| Export CSV/JSON/PDF | — | ❌ |
| Historia dzień/tydzień/miesiąc/rok | SQLite: wiersze 30 s, podsumowania 15 min | 🟡 brak ekranu historii i agregacji tydzień/rok |
| Widget | `widget/SolarWidgetProvider` | ✅ |
| Live Solar | `core/live`, ekran Live | ✅ test 3 min na emulatorze |

## Dane: REAL vs MOCK

- Obliczenia astronomiczne, model PV, zacienienie, prognozy: deterministyczne obliczenia (oznaczane SZACUNEK/PROGNOZA).
- Pogoda: prawdziwe dane Open-Meteo (z cache); bez sieci – klimat lub bezchmurne niebo (oznaczone).
- Falownik: prawdziwy protokół (Modbus SMG/PI30), **nie zweryfikowany na urządzeniu**; symulator zawsze oznaczony SYMULATOR.
- Brak danych udających pomiary (sprawdzone: `FakeAnenjiProvider` ma `simulated = true`, UI pokazuje etykietę).

## Ryzyka / dług techniczny

- `PvSystem.performanceRatio` łączy wszystkie straty w jeden współczynnik – nie da się wyjaśnić, skąd strata.
- Brak ekranu historii i eksportu – dane są zbierane, ale niedostępne dla użytkownika.
- CI: ostrzeżenia o wycofaniu Node 20 / setup-java v4 (do aktualizacji akcji).
- Aktualizacja przez Google Play nie dotyczy (dystrybucja przez GitHub Releases).

## Plan faz (kolejność wg wartości i zależności)

1. PV loss chain (PVSimulationEngine z jawnymi stratami) + dokładność prognoz + AutoCalibration 2.0.
2. PVHealthEngine + PredictiveFaultEngine (dowody, pewność, rekomendacje).
3. BatteryEngine (chemia, napięcie↔SOC, degradacja, cykle, czas do pełna/minimum) + EconomicsEngine (ROI, zwrot, LCOE).
4. EnergyOptimizationEngine + EMSController (okna nadwyżki, decyzje dla odbiorników, bez fizycznego sterowania).
5. PV Designer + World PV map + Vehicle mode.
6. Historia + eksport CSV/JSON/PDF + FeatureAccess/Subscription.
7. UI (ekran Narzędzia, zdrowie, ekonomia, historia), dokumentacja (ARCHITECTURE, ROADMAP, API, CALCULATIONS, TESTING), CI.

## Stan po rozbudowie (FINAL MASTER EXPANSION, wersja 0.8.0)

| Moduł docelowy | Kod | Stan |
|---|---|---|
| PVSimulationEngine (łańcuch strat) | `core/pv/PvSimulationEngine` | ✅ testy (w tym scenariusze 1/2,09/5/10 kWp, kąty, trackery, pogoda, śnieg) |
| Forecast accuracy, AutoCalibration 2.0 | `core/analytics/ForecastAccuracy`, `AutoCalibration` | ✅ testy; panel dokładności w UI — do zrobienia (wymaga zapisu prognoz) |
| PVHealthEngine, PredictiveFaultEngine | `core/health/*`, ekran Centrum → Analizy | ✅ testy; ⚠️ wymaga historii z prawdziwego falownika |
| BatteryEngine (chemia, starzenie, czasy) | `core/energy/BatteryChemistry` | ✅ testy; w UI pośrednio (EMS, ustawienia typu) |
| EconomicsEngine | `core/economics`, Narzędzia → Ekonomia | ✅ |
| EnergyOptimizationEngine (EMS) | `core/ems`, Centrum → Analizy | ✅ tylko zalecenia; odbiorniki elastyczne i agregat bez konfiguracji w UI (ROADMAP) |
| EnergyForecast wieczór/noc/7 dni, nadwyżka/deficyt | `EnergyOptimizationEngine.periods/daily` | ✅ |
| PV Designer, porównanie lokalizacji, tryb pojazdu | `core/design`, `core/vehicle`, zakładka Narzędzia | ✅ (czyste niebo = górna granica, oznaczone) |
| Historia dzień/tydzień/miesiąc/rok/całość | `core/analytics/HistoryPeriods`, Centrum → Analizy | ✅ |
| Eksport CSV/JSON/PDF | `core/export`, `app/energy/PdfReport` | ✅ zapis przez systemowy wybór pliku |
| SubscriptionManager / FeatureAccessManager | `core/access` | ✅ warstwa gotowa; brak płatności (wszystko odblokowane, komunikat w UI) |
| WeatherProvider (wiatr, wilgotność, opady, śnieg, mgła) | — | 🟡 łańcuch strat obsługuje wiatr i śnieg; pobieranie tych pól — ROADMAP |
