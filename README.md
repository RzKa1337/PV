# ☀️ Solar Tracker PRO

Aplikacja Android (Kotlin + Jetpack Compose + Material 3) do śledzenia pozycji słońca
i **szacowania** produkcji energii z instalacji fotowoltaicznej.

> Wszystkie wartości produkcji z modelu to **SZACUNEK** lub **PROGNOZA**, a nie pomiar. Przy włączonej pogodzie
> model korzysta z prognozy Open-Meteo i średnich klimatycznych; bez niej – z modelu
> bezchmurnego nieba (górna granica). Ekran Na żywo i wykresy kątów liczą moc bez zacienienia i śniegu; prognoza w Centrum
> uwzględnia zacienienie (po potwierdzeniu lokalizacji i danych o przeszkodach), śnieg z prognozy, kalibrację
> i limit falownika. Pomiar z falownika jest pokazywany osobno (POMIAR) i nigdy nie jest zastępowany modelem.
> Dokładność modelu nie została sprawdzona na prawdziwych pomiarach instalacji – patrz [docs/PV_MODEL.md](docs/PV_MODEL.md).

## Funkcje
- Aktualna wysokość i azymut słońca, wschód, zachód, długość dnia
- Szacowana moc PV teraz, energia od rana i prognoza na cały dzień
- Wykres godzina → moc PV
- Porównanie kątów nachylenia paneli (0–90°)
- Produkcja miesięczna dla kątów 0°, 30°, 45°, 60°, 90° ze średnią temperaturą miesiąca (archiwum Open-Meteo) oraz najlepszy kąt na każdy miesiąc (zysk ze zmiany kąta co miesiąc vs stały kąt) oraz porównanie z trackerem 1- i 2-osiowym
- Ustawienia: moc [kWp], kąt, azymut, lokalizacja – wyszukiwanie miast z podpowiedziami podczas pisania (Open-Meteo bez klucza, opcjonalnie Google Places – [konfiguracja](docs/GOOGLE_PLACES_SETUP.md)), ostatnio wybrane, GPS lub ręczne współrzędne
- Tryb ciemny
- Zdjęcia słonecznych miejsc w tle każdej zakładki (Wikimedia Commons, wolne licencje, autor widoczny, bez klucza API; działa offline z pamięci, „Zmień scenerię”, dobór do pory dnia), półprzezroczyste karty i płynne animacje z obsługą systemowego „Usuń animacje” – [docs/SCENERY.md](docs/SCENERY.md)
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
- `pv/IrradianceModel` – model nasłonecznienia (wymienny, domyślnie bezchmurne niebo) i transpozycja na płaszczyznę paneli,
- `pv/PvEstimator` – moc, energia dzienna, porównanie kątów, produkcja miesięczna,
- `pv/PvSimulationEngine` – jawny łańcuch strat (projektant, diagnostyka),
- `weather/WeatherAwareIrradianceModel` – prognoza godzinowa → irradiancja w danej chwili,
- `forecast/PredictivePvEngine` – prognoza: estymator + kalibracja + zacienienie + nowcast + limity,
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
- Moc na ekranie Na żywo to wartość **modelowana** (SZACUNEK/PROGNOZA wg źródła irradiancji) – ekran nie podstawia pomiaru
  z falownika; bez internetu liczy dalej z ostatniej zapisanej prognozy lub z modelu czystego nieba (z podpisem źródła
  i wieku prognozy, po 3 h oznaczonej jako nieaktualna).

## Pogoda
- Prognoza godzinowa Open-Meteo na 16 dni (promieniowanie bezpośrednie i rozproszone – **średnie z poprzedniej godziny**,
  temperatura i wiatr 2 m / 10 m – wartości chwilowe, zachmurzenie, opady, śnieg…) – bez klucza API, dane CC BY 4.0.
  Pobierana co 60 min; limit zapytań dostawcy nie jest przekraczany (jedno zapytanie na godzinę, cache na dysku).
- Dla dni bez prognozy: średnie miesięczne z archiwum Open-Meteo (ostatnie 3 lata) dla lokalizacji.
  Miesiąc modelowany jako mieszanka dni bezchmurnych i pochmurnych (światło rozproszone ≈ 25%
  bezchmurnego), dobrana tak, by średnie nasłonecznienie zgadzało się z klimatem.
- Offline: ostatnio pobrane dane (do 3 dni), a w Polsce – wbudowane przybliżone średnie.
- Temperatura ogniw: Faiman (z wiatrem z prognozy) albo NOCT 45 °C bez wiatru; γ z karty katalogowej (Ustawienia, domyślnie −0,40 %/°C).
- Dla Warszawy model klimatyczny daje ok. 900 kWh/kWp (płasko) i ok. 1090–1100 kWh/kWp (30–45°, południe); dla porównania
  PVGIS podaje dla środkowej Polski zbliżone rzędy wielkości (test sprawdza tylko widełki 750–1000 / 900–1200).

## Model szacowania
- Masa powietrza: Kasten–Young (1989)
- Czyste niebo: Meinel (`E0 · 0,7^(AM^0,678)`, E0 = 1361 W/m² z poprawką na odległość Ziemia–Słońce), rozproszone ≈ 10 % wiązki
- Irradiancja na płaszczyźnie paneli: wiązka + rozproszone z nieba **Hay–Davies** + odbicie od gruntu (albedo 0,2)
- Moc: `kWp · POA_efektywna / 1000 · PR · (1 + γ(Tc − 25)) / 0,95`, PR = 0,80 (roczny, zawiera typowe straty temperaturowe
  i kątowe, które są z niego wyjęte i liczone dla chwili), przycięta do limitu falownika – równania, jednostki
  i założenia: [docs/PV_MODEL.md](docs/PV_MODEL.md), [CALCULATIONS.md](CALCULATIONS.md)

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

Od v0.9.0: odbiorniki elastyczne i agregat w Konfiguracji (EMS podaje godziny uruchomienia), wiatr/śnieg/opady/wilgotność/
widoczność w prognozie i na pulpicie, dokładność prognoz (godzina i dzień naprzód) w Analizach.

Dokumentacja techniczna: [ARCHITECTURE.md](ARCHITECTURE.md), [CALCULATIONS.md](CALCULATIONS.md), [TESTING.md](TESTING.md),
[API.md](API.md), [ROADMAP.md](ROADMAP.md).

## Diagnostyka PV (v0.13.0)
Centrum → **Diagnostyka PV** odpowiada na pytanie „ile instalacja powinna teraz produkować, ile produkuje i dlaczego jest różnica”:
- moc teoretyczna → straty (AOI, temperatura, śnieg, zabrudzenie, zacienienie, mismatch, degradacja, okablowanie, MPPT, falownik, clipping) → oczekiwana → rzeczywista, niewyjaśniona reszta i pewność,
- PV Doctor: diagnoza z dowodami, wpływem i jednym zaleceniem (pełna bateria w off-grid to nie awaria),
- wykrywanie zabrudzenia (dni pogodne, poprawa po deszczu) i degradacji (rok do roku, po 2 latach danych),
- MPPT (gdy falownik je raportuje; Anenji SMG – N/A), PV Radar (teraz … +6 h), misje dla baterii,
- log surowych rejestrów z jakością VERIFIED/UNVERIFIED/SUSPECTED/INVALID i eksportem CSV/JSON – do weryfikacji mapy rejestrów na prawdziwym urządzeniu (tylko odczyt).

## Analiza Anenji (Deep Analyzer)
Centrum → **Analiza Anenji** analizuje całą zapisaną historię albo zaimportowany log (CSV/JSON/TXT) i odpowiada: co było nie tak, kiedy, jak często, jakie były prawdopodobne przyczyny, ile energii to kosztowało i co sprawdzić. Zawiera: wynik zdrowia systemu z wyjaśnionymi potrąceniami, ostrzeżenia z dowodami i pewnością, analizę alarmów (częstotliwość, okno godzinowe, warunki przed zdarzeniem), komunikację, trendy, „Co się stało?” dla wybranej chwili, pytania „Dlaczego…?”, snapshoty ustawień z porównaniem oraz eksport raportu JSON/CSV/PDF. **Tylko odczyt** – aplikacja nie zmienia ustawień falownika. Status: **IMPLEMENTED — WAITING FOR REAL ANENJI LOG**.

## Radar i prognoza PV
Zakładka **🌦️ Radar**: radar opadów (RainViewer, z wiekiem danych i oznaczeniem DANE NIEAKTUALNE), pogoda teraz (temperatura, odczuwalna, wilgotność, punkt rosy, chmury, wiatr z porywami i kierunkiem, opad z prawdopodobieństwem), wykres PV oczekiwanej vs rzeczywistej z dotykowymi szczegółami godziny, lista godzinowa (24 h / 48 h / 7 / 14 dni), dzisiejsza prognoza (kWh, szczyt, wschód/zachód, wyprodukowano/oczekiwano do teraz, wydajność), najlepsze i najgorsze godziny, nadchodzące opady z wpływem na PV, alerty, kolejne dni z pewnością i trafność prognozy (MAE/RMSE/bias/MAPE). Godziny w strefie czasowej lokalizacji prognozy. Szczegóły i ograniczenia: [docs/RADAR_FORECAST.md](docs/RADAR_FORECAST.md).

## Co się stało? (Anenji Forensic Analyzer)
Centrum → **Co się stało?** pokazuje najpierw wniosek: stan systemu, najważniejszy problem, pewność (osobno: czy zdarzenie było i czy znamy przyczynę), dlaczego (przyczyny wspierane i wyeliminowane), wpływ (kWh, koszt, czas – albo N/A), kiedy i co sprawdzić. Okresy: dziś, wczoraj, 7/30/90 dni, własny. Każdą diagnozę można prześledzić aż do surowego źródła: **Diagnoza → Dowody → Dane → Surowe → Rejestry → Źródło** (plik i numer linii lub słowa rejestrów). Do tego rekonstrukcja chwili −60…+60 min, trendy 90 dni, zmiany konfiguracji przed incydentami, **tryb walidacji rejestrów** (porównanie z wyświetlaczem falownika) i eksport `ANENJI_FORENSIC_PACKAGE.zip`. Tylko odczyt. Status: **IMPLEMENTED — REAL VALIDATION REQUIRED** – procedura: [ANENJI_REAL_DEVICE_VALIDATION.md](ANENJI_REAL_DEVICE_VALIDATION.md).

## Automatyczna aktualizacja

Ustawienia → **Aktualizacje**: aplikacja sprawdza wydania GitHub (kanał stabilny/beta, wybrana częstotliwość),
pobiera APK z wznowieniem i postępem, a przed instalacją weryfikuje sumę SHA-256 z pliku `SHA256SUMS` wydania,
nazwę pakietu, wersję i **certyfikat podpisu zgodny z zainstalowaną aplikacją**. Niezweryfikowany plik jest usuwany.
Przed instalacją robiona jest kopia ustawień; jeśli nowa wersja się zawiesza, ustawienia są przywracane, a wersja
oznaczana jako wadliwa. Android nie pozwala aplikacji samodzielnie wrócić do starszej wersji – w takim przypadku
powiadomienie prowadzi do listy wydań.

Wymagania: stały klucz wydania w GitHub Secrets oraz (tylko gdyby repozytorium było prywatne) token tylko do odczytu –
zobacz [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## Budowanie

Wymagania: JDK 17+, Android SDK (compileSdk 35).

```bash
./gradlew test               # testy jednostkowe
./gradlew :app:assembleDebug # APK debug -> app/build/outputs/apk/debug/
```

CI (GitHub Actions) uruchamia testy, lint i buduje APK przy każdym pushu.
APK jest dostępne jako artefakt workflow, a dla tagów `v*` – w zakładce Releases.
