import Foundation
import Testing
@testable import Einsatzueberwachung

struct DatenschutzTests {
    static let erklaerung = ###"{ "version": "3f9c0a7b12de4e55", "titel": "Datenschutzerklärung RHS Musterstadt", "text": "## 1. Wer?\nDie Staffel.\n\n- Standort\n- Akku", "stand": "2026-10-31T09:00:00Z" }"###

    func auswahl(_ datenschutz: String?) throws -> TeamAuswahl {
        let feld = datenschutz.map { #", "datenschutz": \#($0)"# } ?? ""
        let json = #"{ "einsatz": "Übung", "server": { "kennung": "k1", "staffel": "RHS" }, "teams": []\#(feld) }"#
        return try JSONDecoder.server.decode(TeamAuswahl.self, from: Data(json.utf8))
    }

    @Test func loginTeamsDreiFaelle() throws {
        #expect(try auswahl(nil).datenschutz == .fehlt, "älterer Server: gar nicht fragen")
        #expect(try auswahl("null").datenschutz == .keine, "Staffel hat nichts hinterlegt: Hinweis")
        guard case .erklaerung(let d) = try auswahl(Self.erklaerung).datenschutz else { Issue.record("keine Erklärung"); return }
        #expect(d.version == "3f9c0a7b12de4e55" && d.titel?.contains("Musterstadt") == true && d.vollstaendig)
        #expect(d.stand != nil)
    }

    @Test func nurVersionInConfigUndLage() throws {
        let d = try JSONDecoder.server.decode(Datenschutz.self, from: Data(#"{ "version": "abc" }"#.utf8))
        #expect(d.version == "abc" && !d.vollstaendig)
        let lage = try JSONDecoder.server.decode(ELLage.self, from: Data(
            #"{ "mode": "pause", "einsatz": { "aktiv": false }, "teams": [], "datenschutz": { "version": "v2" } }"#.utf8))
        #expect(lage.datenschutz?.version == "v2")
        let ohne = try JSONDecoder.server.decode(ELLage.self, from: Data(#"{ "einsatz": { "aktiv": false }, "teams": [], "datenschutz": null }"#.utf8))
        #expect(ohne.datenschutz == nil)
    }

    @Test func bestaetigungJeKennungUndVersion() throws {
        let s = DatenschutzSpeicher(speicher: UserDefaults(suiteName: "ds-\(UUID().uuidString)")!)
        #expect(s.offen(kennung: "k1", version: "v1"))
        #expect(!s.offen(kennung: "k1", version: nil), "ohne Version nichts fragen")
        s.bestaetigen(kennung: "k1", version: "v1")
        #expect(!s.offen(kennung: "k1", version: "v1"))
        #expect(s.offen(kennung: "k1", version: "v2"), "neue Version: erneut fragen")
        #expect(s.offen(kennung: "k2", version: "v1"), "anderer Server: eigene Bestätigung")
        let d = try JSONDecoder.server.decode(Datenschutz.self, from: Data(Self.erklaerung.utf8))
        s.merken(d, kennung: "k1")
        s.merken(Datenschutz(version: "nur-version"), kennung: "k2")
        #expect(s.alle().keys.sorted() == ["k1"], "nur ganze Erklärungen zum Nachlesen")
    }

    @Test func markdownZerlegen() {
        let b = DatenschutzBlock.zerlegen("# Titel\nErste Zeile\nzweite Zeile\n\n## 2. Daten\n- **Standort** nur bei Suche\n* Akku\n\nEnde")
        #expect(b == [
            .ueberschrift("Titel", ebene: 1), .absatz("Erste Zeile zweite Zeile"),
            .ueberschrift("2. Daten", ebene: 2), .punkt("**Standort** nur bei Suche"), .punkt("Akku"), .absatz("Ende"),
        ])
    }

    @Test func elAnmeldungLiefertErklaerung() throws {
        let json = #"{ "token": "e3.x", "expiresAt": "2026-11-01T10:00:00Z", "server": { "kennung": "k1" }, "datenschutz": \#(Self.erklaerung) }"#
        let a = try JSONDecoder.server.decode(ELAnmeldung.Ergebnis.self, from: Data(json.utf8))
        #expect(a.datenschutz.version == "3f9c0a7b12de4e55")
        #expect(a.server?.kennung == "k1")
        let alt = try JSONDecoder.server.decode(ELAnmeldung.Ergebnis.self, from: Data(#"{ "token": "e3.x", "expiresAt": "2026-11-01T10:00:00Z" }"#.utf8))
        #expect(alt.datenschutz == .fehlt)
    }
}
