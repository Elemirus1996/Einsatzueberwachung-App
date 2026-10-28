# Einsatzüberwachung – App

App für iPhone und Android zum [Einsatzüberwachung.Server](https://github.com/Elemirus1996/Einsatzueberwachung.Server), dem Einsatzleitsystem für Rettungshundestaffeln.

- **Team:** QR-Code aus dem Einsatz scannen, fertig. Die App ortet den Hundeführer nur während der Suche. Der Server sagt ihr, wann sie pausieren und wann sie aufhören soll. Nach dem Einsatz fragt sie keinen Standort mehr ab.
- **Einsatzleitung:** Die Einsatzleitung als eigene App-Oberfläche, nur im WireGuard-VPN der Staffel. Der Standort der Einsatzleitung erscheint auf der Karte im ELW.

Jede Staffel nutzt ihren eigenen Server. Die App hat keine fest eingebaute Adresse und sendet nur an den Server aus dem QR-Code bzw. aus dem VPN. Einzige Ausnahme: Push-Mitteilungen laufen über Apple bzw. Google, ohne Namen oder Orte.

**Stand:** in Planung, siehe [PLAN.md](PLAN.md).

Technik: native Apps – Swift/SwiftUI (`ios/`) und Kotlin/Jetpack Compose (`android/`). Beide Rollen sind komplett in der App gebaut, keine eingebetteten Webseiten. Die Daten kommen über die Schnittstelle des Servers.

Lizenz: [MIT](LICENSE)
