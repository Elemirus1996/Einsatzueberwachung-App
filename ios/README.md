# iOS-App

Swift 6, SwiftUI, ab iOS 17. Keine Fremdbibliotheken.

## Bauen

1. Einmalig: `brew install xcodegen`
2. Eigenes Apple-Team eintragen: Datei `ios/Lokal.xcconfig` mit `DEVELOPMENT_TEAM = <Team-ID>` anlegen (nicht im Repo).
   Die Team-ID steht in Xcode unter Settings → Accounts.
3. Nach Änderungen an `project.yml` oder neuen Dateien: `cd ios && xcodegen`
4. `Einsatzueberwachung.xcodeproj` öffnen, iPhone per Kabel verbinden, Start.

Mit kostenlosem Apple-Konto läuft die App 7 Tage, danach erneut aus Xcode starten. Push geht damit nicht.

## Tests

```
xcodebuild test -project ios/Einsatzueberwachung.xcodeproj -scheme Einsatzueberwachung \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro'
```

## Aufbau

| Ordner | Inhalt |
|---|---|
| `Kern/` | Schnittstelle nach `docs/APP_API.md`, `TeamOrtung` (Auswertung von `mode`), Ortungsdienst, Puffer, Schlüsselbund |
| `App/` | Start, Rollenauswahl, Hintergrund-Abfrage |
| `Team/` | Oberfläche der Rolle Team |
