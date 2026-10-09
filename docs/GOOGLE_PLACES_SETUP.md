# Wyszukiwanie miast i Google Places API – konfiguracja

Ustawienia → Lokalizacja: pole **„Miasto, region lub adres”** pokazuje podpowiedzi już podczas pisania („Warsz” → Warszawa,
Mazowieckie, Polska …). Wybranie podpowiedzi zapisuje **współrzędne zwrócone przez dostawcę** (nigdy z tekstu nazwy),
a pozycja Słońca, pogoda, prognoza i produkcja PV od razu ich używają.

## Dostawcy

| Kolejność | Dostawca | Klucz | Kiedy |
|---|---|---|---|
| 1 | **Google Places API (New)** – Autocomplete + Place Details | tak | gdy klucz jest skonfigurowany (w aplikacji albo przy budowaniu) |
| 2 | **Photon** (komoot, dane OpenStreetMap, ODbL) | nie | podpowiedzi podczas pisania: miasta, regiony, pustynie, góry, jeziora, ulice, adresy, obiekty; wyniki bliżej obecnej lokalizacji wyżej |
| 3 | **Open-Meteo Geocoding** (GeoNames, CC BY 4.0) | nie | gdy poprzedni dostawca nic nie znalazł lub odpowiedział błędem |
| – | **OpenStreetMap Nominatim** | nie | tylko po „Szukaj” na klawiaturze lub „Szukaj dokładniej” (pełne zapytania typu „Atakama, Chile”, nazwy w języku aplikacji, adresy, kody) – zasady Nominatim zabraniają autouzupełniania |
| – | **Współrzędne / link do mapy** | – | wpisane „52.23, 21.01”, „24.5S 69.25W”, „52°13'48"N 21°0'36"E”, `geo:` lub wklejony link Google Maps / OpenStreetMap – bez zapytania do sieci |

Aplikacja działa w pełni **bez klucza**. Photon ma zasadę „fair use” (bez gwarancji dostępności, intensywne użycie
jest ograniczane) – zapytania są wysyłane po przerwie w pisaniu i zapamiętywane na 10 minut; przy błędzie aplikacja
przechodzi do Open-Meteo.

## Jak działa wyszukiwanie

- Podpowiedzi po ~300 ms przerwy w pisaniu (od 2 znaków); nowszy znak anuluje starsze zapytanie.
- Wyniki z ostatnich 10 minut są w pamięci – kasowanie i ponowne wpisanie nie wysyła zapytań.
- Każda podpowiedź: nazwa (dopasowany fragment pogrubiony), region i kraj; pod listą źródło wyników.
- Stany: ładowanie (kółko w polu), brak wyników, brak internetu / limit / odrzucony klucz z przyciskiem „Spróbuj ponownie”.
- Google: jedna **sesja** (session token) obejmuje wpisywanie i pobranie szczegółów wybranego miejsca; Place Details pobiera tylko
  pola `location,formattedAddress` (najtańsza grupa pól). Wysokość n.p.m. dla miejsc z Google – z Open-Meteo (Copernicus DEM).
- „Ostatnio wybrane” (do 6) – szybki powrót do zapisanych miejsc, z możliwością usunięcia.
- Awaryjnie: GPS („Użyj mojej lokalizacji”) i ręczne współrzędne (zwinięte pod „Wpisz współrzędne ręcznie”).

## Konfiguracja klucza Google

1. Google Cloud Console → nowy projekt → **włącz rozliczenia** (Places API jest płatne powyżej darmowego progu).
2. APIs & Services → Library → włącz **Places API (New)** (nie „Places API” w starej wersji).
3. Credentials → Create credentials → API key, potem **Edit API key**:
   - **Application restrictions → Android apps**: nazwa pakietu `com.solartracker.pro` i **SHA-1 certyfikatu podpisu**
     wydań (`keytool -list -v -keystore release.jks -alias <alias>`; to inny skrót niż SHA-256 podawany w wydaniach).
     Aplikacja wysyła nagłówki `X-Android-Package` i `X-Android-Cert`, które Google sprawdza z tym ograniczeniem.
   - **API restrictions → Restrict key → Places API (New)**.
4. **Limity kosztów**: APIs & Services → Places API (New) → Quotas – ustaw dzienny limit żądań (np. 500/dzień);
   Billing → Budgets & alerts – budżet z powiadomieniem.
5. Przekaż klucz aplikacji (jedno z):
   - **w aplikacji**: Ustawienia → Lokalizacja → „Wyszukiwarka: … · dodaj klucz Google” → wklej → „Zapisz klucz”.
     Klucz jest szyfrowany kluczem z Android Keystore i zostaje tylko na tym telefonie;
   - **przy budowaniu**: sekret repozytorium GitHub `MAPS_API_KEY` (używany przez krok „Build release APK”) albo
     `MAPS_API_KEY=…` w nieśledzonym `local.properties` przy budowaniu lokalnym.

### Bezpieczeństwo – czego nie obiecujemy

- Klucz wbudowany w APK (`BuildConfig`) **można z niego wyciągnąć**. Chronią go ograniczenia z punktu 3 (pakiet + SHA-1,
  tylko Places API) i limity z punktu 4 – nie „ukrycie” w pliku.
- Klucz nie jest zapisywany w repozytorium, nie trafia do logów ani komunikatów błędów, nie jest częścią adresu URL
  (wysyłany w nagłówku `X-Goog-Api-Key`).

### Atrybucja i dane

- Pod podpowiedziami wyświetlane jest źródło („Wyniki: Google Maps”, „Open-Meteo / GeoNames”, „OpenStreetMap Nominatim”).
  Zasady Google Maps Platform wymagają atrybucji Google przy wynikach Places pokazywanych bez mapy Google – przed publikacją
  w sklepie sprawdź aktualne wymagania (logo „Google Maps” vs tekst).
- Zapisywane są tylko: nazwa, region/kraj, współrzędne i wysokość wybranego miejsca (do obliczeń) oraz lista ostatnich
  miejsc. Pełne odpowiedzi Places nie są przechowywane; podpowiedzi są trzymane w pamięci najwyżej 10 minut.

## Czego nie da się sprawdzić bez prawdziwego klucza

- Odpowiedzi Google są testowane na **zapisanych formatach odpowiedzi** (`PlaceSearchTest`), nie na żywym API.
- Działanie ograniczenia „Android apps” (zgodność SHA-1 i pakietu) – tylko z kluczem i podpisanym APK.
- Test na emulatorze (`PlaceSearchInstrumentedTest`) używa wyszukiwarki bez klucza (Open-Meteo); bez internetu sprawdza,
  że pokazany jest stan błędu, a nie awaria.

## Pliki

- `core/.../geo/PlaceSearch.kt` – dostawcy (Google, Open-Meteo), dopasowanie i podświetlanie, pamięć podręczna,
  przejście na zapasowego dostawcę, lista ostatnich miejsc. Testy: `core/src/test/.../geo/PlaceSearchTest.kt`.
- `app/.../data/PlaceSearchRepository.kt` – klucz (Keystore / BuildConfig), nagłówki Androida, Nominatim przy „Szukaj”, wysokość.
- `app/.../ui/screens/PlaceSearchField.kt` – pole z podpowiedziami, stany, ostatnie miejsca.
- `app/.../data/SettingsRepository.kt` – zapis wybranego miejsca (`selectPlace`) i listy ostatnich.
