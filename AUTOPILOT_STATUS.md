# AUTOPILOT STATUS — Solar Tracker PRO

Ostatnia aktualizacja: 2026-10-05 · gałąź `ccr-81f74041-r3omte` · ostatnie wydanie: v0.4.0

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
| Release APK podpisane stałym kluczem | ❌ brak | każde wydanie ma inny losowy klucz debug → aktualizacja „na wierzch” niemożliwa |
| **Automatyczna aktualizacja z GitHuba** | ❌ brak | zadanie bieżące |
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
| 1 | `:core/update`: wersje SemVer, kanały, parser wydań GitHub, wybór artefaktu (ABI), SHA256SUMS, polityka sprawdzania/odroczeń, ponawianie, dziennik | ✅ 136/136 testów `:core` (w tym 22 nowe: logika + pobieranie przez lokalny serwer HTTP) |
| 2 | CI: podpis wydań kluczem z Secrets, SHA256SUMS, generator klucza | ⏳ |
| 3 | Android: klient GitHub, pobieranie z wznowieniem i postępem, weryfikacja (SHA-256 + certyfikat APK + pakiet + versionCode), instalacja PackageInstaller, WorkManager, UI, kopia ustawień, restart/powiadomienie | ⏳ |
| 4 | Testy, build, emulator, wydanie | ⏳ |

## Dziennik faz

- **Faza 1 ✅** — `core/update`: `SemanticVersion`, `GitHubReleaseParser`, `UpdateSelector` (kanał, ABI, wymagane SHA256SUMS,
  bez downgrade), `Checksums`, `UpdatePolicy`/`UpdateConfig`/`RetryPolicy`, `UpdateLog`, `HttpClient` (ręczne przekierowania,
  token nie trafia do innych hostów, brak przejścia HTTPS→HTTP), `GitHubClient` (czytelne błędy 401/403/404),
  `Downloader` (plik `.part`, Range, postęp, anulowanie, ponawianie 5xx/zerwań, bez ponawiania 4xx),
  `UpdateEngine` (sumy → APK → SHA-256; niezgodny plik usuwany, nigdy nie zwracany). Testy: `UpdateCoreTest`, `DownloadTest`.
