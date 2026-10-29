import Foundation

/// Position auf der Lagekarte (APP_API.md „native App“ Abschnitte 6 und 8): Halsband, Team-Handy, Einsatzleitung, ELW.
struct ELPosition: Decodable, Equatable, Identifiable, Sendable {
    var art: String
    var id: String
    var name: String?
    var teamId: String?
    var lat: Double
    var lng: Double
    var zeit: Date?
    var akku: Int?

    /// Eindeutig über Art und Kennung (ein Halsband und ein Team können dieselbe Kennung tragen)
    var schluessel: String { "\(art)|\(id)" }
}

/// Antwort von `GET /karte`.
struct ELKarte: Decodable, Equatable, Sendable {
    struct Suchgebiet: Decodable, Equatable, Identifiable, Sendable {
        var id: String
        var name: String?
        var farbe: String?
        var punkte: [Koordinate]
        var teamId: String?
        var abdeckungProzent: Double?
    }

    var suchgebiete: [Suchgebiet]
    var positionen: [ELPosition]
}

/// Antwort von `GET /tracks` der Einsatzleitung: laufende Suchen je Team, abgeschlossene mit `teamId`.
struct ELTracks: Decodable, Equatable, Sendable {
    struct Laufend: Decodable, Equatable, Sendable {
        var teamId: String
        var hund: [TeamTracks.Punkt]
        var mensch: [TeamTracks.Punkt]
    }
    struct Suche: Decodable, Equatable, Sendable {
        var sucheId: String?
        var teamId: String?
        var tracks: [TeamTracks.Track]
    }

    var serverZeit: Int64?
    var laufend: [Laufend]
    var abgeschlossen: [Suche]?

    var letzterZeitpunkt: Int64? {
        laufend.flatMap { $0.hund + $0.mensch }.compactMap(\.t).max()
    }

    /// Neuere Punkte (`?seit=`) an die laufenden Suchen anhängen
    mutating func anhaengen(_ neu: ELTracks) {
        for teil in neu.laufend {
            if let i = laufend.firstIndex(where: { $0.teamId == teil.teamId }) {
                laufend[i].hund += teil.hund
                laufend[i].mensch += teil.mensch
            } else {
                laufend.append(teil)
            }
        }
    }
}

protocol ELKartenSchnittstelle: Sendable {
    func karte() async throws -> ELKarte
    func tracks(seit: Int64?) async throws -> ELTracks
}

/// Lagekarte der Einsatzleitung: Gebiete mit Abdeckung, Positionen (live über `position`), Tracks.
@MainActor
final class ELKartenAnsicht: ObservableObject {
    static let trackTakt: Duration = .seconds(30)
    static let trackVollNach: TimeInterval = 300

    @Published private(set) var karte: ELKarte?
    @Published private(set) var tracks: ELTracks?

    private let schnittstelle: ELKartenSchnittstelle
    private let jetzt: () -> Date
    private var aufgabe: Task<Void, Never>?
    private var letzteVolle: Date?

    init(schnittstelle: ELKartenSchnittstelle, jetzt: @escaping () -> Date = Date.init) {
        self.schnittstelle = schnittstelle
        self.jetzt = jetzt
    }

    func starten() {
        guard aufgabe == nil else { return }
        aufgabe = Task { [weak self] in
            await self?.laden(voll: true)
            while !Task.isCancelled {
                try? await Task.sleep(for: Self.trackTakt)
                guard !Task.isCancelled else { return }
                await self?.laden(voll: false)
            }
        }
    }

    func anhalten() {
        aufgabe?.cancel()
        aufgabe = nil
    }

    func laden(voll: Bool) async {
        if voll, let neu = try? await schnittstelle.karte() { karte = neu }
        let vollNoetig = voll || tracks == nil || letzteVolle.map { jetzt().timeIntervalSince($0) >= Self.trackVollNach } ?? true
        if vollNoetig {
            if let neu = try? await schnittstelle.tracks(seit: nil) {
                tracks = neu
                letzteVolle = jetzt()
            }
        } else if var bisher = tracks, let neu = try? await schnittstelle.tracks(seit: bisher.letzterZeitpunkt) {
            bisher.anhaengen(neu)
            tracks = bisher
        }
    }

    /// Live-Ereignis `position`: Marker bewegen bzw. neu aufnehmen
    func position(_ neu: ELPosition) {
        guard var k = karte else { return }
        if let i = k.positionen.firstIndex(where: { $0.schluessel == neu.schluessel }) {
            var p = neu
            p.name = p.name ?? k.positionen[i].name
            k.positionen[i] = p
        } else {
            k.positionen.append(neu)
        }
        karte = k
    }
}
