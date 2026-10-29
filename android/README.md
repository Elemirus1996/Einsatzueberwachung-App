# Android-App

Kotlin, Jetpack Compose, ab Android 8 (API 26), Ziel API 37. Nur AndroidX/Jetpack und Kotlin:
keine Google Play Services, keine Fremd-SDKs.

## Bauen

Android Studio öffnen → Ordner `android/` öffnen. Oder auf der Kommandozeile mit dem Java von Android Studio:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest assembleDebug
```

`local.properties` (Pfad zum SDK) legt Android Studio selbst an; sie ist nicht im Repo.

## Aufbau

| Ordner | Inhalt |
|---|---|
| `kern/` | Schnittstelle nach `docs/APP_API.md`, `TeamOrtung` (Auswertung von `mode`, Stufen fein/grob, Takt), Ortung über `LocationManager`, SQLite-Puffer, Keystore-Verschlüsselung |
| `dienst/` | Vordergrunddienst Typ `location` mit Benachrichtigung |
| `ui/` | Start, Anmeldung, Statusanzeige |

Gleiche Regeln wie iOS: `track` = GPS, `pause` = Grob-Ortung ohne GPS (Positionen verworfen), `stop` = Dienst aus.
