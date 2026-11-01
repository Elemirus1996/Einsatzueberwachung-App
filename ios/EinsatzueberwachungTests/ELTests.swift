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
        return try await ELAnmeldung(server: server, session: StubProtokoll.session).anmelden(passwort: "geheim", geraet: "iPad ELW").sitzung
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
        await #expect(throws: ELAnmeldeFehler.keineELAnmeldung, "öffentlicher Zugang: 404") { try await anmelden(404) }
        await #expect(throws: ELAnmeldeFehler.nameUnbekannt(code: URLError.cannotFindHost.rawValue)) {
            try await anmelden(0, fehler: URLError(.cannotFindHost))
        }
        await #expect(throws: ELAnmeldeFehler.nichtErreichbar(code: URLError.timedOut.rawValue), "ohne VPN") {
            try await anmelden(0, fehler: URLError(.timedOut))
        }
        await #expect(throws: ELAnmeldeFehler.zertifikat(code: URLError.serverCertificateUntrusted.rawValue), "selbst ausgestellt") {
            try await anmelden(0, fehler: URLError(.serverCertificateUntrusted))
        }
    }

    @Test func wireGuardAdresseOhneAnfrage() async {
        let vpn = ServerAdresse(URL(string: "https://dein-einsatz.mywire.org:51820")!)!
        StubProtokoll.letzteAnfrage = nil
        await #expect(throws: ELAnmeldeFehler.vpnAdresse) {
            try await ELAnmeldung(server: vpn, session: StubProtokoll.session).anmelden(passwort: "geheim", geraet: nil)
        }
        #expect(StubProtokoll.letzteAnfrage == nil)
    }

    @Test func meldungen() {
        #expect(ELAnmeldeFehler.vpnAdresse.meldung.contains("WireGuard-VPN"))
        #expect(ELAnmeldeFehler.zertifikat(code: -1202).meldung.hasSuffix("(Fehler -1202)"))
        #expect(ELAnmeldeFehler.keineELAnmeldung.meldung.contains("öffentliche Adresse"))
        #expect(ELAnmeldeFehler.server(status: 502).meldung.contains("HTTP 502"))
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

    /// Entscheidung Paul (29.10.2026): Einsatzleitung ohne Einsatz → Ortung ganz aus, keine Grob-Ortung, kein Takt.
    /// Ein Einsatz (Live-Ereignis bzw. /lage im Vordergrund) startet die feine Ortung.
    @Test func ohneEinsatzIstDieOrtungGanzAus() async throws {
        let quelle = TestQuelle()
        let server = TestSchnittstelle()
        let ortung = TeamOrtung(schnittstelle: server, quelle: quelle, puffer: TestPuffer(),
                                ablauf: Date().addingTimeInterval(3600), pauseOhneOrtung: true, beimBeenden: {})
        let ohne = try JSONDecoder.server.decode(ELLage.self, from: Data(#"{ "mode": "pause", "einsatz": { "aktiv": false }, "teams": [] }"#.utf8))
        let elServer = TestELSchnittstelle(ohne)
        let a = ELAnsicht(schnittstelle: elServer) { ortung.anwenden(SteuerAntwort(mode: $0), melden: false) }
        ortung.anwenden(SteuerAntwort(mode: .track))
        await a.neuLaden()
        #expect(quelle.stufe == nil, "kein GPS und keine Grob-Ortung")
        #expect(ortung.zustand == .pausiert)
        try await Task.sleep(for: .seconds(6))
        #expect(server.statusAbfragen == 0, "im Hintergrund läuft nichts")

        elServer.lageAntwort = .success(try Self.lage())   // Einsatz gestartet
        await a.verarbeiten(.einsatz(aktiv: true))
        #expect(quelle.stufe == .fein)
    }
}

final class TestELAktionen: ELAktionsSchnittstelle, @unchecked Sendable {
    var fehler: Error?
    private(set) var ausgefuehrt: [ELAktion] = []
    var notizListe: [ELNotiz] = []
    var antwort = Data(#"{"doppelt":false}"#.utf8)
    func ausfuehren(_ aktion: ELAktion) async throws -> Data {
        ausgefuehrt.append(aktion)
        if let fehler { throw fehler }
        return antwort
    }
    func vorlage() async throws -> ELVorlage { try JSONDecoder.server.decode(ELVorlage.self, from: Data(ELEinsatzTests.vorlageJSON.utf8)) }
    func notizen(vor: String?) async throws -> ELNotizSeite { ELNotizSeite(notizen: notizListe, weitere: false) }
    func halsbaender() async throws -> ELHalsbaender {
        try JSONDecoder.server.decode(ELHalsbaender.self, from: Data(#"[ { "id": "HB-1", "name": "Halsband 1", "akku": 80 } ]"#.utf8))
    }
    func ergebnisse() async throws -> [ELErgebnis] {
        try JSONDecoder.server.decode([ELErgebnis].self, from: Data(#"[ { "wert": "Uebung abgeschlossen", "anzeige": "Übung abgeschlossen" } ]"#.utf8))
    }
}

@MainActor
struct ELBetriebTests {
    let speicher = UserDefaults(suiteName: "elbetrieb-\(UUID().uuidString)")!
    let server = TestELAktionen()

    func betrieb(_ stop: @escaping () -> Void = {}) -> ELBetrieb {
        ELBetrieb(schnittstelle: server, speicher: speicher, beiStop: stop)
    }

    @Test func aktionGehtRausUndLaedtLage() async {
        let b = betrieb()
        var nachher = 0
        b.nachAktion = { nachher += 1 }
        await b.ausfuehren(.teamStart("t1", name: "Team Rex", halsbandId: "HB-1"))
        #expect(server.ausgefuehrt.map(\.pfad) == ["teams/t1/start"])
        #expect(server.ausgefuehrt[0].inhalt["halsbandId"] == .text("HB-1"))
        #expect(b.offen.isEmpty)
        #expect(nachher == 1)
        #expect(b.hinweis?.fehler == false)
    }

    @Test func ablehnungZeigtTextDesServers() async {
        let b = betrieb()
        server.fehler = ELAktionsFehler.abgelehnt("Halsband vergeben")
        await b.ausfuehren(.teamStart("t1", name: "Team Rex", halsbandId: "HB-1"))
        #expect(b.hinweis?.text == "Halsband vergeben")
        #expect(b.hinweis?.fehler == true)
        #expect(b.offen.isEmpty, "abgelehnt wird nicht wiederholt")
    }

    @Test func funklochWiederholtMitGleicherId() async {
        let b = betrieb()
        server.fehler = URLError(.notConnectedToInternet)
        await b.ausfuehren(.funk("Team Rex bitte melden", teamId: "t1", antwortAuf: nil))
        #expect(b.offen.count == 1)
        let neu = betrieb()   // App neu gestartet
        #expect(neu.offen.count == 1)
        server.fehler = nil
        await neu.senden()
        #expect(neu.offen.isEmpty)
        #expect(server.ausgefuehrt[0].id == server.ausgefuehrt[1].id)
        neu.vergessen()
        #expect(betrieb().offen.isEmpty)
    }

    @Test func http410IstStop() async {
        var gestoppt = false
        let b = betrieb { gestoppt = true }
        server.fehler = SchnittstellenFehler.beendet
        await b.ausfuehren(.teamPause("t1", name: "Team Rex"))
        #expect(gestoppt)
    }

    @Test func koerperDerAktionen() throws {
        let funk = ELAktion.funk("Verstanden", teamId: nil, antwortAuf: "f1")
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(funk.inhalt)) as? [String: Any]
        #expect(json?["text"] as? String == "Verstanden")
        #expect(json?["antwortAuf"] as? String == "f1")
        #expect(json?["teamId"] is NSNull)
        #expect(ELAktion.notizLoeschen("n7").pfad == "notizen/n7/loeschen")
        #expect(ELAktion.mitteilung("t1", name: "Team Rex", text: "Bitte zurück").inhalt["text"] == .text("Bitte zurück"))
    }

    @Test func notizenUndHalsbaenderDekodieren() throws {
        let notizen = try JSONDecoder.server.decode([ELNotiz].self, from: Data(#"""
            [ { "id": "f1", "art": "funk", "text": "Team Rex im Gebiet", "zeit": "2026-10-29T20:00:00Z", "von": "Einsatzleitung", "teamId": "t1",
                "antworten": [ { "id": "a1", "text": "Verstanden", "zeit": "2026-10-29T20:01:00Z", "von": "Team Rex" } ] },
              { "id": "n1", "art": "notiz", "text": "Wetter wird schlechter", "zeit": "2026-10-29T19:50:00Z", "von": null, "teamId": null } ]
            """#.utf8))
        #expect(notizen.first?.antworten?.first?.text == "Verstanden")
        let seite = try JSONDecoder.server.decode(ELNotizSeite.self, from: Data(#"{ "notizen": [ { "id": "n1", "art": "notiz", "text": "x", "zeit": "2026-10-29T19:50:00Z", "loeschbar": true }, { "id": "f1", "art": "funk", "text": "y", "zeit": "2026-10-29T19:49:00Z", "loeschbar": false } ], "weitere": true }"#.utf8))
        #expect(seite.weitere)
        #expect(seite.notizen.map(\.loeschbar) == [true, false])
        let mitZuletzt = try JSONDecoder.server.decode(ELHalsbaender.self, from: Data(#"{ "frei": [ { "id": "HB-2" } ], "zuletzt": [ { "teamId": "t1", "halsbandId": "HB-2" } ] }"#.utf8))
        #expect(mitZuletzt.frei.map(\.id) == ["HB-2"])
        #expect(mitZuletzt.zuletzt?.first?.halsbandId == "HB-2")
    }
}

final class TestELKarte: ELKartenSchnittstelle, @unchecked Sendable {
    var karteAntwort: ELKarte
    var tracksVoll: ELTracks
    var tracksNeu = ELTracks(serverZeit: nil, laufend: [], abgeschlossen: nil)
    private(set) var seitAbrufe: [Int64?] = []
    init(karte: ELKarte, tracks: ELTracks) {
        karteAntwort = karte
        tracksVoll = tracks
    }
    func karte() async throws -> ELKarte { karteAntwort }
    func tracks(seit: Int64?) async throws -> ELTracks {
        seitAbrufe.append(seit)
        return seit == nil ? tracksVoll : tracksNeu
    }
}

@MainActor
struct ELKarteTests {
    static func karte() throws -> ELKarte {
        try JSONDecoder.server.decode(ELKarte.self, from: Data(#"""
            { "suchgebiete": [ { "id": "g1", "name": "Gebiet A", "farbe": "#2f6fd6", "teamId": "t1", "abdeckungProzent": 42.5,
                                 "punkte": [ { "lat": 49.31, "lng": 8.43 }, { "lat": 49.32, "lng": 8.44 }, { "lat": 49.31, "lng": 8.45 } ] } ],
              "positionen": [ { "art": "halsband", "id": "HB-7", "name": "Halsband 7", "teamId": "t1", "lat": 49.312, "lng": 8.431, "zeit": "2026-10-29T20:00:00Z", "akku": 72 },
                              { "art": "el", "id": "iPad ELW", "teamId": null, "lat": 49.30, "lng": 8.40, "zeit": "2026-10-29T20:00:00Z" } ] }
            """#.utf8))
    }

    static func tracks() throws -> ELTracks {
        try JSONDecoder.server.decode(ELTracks.self, from: Data(#"""
            { "serverZeit": 1793300000000,
              "laufend": [ { "teamId": "t1", "hund": [ { "lat": 49.31, "lng": 8.43, "t": 1793299000000 } ], "mensch": [] } ],
              "abgeschlossen": [ { "sucheId": "s1", "teamId": "t1", "tracks": [ { "art": "hund", "farbe": "#d32f2f", "punkte": [] } ] } ] }
            """#.utf8))
    }

    @Test func karteUndTracksAusDerBeschreibung() throws {
        let k = try Self.karte()
        #expect(k.suchgebiete.first?.abdeckungProzent == 42.5)
        #expect(k.positionen.map(\.art) == ["halsband", "el"])
        let t = try Self.tracks()
        #expect(t.letzterZeitpunkt == 1_793_299_000_000)
        #expect(t.abgeschlossen?.first?.teamId == "t1")
    }

    @Test func karteNachServer142() throws {
        // abdeckungProzent null, abgeschlossenes Gebiet, ELW ohne zeit und akku
        let k = try JSONDecoder.server.decode(ELKarte.self, from: Data(#"""
            { "suchgebiete": [ { "id": "g2", "name": "Gebiet B", "farbe": "#2f6fd6", "teamId": null, "abgeschlossen": true, "abdeckungProzent": null,
                                 "punkte": [ { "lat": 49.31, "lng": 8.43 }, { "lat": 49.32, "lng": 8.44 }, { "lat": 49.31, "lng": 8.45 } ] } ],
              "positionen": [ { "art": "elw", "id": "elw", "name": "ELW", "teamId": null, "lat": 49.30, "lng": 8.40, "zeit": null, "akku": null } ] }
            """#.utf8))
        #expect(k.suchgebiete.first?.abdeckungProzent == nil)
        #expect(k.suchgebiete.first?.abgeschlossen == true)
        #expect(k.positionen.first?.art == "elw")
        #expect(k.positionen.first?.zeit == nil && k.positionen.first?.akku == nil)
    }

    @Test func livePositionBewegtMarker() async throws {
        let a = ELKartenAnsicht(schnittstelle: TestELKarte(karte: try Self.karte(), tracks: try Self.tracks()))
        await a.laden(voll: true)
        a.position(ELPosition(art: "halsband", id: "HB-7", teamId: "t1", lat: 49.4, lng: 8.5))
        a.position(ELPosition(art: "handy", id: "t1", teamId: "t1", lat: 49.35, lng: 8.45))
        #expect(a.karte?.positionen.count == 3)
        #expect(a.karte?.positionen.first?.lat == 49.4)
        #expect(a.karte?.positionen.first?.name == "Halsband 7", "Name bleibt, wenn das Ereignis keinen trägt")
    }

    @Test func tracksNachladenMitSeit() async throws {
        let server = TestELKarte(karte: try Self.karte(), tracks: try Self.tracks())
        server.tracksNeu = ELTracks(serverZeit: nil, laufend: [
            .init(teamId: "t1", hund: [.init(lat: 49.32, lng: 8.44, t: 1_793_299_500_000)], mensch: []),
            .init(teamId: "t2", hund: [], mensch: [.init(lat: 49.2, lng: 8.3, t: 1_793_299_600_000)]),
        ], abgeschlossen: nil)
        let a = ELKartenAnsicht(schnittstelle: server)
        await a.laden(voll: true)
        await a.laden(voll: false)
        #expect(server.seitAbrufe == [nil, 1_793_299_000_000])
        #expect(a.tracks?.laufend.first?.hund.count == 2)
        #expect(a.tracks?.laufend.count == 2)
    }

    @Test func positionsEreignisAusDemStrom() {
        var leser = SSELeser()
        let e = "event: position\ndata: {\"art\":\"handy\",\"id\":\"t1\",\"teamId\":\"t1\",\"lat\":49.3,\"lng\":8.4,\"zeit\":\"2026-10-29T20:00:00Z\",\"akku\":50}\n\n"
            .components(separatedBy: "\n").compactMap { leser.zeile($0) }.compactMap(ELEreignis.init)
        guard case .position(let p)? = e.first else { Issue.record("kein Positionsereignis"); return }
        #expect(p.art == "handy" && p.akku == 50)
    }
}


struct ELEinsatzTests {
    static let vorlageJSON = #"""
    { "aktiv": false, "szenarien": ["Mantrailer", "Flaeche", "Truemmer", "Sonstige"],
      "divera": [ { "id": 4711, "titel": "VU Person", "ort": "Testwald 1", "lat": 49.3, "lng": 8.4, "zeit": "2026-10-31T06:00:00Z" } ],
      "einsatzleiter": ["Anna Berg"], "fuehrungsassistenten": ["Ben Kurz"],
      "ergebnisse": [ { "wert": "Uebung abgeschlossen", "anzeige": "Übung abgeschlossen" } ],
      "kartenarten": [ { "wert": "Streets", "anzeige": "Straßenkarte" }, { "wert": "Topographic", "anzeige": "Topografisch" } ] }
    """#

    @Test func startKoerperMitKoordinate() throws {
        var d = ELEinsatzStart()
        d.stichwort = "VP"; d.ort = "Testwald"; d.szenario = .flaeche; d.uebung = true
        d.einsatzleiter = "Paul"; d.fuehrungsassistent = "Eric"; d.lat = 49.3; d.lng = 8.4
        let a = ELAktion.einsatzStart(d)
        #expect(a.pfad == "einsatz/start")
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(a.inhalt)) as? [String: Any]
        #expect(json?["szenario"] as? String == "Flaeche")
        #expect(json?["uebung"] as? Bool == true)
        #expect(json?["lat"] as? Double == 49.3)
        #expect(json?["diveraAlarmId"] == nil)
        #expect(!ELEinsatzStart().vollstaendig, "Ort und Szenario sind Pflicht")
    }

    @Test func startPflichtfelder() {
        var d = ELEinsatzStart()
        d.ort = "Testwald"
        #expect(!d.vollstaendig, "Szenario Unbestimmt reicht nicht")
        d.szenario = .mantrailer
        #expect(d.vollstaendig)
        d.ort = "  "
        #expect(!d.vollstaendig, "ohne Ort und ohne Alarm")
        d.diveraAlarmId = 4711
        #expect(d.vollstaendig, "Ort kommt aus dem Divera-Alarm")
        d.szenario = .unbestimmt
        #expect(!d.vollstaendig, "Szenario auch mit Alarm Pflicht (Server prüft es immer)")
    }

    @Test func startMitDiveraLaesstLeereFelderWeg() throws {
        var d = ELEinsatzStart()
        d.szenario = .mantrailer
        d.diveraAlarmId = 4711
        d.einsatzleiter = "Anna Berg"
        let roh = try JSONEncoder().encode(ELAktion.einsatzStart(d).inhalt)
        let json = try JSONSerialization.jsonObject(with: roh) as? [String: Any]
        #expect(json?["ort"] == nil && json?["stichwort"] == nil && json?["fuehrungsassistent"] == nil,
                "leere Angaben überschreiben den Alarm nicht")
        #expect(json?["einsatzleiter"] as? String == "Anna Berg")
        #expect(String(decoding: roh, as: UTF8.self).contains(#""diveraAlarmId":4711"#), "Ganzzahl für int? im Server")
    }

    @Test func vorlageDekodieren() throws {
        let v = try JSONDecoder.server.decode(ELVorlage.self, from: Data(Self.vorlageJSON.utf8))
        #expect(v.divera.first?.id == 4711 && v.divera.first?.ort == "Testwald 1")
        #expect(v.szenarioWahl == [.mantrailer, .flaeche, .truemmer, .sonstige])
        #expect(v.einsatzleiter == ["Anna Berg"] && v.fuehrungsassistenten == ["Ben Kurz"])
        #expect(v.ergebnisse.first?.wert == "Uebung abgeschlossen")
        #expect(v.kartenWahl.map(\.wert) == ["Streets", "Topographic"])
        // Ohne Divera und ohne Listen: Rückfall
        let leer = try JSONDecoder.server.decode(ELVorlage.self, from: Data(#"{ "aktiv": true, "divera": [] }"#.utf8))
        #expect(leer.aktiv && leer.divera.isEmpty)
        #expect(!leer.szenarioWahl.contains(.unbestimmt) && leer.szenarioWahl.count == 4)
        #expect(leer.kartenWahl.count == 3)
    }

    @Test func beendenKoerperMitPdf() throws {
        let a = ELAktion.einsatzBeenden(ergebnis: "Uebung abgeschlossen", mitTracks: false)
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(a.inhalt)) as? [String: Any]
        #expect(json?["ressourcenGeprueft"] as? Bool == true)
        #expect(json?["endzeit"] == nil && json?["bemerkungen"] == nil, "ohne Angabe = jetzt")
        let pdf = json?["pdf"] as? [String: Any]
        #expect(pdf?["tracks"] as? Bool == false)
        #expect(pdf?["kartenart"] as? String == "Streets", "Standard")
        let topo = try JSONSerialization.jsonObject(with: JSONEncoder().encode(
            ELAktion.einsatzBeenden(ergebnis: "x", mitTracks: true, kartenart: "Topographic").inhalt)) as? [String: Any]
        #expect((topo?["pdf"] as? [String: Any])?["kartenart"] as? String == "Topographic")
        #expect(ELKartenart.allCases.map(\.rawValue) == ["Streets", "Topographic", "Satellite"])
        #expect(pdf?["teamIds"] is NSNull)
        // Warteschlange speichert und liest verschachtelte Werte
        let wieder = try JSONDecoder().decode(ELAktion.self, from: JSONEncoder().encode(a))
        #expect(wieder == a)
    }

    @Test func beendenMitEndzeitTeamsUndBemerkung() throws {
        let ende = Date(timeIntervalSince1970: 1_793_468_700.4)   // 2026-10-31T17:45:00Z
        let a = ELAktion.einsatzBeenden(ergebnis: "x", mitTracks: true, teamIds: ["t1", "t2"], endzeit: ende, bemerkungen: " Hund gefunden ")
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(a.inhalt)) as? [String: Any]
        #expect(json?["endzeit"] as? String == "2026-10-31T17:45:00Z", "UTC mit Z, ohne Bruchteile")
        #expect(json?["bemerkungen"] as? String == "Hund gefunden")
        #expect((json?["pdf"] as? [String: Any])?["teamIds"] as? [String] == ["t1", "t2"])
        let wieder = try JSONDecoder().decode(ELAktion.self, from: JSONEncoder().encode(a))
        #expect(wieder == a, "Liste übersteht die Warteschlange")
    }

    @Test func beendenAntwortDekodieren() throws {
        let ok = try JSONDecoder.server.decode(ELEinsatzEnde.self, from: Data(
            #"{ "doppelt": false, "archivId": "a1", "einsatzNummer": "2026-017", "pdf": { "erstellt": true, "fehler": null } }"#.utf8))
        #expect(ok.archivId == "a1" && ok.einsatzNummer == "2026-017" && ok.pdf?.erstellt == true)
        #expect(ok.meldung.contains("2026-017") && ok.meldung.contains("Archiv"))
        let fehler = try JSONDecoder.server.decode(ELEinsatzEnde.self, from: Data(
            #"{ "archivId": 12, "einsatzNummer": "2026-018", "pdf": { "erstellt": false, "fehler": "Karte nicht erreichbar" } }"#.utf8))
        #expect(fehler.archivId == "12")
        #expect(fehler.meldung.contains("Karte nicht erreichbar"))
        // Wiederholung mit derselben id: nur { doppelt: true }
        let doppelt = try JSONDecoder.server.decode(ELEinsatzEnde.self, from: Data(#"{ "doppelt": true }"#.utf8))
        #expect(doppelt.pdf == nil && doppelt.meldung == "Einsatz beendet und archiviert.")
    }
}

@MainActor
struct ELEinsatzBetriebTests {
    @Test func beendenZeigtPdfFehler() async {
        let server = TestELAktionen()
        server.antwort = Data(#"{ "doppelt": false, "archivId": "a1", "einsatzNummer": "7", "pdf": { "erstellt": false, "fehler": "Zeitüberschreitung" } }"#.utf8)
        let b = ELBetrieb(schnittstelle: server, speicher: UserDefaults(suiteName: "ende-\(UUID().uuidString)")!, beiStop: {})
        await b.ausfuehren(.einsatzBeenden(ergebnis: "x", mitTracks: true))
        #expect(b.einsatzEnde?.pdf?.fehler == "Zeitüberschreitung")
        #expect(b.offen.isEmpty)
    }

    @Test func startAbgelehntZeigtServertext() async {
        let server = TestELAktionen()
        server.fehler = ELAktionsFehler.abgelehnt("Divera-Alarm nicht gefunden.")
        let b = ELBetrieb(schnittstelle: server, speicher: UserDefaults(suiteName: "start-\(UUID().uuidString)")!, beiStop: {})
        var d = ELEinsatzStart(); d.szenario = .flaeche; d.diveraAlarmId = 1
        await b.ausfuehren(.einsatzStart(d))
        #expect(b.hinweis?.text == "Divera-Alarm nicht gefunden." && b.hinweis?.fehler == true)
        #expect(b.offen.isEmpty, "abgelehnt: nicht erneut senden")
        #expect(b.einsatzEnde == nil)
    }
}

final class TestELVermisste: ELVermissteSchnittstelle, @unchecked Sendable {
    var stand: ELVermisste
    var fehler: Error?
    private(set) var gespeichert: [(profilId: String?, version: String?, felder: [String: ELWert])] = []
    init(_ stand: ELVermisste) { self.stand = stand }
    func vermisste() async throws -> ELVermisste { stand }
    func vermissteSpeichern(id: String, profilId: String?, version: String?, felder: [String: ELWert]) async throws -> (profilId: String, version: String) {
        gespeichert.append((profilId, version, felder))
        if let fehler { throw fehler }
        return (profilId ?? "neu-1", "v2")
    }
}

@MainActor
struct ELVermissteTests {
    static let beispiel = #"""
    { "szenario": "Mantrailer", "mehrereErlaubt": false,
      "profile": [ { "profilId": "p1", "version": "v1", "vollerName": "Erna Muster", "zuletztAktualisiert": "2026-10-29T08:12:00Z",
        "vorname": "Erna", "nachname": "Muster", "alter": "82", "geburtsdatum": "03.04.1944",
        "beschreibung": "165 cm", "kleidung": "rote Jacke", "besonderheiten": "Hörgerät",
        "zuletztGesehenOrt": "Seniorenheim", "zuletztGesehenZeit": "gestern ca. 18:30", "zuletztGesehenVon": "Pflegekraft",
        "vorerkrankungen": "Demenz", "medikamente": "", "orientierung": "Eingeschraenkt", "mobilitaet": "Rollator",
        "suizidrisiko": "Nein", "bewaffnet": "Unbekannt", "polizeiKontaktName": "PHK Meier", "polizeiDienstnummer": "", "polizeiTelefon": "",
        "polizeiVermisstenmeldungAufgenommen": true, "polizeiKoordinationBesprochen": false, "polizeiSuchabschnittAbgestimmt": false,
        "polizeiRueckmeldepflichtVereinbart": false, "polizeiDatenschutzGeklaert": false,
        "bosEinheit": "", "bosZugfuehrer": "", "bosFunkrufname": "", "bosAufgabenteilung": "",
        "bosAbschnittAbgestimmt": false, "bosRessourcenBesprochen": false,
        "checkliste": { "name": "Mantrailer", "punkte": [
          { "id": "c1", "text": "Geruchsstück gesichert", "typ": "jaNein", "pflicht": true, "auswahl": [], "wert": "true", "erledigt": true },
          { "id": "c2", "text": "Art des Geruchsstücks", "typ": "auswahl", "pflicht": false, "auswahl": ["Kleidung", "Bettwäsche"], "wert": null, "erledigt": false } ] } } ] }
    """#

    static func stand() throws -> ELVermisste { try JSONDecoder.server.decode(ELVermisste.self, from: Data(beispiel.utf8)) }

    @Test func profilAusDerBeschreibung() throws {
        let p = try #require(try Self.stand().profile.first)
        #expect(p.text("vorname") == "Erna")
        #expect(p.text("zuletztGesehenZeit") == "gestern ca. 18:30", "Freitext, keine Zeit")
        #expect(p.schalter["polizeiVermisstenmeldungAufgenommen"] == true)
        #expect(p.auswahlen["orientierung"] == "Eingeschraenkt")
        #expect(p.checkliste?.punkte.map(\.typ) == ["jaNein", "auswahl"])
    }

    @Test func nurGeaenderteFelderGehenRaus() async throws {
        let server = TestELVermisste(try Self.stand())
        let a = ELVermissteAnsicht(schnittstelle: server)
        let original = try #require(try Self.stand().profile.first)
        var neu = original
        neu.texte["kleidung"] = "blaue Jacke"
        neu.texte["besonderheiten"] = ""          // leeren
        neu.auswahlen["mobilitaet"] = "Rollstuhl"
        neu.schalter["polizeiKoordinationBesprochen"] = true
        let gespeichert = try await a.speichern(neu, original: original)
        let s = try #require(server.gespeichert.first)
        #expect(s.profilId == "p1" && s.version == "v1")
        #expect(s.felder == ["kleidung": .text("blaue Jacke"), "besonderheiten": .text(""),
                             "mobilitaet": .text("Rollstuhl"), "polizeiKoordinationBesprochen": .wahr(true)])
        #expect(gespeichert.version == "v2")
    }

    @Test func neuesProfilOhneKennungUndVersion() async throws {
        let server = TestELVermisste(try Self.stand())
        var neu = ELVermisstenprofil()
        neu.texte["vorname"] = "Max"
        _ = try await ELVermissteAnsicht(schnittstelle: server).speichern(neu, original: ELVermisstenprofil())
        #expect(server.gespeichert.first?.profilId == nil && server.gespeichert.first?.version == nil)
        #expect(server.gespeichert.first?.felder == ["vorname": .text("Max")])
    }

    @Test func konfliktLiefertNeuenStand() async throws {
        let server = TestELVermisste(try Self.stand())
        var aktuell = try #require(try Self.stand().profile.first)
        aktuell.version = "v9"
        server.fehler = ELVermissteFehler.geaendert(aktuell)
        let original = try #require(try Self.stand().profile.first)
        var neu = original
        neu.texte["kleidung"] = "x"
        await #expect(throws: ELVermissteFehler.geaendert(aktuell)) { _ = try await ELVermissteAnsicht(schnittstelle: server).speichern(neu, original: original) }
    }

    @Test func checklisteUndSzenarioAlsAktion() throws {
        let p = try #require(try Self.stand().profile.first)
        let punkt = try #require(p.checkliste?.punkte.last)
        let a = ELAktion.checkliste("p1", punkt: punkt, wert: "Kleidung")
        #expect(a.pfad == "vermisste/p1/checkliste")
        #expect(a.inhalt == ["punktId": .text("c2"), "wert": .text("Kleidung")])
        #expect(ELAktion.szenario(.flaeche).inhalt == ["szenario": .text("Flaeche")])
    }
}
