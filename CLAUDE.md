# Hinweise für Claude Code

- Kommunikation mit dem Nutzer ausschließlich auf Deutsch.
- Plan und Aufgaben: `PLAN.md`. Haken setzen, wenn eine Aufgabe erledigt und getestet ist.
- Server-Repo: https://github.com/Elemirus1996/Einsatzueberwachung.Server (Blazor Server, .NET 9). Schnittstellen der App liegen dort unter `api/team-mobile/app/v1/` und `api/el/app/v1/`; Änderungen an der Schnittstelle zuerst dort, mit Tests.
- Sicherheit (nicht verhandelbar): keine fest eingebaute Server-Adresse, nur HTTPS, Token nur in Keychain/Keystore, nichts loggen, was Token oder Positionen enthält, keine Analyse- oder Fremd-SDKs. Einsatzleitung nur über das VPN der Staffel.
- Akku: GPS nur im Modus `track`. Bei `pause` und `stop` muss der Ortungsdienst wirklich beendet sein, nicht nur das Senden.
- Releases und Veröffentlichung in den Stores nur nach ausdrücklichem OK des Nutzers.
- Commits und PRs: Branch pro Aufgabe, PR nach `main`.
