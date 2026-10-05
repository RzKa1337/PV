# Analiza zacienienia

> **Status: REAL MAP AND HEIGHT DATA VALIDATION REQUIRED.** Silnik geometryczny jest przetestowany na danych
> syntetycznych. Wyniki dla prawdziwej lokalizacji zależą od jakości danych mapowych i wysokości – zawsze z oceną pewności.

## Wybór lokalizacji

1. **Wyszukiwanie** miasta, adresu lub kodu pocztowego: OpenStreetMap Nominatim (adresy, kody; maks. 1 zapytanie/s,
   tylko po kliknięciu „Szukaj”), zapasowo Open-Meteo Geocoding (miasta, z wysokością n.p.m.).
2. **Mapa** (OpenStreetMap, osmdroid): dotknięcie ustawia dokładny punkt instalacji.
3. Pokazywane: współrzędne, wysokość n.p.m., dokładność (punkt z mapy / GPS / adres / kod pocztowy / miasto).
   Ręcznie wskazany punkt nigdy nie jest zastępowany przybliżeniem miasta.
4. **Wymagane potwierdzenie** lokalizacji – bez niego zacienienie nie jest liczone.

## Źródła danych

| Dane | Źródło | Rozdzielczość / uwagi |
|---|---|---|
| Budynki, drzewa, kominy, maszty, słupy, mury/ogrodzenia | OpenStreetMap przez Overpass API | obrysy z mapy; wysokość tylko z tagów |
| Wysokość budynku | tag `height` (MAP_TAG), `building:levels` × wys. kondygnacji (FROM_LEVELS – szacunek), użytkownik (pomiar/szacunek) | **brak tagu → UNKNOWN, nigdy nie zgadujemy** |
| Teren | Copernicus DEM GLO-90 przez Open-Meteo Elevation API | ~90 m; siatka 36 kierunków × 9 odległości (100 m–6 km) |
| LiDAR / DSM | nieobsługiwane automatycznie | wysokość z pomiaru można wpisać jako „potwierdzoną” |

Dane są pobierane tylko na żądanie („Pobierz budynki i teren”) i zapisywane lokalnie (`files/shading`), więc obliczenia
działają offline. „Usuń pobrane” kasuje dane mapowe (przeszkody użytkownika zostają).

## Model

- **Pozycja Słońca:** algorytm NOAA/Meeus (± ~0,01°), z refrakcją, dla daty, czasu, strefy czasowej (czas letni)
  i współrzędnych; wysokość n.p.m. uwzględniana w masie powietrza.
- **Geometria:** lokalny układ wschód/północ (metry) wokół instalacji. Dla każdej przeszkody: odległość, azymut, zakres
  kątowy, wysokość względna = (teren przy przeszkodzie − teren instalacji) + wysokość przeszkody − wysokość paneli,
  kąt elewacji (z krzywizną Ziemi i refrakcją).
- **Bryły:** budynki = graniastosłupy z obrysu; punkty (komin, słup, drzewo) = walce; linie (mur, płot) = cienkie
  pasy; drzewa – korona od zadanej wysokości z przepuszczalnością zależną od sezonu (liście V–X).
- **Panele:** siatka rzędów × kolumn w płaszczyźnie (kąt, azymut, wysokość dolnej krawędzi); w każdej części panelu
  chronionej diodą bocznikującą próbki punktów. Dla każdego punktu promień w stronę Słońca jest testowany ze
  wszystkimi bryłami i horyzontem terenu.
- **Elektryka:** string = szereg grup diod; MPPT wybiera prąd maksymalizujący moc, grupy o mniejszym natężeniu są
  bocznikowane (cień częściowy nie wyłącza całego stringu). Rozproszone światło zmniejszane współczynnikiem
  widoczności nieba z profilu horyzontu.
- **Wyniki:** profil horyzontu 360°, cień bieżący i dla dowolnej godziny (mapa + siatka paneli), zdarzenia cienia
  (początek, koniec, czas, przeszkoda, panele, % powierzchni, strata energii), dzień / miesiąc / rok, strata energii,
  produkcja bez i z zacienieniem.

## Pewność (confidence score)

Iloczyn czynników: dokładność lokalizacji (punkt z mapy 1,0 … miasto 0,3), dane terenu (bez: 0,85), dane budynków
(niepobrane: 0,6), średnia jakość wysokości istotnych przeszkód (potwierdzona 1,0, LiDAR 0,95, tag mapy 0,8,
z kondygnacji 0,55, szacunek 0,5, nieznana 0,2), potwierdzenie lokalizacji (bez: 0,5).
Wynik oznaczany jako **CALCULATED** tylko przy samych danych pewnych; w innym przypadku **ESTIMATED**. Prognozy na
przyszłe dni z pogodą → **FORECAST**. Przeszkody bez wysokości są **pomijane** w obliczeniach i wypisywane na liście
brakujących danych (z ostrzeżeniem „Brak wysokości …”).

## Ręczne przeszkody i korekty

Na mapie: punkt (drzewo, komin, słup), obrys (budynek), linia (mur); lub „odległość + azymut od–do”. Wysokość: względna,
bezwzględna n.p.m. albo kondygnacje × wysokość kondygnacji + dach (zawsze szacunek z zakresem niepewności).
Oznaczenie „potwierdzona/szacowana”, sezonowość i przepuszczalność drzew, wyłączenie przeszkody. Korekta obiektu z mapy
tworzy wpis użytkownika, który ma pierwszeństwo, z zachowaniem źródła i historii zmian; ekran pokazuje stratę przed i po.

## Format danych (offline)

JSON w wersji 1 (`ObstacleCodec`): przeszkody (id, typ, kształt, wysokość + źródło + niepewność, teren, drzewo,
włączona, źródło, data, nadpisywany obiekt, historia) oraz siatka terenu.

## Ograniczenia – kiedy wynik jest tylko szacunkiem

- Brak wysokości budynków w OSM (częste) – trzeba je podać ręcznie.
- Teren 90 m nie oddaje skarp i pojedynczych obiektów; brak DSM/LiDAR w automatycznym trybie.
- Kształt dachów uproszczony (płaski wierzchołek bryły); odbicia od elewacji, śnieg, lokalne zabrudzenie – pomijane.
- Wzajemne zacienianie rzędów paneli: rzędy są w jednej płaszczyźnie (brak osobnych stojaków).
- Dokładność czasu cienia zależy od dokładności lokalizacji i wysokości (np. ±1 m wysokości budynku 20 m dalej
  ≈ kilka minut przesunięcia przy niskim Słońcu).
