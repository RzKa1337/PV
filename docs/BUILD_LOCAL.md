# Budowanie aplikacji na własnym komputerze (bez GitHub Actions)

Przydatne, gdy GitHub Actions nie działa (np. limit płatności). Potrzebny jest komputer z Windows, macOS lub Linux.

## 1. Przygotowanie (jednorazowo)
1. Zainstaluj **Android Studio** (https://developer.android.com/studio) – zawiera Android SDK i Javę.
2. Pobierz kod: na GitHubie w repozytorium **Code → Download ZIP** i rozpakuj (albo `git clone`).
3. W Android Studio: **File → Open** → wybierz rozpakowany folder. Poczekaj, aż Gradle pobierze zależności.

## 2. Klucz podpisu – ważne
Aktualizacja zainstaluje się „na” istniejącą aplikację (z zachowaniem ustawień i historii) **tylko** gdy APK jest
podpisany tym samym kluczem co dotychczasowe wydania: plik `release.jks` z archiwum
`release-signing-key-KEEP-SECRET` (patrz `RELEASE_SIGNING.md`) oraz hasła z plików `.txt` w tym archiwum.

Bez tego klucza można zbudować wersję debug, ale wtedy trzeba najpierw **odinstalować** aplikację (utrata
ustawień i historii), a późniejsze aktualizacje z GitHuba też nie zainstalują się bez ponownej reinstalacji.

## 3. Budowanie podpisanego APK

### Najprościej – z Android Studio
**Build → Generate Signed App Bundle / APK → APK → Next** →
- *Key store path*: `release.jks`,
- *Key store password*: zawartość `SIGNING_KEYSTORE_PASSWORD.txt`,
- *Key alias*: zawartość `SIGNING_KEY_ALIAS.txt`,
- *Key password*: zawartość `SIGNING_KEY_PASSWORD.txt`,
→ **Next** → wariant **release** → **Create**. Plik: `app/release/app-release.apk`.

### Z wiersza poleceń
Linux / macOS:
```sh
export SIGNING_KEYSTORE_FILE=/ścieżka/do/release.jks
export SIGNING_KEYSTORE_PASSWORD='hasło'
export SIGNING_KEY_ALIAS='alias'
export SIGNING_KEY_PASSWORD='hasło klucza'
./gradlew :core:test :app:assembleRelease
```
Windows (PowerShell):
```powershell
$env:SIGNING_KEYSTORE_FILE="C:\ścieżka\release.jks"
$env:SIGNING_KEYSTORE_PASSWORD="hasło"
$env:SIGNING_KEY_ALIAS="alias"
$env:SIGNING_KEY_PASSWORD="hasło klucza"
.\gradlew.bat :core:test :app:assembleRelease
```
Wynik: `app/build/outputs/apk/release/app-release.apk`. Haseł nie zapisuj w plikach projektu.

## 4. Instalacja na telefonie
- Skopiuj `app-release.apk` na telefon (kabel USB, Google Drive, e-mail) i otwórz go w menedżerze plików –
  Android zapyta o zgodę na instalację z tego źródła.
- Albo z telefonem podłączonym kablem (debugowanie USB włączone): `adb install -r app-release.apk`.

Sprawdzenie podpisu (opcjonalnie): `apksigner verify --print-certs app-release.apk` – SHA-256 certyfikatu
powinien być taki sam jak w opisach wydań na GitHubie (`a58b2a09…299b`).
