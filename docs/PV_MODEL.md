# Model produkcji PV – równania, dane, częstotliwości

Jeden wspólny estymator (`core/.../pv/PvEstimator.kt`) liczy moc na żywo, pulpit, prognozę godzinową, kąty, miesiące
i porównanie z trackerem. Silnik łańcucha strat (`PvSimulationEngine`, projektant / diagnostyka) używa tych samych
funkcji irradiancji (`poaComponents`) i tej samej temperatury ogniw. Wszystkie wartości są **szacunkiem modelu**
(ESTYMACJA / PROGNOZA); pomiar z falownika jest pokazywany osobno i nigdy nie jest zastępowany modelem.

## 1. Pozycja Słońca
`SolarCalculator` – algorytm NOAA (Meeus): deklinacja, równanie czasu, kąt godzinny, wysokość z refrakcją, azymut;
wschód/zachód (−0,833°), górowanie, długość dnia, dzień/noc polarna. Wg NOAA wschód/zachód z dokładnością około
minuty (szerokości do ±72°), pozycja – ułamki stopnia; to pomijalne wobec niepewności pogody (NREL SPA byłby
dokładniejszy, ale nie zmieniłby mocy w zauważalny sposób). Czas: `Instant` (UTC), strefa
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
  `min(Pszczyt, limit falownika)` (clipping jest sygnalizowany).
  PR (domyślnie 0,80) to wskaźnik **roczny** – zawiera już typowe straty temperaturowe (0,95) i kątowe. Dlatego obie
  części są z niego wyjęte (0,95 oraz `Fkąt` = średnia roczna IAM przy czystym niebie dla tej orientacji i szerokości,
  12 dni × co 30 min) i liczone dla konkretnej chwili: kształtują dzień i rok, nie zmieniając dwa razy sumy rocznej
  (ważne dla kalibracji na danych z falownika).
- Wiatr jest liczony **raz** – w temperaturze ogniw estymatora (prognoza krótkoterminowa nie mnoży go drugi raz).

## 5. Energia
Energia = całka mocy w czasie (reguła punktu środkowego, krok 5 min na żywo, 5–15 min w planach). Nigdy suma próbek
mocy. Test: energia jednej godziny = średnia moc w tej godzinie (kW·1 h = kWh).

## 6. Na żywo – co się odświeża i jak często

| Element | Częstotliwość | Źródło |
|---|---|---|
| Ekran Na żywo (moc, słońce, POA, Tc) | co 1 s, tylko gdy ekran jest widoczny | obliczenie lokalne (bez sieci) |
| Prognoza +5/+15/+30/+60 min, maksimum dziś, energia od północy / do końca dnia | co 1 min (w tle, nie opóźnia sekundy) | ten sam model |
| Prognoza pogody (GHI/DNI/DHI, temperatura, wiatr) | pobierana co 60 min (kopia na dysku ważna 1 h); dane godzinowe | Open-Meteo |
| Pomiar falownika | wg ustawionego interwału odczytu (Centrum) | Anenji / Modbus (tylko odczyt) |

Ekran pokazuje, kiedy pobrano prognozę i jak dawno; po 3 h dane są oznaczone jako nieaktualne. Wartość zmienia się
co sekundę tylko z ruchem słońca i interpolacją – model **nie dodaje** losowych wahań ani „chmur”, których nie ma
w danych. Bez internetu: ostatnia zapisana prognoza (z wiekiem), potem klimat / czyste niebo – zawsze podpisane.

## 7. Kalibracja i trafność (istniejące)
`AutoCalibration` (współczynniki zależne od pory dnia, nieba, temperatury – dopiero przy wystarczającej liczbie
dni), `ForecastAccuracy` (MAE, RMSE, bias, błąd energii dziennej; MAPE tylko przy mocy ≥ progu), ocena prognoz
„dzień naprzód” w zakładce Radar. Bez historii pomiarów – „kalibracja niedostępna”, bez fikcyjnych danych.

## 8. Ograniczenia
- Dane pogodowe są godzinowe (prognoza numeryczna, nie pomiar). Przejście pojedynczej chmury w skali minut nie jest
  znane modelowi; bez pomiaru z falownika estymacja minutowa jest tak dobra jak prognoza godzinowa.
- Dostawca nie podaje typu chmur – są tylko procenty pokrycia warstw (nie „rozpoznajemy” chmur).
- Radar opadów nie jest pomiarem irradiancji i nie jest używany do liczenia mocy.
- Open-Meteo oferuje też dane 15-minutowe (`minutely_15`, natywnie dla części Europy); nie zostały włączone, bo nie
  mogły być sprawdzone na żywo w tym środowisku – kandydat na kolejny krok.
- Jedna płaszczyzna paneli w ustawieniach; wiele orientacji (wschód/zachód) – liczone osobno w projektancie
  i zacienieniu, nie w głównym estymatorze.
- Rozproszona część odbita od gruntu – izotropowa, stałe albedo (śnieg zwiększa albedo – nieuwzględnione tutaj).
