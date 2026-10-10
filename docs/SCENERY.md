# Tła ze zdjęciami słonecznych miejsc i wygląd aplikacji

Każda zakładka ma w tle zdjęcie słonecznego miejsca. Pod nim leży welon w kolorze motywu, a dane są na
półprzezroczystych kartach. Ekran główny pokazuje aktualną moc na zdjęciu.

## Skąd są zdjęcia

| | |
|---|---|
| Źródło | **Wikimedia Commons**, oficjalne MediaWiki API (`commons.wikimedia.org/w/api.php`, `generator=search` + `prop=imageinfo`) |
| Klucz API | **nie jest potrzebny** – w kodzie nie ma żadnych kluczy |
| Wybór | wyszukiwanie w kategorii **Quality images** (zdjęcia ocenione przez społeczność Commons), tylko JPEG, oryginał ≥ 1600 px |
| Licencje | przyjmowane: **CC0, domena publiczna, CC BY, CC BY-SA** (każda wersja). Odrzucane: NC, ND, sama GFDL, „wszystkie prawa zastrzeżone” |
| Uznanie autorstwa | pasek u góry każdej zakładki: „Fot. *autor* · *licencja* · Wikimedia Commons”. Dotknięcie otwiera stronę pliku z pełną licencją. Zdjęcie bez autora (poza domeną publiczną) jest pomijane |
| Rozmiar | miniatura 1920 px (standardowy rozmiar Wikimedia); na sieci komórkowej (taryfowej) 1280 px |
| User-Agent | `SolarTrackerPRO/<wersja> (Android; https://github.com/RzKa1337/PV)` – zgodnie z zasadami Wikimedia |

Motywy i miejsca (`core/.../scenery/Scenery.kt`, `SceneTheme`):

- **Wybrzeża:** Santorini (Oia), Wybrzeże Amalfi, Didim/Altınkum, wybrzeże Morza Egejskiego, greckie wyspy, Algarve.
- **Miasteczka:** białe wioski Andaluzji, Palermo, Sycylia, Prowansja, Toskania, Cinque Terre.
- **Wyspy:** Wyspy Kanaryjskie, Lanzarote, Teneryfa, Kreta, Majorka, Madera.
- **Pustynie:** Atakama, Sahara, Wadi Rum, Fuerteventura, Dolina Śmierci, Namib.
- **Fotowoltaika:** farmy i elektrownie PV, panele na dachach domów, zachody słońca nad farmami.

Przypisanie motywów do zakładek (`SceneSlot`):

| Zakładki | Motyw |
|---|---|
| Pulpit, Radar | Wybrzeża |
| Live, Centrum | Fotowoltaika |
| Kąty, Miesiące | Miasteczka |
| Energia, Ustawienia | Wyspy |
| Narzędzia | Pustynie |

Zakładki z tym samym motywem dostają różne zdjęcia.

**Unsplash / Pexels nie są użyte.** Oba wymagają klucza API, a klucz w aplikacji można wyciągnąć. Unsplash wymaga
też wyświetlania zdjęć z ich serwerów i zgłaszania pobrań. Wikimedia nie potrzebuje klucza i ma jasne licencje.

## Losowanie i pora dnia

- **Zestaw** jest wyznaczony jednym losowym ziarnem (`seed`) zapisanym na telefonie. Każda zakładka bierze z niego
  swoje zdjęcie, więc **przełączanie zakładek nie losuje ponownie**.
- **„Zmień scenerię”** (ikona krajobrazu w pasku u góry lub Ustawienia → Tło aplikacji) losuje nowe ziarno, czyli
  nowy zestaw dla wszystkich zakładek.
- **Pora dnia** jest liczona z położenia Słońca w wybranej lokalizacji (`DayPhase`) i sprawdzana co 5 minut. Nowe
  zdjęcie pojawia się tylko przy zmianie pory:

  | Pora | Położenie Słońca |
  |---|---|
  | świt | od −6° do 10°, przed południem |
  | dzień | powyżej 10° |
  | popołudnie | po południu, poniżej 25° |
  | zachód | od 10° do −6°, po południu |
  | noc | poniżej −6° |

  Do wyszukiwania dodawane jest słowo „sunrise”, „sunset” lub „night”.
- To **tylko dekoracja**. Zdjęcie nie zależy od pogody ani produkcji i nie sugeruje, że wykryto słońce. Ciepła
  poświata w rogu ekranu nie pojawia się w nocy.

## Pamięć podręczna i offline

- **Wyniki wyszukiwania** są zapisane per motyw, pora dnia i miejsce w `files/scenery/lists`. Ważne są 30 dni, a
  pusty wynik 1 dzień. Na jedno wczytanie przypadają najwyżej 3 zapytania.
- **Zdjęcia** są zapisane w `files/scenery/photos`. Limit to **40 MB**; po jego przekroczeniu usuwane są najdawniej
  używane. Pliki są sprawdzane: pobierany jest tylko JPEG z hosta `*.wikimedia.org` po HTTPS, najwyżej 6 MB, i tylko
  plik, który da się odczytać jako obraz.
- **W pamięci RAM** są najwyżej 3 zdjęcia, zdekodowane z podpróbkowaniem do rozmiaru ekranu.
- **Bez internetu** pokazywane jest zapisane zdjęcie z tego samego motywu (albo jakiekolwiek zapisane). Przy
  pierwszym uruchomieniu bez sieci pokazywana jest **wbudowana ilustracja wektorowa**: Santorini, Algarve, Prowansja,
  Toskania, Atakama lub Dolomity.
- **Bez migotania:** zdjęcie wczytuje się w tle. Poprzednie zostaje na ekranie, a nowe pojawia się płynnym
  przejściem (0,7 s). Błąd pobierania nigdy nie usuwa zdjęcia, które jest już widoczne.
- Ładowanie zdjęć działa w osobnym wątku (`Dispatchers.IO`) i **nie wpływa na pomiary ani obliczenia PV**.
- Zdjęcia można wyłączyć w Ustawieniach → Tło aplikacji → „Zdjęcia słonecznych miejsc”. Wtedy aplikacja pokazuje
  same ilustracje i nic nie pobiera.

## Czytelność i dostępność

- Na zdjęciu leży welon w kolorze tła motywu, krycie 74%. Na czarnym i na białym zdjęciu daje to kontrast ≥ 4,5:1
  dla zwykłego tekstu (`onSurface`, `onSurfaceVariant`) w obu motywach. To wartość obliczona, nie zmierzona na
  urządzeniu.
- Nagłówki ekranów i karta mocy pokazują zdjęcie wyraźniej, pod ciemnym gradientem z białym tekstem z cieniem.
- Karty są półprzezroczyste (86%) z delikatną ramką. Karty statusu (błędy, ostrzeżenia) zostają nieprzezroczyste.
- Przycisk „Zmień scenerię” i autor zdjęcia mają opisy dostępności. Status jest pokazany tekstem i ikoną, nie
  tylko kolorem.

## Animacje

Wszystkie animacje są wyłączone, gdy w systemie włączone jest **„Usuń animacje”** (`ANIMATOR_DURATION_SCALE = 0`).

| Efekt | Opis |
|---|---|
| Ken Burns | zdjęcie w tle powoli przybliża się o 7% przez 24 s **jeden raz** po pojawieniu się, potem stoi – ekran nie odświeża się bez końca |
| Zmiana zdjęcia | płynne przejście 0,7 s |
| Zmiana zakładki | przenikanie 0,22 s |
| Karty | jednorazowe pojawienie się (0,36 s, lekki ruch w górę) |
| Wykresy | rysowanie od lewej do prawej przy pierwszym pokazaniu (0,9 s) |
| Moc na pulpicie | wartość i łuk płynnie przechodzą do nowej wartości (0,7–0,9 s) tylko wtedy, gdy wynik się zmienia |
| Poświata wokół mocy | jasność proporcjonalna do udziału w mocy szczytowej; przy 0 W (noc) brak poświaty |
| Wskaźnik aktualizacji | kółko „Aktualizacja…” w pasku podczas odświeżania pogody, potem godzina pobrania |
| Aktywna zakładka | złote podświetlenie w pasku nawigacji |

Celowo **pominięte:**

- **rozmycie tła (blur)** – w Compose działa dopiero od Androida 12 i obciąża GPU; zastępuje je welon;
- **animowane promienie słońca** – zamiast nich statyczna, ciepła poświata w rogu, tylko w dzień.

## Paleta

- **Jasny motyw:** granat (`#0F1A2A`) dla tekstu, petrol (`#0B4F6C`) jako kolor główny, turkus (`#00796B`) jako
  dodatkowy, złoto (`#FFE3A6`) jako akcent i chłodne, jasne tło.
- **Ciemny motyw:** granat (`#0A1220`), złoto (`#F2C14E`) jako kolor główny, turkus (`#4FD8C4`) jako dodatkowy.
- Kolory wykresów (`ChartColors`) się nie zmieniły.

## Co zostało sprawdzone, a co nie

- **Testy jednostkowe (`SceneryTest`, 10 testów):**
  - parser odpowiedzi API, sprawdzony na zapisanym formacie odpowiedzi, nie na żywym API;
  - filtr licencji;
  - wyłuskanie autora z HTML;
  - kodowanie adresu zapytania;
  - błędy HTTP i brak sieci;
  - pora dnia dla Zielonej Góry;
  - stabilność losowania;
  - zapis i odczyt listy.
- **Test na emulatorze (`SceneryInstrumentedTest`):**
  - pasek z autorem i przycisk „Zmień scenerię”;
  - szybkie przełączanie zakładek;
  - wyłączenie zdjęć, po którym pojawia się ilustracja.

  Test przechodzi z internetem i bez niego.
- **Nie sprawdzono:**
  - jakie dokładnie zdjęcia zwraca Wikimedia dla każdego zapytania – z naszego środowiska nie ma dostępu do
    `commons.wikimedia.org`. Jeśli dla jakiegoś miejsca wyszukiwanie w „Quality images” nic nie da, aplikacja
    próbuje innych miejsc z tego motywu, a potem pokazuje ilustrację;
  - płynność i zużycie pamięci na prawdziwym telefonie średniej klasy;
  - wygląd na tabletach.

## Pliki

- `core/.../scenery/Scenery.kt` – pora dnia, motywy, klient Wikimedia z filtrem licencji, losowanie, zapis list.
- `app/.../data/SceneryRepository.kt` – ziarno, włącznik, pamięć podręczna na dysku i w RAM, pobieranie, offline.
- `app/.../ui/components/ScenicBackground.kt` – `ScenicBackdrop`, `SceneryBar`, `ScenicPicture`, `SceneryWindow`,
  `rememberScenery`, `gentleEnter`, `revealOnAppear`, ograniczenie animacji.
- `app/.../ui/components/SunnyBanner.kt` – nagłówek ekranu pokazujący bieżącą scenerię oraz ilustracje zastępcze.
- `app/.../ui/screens/DashboardScreen.kt` – karta mocy na zdjęciu (W, łuk, poświata, najbliższe godziny).
- `app/.../ui/theme/Theme.kt` – paleta.
