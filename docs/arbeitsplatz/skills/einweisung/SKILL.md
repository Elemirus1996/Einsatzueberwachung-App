---
name: einweisung
description: Kurze Einarbeitung einer neuen Sitzung in ~/einsatzspur (Server, iOS, Android). Zu Beginn jeder neuen Sitzung aufrufen.
user-invocable: true
---

# Einweisung

Sparsam: nur das Nötige lesen, nichts vollständig, was nicht gebraucht wird.

1. Beide Repos auf `main` und aktuell, offene PRs:
   ```
   for r in Einsatzueberwachung-App Einsatzueberwachung.Server; do (cd ~/einsatzspur/$r && echo "== $r" && git status -sb | head -1 && git pull -q --ff-only && git log --oneline -1 && gh pr list); done
   ```
2. Board: nur die Karte `mac-uebergabe` lesen (ArtifactData `get`, https://claude.ai/artifact/EDiazzj2cArneXUVzCHb8K, Sammlung `aufgaben`). Dort stehen Stand und nächste Schritte. Weitere Karten nur bei Bedarf.
3. Schnittstelle: Skill `api-abgleich`, nur wenn der Server-SHA von `docs/APP_API.md` neuer ist als `Einsatzueberwachung-App/.claude/api-stand.txt`.
4. `PLAN.md` und die `CLAUDE.md` der Repos nicht vorab lesen, sondern gezielt (grep), wenn eine Aufgabe sie berührt.
5. Paul in höchstens 5 Zeilen melden: Stand, nächster Schritt laut Karte.
