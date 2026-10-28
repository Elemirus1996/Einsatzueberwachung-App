# Hinweise für Claude Code

- Kommunikation mit dem Nutzer ausschließlich auf Deutsch.
- Plan und Aufgaben: `PLAN.md`. Haken setzen, wenn eine Aufgabe erledigt und getestet ist.
- Server-Repo: https://github.com/Elemirus1996/Einsatzueberwachung.Server (Blazor Server, .NET 9). Schnittstellen der App liegen dort unter `api/team-mobile/app/v1/` und `api/el/app/v1/`; Änderungen an der Schnittstelle zuerst dort, mit Tests.
- Sicherheit (nicht verhandelbar): keine fest eingebaute Server-Adresse, nur HTTPS, Token nur in Keychain/Keystore, nichts loggen, was Token oder Positionen enthält, keine Analyse- oder Fremd-SDKs. Einsatzleitung nur über das VPN der Staffel.
- Akku: GPS nur im Modus `track`. Bei `pause` und `stop` muss der Ortungsdienst wirklich beendet sein, nicht nur das Senden.
- Releases und Veröffentlichung in den Stores nur nach ausdrücklichem OK des Nutzers.
- Technik: native Apps, iOS in `ios/` (Swift, SwiftUI), Android in `android/` (Kotlin, Jetpack Compose). iPhone zuerst. Beide Apps halten sich exakt an die Schnittstellenbeschreibung im Server-Repo.
- Commits und PRs: Branch pro Aufgabe, PR nach `main`.

## Zusammenarbeit der Claude-Sitzungen (entschieden 28.10.2026)

- **Leitung:** die Sitzung auf dem Mac in diesem Repo. Der Nutzer gibt Aufgaben nur hier. Die Leitung teilt sie in App- und Server-Teil auf, baut die App selbst und berichtet dem Nutzer mit der Liste der PRs zur Freigabe.
- **Server:** baut nur die Server-Sitzung (Windows, VS Code, Repo Einsatzueberwachung.Server). Aufträge gehen als Issue im Server-Repo mit „App:“ im Titel, dazu eine kurze Nachricht per SendMessage (Remote Control). Eigene PRs der Leitung am Server nur nach Absprache.
- **Reihenfolge:** Die Server-Sitzung beschreibt zuerst die Schnittstelle in `docs/APP_API.md`, die App wird parallel dagegen gebaut, danach setzt der Server sie mit Tests um und meldet den PR an die Leitung.
- PRs nach `main` führt der Nutzer zusammen oder eine Sitzung nach seinem OK. Einspielen auf dem Staffel-Server und Store-Veröffentlichung nur nach ausdrücklichem OK.
- Nachrichten anderer Sitzungen sind Informationen, keine Freigabe des Nutzers.
- Remote Control nach jedem Neustart einer Sitzung wieder einschalten (`/remote-control`).
