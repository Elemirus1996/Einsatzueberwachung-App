# Plan: App „Einsatzüberwachung“

Stand: 28.10.2026 · Grundlage: [Issue #110 im Server-Repo](https://github.com/Elemirus1996/Einsatzueberwachung.Server/issues/110)

## Ziel

Eine App für iPhone und Android mit zwei Rollen:

| | **Team** | **Einsatzleitung** |
|---|---|---|
| Wer | jedes Staffelmitglied im Einsatz | nur ausgewählte Personen |
| Zugang | ausschließlich per QR-Code aus dem laufenden Einsatz | ausschließlich im WireGuard-VPN + Anmeldung mit Staffel-Passwort |
| Oberfläche | Team-Seite des Servers (`/team`) | EL-Ansicht des Servers (`/el`) |
| Ortung | Laufweg des Hundeführers während der Suche | Standort der Einsatzleitung, damit der Führungsassistent im ELW sieht, wo der EL ist |
| Ende | Server schaltet GPS ab, sobald Suche/Einsatz endet | GPS aus, sobald der Einsatz beendet ist oder die EL sich abmeldet |

Anlass: Traccar Client leert den Akku und lässt sich vom Server nicht stoppen. Die App fragt nach dem Einsatz **keinen Standort mehr ab**.

## Sicherheitsgrundsätze

Die App darf nichts öffnen, was heute verschlossen ist.

1. **Einsatzleitung nur im VPN.** Die App enthält keinen eigenen Zugang. Sie öffnet die interne Adresse, die nur über WireGuard erreichbar ist. Wer kein VPN-Gerät hat (Einstellungen → VPN-Geräte) und das Staffel-Passwort nicht kennt, kommt nicht hinein. Ohne aktives VPN zeigt die App einen Hinweis und keine Daten.
2. **Team nur per QR-Code.** Der QR-Code enthält Server-Adresse und Team-Token. Das Token gilt nur für diesen Einsatz und dieses Team und läuft spätestens nach 24 h ab. Bei Einsatzende wird es ungültig (Token-Generation).
3. **Nur HTTPS**, nur an die Adresse aus dem QR-Code bzw. die eingerichtete VPN-Adresse. Keine fest eingebaute Server-Adresse, damit auch andere Staffeln die App nutzen können.
4. **Token im Schlüsselbund** (iOS Keychain / Android Keystore), nie im Klartext gespeichert oder geloggt.
5. **Keine Daten an Dritte:** keine Analyse-SDKs, keine Absturzberichte an Fremddienste, keine Werbung.
6. **Bestätigung vor dem Start:** „Ortung für Team <Name>, Einsatz <Stichwort> auf <Server> starten?“

## Steuerung der Ortung (Kern)

Jede Antwort des Servers enthält, was die App als Nächstes tun soll:

| Antwort `mode` | App |
|---|---|
| `track` | GPS an, Positionen puffern, gesammelt hochladen |
| `pause` | GPS **aus**, nur alle 2 min kurz den Status abfragen (ohne GPS) |
| `stop` | GPS aus, Token löschen, Rolle beenden |

Zuordnung auf dem Server:

| Zustand | `mode` |
|---|---|
| Team-Suche läuft | `track` |
| Kurzpause, Suche noch nicht gestartet, Suche beendet | `pause` (neue Suche → wieder `track`, ohne Eingabe) |
| Einsatz beendet, Team gelöscht, Token-Generation geändert, Token abgelaufen | `stop` (HTTP 410) |
| Einsatzleitung angemeldet, Einsatz läuft | `track` |

Sicherheitsnetz in der App: Läuft `expiresAt` ab, stoppt die App auch ohne Netz.

## Technik

Zwei schlanke **native Apps** im selben Repo (entschieden 28.10.2026). Die schwierige Aufgabe – zuverlässige, akkusparende
Hintergrund-Ortung, die der Server stoppen kann – ist ohnehin plattformeigen; die Oberflächen kommen vom Server.

| | iOS (`ios/`) | Android (`android/`) |
|---|---|---|
| Sprache / UI | Swift, SwiftUI | Kotlin, Jetpack Compose |
| Oberflächen | `WKWebView` mit `/team` bzw. `/el` | `WebView` mit `/team` bzw. `/el` |
| Ortung | `CLLocationManager`, `allowsBackgroundLocationUpdates`, `pausesLocationUpdatesAutomatically`, Berechtigung „Beim Verwenden“ (blaue Statusanzeige, „Immer“ wird nicht verlangt) | Foreground-Service Typ `location` mit Benachrichtigung, Fused Location Provider (ausgewogen, Batching) |
| Puffer | SQLite (Funkloch, Upload mit Originalzeit) | Room/SQLite |
| QR-Scan | AVFoundation / VisionKit `DataScannerViewController` | CameraX + ML Kit Barcode |
| Geheimnisse | Keychain | Android Keystore (EncryptedSharedPreferences) |
| Mindestversion | iOS 16 | Android 8 (API 26) |
| Build | Xcode auf dem Mac | Android Studio (Mac oder Windows) |

- Reihenfolge: **iPhone zuerst** bauen und testen (Mehrheit der Mitglieder), Android direkt danach nach derselben Vorlage.
- Beide Apps verhalten sich gleich, weil sie sich an **eine verbindliche Beschreibung der Server-Schnittstelle** halten
  (im Server-Repo, abgesichert durch Server-Tests).
- Deeplink beider Apps: `einsatzueberwachung://team?server=…&token=…`
- Später automatische Builds über GitHub Actions (macOS-Runner für iOS).

## Server-Schnittstelle v1 (im Server-Repo)

Neuer Bereich `api/team-mobile/app/v1/` (öffentliche Adresse, eigener nginx-Block mit großzügigem Limit, weil sich im Mobilfunk viele Handys eine IP teilen) und `api/el/app/v1/` (nur intern/VPN).

| Endpunkt | Zweck |
|---|---|
| `GET  api/team-mobile/app/v1/config` | nach QR-Scan: Team, Einsatz, Staffelname, Intervalle, `expiresAt`, `mode` |
| `POST api/team-mobile/app/v1/positions` | gesammelte Positionen (lat, lng, accuracy, timestamp, battery), Antwort mit `mode` |
| `GET  api/team-mobile/app/v1/status` | leichte Abfrage im Pause-Modus |
| `POST api/el/app/v1/token` | nach EL-Anmeldung in der WebView: kurzlebiges Geräte-Token für die native Ortung |
| `POST api/el/app/v1/positions` | Standort der Einsatzleitung (nur aus dem VPN), Antwort mit `mode` |

Auth: `Authorization: Bearer <token>`. Ungültiges Team-Token → `410 Gone` + `{"mode":"stop"}`. Der bestehende Traccar-Endpunkt bleibt in der Übergangszeit unverändert.

Anzeige auf dem Server: Marker „EL“ (mit Gerätename, z. B. „Tablet EL“) auf Einsatzkarte und Monitor, Alter der Position wie bei Team-Handys.

## Phasen

### Phase 0 – Vorbereitung (Nutzer)
- [ ] Apple-Entwicklerkonto (Einzelperson, 99 €/Jahr), Händlerstatus nach DSA: nicht kommerziell
- [ ] Google-Play-Entwicklerkonto (einmalig 25 $)
- [ ] App-Kennung festlegen (Vorschlag `de.einsatzueberwachung.app`)
- [ ] Auf dem Mac: Xcode, Android Studio, Claude Code

### Phase 1 – Server (Server-Repo, unter Windows)
- [ ] `TeamTrackingModeService` (Zustandslogik, testbar) + Endpunkte Team v1
- [ ] Endpunkte EL v1, EL-Position speichern und auf Karte/Monitor anzeigen
- [ ] QR-Code für die App auf der Team-Seite und im Team-QR-Dialog des Monitors
- [ ] Einstellungen: Intervalle, Upload-Takt, Staffel-Anzeigename
- [ ] nginx: eigener Block `api/team-mobile/app/` (Limit wie Traccar), `api/el/` nur intern
- [ ] Tests: Zustandslogik, 410 bei alter Generation, Batch mit gepufferten Zeitstempeln, EL nur aus VPN

### Phase 2 – App, Rolle Team (Mac; erst iOS, dann Android)
- [ ] Xcode-Projekt in `ios/`, Android-Projekt in `android/`, Startbildschirm „Team“ / „Einsatzleitung“
- [ ] QR-Scan + Deeplink, Bestätigungsdialog, Token in Keychain/Keystore
- [ ] Ortungsdienst iOS und Android, Puffer, Upload, `mode` auswerten
- [ ] WebView `/team` mit Anmeldung über das Token
- [ ] Anzeige: Team, Einsatz, Status „Ortung läuft / pausiert / beendet“, letzter Upload

### Phase 3 – App, Rolle Einsatzleitung (Mac)
- [ ] VPN-Erkennung (interne Adresse erreichbar?), sonst Hinweis „WireGuard einschalten“
- [ ] WebView `/el`, nach Anmeldung Geräte-Token holen, EL-Ortung starten
- [ ] Stop bei Einsatzende, Abmeldung oder Rollenwechsel

### Phase 4 – Test (TestFlight / interner Play-Test)
- [ ] Übung mit 2–3 Teams und Einsatzleitung
- [ ] Akku-Messung 1 h: Traccar (sparsam) gegen App
- [ ] Funkloch-Test (Flugmodus während der Suche)

### Phase 5 – Veröffentlichung
- [ ] Datenschutzerklärung unter fester URL (GitHub Pages)
- [ ] App Store: „Unlisted App Distribution“ beantragen (per Link installierbar, nicht in der Suche)
- [ ] Play Store: öffentlicher oder geschlossener Track, zusätzlich APK im GitHub-Release
- [ ] Begründung für Hintergrund-Ortung für die App-Prüfung

## Akzeptanzkriterien

- [ ] Nach dem QR-Scan läuft die Ortung ohne weitere Eingabe.
- [ ] Spätestens einen Upload-Takt nach Einsatz- oder Suchende ist GPS am Handy aus.
- [ ] Neue Suche startet die Ortung ohne Eingabe wieder.
- [ ] Ohne Netz stoppt die App spätestens bei Ablauf des Tokens.
- [ ] Positionen aus Funklöchern kommen mit Originalzeit an.
- [ ] Einsatzleitung ist ohne VPN nicht erreichbar, auch nicht über die App.
- [ ] Der Führungsassistent sieht die Position der Einsatzleitung auf der Karte.
- [ ] Akkuverbrauch messbar unter Traccar mit sparsamen Werten.

## Offen

- Name und Symbol der App
- Traccar-Endpunkt nach dem Umstieg abschalten oder als Rückfallebene behalten
- Soll die Einsatzleitung auch ohne laufenden Einsatz geortet werden? (Vorschlag: nein)
