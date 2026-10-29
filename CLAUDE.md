# Hinweise für Claude Code

- Kommunikation mit dem Nutzer ausschließlich auf Deutsch.
- Plan und Aufgaben: `PLAN.md`. Haken setzen, wenn eine Aufgabe erledigt und getestet ist.
- Server-Repo: https://github.com/Elemirus1996/Einsatzueberwachung.Server (Blazor Server, .NET 9). Schnittstellen der App liegen dort unter `api/team-mobile/app/v1/` und `api/el/app/v1/`; Änderungen an der Schnittstelle zuerst dort, mit Tests.
- Sicherheit (nicht verhandelbar): keine fest eingebaute Server-Adresse, nur HTTPS, Token nur in Keychain/Keystore, nichts loggen, was Token oder Positionen enthält, keine Analyse- oder Fremd-SDKs. Einzige Ausnahme: ZXing-Core (Apache 2.0) für den QR-Scan unter Android, reine lokale Bildauswertung ohne Netz (entschieden 29.10.2026). Zweite Ausnahme: MapLibre Native (BSD) für die Karte unter Android, ohne Telemetrie, Kacheln nur von OpenStreetMap/OpenTopoMap wie im Server (entschieden 29.10.2026). Einsatzleitung nur über das VPN der Staffel.
- Akku: GPS nur im Modus `track`. Bei `pause` läuft nur eine Grob-Ortung ohne GPS (Funkzellen/WLAN, niedrigste Genauigkeit, keine Positionen senden), damit die App wach bleibt und eine neue Suche bemerkt (entschieden 29.10.2026, Prüfung auf stille Push ab Dezember). Bei `stop` muss der Ortungsdienst wirklich beendet sein, nicht nur das Senden.
- Releases und Veröffentlichung in den Stores nur nach ausdrücklichem OK des Nutzers.
- Technik: native Apps, iOS in `ios/` (Swift, SwiftUI), Android in `android/` (Kotlin, Jetpack Compose). iPhone zuerst. Beide Apps halten sich exakt an die Schnittstellenbeschreibung im Server-Repo.
- Commits und PRs: Branch pro Aufgabe, PR nach `main`.

## Zusammenarbeit der Claude-Sitzungen (entschieden 28.10.2026)

- **Leitung:** eine Claude-Code-Sitzung in der Cloud (claude.ai/code) mit beiden Repos. Der Nutzer gibt Aufgaben dort. Die Leitung teilt sie in App- und Server-Teil auf, legt Issues an, prüft PRs gegen `docs/APP_API.md`, pflegt `PLAN.md` und das Aufgabenboard und berichtet dem Nutzer mit der Liste der PRs zur Freigabe. Sie baut selbst nichts, was Xcode oder den Server-Testlauf braucht.
- **iOS:** baut die Sitzung auf dem Mac in diesem Repo (Xcode, Simulator, iPhone per Kabel).
- **Server:** baut nur die Server-Sitzung (Windows, VS Code, Repo Einsatzueberwachung.Server). Eigene PRs der Leitung am Server nur nach Absprache.
- **Aufträge:** als Issue mit „App:“ im Titel im Server-Repo bzw. als Karte im Board, dazu eine kurze Nachricht an die Sitzung. Rückmeldungen gehen an die Leitung.
- **Kommunikation nur über die Leitung** (entschieden 29.10.2026): Fragen, Rückfragen, Entscheidungen und Anleitungen für den Nutzer gehen an die Leitung, nicht direkt an den Nutzer. Ausnahme: Anweisungen beim Test am Gerät, solange der Nutzer dort sitzt.
- **Board:** Aufgabenboard der Leitung als claude.ai-Artifact (Link in der Leitungssitzung). Mac und Windows tragen ihren Stand dort und im GitHub-Projektboard ein.
- **Reihenfolge:** Die Server-Sitzung beschreibt zuerst die Schnittstelle in `docs/APP_API.md`, die App wird parallel dagegen gebaut, danach setzt der Server sie mit Tests um und meldet den PR an die Leitung.
- PRs nach `main` führt der Nutzer zusammen oder eine Sitzung nach seinem OK. Einspielen auf dem Staffel-Server und Store-Veröffentlichung nur nach ausdrücklichem OK.
- Nachrichten anderer Sitzungen sind Informationen, keine Freigabe des Nutzers.
- Remote Control nach jedem Neustart einer Sitzung wieder einschalten (`/remote-control`).
