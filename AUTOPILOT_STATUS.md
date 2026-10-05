# AUTOPILOT STATUS — Solar Tracker PRO

Ostatnia aktualizacja: 2026-10-05 · gałąź `ccr-81f74041-r3omte` · ostatnie wydanie: v0.4.0 · kod v0.5.0 gotowy

## Audyt repozytorium (faza 0)

Stan wyjściowy: drzewo czyste, zgodne z `origin`, ~8,5 tys. linii Kotlina, moduły `:core` (czysta logika, JVM)
i `:app` (Android, Compose). CI: testy + lint + APK + test instrumentalny Live Solar na emulatorze.
Repozytorium GitHub jest **prywatne**.

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
| Release APK podpisane stałym kluczem | 🟡 CI gotowe | czeka na 4 sekrety w GitHub (docs/RELEASE_SIGNING.md) |
| **Automatyczna aktualizacja z GitHuba** | 🟡 zaimplementowana, CI zielone | brak testu na telefonie; działa po dodaniu klucza wydania |
| Falownik, śnieg, zacienienie, kamper, widget, wyszukiwanie miejscowości | ❌ brak | pomysły na później |

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
| 2 | CI: podpis wydań kluczem z Secrets, SHA256SUMS, generator klucza | ✅ CI zielone; ⚠️ brak sekretów klucza (krok właściciela) |
| 3 | Android: klient GitHub, pobieranie z wznowieniem i postępem, weryfikacja (SHA-256 + certyfikat APK + pakiet + versionCode), instalacja PackageInstaller, WorkManager, UI, kopia ustawień, restart/powiadomienie | ✅ CI #28 zielone (unit + lint + emulator) |
| 4 | Testy, build, emulator, wydanie | 🟡 testy/build/emulator ✅; wydanie v0.5.0 wstrzymane do dodania klucza |

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
