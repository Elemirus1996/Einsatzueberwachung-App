# Arbeitsplatz EinsatzSpur (Ordner ~/einsatzspur auf dem Mac)

Diese Datei gehört nach `~/einsatzspur/CLAUDE.md` (außerhalb der Repos). Vorlage: `Einsatzueberwachung-App/docs/arbeitsplatz/CLAUDE.md`.

- Kommunikation mit Paul auf Deutsch, kurz und klar, keine Emojis, nichts schönreden. Nichts erfinden, lieber nachfragen. Bei Entscheidungen einen Vorschlag mit Begründung machen.
- `Einsatzueberwachung-App/`: App EinsatzSpur, iOS in `ios/` (Xcode), Android in `android/` (Android Studio). Regeln in `Einsatzueberwachung-App/CLAUDE.md`, Plan in `PLAN.md`.
- `Einsatzueberwachung.Server/`: Server (Blazor, .NET 9). Die Schnittstelle der App ist verbindlich in `docs/APP_API.md`.
- Schnittstelle ändern: erst `APP_API.md` beschreiben, dann Server mit Tests, dann iOS und Android. Jeder Server-PR nach Skill `pr-checkliste`.
- Branch pro Aufgabe, PR nach `main`, mergen nach grünen Tests. Builds und Tests nur mit Skill `tests-kurz`.
- Releases des Servers und alles in den Stores nur nach Pauls ausdrücklichem OK. Einspielen auf dem Staffel-Server macht Paul.
- Stand und Aufgaben: Board https://claude.ai/artifact/EDiazzj2cArneXUVzCHb8K (Sammlung `aufgaben`, Karte `mac-uebergabe`, Werkzeug ArtifactData) und `PLAN.md`. Nach jeder abgeschlossenen Stufe Skill `uebergabe`.
- Tokens sparen: gezielt lesen (grep, Ausschnitte), lange Ausgaben in Dateien, Antworten knapp.
- Skills liegen in `Einsatzueberwachung-App/docs/arbeitsplatz/skills/`, eingebunden per Symlink in `~/einsatzspur/.claude/skills/`; `pr-checkliste`, `impeccable` und `interface-design` per Symlink aus dem Server-Repo.
- Sicherheitsrelevante PRs (Anmeldung, Token, Ortung, Datenschutz) vor dem Merge mit `/security-review` prüfen.
