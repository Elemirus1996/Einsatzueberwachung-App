# Plan: App „Einsatzüberwachung“

Stand: 28.10.2026 · Grundlage: [Issue #110 im Server-Repo](https://github.com/Elemirus1996/Einsatzueberwachung.Server/issues/110)

## Ziel

Eine App für iPhone und Android mit zwei Rollen:

| | **Team** | **Einsatzleitung** |
|---|---|---|
| Wer | jedes Staffelmitglied im Einsatz | nur ausgewählte Personen |
| Zugang | ausschließlich per QR-Code aus dem laufenden Einsatz | ausschließlich im WireGuard-VPN + Anmeldung mit Staffel-Passwort |
| Oberfläche | Team-Seite des Servers (`/team`) in der App | **komplett nativ** (SwiftUI / Jetpack Compose), entschieden 28.10.2026 |
| Ortung | Laufweg des Hundeführers während der Suche | Standort der Einsatzleitung, damit der Führungsassistent im ELW sieht, wo der EL ist |
| Ende | Server schaltet GPS ab, sobald Suche/Einsatz endet | GPS aus, sobald der Einsatz beendet ist oder die EL sich abmeldet |

Anlass: Traccar Client leert den Akku und lässt sich vom Server nicht stoppen. Die App fragt nach dem Einsatz **keinen Standort mehr ab**.

## Sicherheitsgrundsätze

Die App darf nichts öffnen, was heute verschlossen ist.

1. **Einsatzleitung nur im VPN.** Die App enthält keinen eigenen Zugang. Sie öffnet die interne Adresse, die nur über WireGuard erreichbar ist. Wer kein VPN-Gerät hat (Einstellungen → VPN-Geräte) und das Staffel-Passwort nicht kennt, kommt nicht hinein. Ohne aktives VPN zeigt die App einen Hinweis und keine Daten.
2. **Team nur per QR-Code, wie bisher.** Die App scannt den **bestehenden** QR-Code aus dem Monitor (Team-Dialog), das Mitglied meldet sich in der App wie gewohnt an und wählt sein Team. Erst danach übergibt die Team-Seite der App ein App-Token. Es gilt nur für diesen Einsatz und dieses Team und läuft spätestens nach 24 h ab. Bei Einsatzende wird es ungültig (Token-Generation).
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
| Mindestversion | iOS 17 (für `CLBackgroundActivitySession`, entschieden 28.10.2026) | Android 8 (API 26) |
| Build | Xcode auf dem Mac | Android Studio (Mac oder Windows) |

- Reihenfolge: **iPhone zuerst** bauen und testen (Mehrheit der Mitglieder), Android direkt danach nach derselben Vorlage.
- Beide Apps verhalten sich gleich, weil sie sich an **eine verbindliche Beschreibung der Server-Schnittstelle** halten
  (im Server-Repo, abgesichert durch Server-Tests).
- Anmeldung über den bestehenden Team-QR-Code (`https://<server>/team/login?code=…`); die Team-Seite übergibt das Token per
  `WKScriptMessageHandler` „einsatzApp“ (iOS) bzw. `@JavascriptInterface` „EinsatzAppAndroid“ (Android).
- Später automatische Builds über GitHub Actions (macOS-Runner für iOS).

## Einsatzleitung nativ – Umfang

Nachgebaut wird, was `/el` heute kann (Stand Server 1.11.7):

| Bereich | Funktionen |
|---|---|
| Einsatz starten | Stichwort, Ort, Szenario, Einsatz/Übung (wie `/el/start`) |
| Übersicht | Teams mit Suchzeit und Warnstufe, Starten (mit Halsband-Auswahl), Pause, Weiter, Stopp, Zurücksetzen; Warnton bei Eskalation; Wetter |
| Vermisste | Profile anlegen/bearbeiten, Szenario, Checklisten |
| Karte | Suchgebiete, Halsbänder und Team-Handys live, Tracks, Abdeckung in %, ELW; Kartenkacheln OpenStreetMap/OpenTopoMap über **MapLibre** (beide Plattformen, keine Google-Schlüssel) |
| Notizen und Funk | Liste, neue Einträge, Antworten; Funkloch-Puffer wie heute |
| Einsatz beenden | Ergebnis, Prüfung der Ressourcen, PDF-Optionen (wie `/el/beenden`) |
| Ortung | Standort der Einsatzleitung für die Karte im ELW (Server fertig, PR #113) |

Bewusst **nicht** in der App: Teams anlegen, Stammdaten, Einstellungen, Archiv – das bleibt im Monitor (Absprache: `/el` ist keine Kopie des Monitors).

Live-Aktualisierung: SignalR-Hub für die App (Bearer-Token), damit Suchzeiten, Positionen, Funk und Notizen ohne Neuladen erscheinen.

Folge: Jede neue EL-Funktion auf dem Server muss in beiden Apps nachgezogen werden (App-Update, bei Apple mit Prüfung).

## Server-Schnittstelle v1 (im Server-Repo)

**Verbindlich:** [docs/APP_API.md](https://github.com/Elemirus1996/Einsatzueberwachung.Server/blob/main/docs/APP_API.md) im Server-Repo. Die Übersicht hier ist nur eine Zusammenfassung.

Neuer Bereich `api/team-mobile/app/v1/` (öffentliche Adresse, eigener nginx-Block mit großzügigem Limit, weil sich im Mobilfunk viele Handys eine IP teilen) und `api/el/app/v1/` (nur intern/VPN).

| Endpunkt | Zweck |
|---|---|
| `GET  api/team-mobile/app/v1/config` | nach QR-Scan: Team, Einsatz, Staffelname, Intervalle, `expiresAt`, `mode` |
| `POST api/team-mobile/app/v1/positions` | gesammelte Positionen (lat, lng, accuracy, timestamp, battery), Antwort mit `mode` |
| `GET  api/team-mobile/app/v1/status` | leichte Abfrage im Pause-Modus |
| `POST api/el/app/v1/login` | Anmeldung der nativen EL-App mit Staffel-Passwort (nur VPN, Sperre nach Fehlversuchen wie im Browser) → EL-Token |
| `POST api/el/app/v1/positions`, `GET …/status` | Standort der Einsatzleitung, Antwort mit `mode` (fertig, PR #113) |
| `api/el/app/v1/einsatz`, `…/teams`, `…/vermisste`, `…/karte`, `…/funk`, `…/notizen`, `…/beenden` | Daten und Aktionen der EL-Oberfläche (Phase 1b) |
| `/hubs/el-app` | Live-Aktualisierungen für die native EL-App (Phase 1b) |

Auth: `Authorization: Bearer <token>`. Ungültiges Team-Token → `410 Gone` + `{"mode":"stop"}`. Der bestehende Traccar-Endpunkt bleibt in der Übergangszeit unverändert.

Anzeige auf dem Server: Marker „EL“ (mit Gerätename, z. B. „Tablet EL“) auf Einsatzkarte und Monitor, Alter der Position wie bei Team-Handys.

## Phasen

### Phase 0 – Vorbereitung (Nutzer)
- [ ] Apple-Entwicklerkonto (Einzelperson, 99 €/Jahr), Händlerstatus nach DSA: nicht kommerziell
- [ ] Google-Play-Entwicklerkonto (einmalig 25 $)
- [ ] App-Kennung festlegen (Vorschlag `de.einsatzueberwachung.app`)
- [ ] Auf dem Mac: Xcode, Android Studio, Claude Code

### Phase 1 – Server (Server-Repo, unter Windows)
- [x] Zustandslogik `OrtungsSteuerung` + Endpunkte Team v1 + App-Token mit Ablauf (Server PR #112)
- [x] Endpunkte EL v1, EL-Position als Marker „EL“ auf der Einsatzkarte (Server PR #113)
- [x] Anmeldung über den bestehenden QR-Code, Token-Übergabe von der Team-Seite an die App (PR #112)
- [x] Einstellungen: Intervall (Traccar-Einstellung), Upload-Takt `AppUploadSekunden`, Staffelname aus den Staffel-Einstellungen
- [x] nginx: eigener Block `api/team-mobile/app/` (300/min je IP) (PR #112)
- [x] `api/el/` nur intern erreichbar (öffentlicher Port 404, getestet) (PR #113)
- [x] Tests Team: Zustandslogik, 410 nach Einsatzende, Batch mit gepufferten Zeitstempeln (PR #112)
- [x] Tests EL: nur intern, Token ungültig nach Passwortänderung, pause ohne Einsatz (PR #113)

### Phase 1b – Server: Schnittstelle für die native Einsatzleitung
- [ ] Login-Endpunkt für die App (Staffel-Passwort, Sperre, Audit-Log mit Gerätename)
- [ ] Lesen: Einsatz, Teams (Suchzeit, Warnstufe, Halsband, Handy), Vermisste, Karte (Gebiete, Positionen, Tracks, Abdeckung), Funk, Notizen, Wetter
- [ ] Aktionen: Einsatz starten, Team starten/pausieren/stoppen/zurücksetzen (Halsband-Rückfrage wie heute), Vermisste speichern, Funk/Notiz senden, Einsatz beenden
- [ ] SignalR-Hub `/hubs/el-app` mit Bearer-Token, nur intern
- [ ] Tests für jede Aktion und für „nur intern“; Beschreibung in `docs/APP_API.md`

### Phase 2 – App, Rolle Team (Mac; erst iOS, dann Android)
- [ ] Xcode-Projekt in `ios/`, Android-Projekt in `android/`, Startbildschirm „Team“ / „Einsatzleitung“
- [ ] QR-Scan (nur `https://…/team/login?code=…`), Bestätigungsdialog mit Servername, WebView-Anmeldung, Token-Übergabe annehmen (nur vom selben Server), Token in Keychain/Keystore
- [ ] Ortungsdienst iOS und Android, Puffer, Upload, `mode` auswerten
- [ ] WebView `/team` mit Anmeldung über das Token
- [ ] Anzeige: Team, Einsatz, Status „Ortung läuft / pausiert / beendet“, letzter Upload

### Phase 3 – App, Rolle Einsatzleitung nativ (Mac; erst iOS, dann Android)
- [ ] Einrichtung: interne Adresse einmal eingeben, VPN-Erkennung, sonst Hinweis „WireGuard einschalten“
- [ ] Anmeldung mit Staffel-Passwort (nativer Dialog), Token in Keychain/Keystore
- [ ] Übersicht mit Teams und Aktionen, Warnton/Haptik bei Eskalation
- [ ] Vermisste, Notizen und Funk (mit Funkloch-Puffer), Einsatz starten und beenden
- [ ] Karte mit MapLibre (Gebiete, Positionen, Tracks, Abdeckung)
- [ ] EL-Ortung, Stop bei Einsatzende, Abmeldung oder Rollenwechsel

### Phase 3b – Push-Mitteilungen für die Einsatzleitung (entschieden 28.10.2026)
- [ ] Jeder EL stellt **in der App selbst** ein, welche Mitteilungen er bekommt (z. B. Zeitwarnung Team, Funkstille Handy/Halsband, Hund außerhalb des Gebiets, neuer Funkspruch, Einsatzende) – alles einzeln abschaltbar, Standard: nur kritische Warnungen
- [ ] Server: Geräte-Registrierung je EL-Gerät mit seinen Einstellungen, Versand bei Warnzentrum-Ereignissen und Funk
- [ ] iOS über Apple (APNs), Android über Google (Firebase Cloud Messaging); Mitteilungstexte knapp, ohne Namen von Vermissten oder Ortsangaben
- [ ] Mitteilungen nur, solange ein Einsatz läuft; Abmelden in der App entfernt die Registrierung

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
- Soll die Einsatzleitung auch ohne laufenden Einsatz geortet werden? (umgesetzt: nein, `pause`)
- (entschieden 28.10.2026) Push-Mitteilungen siehe Phase 3b
