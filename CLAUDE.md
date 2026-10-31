# Hinweise für Claude Code

- Kommunikation mit dem Nutzer ausschließlich auf Deutsch.
- Plan und Aufgaben: `PLAN.md`. Haken setzen, wenn eine Aufgabe erledigt und getestet ist.
- Server-Repo: https://github.com/Elemirus1996/Einsatzueberwachung.Server (Blazor Server, .NET 9). Schnittstellen der App liegen dort unter `api/team-mobile/app/v1/` und `api/el/app/v1/`; Änderungen an der Schnittstelle zuerst dort, mit Tests.
- Sicherheit (nicht verhandelbar): keine fest eingebaute Server-Adresse, nur HTTPS, Token nur in Keychain/Keystore, nichts loggen, was Token oder Positionen enthält, keine Analyse- oder Fremd-SDKs. Einzige Ausnahme: ZXing-Core (Apache 2.0) für den QR-Scan unter Android, reine lokale Bildauswertung ohne Netz (entschieden 29.10.2026). Zweite Ausnahme: MapLibre Native (BSD) für die Karte unter Android, ohne Telemetrie, Kacheln nur von OpenStreetMap/OpenTopoMap wie im Server (entschieden 29.10.2026). Einsatzleitung nur über das VPN der Staffel.
- Akku: GPS nur im Modus `track`. Bei `pause` läuft nur eine Grob-Ortung ohne GPS (Funkzellen/WLAN, niedrigste Genauigkeit, keine Positionen senden), damit die App wach bleibt und eine neue Suche bemerkt (entschieden 29.10.2026, Prüfung auf stille Push ab Dezember). Bei `stop` muss der Ortungsdienst wirklich beendet sein, nicht nur das Senden. Rolle Einsatzleitung: ohne laufenden Einsatz ist die Ortung ganz aus, auch keine Grob-Ortung; sie startet erst, wenn die App geöffnet wird bzw. ein Einsatz läuft (entschieden 29.10.2026).
- Releases und Veröffentlichung in den Stores nur nach ausdrücklichem OK des Nutzers.
- Technik: native Apps, iOS in `ios/` (Swift, SwiftUI), Android in `android/` (Kotlin, Jetpack Compose). iPhone zuerst. Beide Apps halten sich exakt an die Schnittstellenbeschreibung im Server-Repo.
- Commits und PRs: Branch pro Aufgabe, PR nach `main`.

## Arbeitsweise (seit 31.10.2026)

Eine lokale Sitzung in `~/einsatzspur` (beide Repos) arbeitet direkt mit dem Nutzer. Cloud-Leitung und Windows-Sitzung sind beendet. Regeln und Skills: `docs/arbeitsplatz/`.
