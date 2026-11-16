# Marke EinsatzSpur

Logos und Symbole der App. Quelle: Logo-Paket vom 16.11.2026.

| Datei | Verwendung |
|---|---|
| `ios-appicon-1024.png` | iOS-App-Symbol (ohne Alpha-Kanal) |
| `android-foreground.svg`, `android-adaptive-background-color.txt` | Android: adaptives Symbol, Vordergrund und Hintergrundfarbe (#121821) |
| `android-adaptive-foreground-432.png`, `icon-1024.png`, `playstore-512.png` | Raster-Fassungen, Play-Store-Eintrag |
| `icon.svg`, `icon-maskable.svg`, `mark-dark.svg`, `mark-light.svg` | Symbol mit/ohne Grund |
| `EinsatzSpur-Logo-transparent-fuer-hell.png` | Langes Logo für hellen Grund; Startseite (helles Schema) |
| `EinsatzSpur-Logo-transparent-fuer-dunkel.png` | Langes Logo für dunklen Grund; Startseite (dunkles Schema) |
| `EinsatzSpur-Logo-hell.png` | Langes Logo mit weißem Grund |

Die Fassung „transparent-fuer-dunkel“ ist aus der hellen abgeleitet (Schrift und Strichkreis umgefärbt auf #EFE9DC), bis eine Originalfassung vorliegt.

Im Programm:
- iOS: `AppIcon.appiconset`, `EinsatzSpurLogo.imageset` (hell/dunkel, Rand beschnitten)
- Android: `drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml` (Vektor aus `android-foreground.svg`, Strichkreis als einzelne Bögen), `ic_mitteilung.xml` (nur Pfote, Mitteilungssymbol), `drawable-nodpi`/`drawable-night-nodpi/logo_einsatzspur.png`
