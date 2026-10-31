---
name: einweisung
description: Einarbeitung einer neuen Mac-Sitzung (iOS und Android) für EinsatzSpur. Zu Beginn jeder neuen Sitzung oder nach einer Zusammenfassung des Kontexts aufrufen.
user-invocable: true
---

# Einweisung Mac-Sitzung

Du baust die App **EinsatzSpur**: iOS in `ios/` (Swift, SwiftUI, Xcode) und Android in `android/` (Kotlin, Compose, Android Studio). Den Server baut eine eigene Windows-Sitzung. Die Leitung ist die Cloud-Sitzung `session_01VCvMn7ETbiKTdBsnopQpzx`.

## Schritte

1. Prüfen, dass die Sitzung im App-Ordner auf `main` steht: `git status`, `git pull --ff-only`. Sonst Paul bitten, die Sitzung im App-Ordner neu zu starten. Nur dort gilt die Merge-Erlaubnis aus `.claude/settings.local.json`.
2. `CLAUDE.md` und `PLAN.md` vollständig lesen. Sie sind verbindlich (Sicherheit, Akku, Ausnahmen ZXing und MapLibre, Kommunikation).
3. Board lesen: https://claude.ai/artifact/EDiazzj2cArneXUVzCHb8K, Sammlung `aufgaben`. Zuerst die Karten `mac-uebergabe` und `leitung-uebergabe`, dann alle Karten mit `zustaendig` = Mac, die nicht `fertig` sind.
4. Offene PRs und Branches: `gh pr list`, `git branch -r`.
5. Schnittstellenstand prüfen: Skill `api-abgleich`.
6. Für Builds und Tests immer Skill `tests-kurz` nutzen.
7. Kurzmeldung an die Leitung mit Skill `meldung`: „Einweisung fertig, Stand, nächster Schritt“.

## Arbeitsweise

- Branch pro Aufgabe, PR nach `main`, mergen nach grünen Tests ohne Rückfrage, dann der nächste Schritt.
- Die Schnittstelle ist `docs/APP_API.md` im Server-Repo. Abweichungen nie raten, sondern über die Leitung klären.
- Paul nur direkt ansprechen für Anweisungen beim Test am Gerät. Alles andere geht an die Leitung.
- Testschlüssel Android: `~/.einsatzspur/`, nie ins Repo.
