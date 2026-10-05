# Integracja z falownikiem Anenji 6.2 kW 48 V

> **Status: REAL DEVICE VALIDATION REQUIRED.** Warstwa integracji, protokoły, testy i symulator są gotowe.
> Mapa rejestrów pochodzi z dokumentacji społeczności (otwarte integracje Home Assistant), a nie z oficjalnego
> dokumentu producenta. Nie zweryfikowano jej na fizycznym urządzeniu w tym projekcie.

## Obsługiwany model

- Anenji **ANJ-6200W-48V** (6.2 kW, 48 V, hybrydowy on/off-grid) i jego warianty (single/dual output).
- Ta sama rodzina sprzętu bywa sprzedawana jako PowMr, EASUN, Sandisolar i inne („SMG”/Voltronic).

## Interfejsy komunikacyjne (analiza)

| Interfejs | Dostępny w falowniku | Obsługa w aplikacji |
|---|---|---|
| RS232 (gniazdo RJ45 „RS232”) | ✅ | ✅ Modbus RTU 9600 8N1, adres 1, funkcja 03 (mapa SMG); wariant PI30 ASCII |
| RS485 (gniazdo BMS) | ✅ – do komunikacji z BMS baterii | ❌ (to port baterii, nie monitoringu) |
| Wbudowane Wi-Fi (EyeBond/SmartESS) | ✅ – wysyła dane do chmury producenta | ❌ lokalnie (wymaga przechwycenia ruchu – poza zakresem) |
| Bluetooth | zależnie od wersji, tylko aplikacja producenta | ❌ (brak dokumentacji protokołu) |
| USB | kabel RS232→USB | ✅ przez adapter USB-RS232 i OTG telefonu |
| Modbus TCP | ❌ natywnie | ✅ przez bramkę RS232→Modbus TCP |

## Sposób połączenia (do wyboru w Centrum energii → Konfiguracja)

1. **Most RS232 → Wi-Fi/LAN (TCP)** – np. konwerter typu „serial server” w trybie transparentnym (TCP server,
   9600 8N1). Aplikacja łączy się jako klient na podany adres IP i port (domyślnie 8899) i wysyła ramki Modbus RTU.
2. **Bramka Modbus TCP** – konwerter tłumaczący Modbus TCP ↔ RTU (ramki MBAP, unit id = adres falownika).
3. **Kabel RS232 → USB (OTG)** – adapter FTDI/CH340/CP210x/PL2303 podłączony do telefonu; system zapyta o zgodę.
4. **Symulator** – dane testowe oznaczone jako SYMULATOR (nigdy nie są pokazywane jako pomiar).

Aplikacja nie otwiera żadnych portów na telefonie – tylko połączenia wychodzące. Interfejsy nie wymagają haseł.

## Wymagania

- Kabel RJ45 → RS232 (DB9) zgodny z pinoutem falownika (RS232: TX/RX/GND) oraz konwerter (TCP/USB).
- Telefon z USB-OTG (dla połączenia kablowego) lub w tej samej sieci co most TCP.

## Częstotliwość odczytu

- LIVE: domyślnie co 5 s (1–300 s). Odczyt działa **tylko gdy ekran Centrum energii jest otwarty** (bez usługi w tle i
  wake-locków – oszczędność baterii).
- Dane starsze niż 3 × interwał (min. 10 s) → **STALE DATA**. 3 nieudane odczyty z rzędu → **OFFLINE**; ostatnie dane są
  wtedy pokazywane jako **LAST KNOWN**, nigdy jako LIVE. Ponowne połączenie z wykładniczym odstępem (do 60 s).
- Historia: wiersze co 30 s (średnie + energia całkowana z mocy), podsumowania co 15 min; wiersze 30-sekundowe
  przechowywane 30 dni, podsumowania 2 lata, próbki kalibracji 60 dni (SQLite, pamięć telefonu).

## Mapowanie danych (Modbus SMG, rejestry holding)

| Rejestr | Wielkość | Skala | Znak / uwagi |
|---|---|---|---|
| 100–101 | kody błędów (u32) | bity | opisy w aplikacji |
| 108–109 | kody ostrzeżeń (u32) | bity | bit 9 = bateria niepodłączona |
| 201 | tryb pracy | 0 start, 1 czuwanie, 2 sieć, 3 off-grid, 4 bypass, 5 ładowanie, 6 błąd | |
| 202 / 203 / 204 | sieć: napięcie / częstotliwość / moc | /10 V, /100 Hz, W | moc: + import, − eksport |
| 205–209 | falownik: V, A, Hz, moc, moc ładowania | /10, /10, /100, W, W | |
| 210–214 | wyjście: V, A, Hz, moc odbiorów, VA | /10, /10, /100, W, VA | |
| 215 / 216 / 217 | bateria: V, prąd średni, moc średnia | /10 V, /10 A, W | + ładowanie, − rozładowanie |
| 219 / 220 / 223 / 224 | PV: V, A, moc, moc ładowania z PV | /10, /10, W, W | |
| 225 | obciążenie | % | |
| 226 / 227 | temperatura DC/DC / falownika | °C | ze znakiem |
| 229 | SOC baterii | % | odrzucane poza 0–100 |
| 232 | prąd baterii | /10 A | ze znakiem |

**Niedostępne w tej mapie (N/A z wyjaśnieniem):** energia PV dziś/łącznie, energia odbiorów, import/eksport energii,
prąd sieci, temperatura baterii, osobne MPPT/stringi (model ma 1 MPPT). Energia dzienna jest **obliczana** z mocy
(oznaczenie „OBLICZONE”).

PI30 (wariant): `QPIGS` (napięcia, prądy, moc wyjściowa, SOC, PV), `QMOD` (tryb). Moc baterii = prąd × napięcie
(OBLICZONE). Aplikacja wysyła wyłącznie zapytania (`Q…`) – polecenia zapisu są blokowane.

## Błędy i ich obsługa

- Zła suma CRC, obca ramka, zła długość → odrzucenie odczytu (MalformedFrame), bez użycia danych.
- Wyjątek Modbus (np. nieprawidłowy adres) → komunikat z kodem; brak odpowiedzi → timeout (1,5 s).
- Odczyty z tym samym lub wcześniejszym znacznikiem czasu są ignorowane (duplikaty, skoki zegara).
- Niespójny bilans mocy (PV + rozładowanie + import ≠ odbiory + ładowanie + eksport ± 12 %) jest pokazywany.

## Ograniczenia

- Sterowanie falownikiem (zmiana ustawień) celowo **nie jest** zaimplementowane – aplikacja tylko czyta.
- Lokalny dostęp do wbudowanego Wi-Fi nie jest obsługiwany.
- Monitoring w tle (gdy aplikacja zamknięta) nie działa – wymagałby usługi pierwszoplanowej.

## Bezpieczeństwo

- Brak haseł dla tych interfejsów; token GitHub (aktualizacje) szyfrowany kluczem z Android Keystore.
- Żadne dane uwierzytelniające nie trafiają do logów. Aplikacja nie wykonuje poleceń otrzymanych z sieci.
- Połączenia tylko wychodzące; dane falownika pozostają na telefonie.

## Architektura (rozszerzalność)

`InverterProvider` (marka + protokół + transport) → `InverterDataMapper` → `InverterTelemetry`;
`InverterConnectionManager` (odczyty, OFFLINE/STALE), `InverterRepository` (tworzenie dostawcy z konfiguracji),
`InverterCommandService` (tylko odczyt), `ByteTransport` (TCP, USB). Kolejne marki (Victron, Deye, Growatt, Huawei,
Fronius, SMA, Solis, EPEVER) dodaje się nową implementacją `InverterProvider` – UI i analityka się nie zmieniają.
Testy: `FakeAnenjiProvider`, `ScriptedTransport`, prawdziwy socket TCP w testach JVM.
