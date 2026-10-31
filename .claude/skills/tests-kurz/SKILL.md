---
name: tests-kurz
description: Build und Tests mit knapper Ausgabe ausführen, um Kontext zu sparen. Immer statt der normalen Build-/Test-Befehle verwenden.
user-invocable: true
---

# Build und Tests mit kurzer Ausgabe

Lange Build- und Testausgaben kommen nie vollständig in den Kontext. Ausgabe in eine Datei schreiben, nur Zusammenfassung und Fehler lesen.

## iOS

```
cd ios && xcodebuild test -scheme Einsatzueberwachung -destination "platform=iOS Simulator,name=<Gerät>" -quiet > /tmp/einsatzspur-ios.log 2>&1; echo "Exit $?"
grep -E "error:|failed|Test Suite .* (passed|failed)|Executed [0-9]+ tests" /tmp/einsatzspur-ios.log | tail -20
```

## Android

```
cd android && ./gradlew -q testDebugUnitTest > /tmp/einsatzspur-android.log 2>&1; echo "Exit $?"
grep -E "FAILED|tests completed|error:|e: " /tmp/einsatzspur-android.log | head -40
```

Für die APK: `./gradlew -q assembleRelease > /tmp/einsatzspur-apk.log 2>&1; echo "Exit $?"`

## Regeln

- Bei Erfolg reicht eine Zeile: „<n>/<n> grün“.
- Bei Fehlern nur die fehlschlagenden Tests bzw. Fehlerzeilen lesen (`grep`, höchstens 40 Zeilen), nicht die ganze Datei.
- Logdateien liegen unter `/tmp/einsatzspur-*.log` und gehören nicht ins Repo.
