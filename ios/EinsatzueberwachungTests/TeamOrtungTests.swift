import Foundation
import Testing
@testable import Einsatzueberwachung

@MainActor
final class TestQuelle: OrtungsQuelle {
    var beiPosition: ((Position) -> Void)?
    private(set) var aktiv = false
    private(set) var starts = 0

    func starten(intervall: TimeInterval) {
        aktiv = true
        starts += 1
    }

    func beenden() { aktiv = false }
}

@MainActor
final class TestPuffer: PositionsSpeicher {
    private var eintraege: [(id: Int64, position: Position)] = []
    private var naechsteId: Int64 = 1

    func hinzufuegen(_ position: Position) {
        eintraege.append((naechsteId, position))
        naechsteId += 1
    }
    func aelteste(_ anzahl: Int) -> [(id: Int64, position: Position)] { Array(eintraege.prefix(anzahl)) }
    func loeschen(bisEinschliesslich id: Int64) { eintraege.removeAll { $0.id <= id } }
    func anzahl() -> Int { eintraege.count }
    func leeren() { eintraege.removeAll() }
}

final class TestSchnittstelle: TeamSchnittstelle, @unchecked Sendable {
    var antwort: Result<SteuerAntwort, SchnittstellenFehler> = .success(SteuerAntwort(mode: .track))
    private(set) var gesendet: [[Position]] = []
    private(set) var statusAbfragen = 0

    func config() async throws -> TeamConfig { throw SchnittstellenFehler.antwortUngueltig }

    func positionenSenden(_ positionen: [Position]) async throws -> SteuerAntwort {
        let ergebnis = try antwort.get()
        gesendet.append(positionen)
        return ergebnis
    }

    func status() async throws -> SteuerAntwort {
        statusAbfragen += 1
        return try antwort.get()
    }
}

@MainActor
struct TeamOrtungTests {
    let quelle = TestQuelle()
    let puffer = TestPuffer()
    let server = TestSchnittstelle()
    var zeit = Date(timeIntervalSince1970: 1_800_000_000)
    var beendet = 0

    func ortung(ablauf: TimeInterval = 3600, uhr: @escaping () -> Date, beimBeenden: @escaping () -> Void = {}) -> TeamOrtung {
        TeamOrtung(schnittstelle: server, quelle: quelle, puffer: puffer,
                   ablauf: uhr().addingTimeInterval(ablauf), jetzt: uhr, beimBeenden: beimBeenden)
    }

    func position(_ datum: Date) -> Position {
        Position(lat: 49.31, lng: 8.43, accuracy: 5, timestamp: Int64(datum.timeIntervalSince1970 * 1000), battery: 70)
    }

    @Test func trackStartetUndPauseBeendetDenOrtungsdienst() {
        let t = zeit
        let o = ortung(uhr: { t })
        o.anwenden(SteuerAntwort(mode: .track, intervalSec: 20))
        #expect(quelle.aktiv)
        #expect(o.zustand == .ortet)
        o.anwenden(SteuerAntwort(mode: .pause))
        #expect(!quelle.aktiv)
        #expect(o.zustand == .pausiert)
        o.anwenden(SteuerAntwort(mode: .track))
        #expect(quelle.aktiv, "Neue Suche startet die Ortung ohne Eingabe wieder")
    }

    @Test func stopBeendetAllesEndgueltig() {
        let t = zeit
        var aufgerufen = 0
        let o = ortung(uhr: { t }, beimBeenden: { aufgerufen += 1 })
        o.anwenden(SteuerAntwort(mode: .track))
        puffer.hinzufuegen(position(t))
        o.anwenden(.stop)
        #expect(!quelle.aktiv)
        #expect(o.zustand == .beendet)
        #expect(puffer.anzahl() == 0)
        #expect(aufgerufen == 1)
        o.anwenden(SteuerAntwort(mode: .track))
        #expect(!quelle.aktiv, "Nach stop startet nichts mehr")
    }

    @Test func ablaufBeendetOhneNetz() {
        var t = zeit
        let o = ortung(ablauf: 60, uhr: { t })
        o.anwenden(SteuerAntwort(mode: .track))
        t = t.addingTimeInterval(61)
        o.positionErhalten(position(t))
        #expect(o.zustand == .beendet)
        #expect(!quelle.aktiv)
        #expect(puffer.anzahl() == 0)
    }

    @Test func positionenWerdenNachIntervallGedrosselt() {
        let t = zeit
        let o = ortung(uhr: { t })
        o.anwenden(SteuerAntwort(mode: .track, intervalSec: 20))
        o.positionErhalten(position(t))
        o.positionErhalten(position(t.addingTimeInterval(5)))
        o.positionErhalten(position(t.addingTimeInterval(20)))
        #expect(puffer.anzahl() + server.gesendet.joined().count == 2)
    }

    @Test func uploadLeertDenPufferUndUebernimmtMode() async {
        let t = zeit
        let o = ortung(uhr: { t })
        o.anwenden(SteuerAntwort(mode: .track))
        puffer.hinzufuegen(position(t))
        puffer.hinzufuegen(position(t.addingTimeInterval(20)))
        server.antwort = .success(SteuerAntwort(mode: .pause, accepted: 2))
        await o.hochladen()
        #expect(server.gesendet.count == 1)
        #expect(server.gesendet[0].count == 2)
        #expect(puffer.anzahl() == 0)
        #expect(o.zustand == .pausiert)
        #expect(!quelle.aktiv)
    }

    @Test func grosserPufferGehtInPaketenZu500() async {
        let t = zeit
        let o = ortung(uhr: { t })
        o.anwenden(SteuerAntwort(mode: .track))
        for i in 0..<1200 { puffer.hinzufuegen(position(t.addingTimeInterval(Double(i)))) }
        await o.hochladen()
        #expect(server.gesendet.map(\.count) == [500, 500, 200])
        #expect(puffer.anzahl() == 0)
    }

    @Test func funklochBehaeltPositionenMitOriginalzeit() async {
        let t = zeit
        let o = ortung(uhr: { t })
        o.anwenden(SteuerAntwort(mode: .track))
        let p = position(t.addingTimeInterval(-300))
        puffer.hinzufuegen(p)
        server.antwort = .failure(.server(status: 503))
        await o.hochladen()
        #expect(puffer.anzahl() == 1)
        #expect(o.zustand == .ortet)
        server.antwort = .success(SteuerAntwort(mode: .track))
        await o.hochladen()
        #expect(server.gesendet.last == [p])
        #expect(puffer.anzahl() == 0)
    }

    @Test func http410IstImmerStop() async {
        let t = zeit
        let o = ortung(uhr: { t })
        o.anwenden(SteuerAntwort(mode: .pause))
        server.antwort = .failure(.beendet)
        await o.statusPruefen()
        #expect(o.zustand == .beendet)
        #expect(!quelle.aktiv)
    }

    @Test func leererPufferFragtNurStatus() async {
        let t = zeit
        let o = ortung(uhr: { t })
        o.anwenden(SteuerAntwort(mode: .track))
        server.antwort = .success(SteuerAntwort(mode: .pause))
        await o.hochladen()
        #expect(server.statusAbfragen == 1)
        #expect(server.gesendet.isEmpty)
        #expect(o.zustand == .pausiert)
    }

    @Test func taktFragtOhneBewegungNachUndSchaltetGpsAb() async throws {
        let o = ortung(uhr: Date.init)
        server.antwort = .success(SteuerAntwort(mode: .pause))
        o.anwenden(SteuerAntwort(mode: .track, uploadEverySec: 5))
        #expect(quelle.aktiv)
        // keine einzige Position: Hundeführer steht still, Suche wird beendet
        try await Task.sleep(for: .seconds(6))
        #expect(server.statusAbfragen >= 1)
        #expect(o.zustand == .pausiert)
        #expect(!quelle.aktiv)
    }

    @Test func neuerAblaufAusAntwortWirdUebernommen() {
        var t = zeit
        let o = ortung(ablauf: 60, uhr: { t })
        o.anwenden(SteuerAntwort(mode: .track, expiresAt: t.addingTimeInterval(7200)))
        t = t.addingTimeInterval(120)
        o.ablaufPruefen()
        #expect(o.zustand == .ortet)
    }
}
