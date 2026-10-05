# Podpis wydań i automatyczna aktualizacja

Android instaluje aktualizację **tylko wtedy, gdy nowy APK jest podpisany tym samym kluczem** co zainstalowana
aplikacja. Dlatego wydania muszą być podpisywane jednym, stałym kluczem. Klucz nigdy nie trafia do repozytorium –
jest przechowywany wyłącznie w **GitHub Secrets**.

## Jednorazowa konfiguracja (właściciel repozytorium)

1. GitHub → repozytorium → **Actions** → *Generate release signing key* → **Run workflow**, wpisz `GENERATE`.
2. Po zakończeniu pobierz artefakt `release-signing-key-KEEP-SECRET` (zip z czterema plikami `.txt` i `release.jks`).
3. GitHub → **Settings → Secrets and variables → Actions → New repository secret** – dodaj cztery sekrety,
   wklejając zawartość plików o tych samych nazwach:
   - `SIGNING_KEYSTORE_BASE64`
   - `SIGNING_KEYSTORE_PASSWORD`
   - `SIGNING_KEY_ALIAS`
   - `SIGNING_KEY_PASSWORD`
4. Usuń przebieg workflow (⋯ → *Delete workflow run*), żeby artefakt zniknął od razu.
5. Zachowaj `release.jks` i hasło w bezpiecznym miejscu (menedżer haseł). **Utrata klucza = brak możliwości
   aktualizacji** – trzeba będzie odinstalować aplikację i zainstalować ją od nowa.

Alternatywnie klucz można wygenerować lokalnie:

```sh
keytool -genkeypair -storetype PKCS12 -keystore release.jks -alias solartracker \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks   # → SIGNING_KEYSTORE_BASE64
```

## Co robi CI

- Każdy build tworzy `app-release.apk`; z sekretami podpisuje go kluczem wydania, bez nich – kluczem debug
  (z ostrzeżeniem w podsumowaniu przebiegu).
- Przy wydaniu (`release_tag`) do GitHub Release trafiają:
  - `SolarTrackerPRO-<tag>-universal.apk`
  - `SHA256SUMS` – sumy SHA-256 w formacie `sha256sum`
  - w opisie: SHA-256 certyfikatu podpisu.

## Jak aplikacja weryfikuje aktualizację

1. Pobiera listę wydań z API GitHuba (kanał stabilny lub beta), wybiera najnowsze nowsze od zainstalowanego.
2. Wymaga pliku `SHA256SUMS`; wydanie bez niego nie jest proponowane do instalacji.
3. Pobiera APK (z wznowieniem i ponawianiem) i porównuje SHA-256 – niezgodny plik jest usuwany.
4. Sprawdza w APK: nazwę pakietu, wyższy `versionCode` i **certyfikat podpisu identyczny z zainstalowaną aplikacją**.
5. Dopiero wtedy przekazuje plik do instalatora systemu.

## Pierwsze przejście na klucz wydania

Wersje do v0.4.0 były podpisane losowym kluczem debug. Pierwszą wersję podpisaną kluczem wydania trzeba
zainstalować ręcznie po odinstalowaniu poprzedniej (Android nie pozwala zmienić klucza). Ustawienia warto
wcześniej zanotować. Od tej wersji aktualizacje działają już automatycznie.

## Prywatne repozytorium

API GitHuba nie pokazuje wydań prywatnego repozytorium bez uwierzytelnienia. W ustawieniach aktualizacji
w aplikacji można wpisać token (*fine-grained*, dostęp tylko do tego repozytorium, uprawnienie
**Contents: Read-only**). Token jest zapisany tylko na telefonie i wysyłany wyłącznie do `api.github.com`.
