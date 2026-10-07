# PV Reality & Diagnostics – audyt i architektura

Dokument roboczy etapów 1–2 (audyt kodu, projekt). Źródłem prawdy jest kod; stan na 2026-10-07 (v0.12.0).

## 1. Audyt – co już istnieje

| Obszar | Klasa / plik | Stan |
|---|---|---|
| Model PV (prognozy, pulpit) | `pv/PvEstimator` | Działa. POA (beam + izotropowe rozproszone + albedo), `performanceRatio` 0,80 + współczynnik temperaturowy (NOCT, −0,4 %/°C). Brak strat AOI – zawarte „w PR”. |
| Jawny łańcuch strat | `pv/PvSimulationEngine` + `PvLossBreakdown` (W) | Działa: temperatura (Faiman/NOCT), śnieg, zabrudzenie, zacienienie, mismatch, degradacja, DC, falownik, clipping, AC. **Brak: AOI/optyka, MPPT**. Używany przez: projektant, optymalizator kąta, tryb pojazdu, `PvPerformanceAnalyzer`. |
| Rzeczywistość vs model | `health/PvPerformanceAnalyzer` | Działa: rozkład strat z łańcucha + „nieznane” = model − pomiar. Zabrudzenie/mismatch to **założenia z profilu**, nie wykrycie. |
| Druga, starsza analiza odchyleń | `analytics/ModelComparison` | Duplikat koncepcji (heurystyczne przyczyny). Pozostawiony – używany przez Centrum; nowa diagnostyka nie powiela go, tylko korzysta z łańcucha strat. |
| Kalibracja | `analytics/Calibration` (mediana) + `analytics/AutoCalibration` (3.0, warunkowa) | Dwie implementacje; 3.0 zastępuje starszą, gdy gotowa (`PredictivePvEngine`). |
| Prognoza PV | `forecast/PredictivePvEngine` (+ nowcast, zacienienie, clipping), `ShortHorizon` (+5…+6 h) | Działa; rozdzielczość pogody **godzinowa** (Open-Meteo hourly). |
| Bateria | `energy/BatteryStorage`, `EnergyFlowSimulator`, `forecast/BatteryPredictor`, `EnergySecurityAnalyzer` (3 scenariusze) | Działa. |
| Zdrowie / alerty | `health/PvHealthEngine`, `PredictiveFaultEngine`, `analytics/Anomalies` | Działa (heurystyki z dowodami). |
| Cyfrowy bliźniak | `twin/DigitalTwin` | Działa (MEASURED/CALCULATED/FORECAST). |
| Falownik | `inverter/*` – SMG Modbus, PI30, symulator `FakeAnenjiProvider` | Tylko odczyt (wymuszone w `ModbusCodec`/`Pi30Protocol`). Mapa SMG **niezweryfikowana na urządzeniu**; brak danych per-MPPT w SMG (`MPPT_DETAILS` poza `CAPABILITIES`). |
| Jakość danych | `quality/DataKind`, `Quantity` | Działa. **Błąd**: dane symulatora oznaczane jako `MEASURED` (`freshnessKind`). |
| Pojazd | `vehicle/VehicleSolar` | Działa: panele z pozycją, walidacja nakładania, kierunek parkowania, porównanie kątów. Brak optymalizatora rozmieszczenia. |
| Kąt | `design/TiltOptimizer`, `AdjustableMount` | Działa: dzienny/miesięczny/roczny. Brak harmonogramu godzinowego z ograniczeniami. |
| Ekonomia | `economics/EconomicsEngine` | Działa. Brak „co jeśli”. |
| Pogoda | `weather/OpenMeteo` | Brak porywów wiatru (`wind_gusts_10m`). |

## 2. Uproszczenia i ryzyka znalezione w audycie

1. **Brak strat AOI w łańcuchu** – rano/wieczorem i przy płaskich panelach model zawyża moc wiązki. → dodany krok IAM (ASHRAE, b₀ = 0,05) na składowej bezpośredniej.
2. **Brak kroku MPPT** – sprawność śledzenia nie była jawna. → `LossProfile.mpptEfficiency` (domyślnie 0,995 – wartość typowa z kart katalogowych, edytowalna).
3. **Dwie formuły temperatury** (`PvEstimator`: NOCT −0,4 %/°C; łańcuch: Faiman −0,35 %/°C) – świadome (PR-model vs model jawny), opisane w CALCULATIONS.
4. **Zduplikowana reguła śniegu** (`WeatherEffects.snowCovered` i `PvSimulationEngine.isSnowCovered`) – ujednolicona do jednej funkcji.
5. **Symulator jako pomiar** – naprawione: nowy `DataKind.SIMULATED`.
6. **Zabrudzenie tylko jako założenie** – brak wykrywania; dodany `SoilingDetector` (deszcz jako naturalne czyszczenie).

## 3. Architektura nowych komponentów (`:core`)

```
core
├── pv           PvSimulationEngine (+AOI, +MPPT, poaComponents)
├── diagnostics  PvRealityEngine, PvDiagnosticEngine, SoilingDetector, DegradationAnalyzer, MpptAnalyzer
├── inverter     RegisterQuality + RegisterSample + RegisterLog (CSV/JSON)
├── forecast     PvRadar (NOW…+6 h, zdarzenia chmur), EnergyMissionPlanner
├── design       TiltScheduleOptimizer (harmonogram z ograniczeniami)
├── vehicle      PanelLayoutOptimizer, PvWindSafetyEngine
└── economics    WhatIfSimulator
```

Zasady: obliczenia w `:core` (czysty Kotlin, testy JUnit), UI tylko wyświetla; każda liczba z `DataKind`
i pewnością; brak danych = N/A / NISKA PEWNOŚĆ, nigdy wartość wymyślona. Integracja z falownikiem tylko do odczytu.
