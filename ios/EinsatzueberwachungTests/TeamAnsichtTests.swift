import Foundation
import Testing
@testable import Einsatzueberwachung

struct SSELeserTests {
    func lesen(_ text: String) -> [SSELeser.Ereignis] {
        var leser = SSELeser()
        return text.components(separatedBy: "\n").compactMap { leser.zeile($0) }
    }

    @Test func ereignisseAusDerBeschreibung() {
        let strom = """
        retry: 5000

        event: halsband
        data: {"lat":49.312,"lng":8.431,"zeit":"2026-10-28T09:14:48Z","akku":72}

        : ping

        event: lage
        data: {}

        event: stop
        data: {}

        """
        let ereignisse = lesen(strom + "\nevent: verdraengt\ndata: {\"wiederholenNachSec\":60}\n\n")
        #expect(ereignisse.map(\.name) == ["halsband", "lage", "stop", "verdraengt"])
        #expect(TeamEreignis(ereignisse[3]) == .verdraengt(wiederholenNachSec: 60))
        #expect(TeamEreignis(ereignisse[0]) == .halsband(Halsband(
            lat: 49.312, lng: 8.431, zeit: Date(timeIntervalSince1970: 1_793_178_888), akku: 72)))
        #expect(TeamEreignis(ereignisse[1]) == .lage)
        #expect(TeamEreignis(ereignisse[2]) == .stop)
    }

    @Test func mehrzeiligeDatenUndUnbekanntes() {
        let ereignisse = lesen("event: x\ndata: a\ndata: b\n\nevent: mitteilung\ndata: kaputt\n\n")
        #expect(ereignisse == [.init(name: "x", daten: "a\nb"), .init(name: "mitteilung", daten: "kaputt")])
        #expect(TeamEreignis(ereignisse[0]) == nil)
        #expect(TeamEreignis(ereignisse[1]) == nil)
    }
}

struct LageModellTests {
    @Test func lageAusDerBeschreibung() throws {
        let json = """
        { "mode": "track", "serverZeit": "2026-10-28T09:15:00Z",
          "einsatz": { "text": "Vermisste Person · Testwald", "uebung": false, "staffel": "RHS Musterstadt" },
          "team": { "teamId": "t1", "teamName": "Team Rex", "dogName": "Rex", "hundefuehrerName": "Anna", "helfer": ["Ben"] },
          "suche": { "status": "laeuft", "seit": "2026-10-28T08:40:00Z", "dauerSec": 2100 },
          "suchgebiet": { "id": "g", "name": "Gebiet A", "farbe": "#2f6fd6", "punkte": [ { "lat": 49.31, "lng": 8.43 } ] },
          "halsband": { "id": "HB-7", "name": "Halsband 7", "lat": 49.312, "lng": 8.431, "zeit": "2026-10-28T09:14:48Z", "akku": 72 },
          "mitteilungen": [ { "id": "m1", "text": "Bitte Funkkontakt", "zeit": "2026-10-28T09:10:00Z" } ] }
        """
        let lage = try JSONDecoder.server.decode(TeamLage.self, from: Data(json.utf8))
        #expect(lage.suche.status == .laeuft)
        #expect(lage.team.helfer == ["Ben"])
        #expect(lage.mitteilungen.first?.text == "Bitte Funkkontakt")
        #expect(lage.halsband?.akku == 72)
    }

    @Test func lageOhneGebietUndHalsband() throws {
        let json = """
        { "einsatz": { "text": "Übung" , "uebung": true }, "team": { "teamId": "t1", "teamName": "Team Rex" },
          "suche": { "status": "bereit", "seit": null, "dauerSec": 0 }, "suchgebiet": null, "halsband": null, "mitteilungen": [] }
        """
        let lage = try JSONDecoder.server.decode(TeamLage.self, from: Data(json.utf8))
        #expect(lage.suchgebiet == nil && lage.halsband == nil && lage.einsatz.uebung == true)
    }

    @Test func tracksAusDerBeschreibung() throws {
        let json = """
        { "serverZeit": 1761642900000,
          "laufend": { "hund": [ { "lat": 49.31, "lng": 8.43, "t": 1761641000000 } ],
                       "mensch": [ { "lat": 49.30, "lng": 8.42, "t": 1761641005000 } ] },
          "abgeschlossen": [ { "sucheId": "s1", "beginn": "2026-10-28T08:00:00Z", "ende": "2026-10-28T08:30:00Z",
            "tracks": [ { "art": "hund", "farbe": "#d32f2f", "punkte": [ { "lat": 49.3, "lng": 8.4, "t": 1761630000000 } ] } ] } ] }
        """
        let tracks = try JSONDecoder.server.decode(TeamTracks.self, from: Data(json.utf8))
        #expect(TeamAnsicht.letzterZeitpunkt(tracks) == 1_761_641_005_000)
        #expect(tracks.abgeschlossen?.first?.tracks.first?.art == "hund")
    }
}

final class TestAnsichtSchnittstelle: TeamAnsichtSchnittstelle, @unchecked Sendable {
    var lageAntwort: Result<TeamLage, SchnittstellenFehler>
    var meldungFehler: SchnittstellenFehler?
    private(set) var meldungen: [(id: String, code: Statusmeldung.Code)] = []
    private(set) var trackAbrufe: [Int64?] = []

    init(lage: TeamLage) { lageAntwort = .success(lage) }

    func lage() async throws -> TeamLage { try lageAntwort.get() }

    func tracks(seit: Int64?) async throws -> TeamTracks {
        trackAbrufe.append(seit)
        return TeamTracks(laufend: .init(hund: [], mensch: []))
    }

    func statusmeldung(id: String, code: Statusmeldung.Code) async throws {
        meldungen.append((id, code))
        if let meldungFehler { throw meldungFehler }
    }

    /// `true`: Live-Verbindung bleibt offen, liefert aber nichts (verpasstes `lage`-Ereignis)
    var offenerStrom = false
    private var offen: AsyncThrowingStream<TeamEreignis, Error>.Continuation?

    func ereignisse() async throws -> AsyncThrowingStream<TeamEreignis, Error> {
        AsyncThrowingStream { fortsetzung in
            if offenerStrom { offen = fortsetzung } else { fortsetzung.finish() }
        }
    }
}

@MainActor
struct TeamAnsichtTests {
    static let lage = TeamLage(
        mode: .pause, serverZeit: Date(timeIntervalSince1970: 1_800_000_100),
        einsatz: .init(text: "Übung", uebung: true, staffel: nil),
        team: .init(teamId: "t1", teamName: "Team Rex"),
        suche: .init(status: .bereit), suchgebiet: nil,
        halsband: Halsband(lat: 49, lng: 8, zeit: Date(timeIntervalSince1970: 1_800_000_090)),
        mitteilungen: [Mitteilung(id: "m1", text: "Hallo", zeit: Date(timeIntervalSince1970: 1_800_000_000))])

    let speicher = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
    let server = TestAnsichtSchnittstelle(lage: lage)
    let jetzt = Date(timeIntervalSince1970: 1_800_000_000)

    func ansicht(lageTakt: Duration = .seconds(60), _ modi: @escaping (Modus) -> Void = { _ in }) -> TeamAnsicht {
        let t = jetzt
        return TeamAnsicht(schnittstelle: server, teamId: "t1", speicher: speicher, jetzt: { t },
                           lageTakt: lageTakt, beiSteuerung: modi)
    }

    /// Fehler aus dem iPhone-Test: Live-Verbindung steht, aber das `lage`-Ereignis zur neuen Suche kommt nicht.
    /// Die App muss die Lage trotzdem regelmäßig holen und die Ortung wieder starten.
    @Test func verpasstesLageEreignisWirdDurchTaktAufgefangen() async throws {
        var modi: [Modus] = []
        server.offenerStrom = true
        let a = ansicht(lageTakt: .milliseconds(200)) { modi.append($0) }
        a.starten()
        try await Task.sleep(for: .milliseconds(100))
        #expect(a.live)
        #expect(modi == [.pause])

        var neueSuche = Self.lage
        neueSuche.mode = .track
        neueSuche.suche = .init(status: .laeuft, dauerSec: 0)
        server.lageAntwort = .success(neueSuche)   // Server: Suche 2 läuft, kein Ereignis
        try await Task.sleep(for: .milliseconds(500))
        a.anhalten()
        #expect(modi.last == .track)
        #expect(a.lage?.suche.status == .laeuft)
    }

    @Test func moduswechselDerOrtungLaedtDieLage() async throws {
        let a = ansicht()
        a.starten()
        try await Task.sleep(for: .milliseconds(100))
        var neu = Self.lage
        neu.suche = .init(status: .beendet, dauerSec: 600)
        server.lageAntwort = .success(neu)
        a.modusGeaendert()
        try await Task.sleep(for: .milliseconds(100))
        a.anhalten()
        #expect(a.lage?.suche.status == .beendet)
    }

    @Test func lageGibtModusAnDieOrtungWeiter() async {
        var modi: [Modus] = []
        let a = ansicht { modi.append($0) }
        await a.neuLaden()
        #expect(modi == [.pause])
        #expect(a.lage?.einsatz.uebung == true)
        #expect(server.trackAbrufe == [nil])
    }

    @Test func halsbandAlterNachServeruhr() async {
        let a = ansicht()
        await a.neuLaden()
        // Server ist 100 s vor der Handyuhr, Halsband-Zeit 10 s vor der Serverzeit
        #expect(a.halsbandAlter(um: jetzt) == 10)
    }

    @Test func statusmeldungImFunklochBleibtUndGehtMitGleicherIdRaus() async {
        let a = ansicht()
        server.meldungFehler = .server(status: 503)
        await a.melden(.funk)
        #expect(a.offeneMeldungen.count == 1)

        let neu = ansicht()   // App neu gestartet: Warteschlange überlebt
        #expect(neu.offeneMeldungen.count == 1)
        server.meldungFehler = nil
        await neu.meldungenSenden()
        #expect(neu.offeneMeldungen.isEmpty)
        #expect(server.meldungen.count == 2)
        #expect(server.meldungen[0].id == server.meldungen[1].id)
        #expect(neu.gesendet?.code == .funk)
    }

    @Test func abgelehnteMeldungWirdNichtEndlosWiederholt() async {
        let a = ansicht()
        server.meldungFehler = .server(status: 400)
        await a.melden(.pause)
        #expect(a.offeneMeldungen.isEmpty)
    }

    @Test func http410BeiMeldungIstStop() async {
        var modi: [Modus] = []
        let a = ansicht { modi.append($0) }
        server.meldungFehler = .beendet
        await a.melden(.beendet)
        #expect(modi == [.stop])
    }

    @Test func mitteilungenOhneDoppelteUndGelesenBleibtGespeichert() async {
        let a = ansicht()
        await a.neuLaden()
        await a.verarbeiten(.mitteilung(Mitteilung(id: "m1", text: "Hallo", zeit: jetzt)))
        await a.verarbeiten(.mitteilung(Mitteilung(id: "m2", text: "Bitte Funk", zeit: jetzt)))
        #expect(a.lage?.mitteilungen.map(\.id) == ["m1", "m2"])
        a.alsGelesen(a.ungelesen[0])
        #expect(a.ungelesen.map(\.id) == ["m2"])
        #expect(ansicht().gelesen == ["m1"])
        a.vergessen()
        #expect(ansicht().gelesen.isEmpty)
    }

    @Test func stopEreignisMit410BeendetOrtung() async {
        var modi: [Modus] = []
        let a = ansicht { modi.append($0) }
        server.lageAntwort = .failure(.beendet)
        await a.verarbeiten(.stop)
        #expect(modi == [.stop])
    }

    /// Server verdrängt die 4. Verbindung eines Teams mit `stop` – das Token gilt aber weiter.
    @Test func stopEreignisOhne410IstNurVerdraengung() async {
        var modi: [Modus] = []
        let a = ansicht { modi.append($0) }
        await a.verarbeiten(.stop)
        #expect(!modi.contains(.stop))
        #expect(modi == [.pause])   // Modus aus /lage übernommen
    }

    @Test func verdraengtBeendetNichtsUndFragtNichtNach() async {
        var modi: [Modus] = []
        let a = ansicht { modi.append($0) }
        await a.verarbeiten(.verdraengt(wiederholenNachSec: 60))
        #expect(modi.isEmpty)
        #expect(a.lage == nil)   // keine Rückfrage nötig
    }

    @Test func stopEreignisImFunklochBeendetNichts() async {
        var modi: [Modus] = []
        let a = ansicht { modi.append($0) }
        server.lageAntwort = .failure(.server(status: 503))
        await a.verarbeiten(.stop)
        #expect(modi.isEmpty)
    }

    @Test func lageEreignisLaedtAllesNeu() async {
        let a = ansicht()
        await a.verarbeiten(.lage)
        #expect(a.lage != nil)
        #expect(server.trackAbrufe == [nil])
    }
}
