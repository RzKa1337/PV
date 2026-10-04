# ☀️ Solar Tracker PRO

Aplikacja Android (Kotlin + Jetpack Compose + Material 3) do śledzenia pozycji słońca
i **szacowania** produkcji energii z instalacji fotowoltaicznej.

> Wszystkie wartości produkcji to **SZACUNEK** z lokalnego modelu bezchmurnego nieba,
> a nie pomiar. Rzeczywista produkcja zwykle jest niższa (chmury, zacienienie, temperatura).

## Funkcje
- Aktualna wysokość i azymut słońca, wschód, zachód, długość dnia
- Szacowana moc PV teraz, energia od rana i prognoza na cały dzień
- Wykres godzina → moc PV
- Porównanie kątów nachylenia paneli (0–90°)
- Produkcja miesięczna dla kątów 0°, 30°, 45°, 60°, 90°
- Ustawienia: moc [kWp], kąt, azymut, lokalizacja (GPS lub ręcznie)
- Tryb ciemny

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
- `pv/PvEstimator` – moc, energia dzienna, porównanie kątów, produkcja miesięczna.

## Model szacowania
- Masa powietrza: Kasten–Young (1989)
- Promieniowanie bezpośrednie: Meinel (`1361 W/m² · 0.7^(AM^0.678)`), rozproszone ≈ 10%
- Irradiancja na płaszczyźnie paneli: składowa bezpośrednia + izotropowe rozproszenie + odbicie od gruntu (albedo 0,2)
- Moc: `kWp · POA / 1000 W/m² · PR`, PR = 0,80

## Budowanie

Wymagania: JDK 17+, Android SDK (compileSdk 35).

```bash
./gradlew test               # testy jednostkowe
./gradlew :app:assembleDebug # APK debug -> app/build/outputs/apk/debug/
```

CI (GitHub Actions) uruchamia testy, lint i buduje APK przy każdym pushu.
APK jest dostępne jako artefakt workflow, a dla tagów `v*` – w zakładce Releases.
