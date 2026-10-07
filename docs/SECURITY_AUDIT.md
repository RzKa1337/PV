# Audyt bezpieczeństwa – Solar Tracker PRO

Data: 2026-10-07 · zakres: kod `:core` i `:app`, manifest, przechowywanie danych, sieć, integracja z falownikiem,
aktualizator, GitHub Actions, historia git. Repozytorium jest **publiczne** – to uwzględniono w ocenie.

## Wynik w skrócie

| Obszar | Ocena | Uwagi |
|---|---|---|
| Sekrety w repozytorium i historii git | ✅ brak | Jedyne trafienia to fałszywe wartości w testach (`github_pat_TEST_123`, `good-token`). `local.properties`, `*.jks`, `.env` w `.gitignore`. |
| Token GitHub w aplikacji | ✅ | Szyfrowany AES-256-GCM kluczem z Android Keystore (`SecretStore`), stary zapis jawny jest migrowany i usuwany; plik wykluczony z kopii zapasowej i transferu urządzenia; pole maskowane w UI. |
| Wyciek tokena przy pobieraniu | ✅ | Przekierowania obsługiwane ręcznie, nagłówek `Authorization` tylko do hosta źródłowego, odrzucane przejście HTTPS→HTTP. |
| Aktualizacje | ✅ | SHA-256 z `SHA256SUMS`, potem kontrola pakietu, wersji i **identycznego certyfikatu podpisu** z zainstalowaną aplikacją; plik niezgodny jest usuwany. |
| Falownik – tylko odczyt | ✅ | `ModbusCodec` koduje wyłącznie funkcje 0x03/0x04, PI30 tylko polecenia `Q…`; `InverterCommandService` ma jedynie „odśwież”. Nowy test regresyjny pilnuje odrzucania zapisu. |
| Porty / komendy z sieci | ✅ | Tylko połączenia wychodzące (TCP do mostka, USB-OTG); żaden port nie jest otwierany, żadne polecenia z sieci nie są wykonywane. |
| Sieć | ✅ | Wszystkie adresy HTTPS (Open-Meteo, GitHub, Nominatim, Overpass); dane użytkownika w URL kodowane; brak ruchu jawnego HTTP (domyślna polityka Androida). |
| Baza SQLite | ✅ | Zapytania parametryzowane, nazwy tabel to stałe. |
| Eksport CSV/JSON/PDF | ✅ | Przez systemowy wybór pliku (SAF); brak plików w pamięci współdzielonej i brak FileProvidera. |
| Logi | ✅ | Brak `Log.*`/`println`; dziennik aktualizacji nie zapisuje tokena ani nagłówków. |
| Komponenty eksportowane | ✅ | Tylko launcher i widżet (odświeżenie z pamięci podręcznej, bez sieci); odbiorniki instalacji nieeksportowane, PendingIntent z jawnym celem. |
| GitHub Actions | ⚠️ → ✅ poprawione | Patrz niżej. |

## Poprawki wprowadzone w tym audycie

1. **Uprawnienia CI (zasada najmniejszych uprawnień)** – domyślnie `contents: read`; zapis (`contents: write`)
   tylko w zadaniu `build`, które dołącza APK do wydania. Zadanie emulatora nie ma już prawa zapisu do repozytorium.
2. **Przypięcie akcji zewnętrznej** – `reactivecircus/android-emulator-runner` przypięta do SHA commita
   zamiast ruchomego tagu `v2` (ochrona przed podmianą tagu w cudzym repozytorium).
3. **Generator klucza podpisu** – workflow `signing-key.yml` odmawia działania w publicznym repozytorium:
   artefakty publicznego repo może pobrać każdy zalogowany użytkownik GitHuba, więc klucz byłby ujawniony.
   Sprawdzono: w repozytorium nie ma żadnego zachowanego artefaktu z kluczem.
4. **Test regresyjny „tylko odczyt”** – `modbusWriteFunctionsAreRefused`: funkcje 0x05/0x06/0x0F/0x10/0x16/0x17
   (RTU i TCP) oraz polecenia PI30 zmieniające ustawienia (`PBCV`, `PCP`, `MCHGC`, `F50`) muszą być odrzucane.

## Ryzyka zaakceptowane / zalecenia

- **Modbus TCP / mostek RS485-Wi-Fi nie ma szyfrowania ani uwierzytelniania** (cecha protokołu). Mostek trzymaj
  w sieci domowej, nie przekierowuj jego portu na routerze do internetu.
- **Kopia zapasowa Androida** (`allowBackup=true`) obejmuje ustawienia, lokalizację instalacji i historię
  pomiarów (bez tokena). Kopia Google jest szyfrowana blokadą ekranu; jeśli to niepożądane – wyłącz kopię dla aplikacji.
- **Prywatność**: współrzędne instalacji trafiają do Open-Meteo (pogoda), a przy wyszukiwaniu/analizie zacienienia
  do Nominatim/Overpass (OpenStreetMap). Inne dane nie są wysyłane.
- **Sekrety CI** (`SIGNING_*`): dostępne tylko dla pushy właściciela/współpracowników; PR z forków ich nie dostają.
  Nie dodawaj workflow z `pull_request_target`. Kopię `release.jks` i haseł trzymaj poza repozytorium.
- **Zależności**: wersje z grudnia 2024 (AGP 8.7.3, Compose BOM 2024.12.01, osmdroid 6.1.20, usb-serial 3.8.1)
  – w tym audycie nie sprawdzano baz CVE (brak dostępu z sandboksu); zalecane okresowe aktualizacje (np. Dependabot).
- Release bez R8/minifikacji – nie jest to ryzyko bezpieczeństwa (kod jest otwarty), jedynie większy APK.
