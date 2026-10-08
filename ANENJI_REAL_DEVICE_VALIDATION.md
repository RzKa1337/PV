# ANENJI — walidacja na prawdziwym urządzeniu

> **Status: NIE WYKONANO.** Ten dokument to procedura do wykonania przy fizycznym falowniku Anenji 6.2 kW 48 V.
> Do jej wykonania żaden rejestr nie jest zweryfikowany, a każda diagnoza z urządzenia ma status
> **IMPLEMENTED — REAL VALIDATION REQUIRED**. Pola „Zaobserwowano” wypełnia osoba wykonująca test – aplikacja ani
> autorzy kodu ich nie uzupełniają.

## Zasady

- Aplikacja tylko **czyta**. Żaden test nie wymaga zmiany ustawień falownika z aplikacji (zapis Modbus jest zablokowany w kodzie).
  Jeśli test wymaga innego stanu (np. obciążenia), zmienia się go fizycznie: włącza odbiornik, odłącza kabel.
- Wartość referencyjna = to, co pokazuje **wyświetlacz falownika** (lub miernik), wpisane w **Centrum → Co się stało? →
  Tryb walidacji rejestrów**. Aplikacja zapisuje surowe słowo rejestru, wartość zdekodowaną i wartość z wyświetlacza.
- Werdykt pojedynczego porównania: MATCH (≤ 3 %), CLOSE (≤ 10 %), SUSPECT, WRONG DECODING (wzorzec ×10/÷10/×100/znak).
- Rejestr staje się **VERIFIED** dopiero po ≥ 3 porównaniach MATCH z prawdziwego urządzenia przy **różnych** wartościach
  i bez sprzecznych porównań. Dokumentacja ani symulator nigdy nie dają VERIFIED.
- Przed testami: Diagnostyka PV → **Nagrywaj log rejestrów** (włączone przez cały czas testów); po testach eksport
  `ANENJI_FORENSIC_PACKAGE.zip` i logu rejestrów CSV.

Adresy wg mapy SMG ze źródeł społecznościowych (`SmgRegisterMap`, niezweryfikowane).

---

## Test 1 — Komunikacja i ciągłość odczytów

| | |
|---|---|
| **Oczekiwane** | Status ONLINE; odczyt co ustawiony interwał; brak przekroczeń czasu przy stabilnym połączeniu; log rejestrów zawiera bloki 100–109 i 201–234. |
| **Zaobserwowano** | … |
| **Rejestry** | wszystkie z bloków 100–109, 201–234 (funkcja 03) |
| **Kryteria akceptacji** | ≥ 99 % udanych odczytów przez 30 min; wynik komunikacji w raporcie ≥ 90/100; żadnych ramek z błędnym CRC przy sprawnym kablu. |

## Test 2 — Napięcie baterii

| | |
|---|---|
| **Oczekiwane** | Wartość w aplikacji = wyświetlacz (V) z dokładnością ≤ 3 %. |
| **Zaobserwowano** | … (3 odczyty: rano / w trakcie ładowania / wieczorem) |
| **Rejestry** | 215 (`BATTERY_VOLTAGE`, ÷10 V) |
| **Kryteria akceptacji** | 3 × MATCH przy różnych napięciach → VERIFIED; jakikolwiek WRONG DECODING → mapa do poprawy (wskazówka skali w aplikacji). |

## Test 3 — SOC baterii

| | |
|---|---|
| **Oczekiwane** | SOC w aplikacji = SOC na wyświetlaczu (%). |
| **Zaobserwowano** | … |
| **Rejestry** | 229 (`BATTERY_PERCENT`, %) |
| **Kryteria akceptacji** | 3 × MATCH przy SOC różniących się o ≥ 10 p.p.; brak skoków SOC ≥ 10 p.p./5 min w logu bez przyczyny (anomalia SOC_JUMP). |

## Test 4 — PV: napięcie, prąd, moc

| | |
|---|---|
| **Oczekiwane** | Napięcie, prąd i moc PV zgodne z wyświetlaczem; P ≈ V × I (różnica ≤ 35 %, inaczej anomalia PV_CURRENT_ABNORMAL). |
| **Zaobserwowano** | … (rano, południe, popołudnie) |
| **Rejestry** | 219 (`PV_VOLTAGE`, ÷10 V), 220 (`PV_CURRENT`, ÷10 A), 223 (`PV_POWER`, W, ze znakiem), 224 (`PV_CHARGING_POWER`) |
| **Kryteria akceptacji** | 3 × MATCH na każdy rejestr; w raporcie brak PV_CURRENT_ABNORMAL przy stabilnym słońcu. Przy ≥ 2 MPPT: odczyt każdego MPPT – obecnie mapa nie zawiera rejestrów MPPT 2 (NOT AVAILABLE). |

## Test 5 — Prąd i moc baterii: znak

| | |
|---|---|
| **Oczekiwane** | Ładowanie i rozładowanie mają przeciwne znaki zgodne z opisem w aplikacji (ładowanie > 0). |
| **Zaobserwowano** | … (odczyt przy ładowaniu z PV i przy rozładowaniu nocą) |
| **Rejestry** | 216 (`BATTERY_AVERAGE_CURRENT`, ÷10 A, ze znakiem), 217 (`BATTERY_AVERAGE_POWER`), 232 (`BATTERY_CURRENT`) |
| **Kryteria akceptacji** | MATCH w obu kierunkach; wskazówka „odwrócony znak” w trybie walidacji = mapa do poprawy. |

## Test 6 — Obciążenie

| | |
|---|---|
| **Oczekiwane** | Włączenie odbiornika o znanej mocy (np. czajnik ~2 kW) zwiększa moc obciążenia o tę wartość ±10 %; procent obciążenia rośnie proporcjonalnie. |
| **Zaobserwowano** | … |
| **Rejestry** | 213 (`OUTPUT_POWER`, W), 214 (`OUTPUT_VA`), 225 (`LOAD_PERCENT`), 210/211 (napięcie/prąd wyjścia) |
| **Kryteria akceptacji** | 3 × MATCH (bez odbiornika, z odbiornikiem, z dwoma); osie czasu „Co się stało?” pokazują skok obciążenia w ciągu jednego interwału. |

## Test 7 — Sieć / wyjście AC

| | |
|---|---|
| **Oczekiwane** | Napięcie 207–253 V, częstotliwość 49,5–50,5 Hz przy obecnej sieci; po odłączeniu wejścia AC napięcie sieci ~0 V. |
| **Zaobserwowano** | … |
| **Rejestry** | 202 (`GRID_VOLTAGE`, ÷10 V), 203 (`GRID_FREQUENCY`, ÷100 Hz), 204 (`GRID_POWER`), 212 (`OUTPUT_FREQUENCY`) |
| **Kryteria akceptacji** | MATCH dla napięcia i częstotliwości; po fizycznym odłączeniu wejścia AC na ≥ 15 min aplikacja pokazuje GRID_OUTAGE z czasem przerwy ±1 interwał (wymaga, by instalacja miała podłączoną sieć). |

## Test 8 — Temperatura falownika

| | |
|---|---|
| **Oczekiwane** | Temperatura zgodna z wyświetlaczem (jeśli ją pokazuje) lub z termometrem przy obudowie ±5 °C. |
| **Zaobserwowano** | … |
| **Rejestry** | 227 (`INVERTER_TEMPERATURE`, °C, ze znakiem), 226 (`DCDC_TEMPERATURE`) |
| **Kryteria akceptacji** | 3 × MATCH/CLOSE przy różnych obciążeniach; brak wartości poza zakresem fizycznym (INVALID). |

## Test 9 — Tryb pracy, ostrzeżenia, awarie

| | |
|---|---|
| **Oczekiwane** | Tryb pracy w aplikacji = tryb na wyświetlaczu; kod ostrzeżenia na wyświetlaczu odpowiada bitowi w rejestrze ostrzeżeń (np. niska bateria). |
| **Zaobserwowano** | … (kody z wyświetlacza i bity z logu) |
| **Rejestry** | 201 (`OPERATING_MODE`), 100–101 (`FAULT_CODE`, u32), 108–109 (`WARNING_CODE`, u32) |
| **Kryteria akceptacji** | Każdy zaobserwowany kod ma potwierdzony bit; nieznane bity pozostają „Kod producenta (bit N)” – bez zgadywania nazw. |

## Test 10 — Utrata komunikacji i restart

| | |
|---|---|
| **Oczekiwane** | Odłączenie kabla RS232 na 5 min → COMM_TIMEOUT z czasem przerwy, „Co się stało?” pokazuje BRAK DANYCH w tym oknie (bez interpolacji), nie ma fałszywej diagnozy PV. Wyłączenie i włączenie falownika → zdarzenie restartu. |
| **Zaobserwowano** | … |
| **Rejestry** | brak (komunikacja); restart: mapa SMG nie zawiera licznika czasu pracy ani energii łącznej – restart wykrywany jest tylko jako tryb POWER_ON po przerwie w danych |
| **Kryteria akceptacji** | Czas przerwy w raporcie ±1 interwał od rzeczywistego; restart wykryty albo jawnie opisany jako niewykrywalny dla tego modelu (wtedy: znaleźć rejestr licznika i dopisać do mapy po walidacji). |

---

## Po walidacji

1. Wyeksportuj `ANENJI_FORENSIC_PACKAGE.zip` (metadata.json zawiera listę rejestrów VERIFIED i liczbę odczytów referencyjnych).
2. Rejestry z werdyktem WRONG DECODING: popraw skalę/znak w `SmgRegisters` i powtórz test.
3. Dopiero po zaliczeniu testów 2–7 statusy w PROJECT_AUDIT.md mogą zmienić się z **REAL DEVICE REQUIRED** na **PARTIALLY VERIFIED** / **DONE** dla konkretnych pomiarów.
