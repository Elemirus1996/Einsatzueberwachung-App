# Plan: App „Einsatzüberwachung“

Stand: 28.10.2026 (Team ebenfalls nativ, stille Push) · Grundlage: [Issue #110 im Server-Repo](https://github.com/Elemirus1996/Einsatzueberwachung.Server/issues/110)

## Ziel

Eine App für iPhone und Android mit zwei Rollen:

| | **Team** | **Einsatzleitung** |
|---|---|---|
| Wer | jedes Staffelmitglied im Einsatz | nur ausgewählte Personen |
| Zugang | ausschließlich per QR-Code aus dem laufenden Einsatz | ausschließlich im WireGuard-VPN + Anmeldung mit Staffel-Passwort |
| Oberfläche | **komplett nativ** (SwiftUI / Jetpack Compose), keine WebView, entschieden 28.10.2026 | **komplett nativ** (SwiftUI / Jetpack Compose), entschieden 28.10.2026 |
| Ortung | Laufweg des Hundeführers während der Suche | Standort der Einsatzleitung, damit der Führungsassistent im ELW sieht, wo der EL ist |
| Ende | Server schaltet GPS ab, sobald Suche/Einsatz endet | GPS aus, sobald der Einsatz beendet ist oder die EL sich abmeldet |

Anlass: Traccar Client leert den Akku und lässt sich vom Server nicht stoppen. Die App fragt nach dem Einsatz **keinen Standort mehr ab**.

## Sicherheitsgrundsätze

Die App darf nichts öffnen, was heute verschlossen ist.

1. **Einsatzleitung nur im VPN.** Die App enthält keinen eigenen Zugang. Sie öffnet die interne Adresse, die nur über WireGuard erreichbar ist. Wer kein VPN-Gerät hat (Einstellungen → VPN-Geräte) und das Staffel-Passwort nicht kennt, kommt nicht hinein. Ohne aktives VPN zeigt die App einen Hinweis und keine Daten.
2. **Team nur per QR-Code, wie bisher.** Die App scannt den **bestehenden** QR-Code aus dem Monitor (Team-Dialog), das Mitglied wählt in der nativen Oberfläche sein Team. Erst danach gibt der Server der App ein App-Token. Es gilt nur für diesen Einsatz und dieses Team und läuft spätestens nach 24 h ab. Bei Einsatzende wird es ungültig (Token-Generation).
3. **Nur HTTPS**, nur an die Adresse aus dem QR-Code bzw. die eingerichtete VPN-Adresse. Keine fest eingebaute Server-Adresse, damit auch andere Staffeln die App nutzen können.
4. **Token im Schlüsselbund** (iOS Keychain / Android Keystore), nie im Klartext gespeichert oder geloggt.
5. **Keine Daten an Dritte:** keine Analyse-SDKs, keine Absturzberichte an Fremddienste, keine Werbung. **Einzige Ausnahme (entschieden 28.10.2026): Push** über Apple (APNs) und Google (Firebase Cloud Messaging, nur das Messaging-Modul, Analytics aus). Push-Inhalte ohne Namen, Orte oder Positionen.
6. **Bestätigung vor dem Start:** „Ortung für Team <Name>, Einsatz <Stichwort> auf <Server> starten?“

## Steuerung der Ortung (Kern)

Jede Antwort des Servers enthält, was die App als Nächstes tun soll:

| Antwort `mode` | App |
|---|---|
| `track` | GPS an, Positionen puffern, gesammelt hochladen |
| `pause` | GPS **aus**. Grob-Ortung ohne GPS hält die App wach, sie fragt jede Minute den Status ab und startet bei `track` selbst (siehe unten) |
| `stop` | GPS aus, Token löschen, Rolle beenden |

Zuordnung auf dem Server:

| Zustand | `mode` |
|---|---|
| Team-Suche läuft | `track` |
| Kurzpause, Suche noch nicht gestartet, Suche beendet | `pause` (neue Suche → wieder `track`, ohne Eingabe) |
| Einsatz beendet, Team gelöscht, Token-Generation geändert, Token abgelaufen | `stop` (HTTP 410) |
| Einsatzleitung angemeldet, Einsatz läuft | `track` |

Sicherheitsnetz in der App: Läuft `expiresAt` ab, stoppt die App auch ohne Netz.

### Aufwecken im Modus `pause` (entschieden 29.10.2026: Grob-Ortung ohne GPS; stille Push ab Dezember prüfen)

**Stand 29.10.2026:** Im iPhone-Test hat iOS die gesperrte App in der Pause schlafen gelegt, eine neue Suche kam nicht an.
Entscheidung: In `pause` läuft eine Grob-Ortung (niedrigste Genauigkeit, Funkzellen/WLAN, kein GPS, keine Positionen senden).
Sie hält die App wach, die App fragt jede Minute `/status` und startet bei `track` das GPS selbst. Das blaue Standort-Symbol
bleibt in der Pause sichtbar. Bei `stop` ist alles aus. Akku-Messung nötig. Ab Dezember (bezahltes Apple-Konto) prüfen,
ob stille Push zuverlässig genug ist, um die Grob-Ortung zu ersetzen. Der Abschnitt unten beschreibt diesen Push-Weg.

iOS hält eine App ohne laufende Ortung im Hintergrund nach kurzer Zeit an, ein Takt „alle 2 min `/status`“ ist dort nicht möglich.
Darum:

- Die App meldet beim Anmelden ihr Push-Token beim Server an (Team und EL).
- Wechselt der Server für ein Gerät von `pause`/Start auf `track` (oder auf `stop`), schickt er eine **stille Push-Mitteilung**
  (iOS `content-available`, Android FCM-Datennachricht hoher Priorität). Die App wacht auf, fragt `/status` und startet bzw. beendet die Ortung.
- Grenzen: iOS stellt stille Pushes nicht garantiert zu (gedrosselt, nicht nach „App wischen“). Rückfallebene: `BGAppRefreshTask`,
  `/status` bei jedem Öffnen, und der Server kann zusätzlich eine sichtbare Mitteilung „Suche gestartet – App öffnen“ schicken.
- Android: Der Foreground-Service kann im Modus `pause` ohne GPS weiter `/status` abfragen; der Push ist dort Beschleunigung, keine Voraussetzung.
- **Kostenloses Apple-Konto kann kein Push.** In der ersten Testphase (eigenes iPhone) startet die Ortung nach einer Pause
  daher erst beim Öffnen der App bzw. per `BGAppRefreshTask`. Der Push wird erst mit der bezahlten Mitgliedschaft eingebaut und getestet.

## Technik

Zwei **native Apps** im selben Repo (entschieden 28.10.2026). Beide Rollen sind vollständig nativ gebaut, ohne WebView,
damit alles flüssig läuft. Die Daten kommen ausschließlich über die Schnittstelle `…/app/v1/` des Servers.

| | iOS (`ios/`) | Android (`android/`) |
|---|---|---|
| Sprache / UI | Swift, SwiftUI | Kotlin, Jetpack Compose |
| Oberflächen | SwiftUI, Karte mit MapKit (entschieden 28.10.2026) | Jetpack Compose, Karte mit MapLibre Native (entschieden 29.10.2026) |
| Live-Daten | Server-Sent Events mit `URLSession` (Bearer-Token im Header), Rückfall Abfrage per HTTP | Server-Sent Events (Bearer-Token im Header), Rückfall Abfrage per HTTP |
| Push | APNs (sichtbar und still) | Firebase Cloud Messaging (nur Messaging) |
| Ortung | `CLLocationManager`, `allowsBackgroundLocationUpdates`, `pausesLocationUpdatesAutomatically`, Berechtigung „Beim Verwenden“ (blaue Statusanzeige, „Immer“ wird nicht verlangt) | Foreground-Service Typ `location` mit Benachrichtigung, Fused Location Provider (ausgewogen, Batching) |
| Puffer | SQLite (Funkloch, Upload mit Originalzeit) | Room/SQLite |
| QR-Scan | AVFoundation / VisionKit `DataScannerViewController` | CameraX + ML Kit Barcode |
| Geheimnisse | Keychain | Android Keystore (EncryptedSharedPreferences) |
| Mindestversion | iOS 17 (für `CLBackgroundActivitySession`, entschieden 28.10.2026) | Android 8 (API 26) |
| Build | Xcode auf dem Mac | Android Studio (Mac oder Windows) |

- Reihenfolge: **iPhone zuerst** bauen und testen (Mehrheit der Mitglieder), Android direkt danach nach derselben Vorlage.
- Beide Apps verhalten sich gleich, weil sie sich an **eine verbindliche Beschreibung der Server-Schnittstelle** halten
  (im Server-Repo, abgesichert durch Server-Tests).
- Anmeldung über den bestehenden Team-QR-Code (`https://<server>/team/login?code=…`). Die App liest Server und Code aus dem
  QR-Code und meldet sich **nativ** an (Teamliste holen, Team wählen, Token erhalten). Die bisherige Übergabe aus der WebView
  (`einsatzApp` / `EinsatzAppAndroid`) wird von der App nicht genutzt.
- Erste Tests mit **kostenlosem Apple-Konto** auf dem eigenen iPhone (entschieden 28.10.2026): Installation direkt aus Xcode,
  App läuft 7 Tage, dann neu aus Xcode starten. Hintergrund-Ortung, Kamera und Keychain gehen, **Push nicht**.
  Bezahlte Mitgliedschaft erst für Push und TestFlight.
- Später automatische Builds über GitHub Actions (macOS-Runner für iOS).

## Team nativ – Umfang

Nachgebaut wird, was `/team` heute kann:

| Bereich | Funktionen |
|---|---|
| Anmeldung | QR-Code scannen oder Anmelde-Code eingeben, Einsatz anzeigen, Team wählen |
| Kopf | Teamname, Hund, „aktiv“, Abmelden |
| Karte | Suchgebiet des Teams, Halsband-Position des Hundes, eigene Position, Entfernung zum Gebiet, „Auf Hund zentrieren“ |
| Statusmeldungen | „Im Gebiet“, „Pause“, „Funk“, „Beendet“ an die Einsatzleitung |
| Mitteilungen | Nachrichten der Einsatzleitung an das Team (Liste, quittieren) |
| Fußzeile | Halsband-GPS-Alter, Handy-Ortung (läuft / pausiert / beendet), letzter Upload |

Der Traccar-Teil von `/team` entfällt in der App.

## Einsatzleitung nativ – Umfang

Nachgebaut wird, was `/el` heute kann (Stand Server 1.11.7):

| Bereich | Funktionen |
|---|---|
| Einsatz starten | Stichwort, Ort, Szenario, Einsatz/Übung (wie `/el/start`) |
| Übersicht | Teams mit Suchzeit und Warnstufe, Starten (mit Halsband-Auswahl), Pause, Weiter, Stopp, Zurücksetzen; Warnton bei Eskalation; Wetter |
| Vermisste | Profile anlegen/bearbeiten, Szenario, Checklisten |
| Karte | Suchgebiete, Halsbänder und Team-Handys live, Tracks, Abdeckung in %, ELW; iOS mit MapKit, Android mit MapLibre und OSM/OpenTopoMap (keine Google-Schlüssel) |
| Notizen und Funk | Liste, neue Einträge, Antworten; Funkloch-Puffer wie heute |
| Einsatz beenden | Ergebnis, Prüfung der Ressourcen, PDF-Optionen (wie `/el/beenden`) |
| Ortung | Standort der Einsatzleitung für die Karte im ELW (Server fertig, PR #113) |

Bewusst **nicht** in der App: Teams anlegen, Stammdaten, Einstellungen, Archiv – das bleibt im Monitor (Absprache: `/el` ist keine Kopie des Monitors).

Live-Aktualisierung: Server-Sent Events `/ereignisse` (Bearer-Token im Header), damit Suchzeiten, Positionen, Funk und Notizen ohne Neuladen erscheinen.

Folge: Jede neue EL-Funktion auf dem Server muss in beiden Apps nachgezogen werden (App-Update, bei Apple mit Prüfung).

## Server-Schnittstelle v1 (im Server-Repo)

**Verbindlich:** [docs/APP_API.md](https://github.com/Elemirus1996/Einsatzueberwachung.Server/blob/main/docs/APP_API.md) im Server-Repo. Die Übersicht hier ist nur eine Zusammenfassung.

Neuer Bereich `api/team-mobile/app/v1/` (öffentliche Adresse, eigener nginx-Block mit großzügigem Limit, weil sich im Mobilfunk viele Handys eine IP teilen) und `api/el/app/v1/` (nur intern/VPN).

| Endpunkt | Zweck |
|---|---|
| `GET  api/team-mobile/app/v1/config` | nach QR-Scan: Team, Einsatz, Staffelname, Intervalle, `expiresAt`, `mode` |
| `POST api/team-mobile/app/v1/positions` | gesammelte Positionen (lat, lng, accuracy, timestamp, battery), Antwort mit `mode` |
| `GET  api/team-mobile/app/v1/status` | leichte Abfrage im Pause-Modus |
| `POST api/team-mobile/app/v1/login/teams`, `POST …/login` | native Anmeldung: Anmelde-Code → Einsatz und Teamliste; Code + Team → Token (Phase 1c) |
| `GET  api/team-mobile/app/v1/lage`, `POST …/statusmeldung`, `…/mitteilungen` | Daten der Team-Ansicht und Statusmeldungen (Phase 1c) |
| `POST api/team-mobile/app/v1/push`, `POST api/el/app/v1/push` | Push-Token des Geräts anmelden/abmelden (Phase 1c bzw. 3b) |
| `api/team-mobile/app/v1/ereignisse` | Live-Aktualisierungen für die native Team-Ansicht (SSE, Phase 1c) |
| `POST api/el/app/v1/login` | Anmeldung der nativen EL-App mit Staffel-Passwort (nur VPN, Sperre nach Fehlversuchen wie im Browser) → EL-Token |
| `POST api/el/app/v1/positions`, `GET …/status` | Standort der Einsatzleitung, Antwort mit `mode` (fertig, PR #113) |
| `api/el/app/v1/einsatz`, `…/teams`, `…/vermisste`, `…/karte`, `…/funk`, `…/notizen`, `…/beenden` | Daten und Aktionen der EL-Oberfläche (Phase 1b) |
| `api/el/app/v1/ereignisse` | Live-Aktualisierungen für die native EL-App (SSE, Phase 1b) |

Auth: `Authorization: Bearer <token>`. Ungültiges Team-Token → `410 Gone` + `{"mode":"stop"}`. Der bestehende Traccar-Endpunkt bleibt in der Übergangszeit unverändert.

Anzeige auf dem Server: Marker „EL“ (mit Gerätename, z. B. „Tablet EL“) auf Einsatzkarte und Monitor, Alter der Position wie bei Team-Handys.

## Umzug auf den Mac (entschieden 31.10.2026)

Nach Abschluss der laufenden Aufträge (Löschfristen #151, Release v1.12.0, Android-Karte) wird die Arbeit auf dem Mac zusammengeführt, um Tokens zu sparen:
- Ordner `~/einsatzspur/` mit beiden Repos, darüber `CLAUDE.md` aus `docs/arbeitsplatz/CLAUDE.md`.
- Eine lokale Sitzung in der Claude-Desktop-App in `~/einsatzspur/`, bei Bedarf eine zweite für parallele Arbeit. Kein Projekt (Cloud-Teilsitzungen haben kein Xcode/Android Studio).
- Paul arbeitet direkt mit dieser Sitzung. Die Cloud-Leitung und die Windows-Sitzung werden beendet.
- Mitnehmen: `.claude/settings.local.json` (Merge-Erlaubnis), `ios/Lokal.xcconfig` (Team-ID), Testschlüssel `~/.einsatzspur/` bleibt, wo er ist. Auf dem Mac das .NET-9-SDK installieren.

## Phasen

### Phase 0 – Vorbereitung (Nutzer)
- [x] Kostenloses Apple-Konto in Xcode anmelden, eigenes iPhone im Entwicklermodus (für die ersten Tests) – erster Test 28.10.2026
- [ ] Später: Apple-Entwicklerkonto (Einzelperson, 99 €/Jahr) für Push und TestFlight, Händlerstatus nach DSA: nicht kommerziell
- [ ] Google-Play-Entwicklerkonto (einmalig 25 $)
- [x] App-Kennung: `io.github.elemirus1996.einsatzspur` (an den GitHub-Namen gebunden, passend zum App-Namen; entschieden 28.10.2026, ersetzt `…einsatzueberwachung`)
- [x] App-Name „EinsatzSpur“ und Symbol (Pfote mit Standort-Pin), für alle Staffeln gleich; Marke bei DPMA und TMview ohne Treffer (App PR #6, entschieden 28.10.2026)
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

### Phase 1b – Server: Schnittstelle für die native Einsatzleitung ([Server #125](https://github.com/Elemirus1996/Einsatzueberwachung.Server/issues/125))
- [ ] Beschreibung in `docs/APP_API.md`
- [ ] Anmeldung nativ (Staffel-Passwort, Sperre wie im Browser, Audit-Log mit Gerätename), `/lage`, `/ereignisse` (Server-Sent Events wie bei der Team-Rolle)
- [ ] Aktionen: Team starten/pausieren/stoppen/zurücksetzen (Halsband-Rückfrage), Notizen, Funk, Mitteilung an Teams – mit `id` gegen doppelte Ausführung
- [ ] Vermisste, Karte (Gebiete, Positionen, Tracks, Abdeckung)
- [ ] Einsatz starten und beenden
- [ ] Tests für jede Aktion und für „nur intern“

### Phase 1c – Server: Schnittstelle für die native Team-Ansicht
- [x] Native Anmeldung: Anmelde-Code → Einsatz + Teamliste, Code + Team → Token, auch in Übungen (Server #116–#118)
- [x] Lesen: `/lage`, `/tracks`; Aktion `/statusmeldung` mit Entdoppelung; Mitteilungen der EL gespeichert (Server #122, #123)
- [x] Live über Server-Sent Events `/ereignisse` statt SignalR (kein Fremd-Client unter iOS nötig), höchstens 10 Verbindungen je Team, eigenes Ereignis `verdraengt` (Server #122–#124)
- [x] Tests, Beschreibung in `docs/APP_API.md`; Release v1.11.10, auf dem Staffel-Server eingespielt 28.10.2026
- [ ] Logo der Staffel und feste Server-Kennung für die Heimatstaffel ([Server #121](https://github.com/Elemirus1996/Einsatzueberwachung.Server/issues/121))
- [ ] Mitteilung quittieren (optional)
- [ ] Push-Token je Gerät speichern, stille Push bei `pause`→`track` und bei `stop` (erst mit Apple-Mitgliedschaft testbar; nach dem iPhone-Test vorerst nicht nötig, siehe Akzeptanzkriterien)

### Phase 2 – App, Rolle Team (Mac; erst iOS, dann Android)
iOS, getestet auf dem iPhone 15 Pro gegen Server v1.11.10 am 28.10.2026:
- [x] Xcode-Projekt in `ios/` (kostenloses Konto) (App PR #2)
- [x] QR-Scan und Code-Eingabe, Bestätigung mit Servername, native Teamauswahl, Token im Schlüsselbund (App PR #7)
- [x] Ortungsdienst, Puffer, Upload, `mode` auswerten, eigener Upload-Takt auch bei Stillstand (App PR #2, #5)
- [x] Native Team-Ansicht: Karte (MapKit, entschieden 28.10.2026), Statusmeldungen, Mitteilungen, Live (App PR #5)
- [ ] `stop` im Live-Strom erst nach Bestätigung durch `/lage` (App PR #8), Ereignis `verdraengt` auswerten
- [ ] Symbol, Name „EinsatzSpur“ und neue App-Kennung einbinden
- [ ] Heimatstaffel: bei der ersten Verbindung fragen, nur dann Logo und Name der Staffel auf der Startseite; fremde Staffel im gemeinsamen Einsatz ändert nichts; in den Einstellungen änderbar (braucht Server #121)

Android:
- [ ] Android-Projekt in `android/` nach derselben Vorlage, gebaut vom Mac (Android Studio; entschieden 28.10.2026)

### Phase 3 – App, Rolle Einsatzleitung nativ (Mac; erst iOS, dann Android)
- [ ] Einrichtung: interne Adresse einmal eingeben, VPN-Erkennung, sonst Hinweis „WireGuard einschalten“
- [ ] Anmeldung mit Staffel-Passwort (nativer Dialog), Token in Keychain/Keystore
- [ ] Übersicht mit Teams und Aktionen, Warnton/Haptik bei Eskalation
- [ ] Vermisste, Notizen und Funk (mit Funkloch-Puffer), Einsatz starten und beenden
- [ ] Karte (iOS: MapKit; Android: MapLibre) mit Gebieten, Positionen, Tracks, Abdeckung
- [ ] EL-Ortung, Stop bei Einsatzende, Abmeldung oder Rollenwechsel
- [ ] Tablet (iPad und Android-Tablets): eigenes Layout für die Einsatzleitung (Karte groß, Teamliste daneben); Team-Ansicht läuft mit. Tablets ohne Mobilfunk haben kein GPS, EL-Position dann ungenau oder ohne (entschieden 29.10.2026)

### Phase 3a – Navigationsziele und Punkte, Variante C (Wunsch 31.10.2026, Server #159)
- [x] Team: Ziel der EL als Mitteilung mit „Navigation starten“ (iOS: Apple Karten, Google Maps, Waze; Android: `geo:` mit Auswahl)
- [x] Team: „Punkt setzen“ am eigenen Standort mit kurzem Text, im Funkloch gepuffert (`POST /punkt`)
- [x] EL: Kartenpunkte auf der Lagekarte, antippen → an ein Team senden; Ereignis `punkt` mit Hinweis
- [x] Töne je Art in den Einstellungen (Warnung Stufe 2 nicht abschaltbar)
- [x] iOS: „Kartenpunkte in den Bericht“ beim Einsatzende
- [ ] Android: „Kartenpunkte in den Bericht“, sobald Einsatz beenden in der Android-EL kommt
- [ ] Test auf echten Geräten (Navi-Apps, Ortung in der Pause)

### Phase 3b – Push-Mitteilungen für die Einsatzleitung (entschieden 28.10.2026)
- [ ] Jeder EL stellt **in der App selbst** ein, welche Mitteilungen er bekommt (z. B. Zeitwarnung Team, Funkstille Handy/Halsband, Hund außerhalb des Gebiets, neuer Funkspruch, Einsatzende) – alles einzeln abschaltbar, Standard: nur kritische Warnungen
- [ ] Server: Geräte-Registrierung je EL-Gerät mit seinen Einstellungen, Versand bei Warnzentrum-Ereignissen und Funk; stille Push zum Aufwecken der EL-Ortung bei Einsatzbeginn
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

- [x] Nach dem QR-Scan läuft die Ortung ohne weitere Eingabe. (iPhone, 28.10.2026)
- [x] Spätestens einen Upload-Takt nach Einsatz- oder Suchende ist GPS am Handy aus. (iPhone, auch gesperrt und bei Stillstand, 28.10.2026)
- [ ] Neue Suche startet die Ortung ohne Eingabe wieder. (iPhone im Hintergrund bestanden 28.10.2026 nach kurzer Pause; noch mit 15–30 min Pause in einer Übung prüfen)
- [ ] Ohne Netz stoppt die App spätestens bei Ablauf des Tokens.
- [x] Positionen aus Funklöchern kommen mit Originalzeit an. (iPhone, Flugmodus, 28.10.2026)
- [ ] Einsatzleitung ist ohne VPN nicht erreichbar, auch nicht über die App.
- [ ] Der Führungsassistent sieht die Position der Einsatzleitung auf der Karte.
- [ ] Akkuverbrauch messbar unter Traccar mit sparsamen Werten.

## Offen

- **CarPlay / Android Auto** (Prüfpunkt Dezember 2026, nach dem bezahlten Apple-Konto): Apple erlaubt CarPlay nur für bestimmte App-Arten und mit eigener Freigabe; denkbar wäre höchstens eine sehr einfache Ansicht „Aufgabe während der Fahrt“ (Warnungen, Statusänderungen der Teams). Bis dahin: Mitteilungen auf dem Handy, die im Auto angezeigt bzw. vorgelesen werden.
- **Push bei selbst betriebenen Servern:** Der Push-Schlüssel von Apple gilt für alle Geräte mit der App und lässt sich nicht
  auf eine Staffel beschränken. Vorschlag: kleiner Push-Weiterleiter (Zugang je Staffel einzeln sperrbar, nur „wecken“ ohne
  Inhalt, sichtbare Texte Ende-zu-Ende verschlüsselt). Betrieb **nicht zu Hause**; Vorschlag: kleiner eigener Server in
  Deutschland. Erst nötig mit der bezahlten Apple-Mitgliedschaft, klären vor Phase 3b.
- Traccar-Endpunkt nach dem Umstieg abschalten oder als Rückfallebene behalten
- Soll die Einsatzleitung auch ohne laufenden Einsatz geortet werden? (umgesetzt: nein, `pause`)
- (entschieden 28.10.2026) Push-Mitteilungen siehe Phase 3b
