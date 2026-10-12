# Radar i prognoza PV

Zakładka **🌦️ Radar** łączy radar opadów z godzinową prognozą pogody i produkcji PV oraz porównuje prognozę z pomiarem.
Status: **IMPLEMENTED** (kod + testy JVM + test instrumentalny). Dokładność prognozy PV na konkretnej instalacji zależy
od kalibracji na prawdziwych danych z falownika (patrz „Trafność prognozy”).

## Źródła danych

| Dane | Źródło | Klucz API | Internet | Offline |
|---|---|---|---|---|
| Prognoza godzinowa (temperatura, odczuwalna, wilgotność, punkt rosy, chmury całkowite/niskie/średnie/wysokie, opad, prawdopodobieństwo opadu, deszcz, śnieg, wiatr, porywy, kierunek, UV, kod pogody, GHI/DNI/DHI) | Open-Meteo `/v1/forecast` (CC BY 4.0), `timezone=auto` | nie | tak | ostatnia prognoza z pamięci (do 3 dni), oznaczona „Z PAMIĘCI” |
| Nasłonecznienie co 15 min (GHI/DNI/DHI, średnia z poprzednich 15 min) na 48 h | Open-Meteo `/v1/forecast` (`minutely_15`); natywnie DWD ICON-D2 / Météo-France AROME w Europie Środkowej, gdzie indziej interpolowane z danych godzinowych | nie | tak | z tej samej kopii co prognoza godzinowa; gdy dostawca odrzuci zapytanie – sama prognoza godzinowa |
| Prognoza zespołowa (ensemble): GHI, DHI, temperatura, zachmurzenie dla ~40 scenariuszy pogody, 7 dni | Open-Meteo Ensemble API (`ensemble-api.open-meteo.com/v1/ensemble`, CC BY 4.0): DWD ICON-EPS (ICON-D2-EPS → ICON-EU-EPS → globalny), awaryjnie ECMWF IFS ENS | nie | tak (co najwyżej co 3 h, ~0,2–0,4 MB) | kopia do 24 h; bez niej zakres z reguł (jak dotąd) |
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

## Zakres 80% z prognozy zespołowej (ensemble)
Model pogody liczony wiele razy z lekko różnych warunków początkowych daje wiele równie prawdopodobnych scenariuszy.
Ich rozrzut to miara niepewności pogody na **ten konkretny dzień** (wąski przy stabilnym wyżu, szeroki przy
trudnych do umiejscowienia chmurach), a nie średnia z reguł.

1. Dla każdego scenariusza ten sam silnik PV co prognoza główna (`PredictivePvEngine.withWeather`): POA, temperatura ogniw,
   kalibracja, zacienienie, limit falownika. Wiatr, śnieg i opady – z prognozy głównej (ensemble ich nie podaje).
   Bez bieżącej korekty z pomiaru (nowcast) – ensemble opisuje godziny i dni naprzód.
2. Rozdział GHI na wiązkę i rozproszone: DHI dostawcy, a gdy go brak – korelacja Erbsa (1982) z indeksem przejrzystości godziny;
   DNI = (GHI − DHI)/⟨cos z⟩ godziny, więc GHI scenariusza jest zachowane.
3. Godzina: średnia moc z 6 punktów co 10 min (ta sama siatka co prognoza główna) dla każdego scenariusza → P10 / P50 / P90.
4. Dzień / reszta dnia: energia **sumowana osobno dla każdego scenariusza**, dopiero potem kwantyle. Suma godzinowych P90
   zakładałaby, że każda godzina jest jednocześnie najlepsza – zakres byłby o wiele za szeroki.
5. Zacienienie liczone raz na chwilę (geometria jest wspólna), z podziału wiązka/rozproszone prognozy głównej – przybliżenie,
   które przy dużym cieniu i pochmurnym scenariuszu lekko zawyża stratę z cienia.
6. Przeliczane w tle co najwyżej co 30 min albo po zmianie pogody, kalibracji lub ustawień.

Gdzie widać: pasmo na wykresie Radaru i „Zakres prognozy” godziny, zakres kWh pod każdym dniem, „Zakres 80%” dziś/jutro
w Centrum energii. Poza zasięgiem ensemble (> ~7 dni, offline) zostaje dotychczasowy zakres z pewności modelu.

**Sprawdzanie zakresu:** prognoza „dzień naprzód” zapisuje swoje P10/P90 (baza v6). W „Trafności prognozy” widać, w ilu
godzinach pomiar mieścił się w zakresie – dobrze dobrany zakres trafia ok. 80 %; dużo mniej = zakres za wąski,
dużo więcej = za szeroki. Ocena dopiero po zebraniu pomiarów z falownika.

## Rzeczywista vs oczekiwana
- Rzeczywista = średnia moc z wierszy historii z falownika w danej godzinie; < 90 % pokrycia → „POMIAR CZĘŚCIOWY”.
- Wiersze z symulatora są pokazywane osobno jako SYMULACJA i **nigdy** jako rzeczywista.
- Brak pomiaru → N/A (nie model).
- Wydajność dnia = Σ rzeczywista / Σ oczekiwana w tym samym czasie, w którym był pomiar.

## Pewność prognozy (WYSOKA / ŚREDNIA / NISKA)
Iloczyn jawnych czynników (każdy pokazany): źródło pogody, prognoza z pamięci, horyzont (0–1 d / 2–3 / 4–7 / > 7 dni), zmienne zachmurzenie (20–80 % słońca zasłonięte), kompletność nasłonecznienia w prognozie, pewność modelu PV (kalibracja, zacienienie), trafność historyczna (MAE/RMSE/bias/MAPE z 30 dni prognoz „dzień naprzód”; bez automatycznej korekty).
Gdy dla dnia jest ensemble, reguły „horyzont” i „zmienne zachmurzenie” zastępuje **zgodność scenariuszy**:
czynnik = 1 − 0,5 · (P90 − P10)/P50 (w granicach 0,4–1; mianownik co najmniej 15 % energii przy czystym niebie, żeby ciemny
dzień nie wyglądał na „nieskończenie” niepewny).

## Alerty
Opady w ciągu 2 h (rozdzielczość 1 h), gęste chmury (≥ 2 h z ≥ 80 % zasłonięcia), silny wiatr (domyślnie: porywy ≥ 17,2 m/s = 8° Beauforta lub wiatr ≥ 10,8 m/s = 6° B), szczyt PV w ciągu 30 min, PV ≥ 20 % poniżej prognozy (oczekiwane ≥ 200 W), temperatura > 30 °C.

## Wydajność
Raport godzinowy jest liczony w tle (Dispatchers.Default) najwyżej co 5 minut, po nowej prognozie pogody albo gdy wybrany zakres wykracza poza policzone dni; równoległe przeliczenia nie są uruchamiane. Trafność (30 dni z bazy) jest czytana najwyżej raz na godzinę. Mapa trzyma warstwę radaru dla każdej klatki – suwak i animacja tylko przełączają widoczność.

## Testy
`core/src/test/.../forecast/RadarForecastTest.kt` – punkt rosy (typowy, wysoka/niska wilgotność, mróz, granice), parsowanie nowych pól i strefy, dzień zmiany czasu (25 h), brak prognozy, PV (czyste niebo, chmury, upał, noc, wschód, szczyt), opady z wpływem i nocne bez wpływu, alerty, rzeczywista vs oczekiwana (równa, +10 %, −10 %, częściowa, symulator, brak), pewność, radar (poprawne klatki, nieaktualne, brak internetu, uszkodzone dane). `app/src/androidTest/.../RadarInstrumentedTest.kt` – zakładka na emulatorze.
