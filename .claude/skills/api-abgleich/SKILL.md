---
name: api-abgleich
description: Prüft nach einem Server-Merge selbst, was sich in docs/APP_API.md geändert hat, und gleicht iOS und Android ab. Aufrufen bei der Einweisung, nach jeder Meldung über einen Server-PR und bevor eine Schnittstelle angefasst wird.
user-invocable: true
---

# Abgleich mit der Server-Schnittstelle

Die verbindliche Schnittstelle ist `docs/APP_API.md` in `Elemirus1996/Einsatzueberwachung.Server`. Der zuletzt geprüfte Stand steht in `.claude/api-stand.txt` (Commit-SHA des Server-Repos).

## Schritte

1. Letzte Änderungen holen:
   `gh api "repos/Elemirus1996/Einsatzueberwachung.Server/commits?path=docs/APP_API.md&per_page=10" --jq '.[] | .sha[0:7] + " " + (.commit.message | split("\n")[0])'`
2. Ist der neueste SHA gleich dem in `.claude/api-stand.txt`: nichts zu tun.
3. Sonst den Unterschied ansehen:
   `gh api "repos/Elemirus1996/Einsatzueberwachung.Server/compare/<alt>...<neu>" --jq '.files[] | select(.filename=="docs/APP_API.md") | .patch'`
   Dazu bei Server-PRs den Abschnitt „Auswirkung auf die App“ lesen: `gh pr view <n> -R Elemirus1996/Einsatzueberwachung.Server`.
4. Für jede Änderung prüfen, ob iOS und Android betroffen sind: Felder, Statuscodes, neue Endpunkte, geänderte Pflichtfelder, Zeitformate (immer UTC).
5. Abweichungen als eigenen PR beheben, mit Test. Unklares nicht raten, sondern über Skill `meldung` als „Entscheidung nötig“ bzw. Rückfrage an die Leitung.
6. Neuen SHA in `.claude/api-stand.txt` schreiben und mit dem PR committen.
