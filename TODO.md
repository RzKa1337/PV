# TODO — Solar Tracker PRO

Legenda: `[ ]` do zrobienia, `[x]` zrobione **i przetestowane**.

## v0.1.0

### Projekt
- [x] Repozytorium Git + remote GitHub
- [x] `.gitignore` dla Android Studio / Kotlin / Gradle
- [x] Projekt Gradle (moduły `:core` i `:app`), wrapper Gradle
- [ ] CI GitHub Actions (testy, lint, APK)

### Obliczenia (`:core`)
- [x] Pozycja słońca (wysokość, azymut)
- [x] Wschód, zachód, długość dnia, noc i dzień polarny
- [ ] Model produkcji PV (bezchmurne niebo, kąt i azymut paneli)
- [ ] Dzienny profil mocy i energia dzienna
- [ ] Porównanie kątów 0–90°
- [ ] Produkcja miesięczna dla kątów 0/30/45/60/90°

### Dane (`:app`)
- [ ] Ustawienia PV zapisywane w DataStore (moc, kąt, azymut)
- [ ] Lokalizacja ręczna
- [ ] Lokalizacja z GPS (opcjonalna)

### Interfejs (`:app`)
- [ ] Ekran główny (słońce, PV, dziś, aktualna moc, wschód/zachód)
- [ ] Wykres godzina → moc PV
- [ ] Ekran porównania kątów
- [ ] Ekran produkcji miesięcznej
- [ ] Ekran ustawień
- [ ] Dark Mode

### Wydanie
- [ ] Testy jednostkowe zielone w CI
- [ ] APK zbudowane w CI
- [ ] Tag `v0.1.0`

## Później (pomysły)
- [ ] Prognoza pogody / zachmurzenie zamiast modelu bezchmurnego nieba
- [ ] Współczynnik korekty na podstawie rzeczywistych odczytów z falownika
- [ ] Wpisywanie rzeczywistej produkcji i porównanie z szacunkiem
- [ ] Widget na ekran główny
- [ ] Wyszukiwanie lokalizacji po nazwie miejscowości
- [ ] Release APK podpisane kluczem (bez kluczy w repozytorium)
