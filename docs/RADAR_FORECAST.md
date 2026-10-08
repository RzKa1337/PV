# Radar i prognoza PV

Zakładka **🌦️ Radar** łączy radar opadów z godzinową prognozą pogody i produkcji PV oraz porównuje prognozę z pomiarem.
Status: **IMPLEMENTED** (kod + testy JVM + test instrumentalny). Dokładność prognozy PV na konkretnej instalacji zależy
od kalibracji na prawdziwych danych z falownika (patrz „Trafność prognozy”).

## Źródła danych

| Dane | Źródło | Klucz API | Internet | Offline |
|---|---|---|---|---|
| Prognoza godzinowa (temperatura, odczuwalna, wilgotność, punkt rosy, chmury całkowite/niskie/średnie/wysokie, opad, prawdopodobieństwo opadu, deszcz, śnieg, wiatr, porywy, kierunek, UV, kod pogody, GHI/DNI/DHI) | Open-Meteo `/v1/forecast` (CC BY 4.0), `timezone=auto` | nie | tak | ostatnia prognoza z pamięci (do 3 dni), oznaczona „Z PAMIĘCI” |
| Strefa czasowa lokalizacji | Open-Meteo (`timezone` w odpowiedzi) | – | – | stara kopia bez strefy → strefa telefonu, oznaczona |
| Radar opadów | RainViewer public Weather Maps API (`api.rainviewer.com/public/weather-maps.json` + kafelki `tilecache.rainviewer.com`) | nie | tak | ostatni indeks klatek z pamięci; wiek decyduje o statusie |
| Mapa | OpenStreetMap (osmdroid, kafelki w pamięci podręcznej) | nie | tak | kafelki z pamięci |
| Pozycja słońca, wschód/zachód, górowanie, długość dnia | `SolarCalculator` (NOAA/Meeus) | – | nie | tak |
| Oczekiwana moc PV | `PredictivePvEngine` → `PvEstimator` (pogoda, POA, temperatura ogniw NOCT, współczynnik temp., orientacja, kąt, zacienienie, kalibracja, limit falownika) | – | nie | tak |
| Rzeczywista moc PV | historia telemetrii Anenji (SQLite) i odczyt na żywo | – | nie | tak |

### Ograniczenia RainViewer
- Darmowy dostęp ma ograniczenia (m.in. maksymalny zoom kafelków, schematy kolorów, liczba zapytań). Aplikacja prosi o kafelki najwyżej do zoomu 7 i czyta indeks najwyżej co 5 minut. Nowa mozaika pojawia się co ~10 min.
- Prognoza radarowa (`nowcast`) jest pokazywana tylko, jeśli dostawca ją zwróci; aplikacja **nie ekstrapoluje** ruchu opadów z obrazów.
- Status: **NA ŻYWO** (ostatnia klatka ≤ 30 min), **DANE NIEAKTUALNE** (> 30 min, z godziną ostatniej klatki), **Z PAMIĘCI** (brak połączenia, kopia jeszcze świeża), **NIEDOSTĘPNY** (brak danych – nic nie jest rysowane).

### Windy
Windy udostępnia mapy tylko przez oficjalne API z kluczem (biblioteka JavaScript / Point Forecast API). Aplikacja **nie** scrapuje Windy i nie używa nieudokumentowanych adresów. Jest przycisk „Otwórz w Windy”, który otwiera stronę windy.com w przeglądarce dla lokalizacji instalacji. Dostawca radaru jest abstrakcją (`RadarProvider`), więc oficjalny dostawca z kluczem (przechowywanym jak inne sekrety, nigdy w repozytorium) może zostać dodany bez zmian w UI.

## Jak liczona jest prognoza PV (dla każdej godziny)
1. Godziny = godziny prognozy Open-Meteo (koniec przedziału jak w API), etykiety w strefie lokalizacji (DST: dzień ma 23/25 godzin, bez „+1 h”).
2. Oczekiwana moc = średnia z 6 punktów co 10 minut z `PredictivePvEngine` (ta sama siatka co zapisane prognozy do oceny trafności). Dla przeszłych godzin bez bieżącej korekty z pomiaru (nowcast), żeby oczekiwanie nie „goniło” pomiaru, z którym jest porównywane.
3. Zakres niepewności = pasmo z `PredictivePvEngine` (zależne od pewności).
4. Czyste niebo = ten sam silnik na modelu bezchmurnym (wpływ pogody, w tym opadów).
5. GHI/POA = `PvEstimator.pointEstimate` w środku godziny.
6. Punkt rosy = wartość dostawcy; gdy brak – wzór Magnusa (Alduchov–Eskridge, a = 17,625, b = 243,04 °C) z temperatury i wilgotności **tej samej godziny**, oznaczony „obliczony”.
7. „Słońce zasłonięte” = (1 − indeks czystego nieba) × 100 – zachmurzenie całkowite liczy też cienkie cirrusy.

## Rzeczywista vs oczekiwana
- Rzeczywista = średnia moc z wierszy historii z falownika w danej godzinie; < 90 % pokrycia → „POMIAR CZĘŚCIOWY”.
- Wiersze z symulatora są pokazywane osobno jako SYMULACJA i **nigdy** jako rzeczywista.
- Brak pomiaru → N/A (nie model).
- Wydajność dnia = Σ rzeczywista / Σ oczekiwana w tym samym czasie, w którym był pomiar.

## Pewność prognozy (WYSOKA / ŚREDNIA / NISKA)
Iloczyn jawnych czynników (każdy pokazany): źródło pogody, prognoza z pamięci, horyzont (0–1 d / 2–3 / 4–7 / > 7 dni), zmienne zachmurzenie (20–80 % słońca zasłonięte), kompletność nasłonecznienia w prognozie, pewność modelu PV (kalibracja, zacienienie), trafność historyczna (MAE/RMSE/bias/MAPE z 30 dni prognoz „dzień naprzód”; bez automatycznej korekty).

## Alerty
Opady w ciągu 2 h (rozdzielczość 1 h), gęste chmury (≥ 2 h z ≥ 80 % zasłonięcia), silny wiatr (domyślnie: porywy ≥ 17,2 m/s = 8° Beauforta lub wiatr ≥ 10,8 m/s = 6° B), szczyt PV w ciągu 30 min, PV ≥ 20 % poniżej prognozy (oczekiwane ≥ 200 W), temperatura > 30 °C.

## Testy
`core/src/test/.../forecast/RadarForecastTest.kt` – punkt rosy (typowy, wysoka/niska wilgotność, mróz, granice), parsowanie nowych pól i strefy, dzień zmiany czasu (25 h), brak prognozy, PV (czyste niebo, chmury, upał, noc, wschód, szczyt), opady z wpływem i nocne bez wpływu, alerty, rzeczywista vs oczekiwana (równa, +10 %, −10 %, częściowa, symulator, brak), pewność, radar (poprawne klatki, nieaktualne, brak internetu, uszkodzone dane). `app/src/androidTest/.../RadarInstrumentedTest.kt` – zakładka na emulatorze.
