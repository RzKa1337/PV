# Model produkcji PV – równania, dane, częstotliwości

Jeden wspólny estymator (`core/.../pv/PvEstimator.kt`) liczy moc na żywo, pulpit, prognozę godzinową, kąty, miesiące
i porównanie z trackerem. Silnik łańcucha strat (`PvSimulationEngine`, projektant / diagnostyka) używa tych samych
funkcji irradiancji (`poaComponents`) i tej samej temperatury ogniw. Wszystkie wartości są **szacunkiem modelu**
(ESTYMACJA / PROGNOZA); pomiar z falownika jest pokazywany osobno i nigdy nie jest zastępowany modelem.

## 1. Pozycja Słońca
`SolarCalculator` – algorytm NOAA (Meeus): deklinacja, równanie czasu, kąt godzinny, wysokość z refrakcją, azymut;
wschód/zachód (−0,833°), górowanie, długość dnia, dzień/noc polarna. Wg NOAA wschód/zachód z dokładnością około
minuty (szerokości do ±72°). **Sprawdzone** (`SolarReferenceTest`) z przykładem z publikacji NREL SPA
(2003-10-17 12:30:30 UTC−7, 39,742476°N, 105,1786°W, 1830,14 m): wysokość geometryczna 39,87207° (SPA: 39,872046°),
azymut 194,3426° (SPA: 194,34024°), zenit z refrakcją różni się o ≈ 0,003° (SPA liczy refrakcję dla 820 mbar i 11 °C,
NOAA dla atmosfery standardowej; paralaksa Słońca ≤ 0,0024° pominięta) – dlatego tolerancje testu to 0,005–0,01°.
Dodatkowo: deklinacja w chwilach równonocy/przesilenia 2024 wg USNO (±0,01°), równanie czasu (±0,3 min),
tożsamość wysokości w południe 90° − |φ − δ| dla szerokości −34°…+70° w lecie i zimie, dzień/noc polarna obu półkul,
południe słoneczne na zegarze lokalnym przesuwa się o ≈ 60 min przy zmianie czasu. Algorytm SPA nie jest potrzebny. Czas: `Instant` (UTC), strefa
z prognozy lub telefonu; DST obsługiwane przez `java.time` (doba 23 h / 25 h – testy). Słońce pod horyzontem → 0 W.

## 2. Irradiancja na płaszczyźnie paneli (POA)
- **Wiązka**: `DNI · max(0, cos θ)`, cos θ = cos z·cos β + sin z·sin β·cos(γs − γ).
- **Rozproszona z nieba – Hay–Davies (1980)**, jak `pvlib.irradiance.haydavies`:
  `DHI · [A·Rb + (1 − A)·(1 + cos β)/2]`, A = DNI / E0n (E0n = 1361·(1 + 0,033·cos(2π·n/365))),
  Rb = cos θ / max(cos z, 0,01745). Wcześniej model izotropowy (Liu–Jordan) – zaniżał POA paneli zwróconych do słońca
  w pogodne dni. Przy pochmurnym niebie (DNI = 0) oba modele dają to samo.
- **Odbita od gruntu**: `GHI · albedo · (1 − cos β)/2`, albedo 0,2.
- Wartość referencyjna w teście: słońce 60°, panel 30° na południe, DNI 800, DHI 100 → rozproszona 106,33 W/m²
  (izotropowo 93,30).

## 3. Pogoda → irradiancja w danej chwili
Open-Meteo podaje **średnie z poprzedniej godziny** (GHI, DNI, DHI). Stała wartość przez całą godzinę stawiała
godzinną wiązkę na złych kątach słońca (najgorzej o wschodzie i zachodzie) i dawała skoki co pełną godzinę.
Teraz (`WeatherAwareIrradianceModel`):
1. dla każdej godziny indeks czystego nieba k = GHIgodz / GHIczyste,godz (średnia czystego nieba z tej samej godziny,
   6 próbek) i udział rozproszonej d = DHI/GHI; GHIgodz = DNI·⟨cos z⟩ + DHI (spójnie ze składowymi);
2. k i d są interpolowane liniowo między środkami godzin;
3. GHI(t) = k(t)·GHIczyste(t), DHI = d·GHI, DNI = (GHI − DHI)/cos z (od ~3° wysokości, ≤ E0n);
4. temperatura i wiatr (wartości chwilowe na koniec godziny) – interpolacja liniowa.
Energia godziny zostaje zachowana (test: średnia w godzinie = wartość dostawcy ±2 %), krzywa podąża za słońcem.
Indeks k jest ograniczony do 1,2 (wzmocnienie przez chmury bywa, ale rzadko więcej) – absurdalne dane nie dają
fikcyjnej mocy. Brak prognozy → średnie klimatyczne miesiąca (archiwum Open-Meteo) → czyste niebo; źródło jest
zawsze podpisane.

## 4. Temperatura ogniw i moc
- **Faiman (2008)**: `Tc = Ta + POA / (U0 + U1·v)`, U0 = 25 W/m²K, U1 = 6,84 W·s/m³K (domyślne PVsyst/pvlib dla
  konstrukcji wolnostojącej), v – wiatr na 10 m z prognozy. Bez wiatru: NOCT 45 °C (`Ta + POA/800·25`).
- **γ z karty katalogowej** (Ustawienia → Moduły i falownik; domyślnie −0,40 %/°C).
- **Kąt padania (IAM)**: ASHRAE `1 − b0·(1/cos θ − 1)`, b0 = 0,05, tylko dla wiązki.
- **Moc AC**:
  `P = Pstc · [POAwiązka·IAM + rozproszona + odbita] / 1000 / Fkąt · PR · (1 + γ(Tc − 25)) / 0,95`, ograniczona do
  `min(Pszczyt, limit falownika)` (clipping jest sygnalizowany tylko wtedy, gdy limit jest ustawiony).
  PR (domyślnie 0,80) to wskaźnik **roczny** – zawiera już typowe straty temperaturowe (0,95) i kątowe. Dlatego obie
  części są z niego wyjęte (0,95 oraz `Fkąt` = średnia roczna IAM przy czystym niebie dla tej orientacji i szerokości,
  12 dni × co 30 min) i liczone dla konkretnej chwili: kształtują dzień i rok, nie zmieniając dwa razy sumy rocznej
  (ważne dla kalibracji na danych z falownika).
- Wiatr jest liczony **raz** – w temperaturze ogniw estymatora (prognoza krótkoterminowa nie mnoży go drugi raz;
  nieużywana funkcja `WeatherEffects.windFactor` została usunięta).
- Prognoza (`PredictivePvEngine`): estymator bez własnego clippingu → kalibracja → zacienienie → nowcast → **jeden** clipping
  na końcu `min(limit z ustawień, limit falownika, limit MPPT)`. Wcześniej limit z ustawień przycinał moc przed kalibracją
  i cieniem, więc cień 20 % obniżał moc, która nadal przekraczała limit.
- Zgodność z łańcuchem strat: dla łańcucha sprowadzonego do jednej sprawności η = PR zachodzi
  `P_estymator · 0,95 · Fkąt = P_łańcuch` (test `EngineConsistencyTest`) – żadna strata nie jest liczona dwa razy.
  Z domyślnym `LossProfile` (zabrudzenie 2 %, mismatch 2 %, okablowanie 1,5 % + 1 %, MPPT 99,5 %, falownik 96 %) łańcuch daje
  ok. 5 % więcej niż PR 0,80 w pogodne letnie południe – dlatego „oczekiwana moc” w Diagnostyce PV i w prognozie mogą się różnić.

## 5. Energia
Energia = całka mocy w czasie (reguła punktu środkowego po dokładnych przedziałach, krok 5 min na żywo, 5–15 min w planach;
pierwszy i ostatni przedział mogą być krótsze). Nigdy suma próbek mocy pobieranych na początku kroku – tak liczyły
„pozostałą energię dnia” `EnergyForecastEngine.day` i `EnergySecurity.outlook` (zawyżenie o 4,6 % o 14:07 i 19 % o 18:07; naprawione,
test `ForecastEnergyTest`). Test: energia jednej godziny = średnia moc w tej godzinie (kW·1 h = kWh). Wiersze godzinowe
prognozy: średnia z 6 próbek środkowych (co 10 min). Symulacja baterii (`BatteryPredictor`) jest krokiem jawnym Eulera (15 min),
a nie całką krzywej PV. Energia „do teraz” w Centrum = pomiar z falownika (POMIAR), „reszta dnia” = prognoza (PROGNOZA).

## 6. Na żywo – co się odświeża i jak często

| Element | Częstotliwość | Źródło |
|---|---|---|
| Ekran Na żywo (moc, słońce, POA, Tc) | co 1 s, tylko gdy ekran jest widoczny | obliczenie lokalne (bez sieci) |
| Prognoza +5/+15/+30/+60 min, maksimum dziś, energia od północy / do końca dnia | co 1 min (w tle, nie opóźnia sekundy) | ten sam model |
| Prognoza pogody (GHI/DNI/DHI, temperatura, wiatr) | pobierana co 60 min (kopia na dysku ważna 1 h); dane godzinowe | Open-Meteo |
| Pomiar falownika | wg ustawionego interwału odczytu (Centrum) | Anenji / Modbus (tylko odczyt) |

Ekran pokazuje, kiedy pobrano prognozę i jak dawno; po 3 h dane są oznaczone jako nieaktualne. W prognozie (`PredictivePvEngine`)
pewność maleje z wiekiem pobranej prognozy (czas od pobrania do chwili, której dotyczy wartość – nie tylko odległość od „teraz”), a
w opisie podstawy pojawia się „pobrana N h temu” po 3 h. Rodzaj wartości (`DataKind`): POMIAR tylko z falownika (świeży i zwalidowany),
SZACUNEK = model bez prognozy pogody, PROGNOZA = model z prognozą pogody lub skorygowany bieżącym pomiarem, NIEAKTUALNE / OSTATNIA ZNANA
dla pomiarów bez świeżego łącza; falownik rozłączony → tylko szacunek, nigdy pomiar. Nowcast = pomiar / oczekiwanie TEGO SAMEGO silnika
(wcześniej dzielono przez model bez kalibracji, więc kalibracja była nakładana drugi raz: przy współczynniku 0,9 prognoza w chwili pomiaru
była o 10 % za niska). Wartość zmienia się
co sekundę tylko z ruchem słońca i interpolacją – model **nie dodaje** losowych wahań ani „chmur”, których nie ma
w danych. Bez internetu: ostatnia zapisana prognoza (z wiekiem), potem klimat / czyste niebo – zawsze podpisane.

## 7. Kalibracja i trafność (istniejące)
`AutoCalibration` (współczynniki zależne od pory dnia, nieba, temperatury – dopiero przy wystarczającej liczbie
dni), `ForecastAccuracy` (MAE, RMSE, bias, błąd energii dziennej; MAPE tylko przy rzeczywistej wartości ≥ 0,05 kWh/kW), ocena prognoz
„dzień naprzód” w zakładce Radar. Bez historii pomiarów – „kalibracja niedostępna”, bez fikcyjnych danych.
Wykluczane z uczenia: brak łącza / nieświeże dane, awaria, błędna telemetria, clipping, słońce < 10°, POA < 100 W/m²
(gdy znane), 0 W przy oczekiwanych > 0,3 kW, silne zacienienie; pojedynczy odczyt niczego nie zmienia (≥ 60 próbek, ≥ 3 dni).
Współczynnik jest uczony na modelu estymatora (PR) i stosowany tylko w prognozie – nie w łańcuchu strat Diagnostyki.

## 8. Ograniczenia
- Dane pogodowe są godzinowe (prognoza numeryczna, nie pomiar). Przejście pojedynczej chmury w skali minut nie jest
  znane modelowi; bez pomiaru z falownika estymacja minutowa jest tak dobra jak prognoza godzinowa.
- Dostawca nie podaje typu chmur – są tylko procenty pokrycia warstw (nie „rozpoznajemy” chmur).
- Radar opadów nie jest pomiarem irradiancji i nie jest używany do liczenia mocy.
- Open-Meteo oferuje też dane 15-minutowe (`minutely_15`, natywnie dla części Europy); nie zostały włączone, bo nie
  mogły być sprawdzone na żywo w tym środowisku – kandydat na kolejny krok.
- Jedna płaszczyzna paneli w ustawieniach; wiele orientacji (wschód/zachód) – liczone osobno w projektancie
  i zacienieniu, nie w głównym estymatorze. Jedno MPPT / jeden limit falownika.
- Odbicie od gruntu – izotropowe, stałe albedo 0,2 (śnieg zwiększa albedo – nieuwzględnione). Brak modelu Perez (brak możliwości
  zweryfikowania tablic współczynników offline); Hay–Davies nie uwzględnia rozjaśnienia horyzontu.
- IAM (ASHRAE b0 = 0,05) tylko dla wiązki; rozproszone i odbite bez straty kątowej; brak strat spektralnych.
- Porównanie z trackerem: normalizacja kątowa PR liczona jest dla chwilowej orientacji powierzchni (różnica ≤ ok. 1 % w zysku),
  bez backtrackingu i zużycia własnego trackera.
- Zacienienie (`ShadingAnalysisEngine`): geometryczne (promień do próbek na panelach vs bryły przeszkód i horyzont terenu 90 m),
  diody bocznikujące i wybór prądu MPPT, rozproszone × współczynnik widoczności nieba (stosowany także do składowej odbitej).
  Dane o wysokościach z OSM są niepełne – przeszkody bez wysokości są pomijane i wypisane. Energia strat dnia = suma próbek co 5 min
  (próbka na początku kroku, błąd rzędu 0,1 %). Moc po cieniu = moc bez cienia × współczynnik, nie ponowne obliczenie elektryczne.
- Niepewność prognozy (min/max) to heurystyka ∝ (1 − pewność), a nie przedział statystyczny – brak pomiarów, by go skalibrować.
- Nic w tym środowisku nie było sprawdzone na prawdziwych pomiarach instalacji; testy używają wartości z publikacji i obliczeń ręcznych.
