import Foundation
import Testing
@testable import Einsatzueberwachung

/// Fängt Anfragen ab und antwortet mit Status und Inhalt (ohne Netz).
final class StubProtokoll: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var antwort: (status: Int, text: String) = (200, "{}")
    nonisolated(unsafe) static var fehler: URLError?
    nonisolated(unsafe) static var letzteAnfrage: URLRequest?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.letzteAnfrage = request
        if let fehler = Self.fehler {
            client?.urlProtocol(self, didFailWithError: fehler)
            return
        }
        let http = HTTPURLResponse(url: request.url!, statusCode: Self.antwort.status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(Self.antwort.text.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    static var session: URLSession {
        let konfiguration = URLSessionConfiguration.ephemeral
        konfiguration.protocolClasses = [StubProtokoll.self]
        return URLSession(configuration: konfiguration)
    }
}

@Suite(.serialized)
struct ELAnmeldungTests {
    let server = ServerAdresse(URL(string: "https://einsatz.intern")!)!

    func anmelden(_ status: Int, _ text: String = "{}", fehler: URLError? = nil) async throws -> ELSitzung {
        StubProtokoll.antwort = (status, text)
        StubProtokoll.fehler = fehler
        return try await ELAnmeldung(server: server, session: StubProtokoll.session).anmelden(passwort: "geheim", geraet: "iPad ELW").0
    }

    @Test func erfolgreich() async throws {
        let sitzung = try await anmelden(200, #"{ "apiVersion": 1, "rolle": "el", "token": "e2.abc", "expiresAt": "2026-10-30T10:00:00Z", "server": { "kennung": "k", "staffel": "RHS", "logoVersion": null } }"#)
        #expect(sitzung.token == "e2.abc")
        #expect(StubProtokoll.letzteAnfrage?.url?.path() == "/api/el/app/v1/login")
    }

    @Test func fehlerFallweise() async {
        await #expect(throws: ELAnmeldeFehler.passwortFalsch) { try await anmelden(401) }
        await #expect(throws: ELAnmeldeFehler.gesperrt(sekunden: 300)) { try await anmelden(429, #"{ "error": "gesperrt", "wiederholenNachSec": 300 }"#) }
        await #expect(throws: ELAnmeldeFehler.keinPasswort) { try await anmelden(409) }
        await #expect(throws: ELAnmeldeFehler.nichtErreichbar, "öffentlicher Zugang: 404") { try await anmelden(404) }
        await #expect(throws: ELAnmeldeFehler.nichtErreichbar, "ohne VPN") { try await anmelden(0, fehler: URLError(.cannotFindHost)) }
    }
}

final class TestELSchnittstelle: ELLageSchnittstelle, @unchecked Sendable {
    var lageAntwort: Result<ELLage, Error>
    var abrufe = 0
    init(_ lage: ELLage) { lageAntwort = .success(lage) }
    func lage() async throws -> ELLage {
        abrufe += 1
        return try lageAntwort.get()
    }
    func ereignisse() async throws -> AsyncThrowingStream<ELEreignis, Error> { AsyncThrowingStream { $0.finish() } }
}

@MainActor
struct ELAnsichtTests {
    static let beispiel = """
    { "mode": "track", "serverZeit": "2026-10-29T20:00:00Z",
      "einsatz": { "aktiv": true, "text": "Vermisste Person · Testwald", "stichwort": "VP", "ort": "Testwald", "szenario": "Flaeche",
                   "uebung": false, "beginn": "2026-10-29T19:00:00Z", "einsatzleiter": "Paul", "fuehrungsassistent": "Eric" },
      "teams": [ { "teamId": "t1", "teamName": "Team Rex", "dogName": "Rex", "hundefuehrerName": "Anna", "helfer": ["Ben"],
          "suche": { "status": "laeuft", "seit": "2026-10-29T19:25:00Z", "dauerSec": 2100 },
          "warnstufe": 1, "warnung1Min": 45, "warnung2Min": 60,
          "suchgebiet": { "id": "g", "name": "Gebiet A", "farbe": "#2f6fd6" },
          "halsband": { "id": "HB-7", "name": "Halsband 7", "zeit": "2026-10-29T19:59:50Z", "akku": 72 },
          "handy": { "zeit": "2026-10-29T19:59:00Z", "akku": 64 },
          "pause": null } ],
      "wetter": { "temperatur": 12.5, "wind": 14, "beschreibung": "Bewölkt", "zeit": "2026-10-29T19:30:00Z" } }
    """

    static func lage() throws -> ELLage { try JSONDecoder.server.decode(ELLage.self, from: Data(beispiel.utf8)) }

    @Test func lageAusDerBeschreibung() throws {
        let lage = try Self.lage()
        #expect(lage.einsatz.aktiv)
        #expect(lage.teams.first?.warnstufe == 1)
        #expect(lage.teams.first?.halsband?.akku == 72)
        #expect(lage.wetter?.beschreibung == "Bewölkt")
        let ohne = try JSONDecoder.server.decode(ELLage.self, from: Data(#"{ "mode": "pause", "einsatz": { "aktiv": false }, "teams": [], "wetter": null }"#.utf8))
        #expect(!ohne.einsatz.aktiv && ohne.mode == .pause)
    }

    @Test func ereignisseAusDemStrom() {
        var leser = SSELeser()
        let strom = "event: team\ndata: {\"teamId\":\"t1\"}\n\nevent: warnung\ndata: {\"teamId\":\"t1\",\"stufe\":2,\"text\":\"Team Rex 60 min\"}\n\nevent: einsatz\ndata: {\"aktiv\":false}\n\n"
        let ereignisse = strom.components(separatedBy: "\n").compactMap { leser.zeile($0) }.compactMap(ELEreignis.init)
        #expect(ereignisse == [.team(teamId: "t1"), .warnung(teamId: "t1", stufe: 2, text: "Team Rex 60 min"), .einsatz(aktiv: false)])
    }

    @Test func modusAnOrtungUndNeuLadenBeiTeam() async throws {
        let server = TestELSchnittstelle(try Self.lage())
        var modi: [Modus] = []
        let a = ELAnsicht(schnittstelle: server) { modi.append($0) }
        await a.neuLaden()
        #expect(modi == [.track])
        await a.verarbeiten(.team(teamId: "t1"))
        await a.verarbeiten(.einsatz(aktiv: false))
        #expect(server.abrufe == 3)
    }

    @Test func warnungLoestTonAus() async throws {
        let a = ELAnsicht(schnittstelle: TestELSchnittstelle(try Self.lage())) { _ in }
        var stufen: [Int] = []
        a.beiWarnung = { stufen.append($0) }
        await a.verarbeiten(.warnung(teamId: "t1", stufe: 2, text: "Team Rex 60 min"))
        #expect(stufen == [2])
        #expect(a.warnung?.text == "Team Rex 60 min")
    }

    @Test func http410BeendetDieSitzung() async throws {
        let server = TestELSchnittstelle(try Self.lage())
        server.lageAntwort = .failure(SchnittstellenFehler.beendet)
        var modi: [Modus] = []
        let a = ELAnsicht(schnittstelle: server) { modi.append($0) }
        await a.neuLaden()
        #expect(modi == [.stop])
    }

    @Test func ohneEinsatzPausiertDieOrtung() async throws {
        let quelle = TestQuelle()
        let ortung = TeamOrtung(schnittstelle: TestSchnittstelle(), quelle: quelle, puffer: TestPuffer(),
                                ablauf: Date().addingTimeInterval(3600), beimBeenden: {})
        let ohne = try JSONDecoder.server.decode(ELLage.self, from: Data(#"{ "mode": "pause", "einsatz": { "aktiv": false }, "teams": [] }"#.utf8))
        let a = ELAnsicht(schnittstelle: TestELSchnittstelle(ohne)) { ortung.anwenden(SteuerAntwort(mode: $0), melden: false) }
        await a.neuLaden()
        #expect(quelle.stufe == .grob)
        #expect(ortung.zustand == .pausiert)
    }
}
