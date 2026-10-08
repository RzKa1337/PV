# AUTOPILOT STATUS — Solar Tracker PRO

Ostatnia aktualizacja: 2026-10-08 · gałąź `ccr-81f74041-r3omte` · ostatnie wydanie: v0.15.2 · w toku: Anenji Forensic Analyzer v2 (IMPLEMENTED — REAL VALIDATION REQUIRED)

## Audyt repozytorium (faza 0)

Stan wyjściowy: drzewo czyste, zgodne z `origin`, ~8,5 tys. linii Kotlina, moduły `:core` (czysta logika, JVM)
i `:app` (Android, Compose). CI: testy + lint + APK + test instrumentalny Live Solar na emulatorze.
Repozytorium GitHub było wtedy **prywatne**; obecnie jest **publiczne** (token do aktualizacji niepotrzebny).

### Roadmapa — stan

| Obszar | Stan | Uwagi |
|---|---|---|
| Pozycja słońca, wschód/zachód | ✅ zrobione | testy NREL/NOAA |
| Model PV, kąty, miesiące | ✅ zrobione | |
| Magazyn energii, bilans, koszty, autonomia | ✅ zrobione | |
| Pogoda (prognoza + klimat), temperatura paneli | ✅ zrobione | sieć Open-Meteo nieweryfikowalna w sandboxie |
| Live Solar (co 1 s) | ✅ zrobione | test 3 min na emulatorze w CI |
| UI ekranów (pulpit, kąty, miesiące, energia, ustawienia, dark mode) | 🟡 częściowo | kompiluje się, lint, emulator; brak ręcznego testu na telefonie |
| GPS | 🟡 częściowo | logika przetestowana; brak testu na telefonie |
| Release APK podpisane stałym kluczem | ✅ zrobione | od v0.5.0; certyfikat SHA-256 `a58b2a09…299b` |
| **Automatyczna aktualizacja z GitHuba** | 🟡 zaimplementowana, CI zielone | brak testu na telefonie; działa po dodaniu klucza wydania |
| Falownik Anenji 6.2 kW (Modbus SMG/PI30, TCP/USB) | 🟡 zaimplementowany | REAL DEVICE VALIDATION REQUIRED |
| Zacienienie (OSM, teren, horyzont, panele/stringi) | 🟡 zaimplementowane | REAL MAP AND HEIGHT DATA VALIDATION REQUIRED |
| Prognozy PV/obciążenia/SOC, alerty, Solar Advisor | ✅ zrobione | testy JVM + emulator |
| Śnieg, kamper, widget | ❌ brak | pomysły na później |
| Anenji Forensic Analyzer v2 („Co się stało?”) | 🟡 zaimplementowany | testy na 9 scenariuszach SYMULOWANYCH; REAL DEVICE VALIDATION REQUIRED |

### Ograniczenia wpływające na auto-aktualizację

1. **Prywatne repo** → API GitHuba wymaga tokena. Aplikacja ma pole na opcjonalny token tylko do odczytu
   (przechowywany lokalnie na telefonie, nigdy w kodzie/repozytorium).
2. **Podpis APK** → Android instaluje aktualizację tylko z tym samym kluczem. Wymagany stały klucz wydania
   w GitHub Secrets (ręczny krok właściciela repo — brak uprawnień do sekretów w tej sesji).
3. **Android** → instalacja wymaga potwierdzenia systemu (chyba że Android 12+ pozwoli bez niego);
   aplikacja nie może zainstalować starszej wersji (pełny rollback APK niemożliwy); od Androida 10 brak
   samodzielnego uruchamiania w tle.

## Plan faz (auto-aktualizacja)

| Faza | Zakres | Stan |
|---|---|---|
| 1 | `:core/update`: wersje SemVer, kanały, parser wydań GitHub, wybór artefaktu (ABI), SHA256SUMS, polityka sprawdzania/odroczeń, ponawianie, dziennik | ✅ 138/138 testów `:core` (w tym 22 nowe: logika + pobieranie przez lokalny serwer HTTP) |
| 2 | CI: podpis wydań kluczem z Secrets, SHA256SUMS, generator klucza | ✅ sekrety dodane, v0.5.0 podpisane kluczem wydania |
| 3 | Android: klient GitHub, pobieranie z wznowieniem i postępem, weryfikacja (SHA-256 + certyfikat APK + pakiet + versionCode), instalacja PackageInstaller, WorkManager, UI, kopia ustawień, restart/powiadomienie | ✅ CI #28 zielone (unit + lint + emulator) |
| 4 | Testy, build, emulator, wydanie | ✅ wydanie v0.5.0 (klucz wydania, SHA256SUMS) |

## Dziennik faz

- **Faza 1 ✅** — `core/update`: `SemanticVersion`, `GitHubReleaseParser`, `UpdateSelector` (kanał, ABI, wymagane SHA256SUMS,
  bez downgrade), `Checksums`, `UpdatePolicy`/`UpdateConfig`/`RetryPolicy`, `UpdateLog`, `HttpClient` (ręczne przekierowania,
  token nie trafia do innych hostów, brak przejścia HTTPS→HTTP), `GitHubClient` (czytelne błędy 401/403/404),
  `Downloader` (plik `.part`, Range, postęp, anulowanie, ponawianie 5xx/zerwań, bez ponawiania 4xx),
  `UpdateEngine` (sumy → APK → SHA-256; niezgodny plik usuwany, nigdy nie zwracany). Testy: `UpdateCoreTest`, `DownloadTest`.
- **Faza 2 🟡** — `app/build.gradle.kts`: podpis `release` z env (`SIGNING_*`), bez sekretów fallback na debug;
  CI buduje `assembleRelease`, wypisuje certyfikat (`apksigner`), przy wydaniu publikuje
  `SolarTrackerPRO-<tag>-universal.apk` + `SHA256SUMS` + SHA-256 certyfikatu w opisie. Workflow
  `signing-key.yml` (jednorazowe wygenerowanie klucza), instrukcja `docs/RELEASE_SIGNING.md`.
  **Wymaga ręcznego kroku właściciela:** dodanie 4 sekretów.
- **Faza 3 ✅** — pakiet `app/update`: `UpdateStore` (osobny DataStore, wyłączony z kopii zapasowej), `ApkInspector`
  (pakiet, versionCode, certyfikaty), `ApkInstaller` + `InstallResultReceiver` (PackageInstaller, bez pytania na 12+
  gdy system pozwala), `PackageReplacedReceiver` (powiadomienie o restarcie), `UpdateWorker` (WorkManager, Wi-Fi/sieć,
  bateria), `UpdateRecovery` (kopia ustawień, okres próbny 15 s, 2 awarie → przywrócenie + wersja wadliwa),
  `UpdateManager`, UI „Aktualizacje” w Ustawieniach, dziennik. Testy: `UpdateRecoveryTest` (6), instrumentalny
  `UpdateVerificationInstrumentedTest` (3, emulator: tożsamość APK = zainstalowana aplikacja).
- **Faza 4 🟡** — CI #28 zielone. Po drodze naprawione 3 niestabilne asercje testu Live (zbyt ostry próg opóźnienia
  ticka, czas urządzenia mierzony po wolnych odczytach, weryfikacja na skonfladowanym StateFlow → nowy
  niekonfladowany `liveComputed`). Przebieg wydania v0.4.0 (#21) był czerwony z pierwszego z tych powodów
  (APK v0.4.0 został jednak opublikowany przez job `build`).
  **Wydanie v0.5.0 wstrzymane**: bez stałego klucza każde wydanie ma inny podpis, więc auto-aktualizacja v0.5.0 → v0.6.0
  i tak by nie zadziałała. Decyzja właściciela: dodać sekrety (zalecane) albo wydać teraz z kluczem tymczasowym.
- **Wydanie v0.5.0 ✅** — przebieg #30: build, testy, lint, APK release podpisany kluczem z Secrets, wydanie z
  `SolarTrackerPRO-v0.5.0-universal.apk` i `SHA256SUMS`. Emulator w tym przebiegu: „Live tab not found” (aplikacja nie
  pokazała się w 20 s na świeżo uruchomionym emulatorze; ten sam commit przeszedł w #29) → test czeka do 60 s i zamyka
  systemowe okna „nie odpowiada”. Następny krok: ręczny test aktualizacji na telefonie przy v0.5.1.

## Faza: ANENJI 6.2 kW LIVE MONITORING & PREDICTIVE ENERGY (v0.6.0)

Analiza komunikacji (źródła publiczne): Anenji ANJ-6200W-48V ma port RS232 (RJ45) – Modbus RTU 9600 8N1, mapa
rejestrów rodziny „SMG” (udokumentowana przez społeczność, rejestry 100–109 i 201–234); niektóre warianty PI30.
RS485 służy do BMS, wbudowane Wi-Fi (EyeBond) wysyła dane do chmury SmartESS. Aplikacja łączy się przez most
RS232→TCP, bramkę Modbus TCP lub kabel RS232→USB (OTG). Szczegóły: `ANENJI_INTEGRATION.md`, `SHADING_ANALYSIS.md`.

| Etap | Zakres | Stan |
|---|---|---|
| 1 | `core/inverter`, `core/quality`: provider, transporty, Modbus/PI30, connection manager, fake | ✅ testy |
| 2 | `core/analytics`: przepływy, historia, porównanie z modelem, kalibracja, anomalie, alerty | ✅ testy |
| 3 | `core/shading`: przeszkody, wysokości, teren, horyzont, cień panel/string, zdarzenia, straty, pewność, dostawcy | ✅ testy |
| 4 | `core/forecast`: PV, obciążenie, SOC, EnergyForecastEngine, Solar Advisor | ✅ testy |
| 5 | Android: Centrum energii, konfiguracja, mapa (osmdroid), ekran zacienienia, SQLite, USB, Keystore | ✅ build + lint; test emulatora z symulatorem |
| 6 | Dokumentacja, CHANGELOG, wydanie v0.6.0 | ✅ CI 37326391198 zielone (testy, lint, APK, emulator: Live + Centrum z symulatorem + Keystore + SQLite); wydanie v0.6.0 z SHA256SUMS, ten sam certyfikat co v0.5.0 |

Wymaga walidacji w terenie: **REAL DEVICE VALIDATION REQUIRED** (Anenji), **REAL MAP AND HEIGHT DATA VALIDATION REQUIRED**.

## Faza: FINAL MASTER EXPANSION (v0.8.0)

| Etap | Zakres | Stan |
|---|---|---|
| 0 | Audyt (`PROJECT_AUDIT.md`) | ✅ |
| 1 | Łańcuch strat PV, dokładność prognoz, AutoCalibration 2.0 | ✅ testy JVM |
| 2 | PV Health Score, ostrzeżenia predykcyjne | ✅ testy JVM |
| 3 | Chemia baterii, starzenie, czasy, ekonomia | ✅ testy JVM |
| 4 | EMS (tylko decyzje), okna nadwyżki/deficytu, bilans 7 dni, agregat | ✅ testy JVM (w tym zachowanie energii) |
| 5 | Projektant PV, uzysk roczny, porównanie lokalizacji, tryb pojazdu | ✅ testy JVM |
| 6 | Historia okresów, eksport CSV/JSON/PDF, FREE/PRO | ✅ testy JVM |
| 7 | UI: zakładka Narzędzia, Centrum → Analizy; test instrumentalny; dokumentacja | ✅ CI 37353798001 zielone (unit, lint, APK, emulator); wydanie v0.8.0 (run 37355466398), ten sam certyfikat |

Testy `:core`: 232/232. Dokumentacja: `ARCHITECTURE.md`, `CALCULATIONS.md`, `TESTING.md`, `ROADMAP.md`, `API.md`.
Wymaga walidacji w terenie: falownik Anenji (REAL DEVICE VALIDATION REQUIRED), dane mapowe/wysokości, zdrowie instalacji na prawdziwej historii.

## v0.9.0
Odbiorniki elastyczne i agregat dla EMS, dodatkowe dane pogodowe (wiatr, śnieg, opady, wilgotność, widoczność) w prognozie i na pulpicie,
śledzenie dokładności prognoz (godzina/dzień naprzód). Testy `:core` 239/239; CI 37358388435 zielone (unit, lint, APK, emulator).

## PRO: Energy Management + Predictive Monitoring (2026-10-06)
Audyt → `PROJECT_AUDIT.md` (sekcja „Audyt PRO”). Zrealizowane P0.1–P0.5 i P1.1–P1.7 oraz pulpit; testy `:core` 274/274.
Integracja z falownikiem pozostaje TYLKO DO ODCZYTU. Wymaga walidacji na urządzeniu: mapa rejestrów SMG, znaki mocy, kody błędów.

## PV Reality & Diagnostics (2026-10-07)
Etapy: audyt → architektura → reality engine → PV Doctor → zabrudzenie/degradacja → MPPT/rejestry → radar/misje → pojazd/kąt/wiatr/„co jeśli” → UI → testy → dokumentacja. Testy rdzenia: 279 → 333. Każdy etap: `:core:test` lokalnie + CI (build, lint, emulator).
