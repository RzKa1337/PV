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
- [x] Właściciel: dodać sekrety klucza wydania (docs/RELEASE_SIGNING.md)
- [x] Android: weryfikacja certyfikatu/pakietu/versionCode, PackageInstaller, WorkManager, UI ustawień, kopia ustawień, restart/powiadomienie (CI + emulator: weryfikacja APK)
- [ ] Ręczny test aktualizacji na telefonie (pobranie → instalacja → restart)
- [x] Test na emulatorze (CI #28)
- [x] Wydanie v0.5.0 podpisane kluczem wydania (certyfikat SHA-256 a58b2a09…299b) + SHA256SUMS

## Anenji 6.2 kW + zacienienie (v0.6.0)
- [x] Architektura falownika (provider, repository, connection manager, mapper, telemetria, komendy tylko do odczytu) – testy JVM
- [x] Anenji: Modbus SMG (RTU/TCP), PI30, transport TCP i USB, symulator – testy (CRC, ramki uszkodzone, timeout, wyjątki, socket TCP, offline, ponowne połączenie, duplikaty)
- [x] Historia (SQLite), przepływy energii, porównanie z modelem, kalibracja, anomalie, alerty – testy
- [x] Zacienienie: przeszkody, wysokości z oznaczeniem jakości, teren, horyzont 360°, cień panel/string/MPPT, zdarzenia, straty, pewność, dostawcy OSM/DEM/geokodowanie, cache – testy
- [x] Prognozy PV/obciążenia/baterii, EnergyForecastEngine, Solar Advisor – testy
- [x] Centrum energii, konfiguracja, mapa, ekran zacienienia, edytor przeszkód – build + lint + test na emulatorze (symulator)
- [ ] **REAL DEVICE VALIDATION REQUIRED** – test z prawdziwym Anenji (mapa rejestrów, znaki mocy, tryby)
- [ ] **REAL MAP AND HEIGHT DATA VALIDATION REQUIRED** – porównanie czasu cienia z obserwacją na miejscu
- [ ] Monitoring w tle (usługa pierwszoplanowa) – opcjonalnie

## FINAL MASTER EXPANSION (v0.8.0)
- [x] Łańcuch strat PV, trackery, bifacial, śnieg; dokładność prognoz; AutoCalibration 2.0 – testy
- [x] Zdrowie instalacji, ostrzeżenia predykcyjne – testy
- [x] Chemia baterii (LFP, Li-ion, AGM, GEL, kwasowy), starzenie, czasy; ekonomia – testy
- [x] EMS (zalecenia), bilans wieczór/noc/7 dni – testy
- [x] Projektant PV, porównanie lokalizacji, tryb pojazdu – testy
- [x] Historia okresów, eksport CSV/JSON/PDF, FREE/PRO – testy
- [x] UI: Narzędzia, Centrum → Analizy; test instrumentalny
- [x] Odbiorniki elastyczne i agregat w ustawieniach (EMS) – v0.9.0
- [x] Dodatkowe pola pogody (wiatr, wilgotność, opady, śnieg, widoczność) – v0.9.0
- [x] Panel dokładności prognoz (zapis prognoz) – v0.9.0

## PRO: Energy Management + Predictive Monitoring
- [x] Walidacja telemetrii Anenji (tylko odczyt), diagnostyka łącza, katalog rejestrów
- [x] AutoCalibration 3.0
- [x] Prognoza vs pomiar (5 min … miesiąc), R²
- [x] Bezpieczeństwo energetyczne, prognoza 72 h
- [x] Chłodnia, mobile PV, optymalizator kąta
- [x] Wydajność PV, alerty predykcyjne, raport dzienny, cyfrowy bliźniak, pulpit
- [ ] **REAL DEVICE VALIDATION REQUIRED** – rejestry SMG, znaki mocy, temperatury, kody błędów
- [x] Edytor harmonogramu chłodni w UI

## PV Reality & Diagnostics (0.13.0)
- [x] Łańcuch strat z krokami AOI i MPPT, `PvRealityEngine` (teoretyczna → straty → oczekiwana → rzeczywista + niewyjaśniona)
- [x] PV Doctor (`PvDiagnosticEngine`) – 14 typów diagnoz z ważnością, pewnością, dowodami, wpływem i zaleceniem
- [x] Zabrudzenie bez czujnika (dni pogodne + poprawa po deszczu), degradacja metodą rok-do-roku, miesięczny wskaźnik w bazie (v4)
- [x] MPPT: porównanie z modelem i z pozostałymi wejściami (SMG nie raportuje MPPT → N/A)
- [x] Jakość rejestrów VERIFIED/UNVERIFIED/SUSPECTED/INVALID + log rejestrów CSV/JSON (tylko odczyt)
- [x] PV Radar (TERAZ … +6 h, zdarzenia chmur), misje energetyczne
- [x] `DataKind.SIMULATED` – symulator nigdy jako pomiar
- [x] Ekran Centrum → Diagnostyka PV + test instrumentalny
- [x] Rdzeń + testy: optymalizator rozmieszczenia paneli, harmonogram kąta, bezpieczeństwo wiatrowe, symulator „co jeśli”
- [ ] Ekrany w Narzędziach dla: rozmieszczenia paneli, harmonogramu kąta, wiatru, „co jeśli”
- [ ] Konfiguracja MPPT (Wp, liczba modułów, orientacja) zamiast równego podziału
- [ ] Prognoza 15-minutowa Open-Meteo (`minutely_15`) dla radaru
- [ ] Historia opadów (archiwum) dla potwierdzania zabrudzenia starszych dni
- [ ] **REAL DEVICE VALIDATION** – nagrać log rejestrów z prawdziwego Anenji i oznaczyć zweryfikowane rejestry jako VERIFIED

## 24. Anenji Deep Analyzer — status: IMPLEMENTED — WAITING FOR REAL ANENJI LOG
- [x] można zaimportować log (CSV/JSON/TXT; testy na syntetycznych próbkach i na własnym logu rejestrów)
- [x] można przeanalizować historię (baza aplikacji lub import)
- [x] wykrywanie anomalii (walidator, restarty, PV poniżej modelu, zamrożone wartości)
- [x] analiza ustawień (snapshot; z urządzenia NOT_AVAILABLE – brak zweryfikowanych rejestrów ustawień)
- [x] porównywanie snapshotów (diff z oknem czasowym zmiany)
- [x] analiza alarmów (dziennik zdarzeń, wzorce, przyczyny)
- [x] analiza komunikacji
- [x] analiza trendów (1 h … 1 rok)
- [x] korelacja z PV/pogodą/baterią/obciążeniem (model PV i prognoza; starsze dni bez pogody → N/A)
- [x] raport JSON/CSV/PDF
- [x] każdy wynik ma pewność
- [x] dane REAL/SIMULATED rozdzielone (historia v5)
- [x] brak automatycznego zapisu ustawień (tylko odczyt)
- [x] testy jednostkowe, parsera, anomalii, brakujących danych
- [x] `:core:test` lokalnie; `test` + `lint` aplikacji w CI
- [ ] **Rzeczywisty log Anenji** – do dostarczenia; dopiero wtedy „FULLY VALIDATED”
- [ ] Zweryfikowana mapa rejestrów ustawień (odczyt ustawień z urządzenia)
- [ ] Historia pogody dla starszych dni (korelacja z zachmurzeniem w całym okresie)
- [ ] Czas odpowiedzi łącza w zapisie komunikacji (dziś brak opóźnień w historii)

## Anenji Forensic Analyzer v2 — status: IMPLEMENTED — REAL VALIDATION REQUIRED
- [x] surowe źródło każdej próbki (plik/linia/pola/rejestry) i `TelemetrySample` z jakością
- [x] tryb walidacji rejestrów z odczytami z wyświetlacza (bez VERIFIED z dokumentacji/symulatora)
- [x] silnik szeregów czasowych 1 min…90 dni (bez interpolacji)
- [x] anomalie PV/bateria/falownik/sieć/komunikacja + zanik sieci; baseline instalacji
- [x] korelacja, graf dowodów, przyczyny z eliminacją, jawna pewność, poziomy pewności
- [x] rekonstrukcja −60…+60 min, analiza okresów z rankingiem, trendy 90 dni, konfiguracja → incydenty
- [x] „Dlaczego…?”: restart falownika, wyższe zużycie
- [x] ekran „Co się stało?” z drążeniem do surowych danych; eksport `ANENJI_FORENSIC_PACKAGE.zip`
- [x] 9 scenariuszy SYMULOWANYCH z oczekiwanymi diagnozami; test instrumentalny ekranu
- [ ] **Testy 1–10 z [ANENJI_REAL_DEVICE_VALIDATION.md](ANENJI_REAL_DEVICE_VALIDATION.md) na prawdziwym falowniku**
- [ ] Rejestry MPPT 2+ i licznika czasu pracy dla Anenji (obecnie NOT AVAILABLE)
- [ ] Historia pogody dla starszych dni (korelacja pogodowa w całym okresie 90 dni)

## Radar i prognoza PV — status: IMPLEMENTED
- [x] zakładka Radar: mapa, klatki radaru, wiek danych, status nieaktualności, brak internetu bez awarii
- [x] godzinowo: temperatura, odczuwalna, wilgotność, punkt rosy, chmury (warstwy), opad + prawdopodobieństwo, wiatr, porywy, kierunek
- [x] słońce: wschód, zachód, górowanie, długość dnia, wysokość i azymut na godzinę
- [x] PV: oczekiwana na godzinę, dzień (kWh, szczyt, godzina szczytu), kolejne dni, rzeczywista, różnica, odchylenie %
- [x] pewność, alerty, nadchodzące opady z wpływem na PV, trafność prognozy
- [ ] Sprawdzenie na telefonie z prawdziwym falownikiem (rzeczywista PV na wykresie)
- [ ] Opcjonalny oficjalny dostawca radaru z kluczem (abstrakcja `RadarProvider` gotowa)
- [ ] Ustawienia progów wiatru w UI (dziś wartości domyślne ze skali Beauforta w `AlertThresholds`)

## Język angielski i nawigacja wstecz
- [x] Przycisk Wstecz: podstrony → poprzednia zakładka → wyjście po 2. naciśnięciu (test instrumentalny)
- [x] Ustawienie języka (Polski domyślnie / English / Systemowy), zasoby `values` + `values-en`, widget
- [ ] Tłumaczenie tekstów z `:core` (doradca, alerty, raport dzienny, zdrowie, EMS, nazwy problemów telemetrii)
- [ ] Tłumaczenie okna edycji przeszkody i szczegółowych wierszy analizy zacienienia
- [ ] Powiadomienia (aktualizacje, raport dzienny) i eksport PDF/CSV po angielsku
- [ ] Test instrumentalny przełączenia języka na English

## Później (pomysły)
- [x] Śnieg na panelach z `snow_depth` w prognozie – v0.9.0
- [ ] Wpływ temperatury na pojemność i moc ładowania baterii
- [x] Tryb pojazdu (v0.8.0, Narzędzia)
- [ ] Wpisywanie rzeczywistej produkcji i porównanie z szacunkiem
- [x] Widget na ekran główny (v0.7.0) – build/lint + test rejestracji na emulatorze; ręczny test na telefonie do zrobienia
