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
| AstronomyEngine | `core/solar/SolarCalculator` (NOAA/Meeus, refrakcja, wschód/zachód, noc/dzień polarny) | ✅ zweryfikowany z przykładem NREL SPA (≈ 0,003°), USNO, tożsamością wysokości południowej (2026-10-09) |
| PVSimulationEngine | `core/pv/PvEstimator` (stan z 2026-10-09: Hay–Davies, IAM, Faiman/NOCT, γ z ustawień, PR roczny z wyjętymi typowymi stratami, limit falownika) + `PvSimulationEngine` (jawny łańcuch strat) | ✅ patrz sekcja „Audyt silnika produkcji PV” |
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


## Audyt PRO (2026-10-06, przed rozbudową „Energy Management + Predictive Monitoring PRO”)

Stan wyjściowy: v0.9.0, `:core` 239 testów. Mapa przed pracą → po pracy:

| Obszar | Przed | Po |
|---|---|---|
| Anenji read-only (Modbus SMG / PI30) | DONE (parser, CRC, transporty) · brak walidacji wartości | DONE: walidacja zakresów, U×I, skoki, zero PV, dane zamrożone, klasy błędów łącza, statystyki, katalog rejestrów · **NEEDS REAL DEVICE VALIDATION** |
| AutoCalibration | PARTIAL (globalny współczynnik + korekty 2.0, nieużywane w aplikacji) | DONE 3.0: warunki nieba, wysokość Słońca, godzina, miesiąc, wykluczenia, walidacja na ostatnich dniach, użyte w prognozie |
| Forecast vs actual | PARTIAL (godzina / dzień naprzód, MAE/RMSE/MAPE/bias) | DONE: 5 min, 15 min, 1 h, dzień/tydzień/miesiąc, R², błąd na żywo, trafność dnia |
| Energy security | PARTIAL (BatteryPredictor: kamienie milowe 1 h/3 h/21/00/07) | DONE: 21/00/03/06/08 z zakresem, czas do minimum, % bezpieczeństwa, ryzyko, sprawność falownika |
| Prognoza 24–72 h | PARTIAL (dziś/jutro PV, bilans 7 dni bez baterii) | DONE: dziś/jutro/+2/+3 z baterią, SOC min/max, ryzykiem, pewnością |
| Chłodnia | MISSING | DONE: model cyklu sprężarki, średnia / cykl / harmonogram, wychładzanie |
| Mobile PV | PARTIAL (jedna grupa paneli, kurs co 15°) | DONE: wymiary pojazdu, panele z pozycją, walidacja dachu, profil 0–359°, kąt × kurs |
| Optymalizator kąta | PARTIAL (optymalny kąt roczny w porównaniu lokalizacji) | DONE: dziennie/miesięcznie/rocznie, model ruchomego stelaża (bez sterowania) |
| PV performance | PARTIAL (zdrowie dzienne) | DONE: oczekiwana vs rzeczywista z podziałem strat |
| Predictive alerts | PARTIAL (trendy dzienne) | DONE: 10 typów alertów z dowodami i zaleceniem |
| Raport dzienny | MISSING | DONE: karta + powiadomienie wieczorne |
| Digital twin | MISSING | DONE: Słońce → PV → falownik → bateria → odbiorniki z istniejących modeli |
| BROKEN | — | nie znaleziono; flaky test emulatora (okno ANR systemu) naprawiony w testach |

## PV Reality & Diagnostics (2026-10-07)
Pełny audyt i architektura: [docs/PV_DIAGNOSTICS.md](docs/PV_DIAGNOSTICS.md). Najważniejsze ustalenia: łańcuch strat istniał (rozbudowany o AOI/MPPT zamiast drugiej implementacji); zabrudzenie i mismatch były tylko założeniami; symulator był oznaczany jako pomiar (naprawione); zduplikowana reguła śniegu (ujednolicona); `PvPerformanceAnalyzer` gubił małe straty (naprawione, test regresyjny). Pozostałe duplikaty pozostawione świadomie: `ModelComparison` obok `PvPerformanceAnalyzer`, dwa silniki kalibracji, dwa modele temperatury (PR vs łańcuch).

## Anenji Deep Analyzer (2026-10-07)
Nowy pakiet `core/anenji` (analiza całej historii, tylko odczyt). Ograniczenia ujawnione w audycie: brak zweryfikowanych rejestrów ustawień (snapshot z urządzenia = NOT_AVAILABLE), brak historii pogody poza ~2 dniami (korelacja z chmurami tylko dla świeżych danych), wiersze historii sprzed v5 nie mają oznaczenia pochodzenia (traktowane jako dane urządzenia).

## Anenji Forensic Analyzer v2 (2026-10-08)
Audyt przed rozbudową: istniały parser logów, dziennik zdarzeń, `SystemHealthEngine`, `IncidentAnalyzer`, `WhyAnalyzer` i raport – **rozbudowane**, nie zdublowane (jeden parser, jeden silnik zdrowia, jeden analizator incydentów). Braki usunięte: surowe źródło wiersza/rejestru ginęło przy imporcie, rejestr mógł być „VERIFIED” bez dowodu z urządzenia, brak baseline instalacji, brak rozdzielenia pewności zdarzenia i przyczyny, brak zaniku sieci w taksonomii.

Statusy: **IMPLEMENTED** = kod + testy na danych SYMULOWANYCH; **PARTIALLY VERIFIED** = część potwierdzona na urządzeniu; **REAL DEVICE REQUIRED** = poprawność zależy od niezweryfikowanej mapy rejestrów; **NOT AVAILABLE** = brak danych/rejestru.

| Funkcja | Status |
|---|---|
| Pochodzenie surowe (`RawRef`: plik, linia, pola, czas surowy, słowa rejestrów) | IMPLEMENTED |
| `TelemetrySample` (źródło, jakość, pewność; dane z urządzenia = UNVERIFIED) | IMPLEMENTED — REAL DEVICE REQUIRED |
| Tryb walidacji rejestrów (MATCH/CLOSE/SUSPECT/WRONG DECODING, VERIFIED tylko po ≥ 3 zgodnych odczytach z urządzenia) | IMPLEMENTED — REAL DEVICE REQUIRED (0 rejestrów zweryfikowanych) |
| `TimeSeriesEngine` 1 min…90 dni (brakujące = NO DATA, bez interpolacji) | IMPLEMENTED |
| Detekcja anomalii PV / bateria / falownik / sieć / komunikacja (28 typów + zanik sieci) | IMPLEMENTED — REAL DEVICE REQUIRED |
| `SystemBaselineEngine` (podobne warunki: nasłonecznienie modelu ±15 %, wysokość słońca ±5°, ±45 dni) | IMPLEMENTED |
| `AnenjiCorrelationEngine`, `EvidenceGraph`, `RootCauseAnalyzer`, `ConfidenceEngine` | IMPLEMENTED |
| Pewność zdarzenia vs przyczyny (CONFIRMED / LIKELY / POSSIBLE / INSUFFICIENT_DATA; KRYTYCZNE tylko przy potwierdzonym zdarzeniu) | IMPLEMENTED |
| Rekonstrukcja −60…+60 min, analiza okresów 24 h/7/30/90 dni/własny, ranking | IMPLEMENTED |
| Wpływ energii/kosztu/baterii (N/A gdy nie da się policzyć; koszt tylko z ceny w ustawieniach) | IMPLEMENTED |
| Trendy 90 dni (≥ 3 tygodnie danych, tydzień liczy się przy ≥ 4 dniach) | IMPLEMENTED |
| Konfiguracja → incydenty (korelacja w czasie, nie dowód) | IMPLEMENTED; ustawienia z urządzenia NOT AVAILABLE (brak rejestrów ustawień) |
| Ekran „Co się stało?” z drążeniem Diagnoza → Dowody → Dane → Surowe → Rejestry → Źródło | IMPLEMENTED (test instrumentalny w CI) |
| `ANENJI_FORENSIC_PACKAGE.zip` (report.pdf/json, diagnoses, evidence, telemetry, registers, events, settings, metadata z SHA-256) | IMPLEMENTED |
| MPPT 2+ z urządzenia | NOT AVAILABLE (brak rejestrów MPPT w mapie SMG) |
| Wykrywanie restartu z urządzenia | REAL DEVICE REQUIRED (mapa SMG bez licznika czasu pracy – tylko POWER_ON po przerwie) |
| Historia pogody > ~2 dni | NOT AVAILABLE (korelacja pogodowa tylko dla świeżych danych) |

Procedura walidacji: [ANENJI_REAL_DEVICE_VALIDATION.md](ANENJI_REAL_DEVICE_VALIDATION.md).

## Radar i prognoza PV (2026-10-08)
Audyt: istniały Open-Meteo z pamięcią podręczną (`WeatherRepository`), `SolarCalculator`, `PvEstimator`, `PredictivePvEngine` (pogoda, zacienienie, kalibracja, limit falownika), zapis prognoz i `ForecastAccuracy`, mapa osmdroid, historia telemetrii z rozdzieleniem symulatora – **wszystko wykorzystane**, bez drugiego systemu pogody/PV/lokalizacji. Brakowało: radaru, punktu rosy, prawdopodobieństwa opadu, kierunku wiatru, strefy czasowej lokalizacji prognozy (było UTC + strefa telefonu), godzinowego zestawienia oczekiwana/rzeczywista.

| Funkcja | Status |
|---|---|
| Radar RainViewer (klatki, wiek, NA ŻYWO / NIEAKTUALNE / Z PAMIĘCI / NIEDOSTĘPNY) | IMPLEMENTED (wymaga internetu; darmowe API z limitami) |
| Windy | NOT AVAILABLE (tylko oficjalne API z kluczem) – link do windy.com |
| Prognoza godzinowa z punktem rosy (dostawca lub Magnus), opadem, prawdopodobieństwem, wiatrem, kierunkiem | IMPLEMENTED |
| Strefa czasowa lokalizacji, DST | IMPLEMENTED (test dnia 25 h) |
| Oczekiwana moc PV na godzinę, dzień, szczyt, kolejne dni | IMPLEMENTED |
| Rzeczywista PV na godzinę i teraz | IMPLEMENTED — REAL DEVICE REQUIRED (bez falownika: N/A) |
| Pewność, alerty, wpływ opadów, trafność (MAE/RMSE/bias/MAPE) | IMPLEMENTED |
| Przewidywanie ruchu opadów z obrazu radaru | NOT AVAILABLE (świadomie – opady z prognozy godzinowej) |

## Bezpieczeństwo
Pełny audyt bezpieczeństwa: [docs/SECURITY_AUDIT.md](docs/SECURITY_AUDIT.md).

## Audyt silnika produkcji PV (2026-10-09)
Zakres: źródło → walidacja → pogoda → irradiancja → temperatura → DC → limity → AC → estymacja na żywo → prognoza → UI.
Dowody wyłącznie z kodu i testów JVM `:core` (brak Android SDK i sieci – `:app` nie był kompilowany; zmiany w `:app` są minimalne).
Brak prawdziwych pomiarów PV – **nie podajemy żadnego procentu poprawy dokładności**.

| # | Znaleziony problem | Dowód | Stan |
|---|---|---|---|
| 1 | Energia „pozostała do końca dnia” liczona jako suma mocy z początków kroków 15/30 min (`EnergyForecastEngine.day`, `EnergySecurity.outlook`) | zawyżenie +4,6 % o 14:07:30 i +18,6 % o 18:07:30 względem całkowania co 1 min | naprawione (całkowanie po środkach przedziałów), `ForecastEnergyTest` |
| 2 | Nowcast = pomiar / model **bez** kalibracji, a `PredictivePvEngine` mnożył jeszcze raz przez kalibrację (podwójna korekta) | przy kalibracji 0,9 i modelu zgodnym z pomiarem prognoza w chwili pomiaru była o 10 % za niska (3,93 vs 4,37 kW) | naprawione: `nowcastRatio()`, użyte w `EnergyCenterViewModel` (2 miejsca; `:app` niekompilowane lokalnie) |
| 3 | Limit falownika z ustawień przycinał moc **przed** kalibracją/cieniem/nowcastem | test `SingleClipTest` (4,0 kW limit, kalibracja 0,9: 3,6 kW zamiast 4,0 kW) | naprawione: jeden clipping na końcu |
| 4 | Pewność prognozy zależała tylko od odległości od „teraz”, nie od wieku pobranej prognozy (stary cache wyglądał jak świeży) | ta sama pewność dla prognozy sprzed 10 min i sprzed 48 h | naprawione (`StaleForecastTest`) |
| 5 | `AutoCalibrationEngine.evaluate()` nie stosowało wykluczeń (awaria, brak łącza, błędna telemetria, niskie słońce) | współczynnik 0,40 zamiast 0,90 przy zanieczyszczonych próbkach | naprawione; dodane wykluczenie POA < 100 W/m² |
| 6 | `ForecastAccuracy.pairHourly` grupowało wiersze po godzinie UTC – w strefach z offsetem 30/45 min brak par | 0 par dla klucza 10:30Z | naprawione |
| 7 | Silnik łańcucha strat w aplikacji dostawał domyślne γ −0,35 %/°C zamiast γ z ustawień | `PvArrayConfig` bez `temperatureCoefficient` (2 miejsca w `EnergyCenterViewModel`) | naprawione (`:app`) |
| 8 | `WeatherEffects.windFactor` – nieużywany drugi współczynnik wiatru (ryzyko podwójnego liczenia) | brak użyć poza testem | usunięty |
| 9 | `PvPointEstimate.clipped = true` przy samym ograniczeniu do kWp bez ustawionego limitu falownika | test `EstimatorLimitsTest` | naprawione |
| – | Pozycja Słońca | zgodność z SPA: elewacja 0,00003°, azymut 0,002° | brak błędu |
| – | Hay–Davies, grunt, IAM, NOCT/Faiman, znak γ, jednostki W→kWh | testy z ręcznych obliczeń (`PvHandCalcTest`, `PvPhysicsTest`) | brak błędu |
| – | Podwójne straty między `PvEstimator` a `PvSimulationEngine` | tożsamość `P_est·0,95·Fkąt = P_łańcuch` (`EngineConsistencyTest`) | brak błędu |
| – | Czas godzinowy Open-Meteo (średnia z poprzedniej godziny, wartości chwilowe na koniec) | `WeatherForecast.at` (start, koniec], interpolacja | brak błędu |
| – | Sekundowe liczenie lokalne bez sieci | `LiveSolarCalculator` bez zależności sieciowych; pogoda co 60 min (cache 1 h) – test aplikacji `withoutInternet_…` | brak błędu |

Pozostałe, świadomie nienaprawione (od największego wpływu na dokładność):
1. Rozdzielczość pogody godzinowa; chmury w skali minut nieznane bez pomiaru (największe źródło błędu mocy chwilowej).
2. Zbiór parametrów PR/γ/albedo/b0 to założenia, nie kalibracja na tej instalacji; model nie był porównany z prawdziwymi pomiarami.
3. Brak modelu Perez, brak strat spektralnych, IAM tylko dla wiązki, jedna płaszczyzna paneli, jedno MPPT.
4. `BatteryPredictor` – krok jawny Eulera 15 min (nie całka); `ShadingAnalysisEngine.day` – próbka na początku kroku 5 min (≈ 0,1 %).
5. Normalizacja kątowa PR w porównaniu z trackerem liczona dla chwilowej orientacji (≤ ok. 1 % zysku).
6. Dwa „oczekiwane” (estymator PR 0,80 oraz łańcuch strat z domyślnym profilem) różnią się o ok. 5 % – dwa modele temperatury/strat pozostają świadomie (wspólny kod irradiancji i Tc).
7. Nowcast w aplikacji liczony z `live.telemetry.timestamp`; nie sprawdzono na prawdziwym falowniku.
