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

## Test-APK für Testpersonen

Für Tests auf privaten Handys ohne USB-Debugging: signierte Release-APK mit einem eigenen **Testschlüssel**,
der nie ins Repo kommt.

- Schlüssel: `~/.einsatzspur/test-schluessel.jks`, Zugangsdaten in `~/.einsatzspur/test-signierung.properties`
  (`storeFile`, `storePassword`, `keyAlias`, `keyPassword`; nur für den Mac-Nutzer lesbar)
- Bauen: `./gradlew assembleRelease` → `app/build/outputs/apk/release/app-release.apk`
- Ohne diese Datei bleibt die Release-APK unsigniert und lässt sich nicht installieren.
- Installation beim Testgerät: einmalig „Unbekannte Apps installieren“ für den Dateimanager/Browser erlauben.
- Nicht veröffentlichen (kein GitHub-Release), Weitergabe nur nach Absprache.
- Geht der Schlüssel verloren, lassen sich Updates nicht mehr über die alte Test-APK installieren
  (App einmal deinstallieren). Für den Play Store gibt es später einen eigenen Schlüssel.

## Alltagshandys

Die App zeigt in der Team-Ansicht Hinweise, wenn etwas die Ortung verhindern kann: genauer Standort fehlt,
Mitteilungen aus, Akku-Optimierung aktiv (mit Text je Hersteller: Samsung, Xiaomi, Huawei/Honor, OPPO/OnePlus, vivo).
„Einstellung öffnen“ führt in die Systemeinstellung; keine Direktanfrage zur Akku-Optimierung.
Nach `stop` läuft nichts mehr: kein Dienst, kein Takt, keine Abfragen.
