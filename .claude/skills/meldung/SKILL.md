---
name: meldung
description: Kurzmeldung an die Leitung (Cloud-Sitzung) im festen Format. Verwenden, wenn ein Schritt fertig ist, ein Blocker auftritt oder Paul etwas entscheiden muss.
user-invocable: true
---

# Meldung an die Leitung

Die Leitung ist die Cloud-Sitzung `session_01VCvMn7ETbiKTdBsnopQpzx` („Mac und Windows Entwicklung“). Meldungen gehen per Nachricht dorthin (send_message, sonst SendMessage). Nur melden, wenn etwas **fertig** ist, ein **echter Blocker** besteht oder **Paul entscheiden** muss. Keine Zwischenstände, keine Wiederholung von Bekanntem.

## Format (höchstens 10 Zeilen)

```
Mac: <Thema in einem Satz>
Fertig: PR #<n> (<gemergt|offen>), Tests <x/y grün>, Plattform/Gerät
Auswirkung: <was die andere Seite wissen muss, sonst „keine“>
Blocker: <konkret, sonst „keiner“>
Entscheidung nötig: <Frage mit Vorschlag, sonst „keine“>
Weiter mit: <nächster Schritt>
```

## Danach

- Den Stand in die eigene Board-Karte schreiben: https://claude.ai/artifact/EDiazzj2cArneXUVzCHb8K, Sammlung `aufgaben`, Werkzeug ArtifactData. Vor jedem Schreiben lesen und mit `if_version` schreiben.
- Nicht auf Antwort warten: mit „Weiter mit“ beginnen, sofern nichts blockiert.
