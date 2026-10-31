---
name: uebergabe
description: Stand nach einer abgeschlossenen Stufe ins Board schreiben und Paul einen Neustart der Sitzung vorschlagen. Verwenden, wenn eine Stufe bzw. ein größerer PR fertig ist.
user-invocable: true
---

# Übergabe und Neustart

1. Karte `mac-uebergabe` lesen (ArtifactData `get`, https://claude.ai/artifact/EDiazzj2cArneXUVzCHb8K, Sammlung `aufgaben`) und mit `if_version` überschreiben. Inhalt knapp:
   - STAND: was fertig ist (PR-Nummern, Tests x/y grün, Gerät)
   - OFFEN: nächste Schritte in Reihenfolge
   - BESONDERHEITEN: nur, was sich nicht aus Code, PLAN.md oder CLAUDE.md ergibt
2. Betroffene Aufgabenkarten auf `fertig` bzw. `arbeit` setzen (ein `batch`).
3. Paul melden: „Stufe fertig, Board aktuell. Neustart empfohlen, danach /einweisung.“ Nichts Neues mehr anfangen.
