import Foundation

struct Koordinate: Codable, Equatable, Sendable {
    var lat: Double
    var lng: Double
}

/// Antwort von `GET /lage` (APP_API.md, Abschnitt 6).
struct TeamLage: Decodable, Equatable, Sendable {
    struct Einsatz: Decodable, Equatable, Sendable {
        var text: String?
        var uebung: Bool?
        var staffel: String?
    }

    struct Team: Decodable, Equatable, Sendable {
        var teamId: String
        var teamName: String
        var dogName: String?
        var hundefuehrerName: String?
        var helfer: [String]?
    }

    struct Suche: Decodable, Equatable, Sendable {
        enum Status: String, Decodable, Sendable {
            case bereit, laeuft, kurzpause, beendet
        }
        var status: Status
        var seit: Date?
        var dauerSec: Int?
        /// Das Team darf die Suche selbst steuern (APP_API.md Abschnitt 11, Einstellung auf dem Server).
        var darfSteuern: Bool?
    }

    struct Suchgebiet: Decodable, Equatable, Sendable {
        var id: String?
        var name: String?
        var farbe: String?
        var punkte: [Koordinate]
    }

    var mode: Modus?
    var serverZeit: Date?
    var einsatz: Einsatz
    var team: Team
    var suche: Suche
    var suchgebiet: Suchgebiet?
    var halsband: Halsband?
    var mitteilungen: [Mitteilung]
    /// Nur `version` (#148)
    var datenschutz: Datenschutz?
}

struct Halsband: Decodable, Equatable, Sendable {
    var id: String?
    var name: String?
    var lat: Double
    var lng: Double
    var zeit: Date
    var akku: Int?
}

struct Mitteilung: Decodable, Equatable, Identifiable, Sendable {
    var id: String
    var text: String
    var zeit: Date
}

/// Antwort von `GET /tracks` (Abschnitt 7). `t` in Unix-Millisekunden.
struct TeamTracks: Decodable, Equatable, Sendable {
    struct Punkt: Decodable, Equatable, Sendable {
        var lat: Double
        var lng: Double
        var t: Int64?
    }

    struct Laufend: Decodable, Equatable, Sendable {
        var hund: [Punkt]
        var mensch: [Punkt]
    }

    struct Track: Decodable, Equatable, Sendable {
        var art: String
        var farbe: String?
        var punkte: [Punkt]
    }

    struct Suche: Decodable, Equatable, Sendable {
        var sucheId: String?
        var tracks: [Track]
    }

    var serverZeit: Int64?
    var laufend: Laufend
    var abgeschlossen: [Suche]?
}

/// Steuerung der Suche durch das Team (Abschnitt 11). Kurzpause hält die Ortung an (`mode` bleibt `track`).
enum Suchaktion: String, Codable, CaseIterable, Sendable {
    case starten, pause, weiter, beenden

    /// Nur die gültigen Übergänge anbieten.
    static func moeglich(bei status: TeamLage.Suche.Status) -> [Suchaktion] {
        switch status {
        case .bereit, .beendet: [.starten]
        case .laeuft: [.pause, .beenden]
        case .kurzpause: [.weiter, .beenden]
        }
    }

    var titel: String {
        switch self {
        case .starten: "Suche starten"
        case .pause: "Pause"
        case .weiter: "Weiter"
        case .beenden: "Suche beendet"
        }
    }
}

/// Antwort von `POST /suche` (200) bzw. Stand bei 409.
struct SuchAntwort: Decodable, Equatable, Sendable {
    var doppelt: Bool?
    var mode: Modus?
    var suche: TeamLage.Suche?
    var error: String?
}

/// Die Statusknöpfe der Team-Seite (Abschnitt 8). „Pause“ und „Beendet“ zeigt die App seit #133 nicht mehr
/// (ersetzt durch die Steuerung der Suche); die Codes bleiben für Meldungen in der Warteschlange gültig.
enum Statusmeldung {
    enum Code: String, Codable, CaseIterable, Sendable {
        case imGebiet = "im_gebiet"
        case pause
        case funk
        case beendet
    }
}

/// Ereignisse aus `GET /ereignisse` (Abschnitt 9).
enum TeamEreignis: Equatable, Sendable {
    case halsband(Halsband)
    case mitteilung(Mitteilung)
    case lage
    case stop
    /// Zu viele Live-Verbindungen des Teams: angemeldet bleiben, frühestens nach `wiederholenNachSec` neu verbinden.
    case verdraengt(wiederholenNachSec: Int)

    init?(_ roh: SSELeser.Ereignis) {
        let daten = Data(roh.daten.utf8)
        switch roh.name {
        case "halsband":
            guard let wert = try? JSONDecoder.server.decode(Halsband.self, from: daten) else { return nil }
            self = .halsband(wert)
        case "mitteilung":
            guard let wert = try? JSONDecoder.server.decode(Mitteilung.self, from: daten) else { return nil }
            self = .mitteilung(wert)
        case "lage": self = .lage
        case "stop": self = .stop
        case "verdraengt":
            struct Daten: Decodable { var wiederholenNachSec: Int? }
            let sekunden = (try? JSONDecoder.server.decode(Daten.self, from: daten))?.wiederholenNachSec
            self = .verdraengt(wiederholenNachSec: sekunden ?? 60)
        default: return nil
        }
    }
}

/// Zerlegt einen Server-Sent-Events-Strom Zeile für Zeile. Eine Leerzeile schließt ein Ereignis ab,
/// Zeilen mit `:` am Anfang sind Kommentare (`: ping`).
struct SSELeser {
    struct Ereignis: Equatable {
        var name: String
        var daten: String
    }

    private var name = ""
    private var daten: [String] = []

    mutating func zeile(_ zeile: String) -> Ereignis? {
        if zeile.isEmpty {
            defer { name = ""; daten = [] }
            guard !daten.isEmpty else { return nil }
            return Ereignis(name: name.isEmpty ? "message" : name, daten: daten.joined(separator: "\n"))
        }
        if zeile.hasPrefix(":") { return nil }
        let feld: Substring
        var wert: Substring
        if let doppelpunkt = zeile.firstIndex(of: ":") {
            feld = zeile[..<doppelpunkt]
            wert = zeile[zeile.index(after: doppelpunkt)...]
            if wert.hasPrefix(" ") { wert = wert.dropFirst() }
        } else {
            feld = Substring(zeile)
            wert = ""
        }
        switch feld {
        case "event": name = String(wert)
        case "data": daten.append(String(wert))
        default: break
        }
        return nil
    }
}
