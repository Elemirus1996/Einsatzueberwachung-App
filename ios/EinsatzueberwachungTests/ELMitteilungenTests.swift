import Foundation
import Testing
@testable import Einsatzueberwachung

final class TestMitteilungsSchnittstelle: ELMitteilungsSchnittstelle, @unchecked Sendable {
    var seite = ELMitteilungSeite(stand: "a-0", mitteilungen: [])
    var fehler = false
    private(set) var abgefragt: [String?] = []

    func mitteilungen(seit: String?) async throws -> ELMitteilungSeite {
        abgefragt.append(seit)
        if fehler { throw SchnittstellenFehler.server(status: 500) }
        return seite
    }
}

/// Mitteilungen der Einsatzleitung im Hintergrund ohne Push (#79)
@MainActor
struct ELMitteilungenTests {
    let server = TestMitteilungsSchnittstelle()
    let einstellungen = ELMitteilungsEinstellungen(speicher: UserDefaults(suiteName: "test-\(UUID().uuidString)")!)

    func m(_ art: String, _ stufe: Int, _ text: String) -> ELMitteilung {
        ELMitteilung(id: UUID().uuidString, art: art, stufe: stufe, teamId: "t1", text: text)
    }

    @Test func ersterStandNurAusgangspunkt_DannNurNeueUndStandardArten() async {
        var gesendet: [LageMeldung] = []
        let abruf = ELMitteilungsAbruf(schnittstelle: server, einstellungen: einstellungen) { gesendet.append($0) }

        await abruf.standGemeldet("a-3")
        #expect(server.abgefragt.isEmpty, "Erster Stand: keine Flut alter Mitteilungen")
        await abruf.standGemeldet("a-3")
        #expect(server.abgefragt.isEmpty)

        server.seite = ELMitteilungSeite(stand: "a-8", mitteilungen: [
            m("zeitwarnung", 1, "Team Rex: 45 min Suchzeit erreicht"),
            m("zeitwarnung", 2, "Team Rex: 60 min Suchzeit erreicht"),
            m("funk", 1, "Neuer Funkspruch von Team Rex"),
            m("gebiet", 2, "Team Rex: Hund hat das Suchgebiet verlassen"),
            m("unbekannt", 2, "Neue Art eines neueren Servers")
        ])
        await abruf.standGemeldet("a-8")
        #expect(server.abgefragt == ["a-3"])
        #expect(abruf.letzterStand == "a-8")
        #expect(gesendet.map(\.titel) == ["Team Rex: 60 min Suchzeit erreicht", "Team Rex: Hund hat das Suchgebiet verlassen"])
        #expect(gesendet.first?.tonart == .warnung2)
    }

    @Test func einstellungenJeArtUndHauptschalter() async {
        var gesendet: [LageMeldung] = []
        let abruf = ELMitteilungsAbruf(schnittstelle: server, einstellungen: einstellungen) { gesendet.append($0) }
        einstellungen.setzen(true, fuer: .funk)
        einstellungen.setzen(false, fuer: .gebiet)
        await abruf.standGemeldet("a-1")
        server.seite = ELMitteilungSeite(stand: "a-3", mitteilungen: [
            m("funk", 1, "Neuer Funkspruch von Team Rex"), m("gebiet", 2, "Team Rex: Hund hat das Suchgebiet verlassen")
        ])
        await abruf.standGemeldet("a-3")
        #expect(gesendet.map(\.titel) == ["Neuer Funkspruch von Team Rex"])
        #expect(gesendet.first?.tonart == .funk)

        einstellungen.setzen(an: false)
        server.seite = ELMitteilungSeite(stand: "a-4", mitteilungen: [m("funk", 1, "Neuer Funkspruch von Team Rex")])
        await abruf.standGemeldet("a-4")
        #expect(gesendet.count == 1, "Hauptschalter aus: nichts zeigen")
        #expect(abruf.letzterStand == "a-4", "Stand trotzdem merken, sonst kommt nach dem Einschalten alles nach")
    }

    @Test func netzfehlerBehaeltStand() async {
        let abruf = ELMitteilungsAbruf(schnittstelle: server, einstellungen: einstellungen) { _ in }
        await abruf.standGemeldet("a-1")
        server.fehler = true
        await abruf.standGemeldet("a-2")
        #expect(abruf.letzterStand == "a-1")
        server.fehler = false
        server.seite = ELMitteilungSeite(stand: "a-2", mitteilungen: [])
        await abruf.standGemeldet("a-2")
        #expect(server.abgefragt == ["a-1", "a-1"])
        #expect(abruf.letzterStand == "a-2")
    }

    @Test func standardNurKritisch() {
        let an = ELMitteilungsArt.allCases.filter { einstellungen.ist($0) }
        #expect(Set(an) == [.zeitwarnung2, .funkstille, .gebiet, .fund])
        #expect(einstellungen.an)
    }

    @Test func steuerAntwortLiestMitteilungStand() throws {
        let json = #"{"mode":"track","intervalSec":30,"uploadEverySec":30,"mitteilungStand":"a1-17"}"#
        let antwort = try JSONDecoder.server.decode(SteuerAntwort.self, from: Data(json.utf8))
        #expect(antwort.mitteilungStand == "a1-17")
    }

    @Test func ortungMeldetMitteilungStand() {
        let quelle = TestQuelle()
        let t = Date(timeIntervalSince1970: 1_800_000_000)
        let o = TeamOrtung(schnittstelle: TestSchnittstelle(), quelle: quelle, puffer: TestPuffer(),
                           ablauf: t.addingTimeInterval(3600), jetzt: { t }, pauseOhneOrtung: true, beimBeenden: {})
        var staende: [String] = []
        o.beiMitteilungStand = { staende.append($0) }
        o.anwenden(SteuerAntwort(mode: .track, mitteilungStand: "a-1"))
        o.anwenden(SteuerAntwort(mode: .pause, mitteilungStand: "a-2"), melden: false)
        #expect(staende == ["a-1"], "Modus aus /lage (melden: false) meldet keinen Stand")
    }
}
