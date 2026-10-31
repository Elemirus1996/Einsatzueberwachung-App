---
name: api-abgleich
description: Prüft, was sich in docs/APP_API.md des Servers seit dem letzten Abgleich geändert hat, und gleicht iOS und Android ab. Aufrufen nach einem Server-Merge, der die Schnittstelle ändert, und bevor eine Schnittstelle angefasst wird.
user-invocable: true
---

# Abgleich mit der Server-Schnittstelle

Verbindlich ist `~/einsatzspur/Einsatzueberwachung.Server/docs/APP_API.md`. Der zuletzt geprüfte Server-Commit steht in `~/einsatzspur/Einsatzueberwachung-App/.claude/api-stand.txt`.

1. Im Server-Repo (auf aktuellem `main`): `git log --format='%h %s' -5 -- docs/APP_API.md`
2. Neuester SHA gleich dem gespeicherten: fertig.
3. Sonst `git diff <alt>..<neu> -- docs/APP_API.md` lesen, bei Bedarf den Abschnitt „Auswirkung auf die App“ des PR (`gh pr view <n>`).
4. Je Änderung prüfen, ob iOS und Android betroffen sind (Felder, Statuscodes, Endpunkte, Pflichtfelder, Zeiten immer UTC). Gezielt per grep, nicht ganze Dateien.
5. Abweichungen als eigenen PR mit Test beheben. Unklares nicht raten, Paul fragen.
6. Neuen SHA in `api-stand.txt` schreiben und mit dem nächsten App-PR committen.
