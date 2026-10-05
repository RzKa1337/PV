# ☀️ Solar Tracker PRO

Aplikacja Android (Kotlin + Jetpack Compose + Material 3) do śledzenia pozycji słońca
i **szacowania** produkcji energii z instalacji fotowoltaicznej.

> Wszystkie wartości produkcji to **SZACUNEK**, a nie pomiar. Przy włączonej pogodzie
> model korzysta z prognozy Open-Meteo i średnich klimatycznych; bez niej – z modelu
> bezchmurnego nieba (górna granica). Zacienienie i śnieg na panelach nie są uwzględniane.

## Funkcje
- Aktualna wysokość i azymut słońca, wschód, zachód, długość dnia
- Szacowana moc PV teraz, energia od rana i prognoza na cały dzień
- Wykres godzina → moc PV
- Porównanie kątów nachylenia paneli (0–90°)
- Produkcja miesięczna dla kątów 0°, 30°, 45°, 60°, 90°
- Ustawienia: moc [kWp], kąt, azymut, lokalizacja (GPS lub ręcznie)
- Tryb ciemny
- Magazyn energii: symulacja przepływu PV → zużycie → bateria → sieć/agregat,
  bilans, wykresy przepływów i SOC, statystyki, autonomia, koszty i okres zwrotu

## Architektura

```
UI (Compose)            app/src/main/java/.../ui
   ↓
ViewModel               app/src/main/java/.../ui/MainViewModel.kt
   ↓
Logika obliczeniowa     core/  (czysty Kotlin, bez zależności od Androida)
   ↓
Warstwa danych          app/src/main/java/.../data (DataStore, lokalizacja)
```

Moduł `:core` zawiera:
- `solar/SolarCalculator` – algorytm NOAA (Meeus) pozycji słońca i wschodu/zachodu,
- `pv/IrradianceModel` – model nasłonecznienia (wymienny, domyślnie bezchmurne niebo),
- `pv/PvEstimator` – moc, energia dzienna, porównanie kątów, produkcja miesięczna,
- `energy/BatteryStorage` – parametry i walidacja magazynu energii,
- `energy/EnergyFlowSimulator` – symulacja przepływu energii (krok 15 min, SOC przenoszony między dniami);
  produkcję PV bierze z `PvEstimator`, więc to jeden wspólny model,
- `energy/AutonomyCalculator`, `energy/EnergyCost` – autonomia i koszty.

### Model magazynu
- SOC [%] odnosi się do pojemności użytecznej (pojemność nominalna × użyteczna %).
- Nadwyżka PV ładuje baterię do maks. SOC, z limitem mocy ładowania i sprawnością ładowania;
  reszta to nadwyżka (eksport / utracona energia).
- Deficyt pokrywa bateria do min. SOC, z limitem mocy rozładowania i sprawnością rozładowania;
  reszta to energia z sieci/agregatu.

## Live Solar
- Zakładka **Live**: pozycja Słońca, kąt padania, POA i modelowana moc PV, bilans energii z baterią –
  przeliczane lokalnie **co 1 sekundę**, gdy ekran jest widoczny.
- `SecondTicker`: czas astronomiczny z `Instant.now()`, oczekiwanie przez `delay` (zegar monotoniczny)
  do najbliższej granicy sekundy + 5 ms – bez narastającego dryfu, bez gubienia sekund.
- Cykl życia: `StateFlow` + `SharingStarted.WhileSubscribed(0)` + `collectAsStateWithLifecycle` –
  ticker zatrzymuje się po opuszczeniu ekranu lub przejściu aplikacji w tło. Bez usług w tle,
  wake locków i zapytań sieciowych co sekundę (pogoda z cache, odświeżana co godzinę).
- Moc to wartość **modelowana** – aplikacja nie ma danych z falownika.

## Pogoda
- Prognoza godzinowa Open-Meteo na 16 dni (promieniowanie bezpośrednie i rozproszone, temperatura,
  zachmurzenie) – bez klucza API, dane CC BY 4.0.
- Dla dni bez prognozy: średnie miesięczne z archiwum Open-Meteo (ostatnie 3 lata) dla lokalizacji.
  Miesiąc modelowany jako mieszanka dni bezchmurnych i pochmurnych (światło rozproszone ≈ 25%
  bezchmurnego), dobrana tak, by średnie nasłonecznienie zgadzało się z klimatem.
- Offline: ostatnio pobrane dane, a w Polsce – wbudowane przybliżone średnie.
- Temperatura paneli: model NOCT (45 °C), współczynnik −0,4%/°C.
- Dla Warszawy model klimatyczny daje ok. 890 kWh/kWp (płasko) i ok. 1060 kWh/kWp (30–45°, południe).

## Model szacowania
- Masa powietrza: Kasten–Young (1989)
- Promieniowanie bezpośrednie: Meinel (`1361 W/m² · 0.7^(AM^0.678)`), rozproszone ≈ 10%
- Irradiancja na płaszczyźnie paneli: składowa bezpośrednia + izotropowe rozproszenie + odbicie od gruntu (albedo 0,2)
- Moc: `kWp · POA / 1000 W/m² · PR`, PR = 0,80

## Centrum energii (Anenji 6.2 kW)

Zakładka **Centrum**: odczyt falownika Anenji ANJ-6200W-48V przez RS232 (most TCP, bramka Modbus TCP lub kabel USB),
dane LIVE z oznaczeniem jakości (POMIAR / OBLICZONE / SZACUNEK / PROGNOZA / NIEAKTUALNE / OSTATNIA ZNANA / N/A),
przepływy energii, porównanie z modelem, kalibracja, prognozy (+5 min … jutro), prognoza SOC, alerty i Solar Advisor.
**Analiza zacienienia**: lokalizacja z wyszukiwarki lub mapy, budynki i drzewa z OpenStreetMap, teren (DEM), profil
horyzontu, cień na każdym panelu, godziny początku i końca cienia oraz straty energii – z oceną pewności.
Szczegóły: [ANENJI_INTEGRATION.md](ANENJI_INTEGRATION.md), [SHADING_ANALYSIS.md](SHADING_ANALYSIS.md).

## Analizy, EMS i narzędzia (v0.8.0)

**Centrum → Analizy**: ocena zdrowia instalacji 0–100 z listą potrąceń i dowodów (bez oceny, gdy danych jest za mało),
ostrzeżenia predykcyjne (możliwa anomalia vs potwierdzony błąd), zalecenia EMS (okna nadwyżki/deficytu, minimum SOC,
agregat – aplikacja niczego nie przełącza), bilans reszty dnia i 7 dni, historia dzień/tydzień/miesiąc/rok/całość
(autokonsumpcja, autarkia, kompletność danych) oraz eksport CSV/JSON/PDF do wybranego pliku.

Zakładka **Narzędzia**: projektant PV (dobór stringów z danych z kart katalogowych), ekonomia (zwrot, NPV, ROI, LCOE,
koszt magazynowania), porównanie lokalizacji i optymalny kąt, tryb pojazdu (energia, zasięg, najlepszy kierunek parkowania).
Wartości z modelu czystego nieba są oznaczone jako górna granica.

Dokumentacja techniczna: [ARCHITECTURE.md](ARCHITECTURE.md), [CALCULATIONS.md](CALCULATIONS.md), [TESTING.md](TESTING.md),
[API.md](API.md), [ROADMAP.md](ROADMAP.md).

## Automatyczna aktualizacja

Ustawienia → **Aktualizacje**: aplikacja sprawdza wydania GitHub (kanał stabilny/beta, wybrana częstotliwość),
pobiera APK z wznowieniem i postępem, a przed instalacją weryfikuje sumę SHA-256 z pliku `SHA256SUMS` wydania,
nazwę pakietu, wersję i **certyfikat podpisu zgodny z zainstalowaną aplikacją**. Niezweryfikowany plik jest usuwany.
Przed instalacją robiona jest kopia ustawień; jeśli nowa wersja się zawiesza, ustawienia są przywracane, a wersja
oznaczana jako wadliwa. Android nie pozwala aplikacji samodzielnie wrócić do starszej wersji – w takim przypadku
powiadomienie prowadzi do listy wydań.

Wymagania: stały klucz wydania w GitHub Secrets oraz (dla prywatnego repozytorium) token tylko do odczytu –
zobacz [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## Budowanie

Wymagania: JDK 17+, Android SDK (compileSdk 35).

```bash
./gradlew test               # testy jednostkowe
./gradlew :app:assembleDebug # APK debug -> app/build/outputs/apk/debug/
```

CI (GitHub Actions) uruchamia testy, lint i buduje APK przy każdym pushu.
APK jest dostępne jako artefakt workflow, a dla tagów `v*` – w zakładce Releases.
