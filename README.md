# Lektor PDF 🎧

Aplikacja na Androida, która czyta pliki PDF na głos jak audiobook.

## Funkcje

- **Działa jak odtwarzacz muzyki** – czyta przy wyłączonym ekranie i w tle, ma sterowanie z ekranu blokady, z powiadomienia, ze słuchawek, z zegarka i z Androida Auto.
- **Pauza po odłączeniu słuchawek** (przewodowych i Bluetooth) oraz podczas rozmowy telefonicznej (po rozmowie wznawia).
- **Tempo od 0,5× do 4×**: suwak, przyciski ± i szybkie przyciski 1× / 1,5× / 2× / 3× / 4×.
- **Języki lektora**: polski, angielski, rosyjski, francuski, włoski, ukraiński, niemiecki i każdy inny, który obsługuje syntezator w telefonie. Język książki rozpoznaje się automatycznie, a dla każdej książki można go zmienić ręcznie i wybrać głos.
- **Język aplikacji**: polski, angielski, rosyjski, francuski, włoski (menu → Język aplikacji).
- Zapamiętuje miejsce w każdej książce i po uruchomieniu wraca do ostatniej.
- Bieżące zdanie jest podświetlone; dotknięcie zdania zaczyna czytanie od niego.
- Przejście do strony, przewijanie co zdanie albo co 10 zdań, wyłącznik czasowy.
- Pliki PDF otworzysz też z menedżera plików („Otwórz za pomocą…” albo „Udostępnij”).

## Instalacja

1. Otwórz zakładkę **Releases** w tym repozytorium i pobierz `LektorPDF.apk` (każda zmiana w gałęzi `main` buduje nową wersję automatycznie przez GitHub Actions).
2. Zainstaluj plik na telefonie i pozwól na instalację z nieznanych źródeł.
3. Do lepszej jakości głosu zainstaluj **Usługi mowy od Google** (Speech Services by Google) i w ustawieniach syntezy mowy pobierz głosy polski, angielski i rosyjski.

Wymagania: Android 8.0 lub nowszy.

## Budowanie lokalnie

Otwórz folder w Android Studio i uruchom `app`, albo w terminalu:

```
gradle assembleRelease
```

## Ograniczenia

- Skanowane PDF-y (same obrazy, bez warstwy tekstowej) nie są obsługiwane, bo aplikacja nie ma OCR.
- Maksymalne tempo zależy od syntezatora. Google obsługuje 4×, niektóre inne silniki mają niższy limit.
