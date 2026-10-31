import Foundation
import Testing
@testable import Einsatzueberwachung

/// Antwortet je nach Anfrage (z. B. je Token), ohne Netz. Eigene Klasse, damit parallele Suiten sich nicht stören.
final class TokenStub: URLProtocol, @unchecked Sendable {
    nonisolated(unsafe) static var antwort: (URLRequest) -> (status: Int, text: String) = { _ in (200, "{}") }
    nonisolated(unsafe) static var anfragen: [URLRequest] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.anfragen.append(request)
        let (status, text) = Self.antwort(request)
        let http = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: http, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(text.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    static var session: URLSession {
        let konfiguration = URLSessionConfiguration.ephemeral
        konfiguration.protocolClasses = [TokenStub.self]
        return URLSession(configuration: konfiguration)
    }

    static func token(_ r: URLRequest) -> String? {
        r.value(forHTTPHeaderField: "Authorization")?.replacingOccurrences(of: "Bearer ", with: "")
    }
}

final class TestVerlaengern: ELVerlaengerSchnittstelle, @unchecked Sendable {
    var antwort: Result<ELSitzung, Error>
    private(set) var aufrufe: [String?] = []
    init(_ antwort: Result<ELSitzung, Error>) { self.antwort = antwort }
    func verlaengern(geraet: String?) async throws -> ELSitzung {
        aufrufe.append(geraet)
        return try antwort.get()
    }
}

@MainActor
struct ELVerlaengerungTests {
    static let server = ServerAdresse(URL(string: "https://einsatz.intern")!)!
    let jetzt = Date(timeIntervalSince1970: 1_793_440_000)

    func sitzung(_ token: String, restStunden: Double, bis: Date? = nil) -> ELSitzung {
        ELSitzung(server: Self.server, token: token, expiresAt: jetzt.addingTimeInterval(restStunden * 3600),
                  geraet: "iPad ELW", verlaengerbarBis: bis)
    }

    @Test func nurBeiLaufendemEinsatzUndWenigerAls6Stunden() {
        let zugang = ELZugang(sitzung("alt", restStunden: 7))
        let v = ELVerlaengerung(schnittstelle: TestVerlaengern(.failure(URLError(.timedOut))), zugang: zugang, jetzt: { jetzt }) { _ in }
        #expect(!v.faellig(einsatzLaeuft: true), "noch 7 h")
        zugang.ersetzen(sitzung("alt", restStunden: 5))
        #expect(v.faellig(einsatzLaeuft: true))
        #expect(!v.faellig(einsatzLaeuft: false), "ohne Einsatz nicht")
        zugang.ersetzen(sitzung("alt", restStunden: 5, bis: jetzt.addingTimeInterval(5 * 3600)))
        #expect(!v.faellig(einsatzLaeuft: true), "72 h erreicht: das neue Token gälte nicht länger")
        zugang.ersetzen(sitzung("alt", restStunden: -1))
        #expect(!v.faellig(einsatzLaeuft: true), "abgelaufen: neu anmelden")
    }

    @Test func neuesTokenErsetztDasAlteUndWirdGespeichert() async {
        let zugang = ELZugang(sitzung("alt", restStunden: 5))
        let neu = sitzung("neu", restStunden: 24)
        let server = TestVerlaengern(.success(neu))
        var gespeichert: ELSitzung?
        let v = ELVerlaengerung(schnittstelle: server, zugang: zugang, jetzt: { jetzt }) { gespeichert = $0 }
        await v.pruefen(einsatzLaeuft: true)
        #expect(server.aufrufe == ["iPad ELW"], "Gerätename geht mit")
        #expect(zugang.sitzung.token == "neu")
        #expect(gespeichert == neu)
        #expect(!zugang.verlaengerungLaeuft)
        await v.pruefen(einsatzLaeuft: true)
        #expect(server.aufrufe.count == 1, "neues Token hat 24 h")
    }

    @Test func ablehnungNichtWiederholenUndHinweis() async {
        let zugang = ELZugang(sitzung("alt", restStunden: 5))
        let server = TestVerlaengern(.failure(ELVerlaengerFehler.nichtMoeglich("Kein Einsatz läuft.")))
        let v = ELVerlaengerung(schnittstelle: server, zugang: zugang, jetzt: { jetzt }) { _ in }
        await v.pruefen(einsatzLaeuft: true)
        await v.pruefen(einsatzLaeuft: true)
        #expect(server.aufrufe.count == 1, "409 heißt nicht wiederholen")
        let uhrzeit = zugang.sitzung.expiresAt.formatted(date: .omitted, time: .shortened)
        #expect(v.hinweis == "Anmeldung läuft um \(uhrzeit) ab.")
        #expect(zugang.sitzung.token == "alt")
    }

    @Test func funklochWirdWiederholt() async {
        let zugang = ELZugang(sitzung("alt", restStunden: 5))
        let server = TestVerlaengern(.failure(URLError(.notConnectedToInternet)))
        let v = ELVerlaengerung(schnittstelle: server, zugang: zugang, jetzt: { jetzt }) { _ in }
        await v.pruefen(einsatzLaeuft: true)
        server.antwort = .success(sitzung("neu", restStunden: 24))
        await v.pruefen(einsatzLaeuft: true)
        #expect(server.aufrufe.count == 2)
        #expect(zugang.sitzung.token == "neu" && v.hinweis == nil)
    }
}

/// ELClient mit Token-Wechsel: 410 bzw. `stop` mit dem alten Token ist keine Abmeldung (#141).
@Suite(.serialized)
struct ELTokenWechselTests {
    static let server = ServerAdresse(URL(string: "https://einsatz.intern")!)!
    static let lage = #"{ "mode": "track", "einsatz": { "aktiv": true }, "teams": [] }"#

    func zugang(_ token: String) -> ELZugang {
        ELZugang(ELSitzung(server: Self.server, token: token, expiresAt: Date().addingTimeInterval(3600), geraet: "iPad ELW"))
    }

    func client(_ zugang: ELZugang) -> ELClient {
        TokenStub.anfragen = []
        return ELClient(zugang: zugang, session: TokenStub.session, ereignisSession: TokenStub.session)
    }

    @Test func verlaengernSchicktGeraetUndLiefertNeueSitzung() async throws {
        let z = zugang("e3.alt")
        TokenStub.antwort = { _ in (200, #"{ "apiVersion": 1, "rolle": "el", "token": "e3.neu", "expiresAt": "2026-11-01T10:00:00Z", "anmeldungAt": "2026-10-31T06:00:00Z", "verlaengerbarBis": "2026-11-03T06:00:00Z" }"#) }
        let neu = try await client(z).verlaengern(geraet: "iPad ELW")
        let anfrage = try #require(TokenStub.anfragen.first)
        #expect(anfrage.url?.path() == "/api/el/app/v1/verlaengern" && anfrage.httpMethod == "POST")
        #expect(TokenStub.token(anfrage) == "e3.alt")
        #expect(neu.token == "e3.neu" && neu.geraet == "iPad ELW" && neu.verlaengerbarBis != nil)
        #expect(z.sitzung.token == "e3.alt", "ersetzt wird erst in ELVerlaengerung")

        TokenStub.antwort = { _ in (409, #"{ "error": "Anmeldung mit Passwort nötig." }"#) }
        await #expect(throws: ELVerlaengerFehler.nichtMoeglich("Anmeldung mit Passwort nötig.")) { try await client(z).verlaengern(geraet: nil) }
    }

    @Test func altes410MitNeuemTokenWiederholen() async throws {
        let z = zugang("alt")
        // Verlängerung ist fertig geworden, während die Anfrage mit dem alten Token unterwegs war
        TokenStub.antwort = { r in
            if TokenStub.token(r) == "alt" {
                z.ersetzen(ELSitzung(server: Self.server, token: "neu", expiresAt: Date().addingTimeInterval(86400)))
                return (410, #"{ "mode": "stop" }"#)
            }
            return (200, Self.lage)
        }
        let lage = try await client(z).lage()
        #expect(lage.einsatz.aktiv)
        #expect(TokenStub.anfragen.compactMap(TokenStub.token) == ["alt", "neu"])
    }

    @Test func wartetAufLaufendeVerlaengerung() async throws {
        let z = zugang("alt")
        z.verlaengerungBeginnt()
        TokenStub.antwort = { r in TokenStub.token(r) == "alt" ? (410, #"{ "mode": "stop" }"#) : (200, Self.lage) }
        Task {
            try? await Task.sleep(for: .milliseconds(300))
            z.ersetzen(ELSitzung(server: Self.server, token: "neu", expiresAt: Date().addingTimeInterval(86400)))
            z.verlaengerungEndet()
        }
        let lage = try await client(z).lage()
        #expect(lage.einsatz.aktiv)
    }

    @Test func echtes410BleibtStop() async {
        let z = zugang("alt")
        TokenStub.antwort = { _ in (410, #"{ "mode": "stop" }"#) }
        await #expect(throws: SchnittstellenFehler.beendet) { try await client(z).lage() }
        #expect(TokenStub.anfragen.count == 1, "kein neueres Token: nicht wiederholen")
    }

    @Test func stopImLiveStromMitAltemTokenIstKeineAbmeldung() async throws {
        let z = zugang("alt")
        TokenStub.antwort = { _ in
            z.ersetzen(ELSitzung(server: Self.server, token: "neu", expiresAt: Date().addingTimeInterval(86400)))
            return (200, "event: team\ndata: {\"teamId\":\"t1\"}\n\nevent: stop\ndata: {\"mode\":\"stop\"}\n\n")
        }
        var ereignisse: [ELEreignis] = []
        for try await e in try await client(z).ereignisse() { ereignisse.append(e) }
        #expect(ereignisse == [.team(teamId: "t1")], "Strom endet ohne stop, die Ansicht verbindet sich neu")
    }

    @Test func stopImLiveStromOhneNeuesTokenKommtAn() async throws {
        let z = zugang("alt")
        TokenStub.antwort = { _ in (200, "event: stop\ndata: {\"mode\":\"stop\"}\n\n") }
        var ereignisse: [ELEreignis] = []
        for try await e in try await client(z).ereignisse() { ereignisse.append(e) }
        #expect(ereignisse == [.stop])
    }

    @Test func verbindenMitAltemTokenNachVerlaengerung() async throws {
        let z = zugang("alt")
        TokenStub.antwort = { r in
            if TokenStub.token(r) == "alt" {
                z.ersetzen(ELSitzung(server: Self.server, token: "neu", expiresAt: Date().addingTimeInterval(86400)))
                return (410, #"{ "mode": "stop" }"#)
            }
            return (200, "event: einsatz\ndata: {\"aktiv\":true}\n\n")
        }
        var ereignisse: [ELEreignis] = []
        for try await e in try await client(z).ereignisse() { ereignisse.append(e) }
        #expect(ereignisse == [.einsatz(aktiv: true)])
        #expect(TokenStub.anfragen.compactMap(TokenStub.token) == ["alt", "neu"])
    }
}
