# TODO — Solar Tracker PRO

Legenda: `[ ]` do zrobienia, `[x]` zrobione **i przetestowane**.

## v0.1.0

### Projekt
- [x] Repozytorium Git + remote GitHub
- [x] `.gitignore` dla Android Studio / Kotlin / Gradle
- [x] Projekt Gradle (moduły `:core` i `:app`), wrapper Gradle
- [x] CI GitHub Actions (testy, lint, APK)

### Obliczenia (`:core`)
- [x] Pozycja słońca (wysokość, azymut)
- [x] Wschód, zachód, długość dnia, noc i dzień polarny
- [x] Model produkcji PV (bezchmurne niebo, kąt i azymut paneli)
- [x] Dzienny profil mocy i energia dzienna
- [x] Porównanie kątów 0–90°
- [x] Produkcja miesięczna dla kątów 0/30/45/60/90°

### Dane (`:app`)
- [x] Ustawienia PV zapisywane w DataStore (moc, kąt, azymut) — testy jednostkowe
- [x] Lokalizacja ręczna — testy jednostkowe
- [ ] Lokalizacja z GPS (opcjonalna) — zaimplementowane, logika ViewModelu przetestowana; czeka na test na telefonie

### Interfejs (`:app`)
Zaimplementowane, kompiluje się i przechodzi lint w CI — **czeka na ręczny test na telefonie**.
- [ ] Ekran główny (słońce, PV, dziś, aktualna moc, wschód/zachód)
- [ ] Wykres godzina → moc PV
- [ ] Ekran porównania kątów
- [ ] Ekran produkcji miesięcznej
- [ ] Ekran ustawień
- [ ] Dark Mode

### Wydanie
- [x] Testy jednostkowe zielone w CI
- [x] APK zbudowane w CI
- [x] Tag `v0.1.0`

## v0.2.0 — Magazyn energii

### Obliczenia (`:core`)
- [x] Model `BatteryStorage` z walidacją (pojemność, użyteczna %, SOC start/min/maks, moce, sprawności, typ)
- [x] Profil zużycia: stałe zużycie i przedziały godzinowe
- [x] `EnergyFlowSimulator`: PV → zużycie → bateria → sieć/agregat (krok 15 min), limity mocy, sprawności, min/maks SOC
- [x] Symulacja wielodniowa z przenoszeniem SOC (dziś, jutro, 7 dni, miesiąc, rok)
- [x] Bilans energii, porównanie bez/z baterią
- [x] Statystyki magazynu (cykle, SOC śr./min./maks., godziny pusta/pełna, straty)
- [x] Kalkulator autonomii (kWh/dzień i stały pobór)
- [x] Koszty energii, oszczędności, okres zwrotu

### Dane i ViewModel (`:app`)
- [x] Zapis magazynu, profilu zużycia i cen w DataStore z walidacją — testy jednostkowe
- [x] ViewModel: bilans dla okresów, koszty roczne, stan baterii na pulpicie — testy jednostkowe

### Interfejs (`:app`)
Zaimplementowane, kompiluje się i przechodzi lint w CI — **czeka na ręczny test na telefonie**.
- [ ] Ustawienia: Magazyn energii, Profil zużycia, Ceny energii
- [ ] Zakładka „Energia”: bilans, porównanie, wykres przepływów, wykres SOC, statystyki, autonomia, koszty
- [ ] Karta „🔋 Magazyn energii” na pulpicie

## v0.3.0 — Pogoda

### Obliczenia (`:core`)
- [x] Parser Open-Meteo: prognoza godzinowa (GHI, DNI, DHI, temperatura, zachmurzenie) i archiwum klimatyczne
- [x] `WeatherAwareIrradianceModel`: prognoza → średnie klimatyczne (model dwustanowy: dni bezchmurne + pochmurne) → bezchmurne niebo
- [x] Temperatura paneli (NOCT, −0,4%/°C) w `PvEstimator`
- [x] Wszystkie prognozy (pulpit, kąty, miesiące, bilans, magazyn, koszty) korzystają z tego samego modelu
- [x] Wbudowane przybliżone średnie dla Polski do pracy offline

### Dane i ViewModel (`:app`)
- [x] Pobieranie i cache (prognoza 1 h, klimat 180 dni), praca offline — logika ViewModelu w testach
- [x] Przełącznik „Uwzględniaj pogodę” — testy jednostkowe

### Interfejs (`:app`)
Zaimplementowane, kompiluje się i przechodzi lint w CI — **czeka na ręczny test na telefonie**.
- [ ] Karta pogody na pulpicie, sekcja „Pogoda” w ustawieniach, źródło danych pod wynikami
- [ ] Pobieranie danych Open-Meteo na prawdziwym telefonie (sieć niedostępna w środowisku budowania)

## v0.4.0 — Live Solar

### Obliczenia (`:core`)
- [x] `SecondTicker`: tick zsynchronizowany z granicą sekundy zegara ściennego, bez dryfu — testy (czas wirtualny) + 3-minutowy bieg w czasie rzeczywistym
- [x] `SolarCalculator.details`: kąt godzinowy, deklinacja, zenit, air mass (z wysokością n.p.m.)
- [x] Kąt padania i wykorzystanie geometrii; `PvEstimator.pointEstimate` (POA, temperatura ogniwa, moc)
- [x] `EnergyFlowSimulator.instantFlow` i `socAt` — te same reguły co symulacja
- [x] `LiveSolarCalculator`, `SunPath`

### Aplikacja (`:app`)
- [x] ViewModel: ticker tylko przy aktywnym ekranie (WhileSubscribed + lifecycle), pauza — testy jednostkowe
- [x] Wysokość n.p.m. z GPS lub ręcznie
- [x] Ekran Live Solar — test instrumentalny na emulatorze Android 11 w CI: 3 min, 184 kolejne sekundy bez przerw, zegar zgodny z urządzeniem, zatrzymanie w tle i wznowienie
- [ ] Ekran Live Solar — ręczny test na telefonie (wygląd, kompas, animacja)

## Automatyczna aktualizacja (v0.5.0)
- [x] Rdzeń (`:core/update`): SemVer, kanały stable/beta, parser wydań GitHub, wybór APK wg ABI, SHA256SUMS, polityka sprawdzania/odroczeń/pominięć/złych wersji, ponawianie z back-off, dziennik — testy JVM
- [x] Pobieranie z wznowieniem (Range), postępem, anulowaniem i weryfikacją SHA-256 (plik niezgodny usuwany); token tylko do hosta API — testy z lokalnym serwerem HTTP
- [x] CI: podpis wydań stałym kluczem z GitHub Secrets + SHA256SUMS w wydaniu (CI zielone; release APK budowany)
- [ ] Właściciel: dodać sekrety klucza wydania (docs/RELEASE_SIGNING.md)
- [x] Android: weryfikacja certyfikatu/pakietu/versionCode, PackageInstaller, WorkManager, UI ustawień, kopia ustawień, restart/powiadomienie (CI + emulator: weryfikacja APK)
- [ ] Ręczny test aktualizacji na telefonie (pobranie → instalacja → restart)
- [x] Test na emulatorze (CI #28)
- [ ] Wydanie v0.5.0 (po dodaniu sekretów klucza)

## Później (pomysły)
- [ ] Integracja z falownikiem (rzeczywista moc PV obok modelu)
- [ ] Śnieg na panelach (np. z `snow_depth` w prognozie)
- [ ] Wpływ temperatury na pojemność i moc ładowania baterii
- [ ] Zacienienie (horyzont, drzewa, budynki)
- [ ] Tryb pojazdu/kampera (gotowe ustawienia PV + bateria + zużycie)
- [ ] Współczynnik korekty na podstawie rzeczywistych odczytów z falownika
- [ ] Wpisywanie rzeczywistej produkcji i porównanie z szacunkiem
- [ ] Widget na ekran główny
- [ ] Wyszukiwanie lokalizacji po nazwie miejscowości
