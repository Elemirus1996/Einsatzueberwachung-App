---
name: tests-kurz
description: Build und Tests (Server, iOS, Android) mit knapper Ausgabe ausführen, um Kontext zu sparen. Immer statt der normalen Build-/Test-Befehle verwenden.
user-invocable: true
---

# Build und Tests mit kurzer Ausgabe

Ausgabe immer in eine Datei, nur Zusammenfassung und Fehler lesen. Lange Läufe im Hintergrund.

## Server (.NET), in `~/einsatzspur/Einsatzueberwachung.Server`

Nur das Testprojekt bauen, die Solution enthält `LiveTracking` (net9.0-windows) und scheitert unter macOS.
```
dotnet test src/Einsatzueberwachung.Tests --verbosity quiet --nologo > /tmp/einsatzspur-server.log 2>&1; echo "Exit $?"
grep -E "Failed|Passed!|Failed!|error" /tmp/einsatzspur-server.log | tail -30
```

## iOS, in `~/einsatzspur/Einsatzueberwachung-App/ios`

Nach neuen Dateien zuerst `xcodegen`.
```
xcodebuild test -scheme Einsatzueberwachung -destination "platform=iOS Simulator,name=<Gerät>" -quiet > /tmp/einsatzspur-ios.log 2>&1; echo "Exit $?"
grep -E "error:|failed|Executed [0-9]+ tests" /tmp/einsatzspur-ios.log | tail -20
```

## Android, in `~/einsatzspur/Einsatzueberwachung-App/android`

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew -q testDebugUnitTest > /tmp/einsatzspur-android.log 2>&1; echo "Exit $?"
grep -E "FAILED|tests completed|error:|e: " /tmp/einsatzspur-android.log | head -40
```
APK: `./gradlew -q assembleRelease > /tmp/einsatzspur-apk.log 2>&1; echo "Exit $?"` (Testschlüssel in `~/.einsatzspur/`).

## Regeln

- Bei Erfolg eine Zeile: „<n>/<n> grün“.
- Bei Fehlern höchstens 40 Fehlerzeilen lesen, nie die ganze Datei.
- Logs unter `/tmp/einsatzspur-*.log`, nie ins Repo.
