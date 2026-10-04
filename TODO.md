# TODO — Solar Tracker PRO

Legenda: `[ ]` do zrobienia, `[x]` zrobione **i przetestowane**.

## v0.1.0

### Projekt
- [x] Repozytorium Git + remote GitHub
- [x] `.gitignore` dla Android Studio / Kotlin / Gradle
- [x] Projekt Gradle (moduły `:core` i `:app`), wrapper Gradle
- [x] CI GitHub Actions (testy, lint, APK)

### Obliczenia (`:core`)
- [x] Pozycja słońca (wysokość, azymut)
- [x] Wschód, zachód, długość dnia, noc i dzień polarny
- [x] Model produkcji PV (bezchmurne niebo, kąt i azymut paneli)
- [x] Dzienny profil mocy i energia dzienna
- [x] Porównanie kątów 0–90°
- [x] Produkcja miesięczna dla kątów 0/30/45/60/90°

### Dane (`:app`)
- [x] Ustawienia PV zapisywane w DataStore (moc, kąt, azymut) — testy jednostkowe
- [x] Lokalizacja ręczna — testy jednostkowe
- [ ] Lokalizacja z GPS (opcjonalna) — zaimplementowane, logika ViewModelu przetestowana; czeka na test na telefonie

### Interfejs (`:app`)
Zaimplementowane, kompiluje się i przechodzi lint w CI — **czeka na ręczny test na telefonie**.
- [ ] Ekran główny (słońce, PV, dziś, aktualna moc, wschód/zachód)
- [ ] Wykres godzina → moc PV
- [ ] Ekran porównania kątów
- [ ] Ekran produkcji miesięcznej
- [ ] Ekran ustawień
- [ ] Dark Mode

### Wydanie
- [x] Testy jednostkowe zielone w CI
- [x] APK zbudowane w CI
- [x] Tag `v0.1.0`

## Później (pomysły)
- [ ] Prognoza pogody / zachmurzenie zamiast modelu bezchmurnego nieba
- [ ] Współczynnik korekty na podstawie rzeczywistych odczytów z falownika
- [ ] Wpisywanie rzeczywistej produkcji i porównanie z szacunkiem
- [ ] Widget na ekran główny
- [ ] Wyszukiwanie lokalizacji po nazwie miejscowości
- [ ] Release APK podpisane kluczem (bez kluczy w repozytorium)
