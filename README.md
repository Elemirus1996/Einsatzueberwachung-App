# Einsatzüberwachung – App

App für iPhone und Android zum [Einsatzüberwachung.Server](https://github.com/Elemirus1996/Einsatzueberwachung.Server), dem Einsatzleitsystem für Rettungshundestaffeln.

- **Team:** QR-Code aus dem Einsatz scannen, fertig. Die App ortet den Hundeführer nur während der Suche. Der Server sagt ihr, wann sie pausieren und wann sie aufhören soll. Nach dem Einsatz fragt sie keinen Standort mehr ab.
- **Einsatzleitung:** Die EL-Ansicht als App, nur im WireGuard-VPN der Staffel. Der Standort der Einsatzleitung erscheint auf der Karte im ELW.

Jede Staffel nutzt ihren eigenen Server. Die App hat keine fest eingebaute Adresse und sendet nur an den Server aus dem QR-Code bzw. aus dem VPN, keine Daten an Dritte.

**Stand:** in Planung, siehe [PLAN.md](PLAN.md).

Technik: .NET MAUI (C#), Oberflächen aus dem Server, native Hintergrund-Ortung für iOS und Android.

Lizenz: [MIT](LICENSE)
