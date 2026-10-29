import Foundation

/// Eine Aktion der Einsatzleitung (APP_API.md „native App“, Abschnitt 7). Jede trägt eine `id`;
/// dieselbe `id` führt der Server nur einmal aus, deshalb darf die App im Funkloch puffern und wiederholen.
struct ELAktion: Codable, Equatable, Identifiable, Sendable {
    var id: String
    /// Pfad unter `api/el/app/v1`, z. B. `teams/t1/start`
    var pfad: String
    /// JSON-Körper ohne `id`
    var inhalt: [String: ELWert]
    /// Für die Anzeige „… wartet auf Netz“
    var beschreibung: String
    var zeit: Date

    static func neu(_ pfad: String, _ beschreibung: String, _ inhalt: [String: ELWert] = [:], zeit: Date = Date()) -> ELAktion {
        ELAktion(id: UUID().uuidString, pfad: pfad, inhalt: inhalt, beschreibung: beschreibung, zeit: zeit)
    }

    // Die Aktionen aus Abschnitt 7
    static func teamStart(_ teamId: String, name: String, halsbandId: String?) -> ELAktion {
        neu("teams/\(teamId)/start", "\(name): Suche starten", ["halsbandId": halsbandId.map(ELWert.text) ?? .null])
    }
    static func teamPause(_ teamId: String, name: String) -> ELAktion { neu("teams/\(teamId)/pause", "\(name): Pause") }
    static func teamWeiter(_ teamId: String, name: String) -> ELAktion { neu("teams/\(teamId)/weiter", "\(name): Weiter") }
    static func teamStopp(_ teamId: String, name: String) -> ELAktion { neu("teams/\(teamId)/stopp", "\(name): Suche beenden") }
    static func teamZuruecksetzen(_ teamId: String, name: String) -> ELAktion { neu("teams/\(teamId)/zuruecksetzen", "\(name): Zurücksetzen") }
    static func mitteilung(_ teamId: String, name: String, text: String) -> ELAktion {
        neu("teams/\(teamId)/mitteilung", "Mitteilung an \(name)", ["text": .text(text)])
    }
    static func notiz(_ text: String, teamId: String?) -> ELAktion {
        neu("notizen", "Notiz", ["text": .text(text), "teamId": teamId.map(ELWert.text) ?? .null])
    }
    /// Einsatz starten wie `/el/start`; läuft schon einer → 409
    static func einsatzStart(_ daten: ELEinsatzStart) -> ELAktion {
        var inhalt: [String: ELWert] = [
            "stichwort": .text(daten.stichwort), "ort": .text(daten.ort), "szenario": .text(daten.szenario.rawValue),
            "uebung": .wahr(daten.uebung), "einsatzleiter": .text(daten.einsatzleiter),
            "fuehrungsassistent": .text(daten.fuehrungsassistent),
        ]
        if let lat = daten.lat, let lng = daten.lng {
            inhalt["lat"] = .zahl(lat)
            inhalt["lng"] = .zahl(lng)
        }
        return neu("einsatz/start", daten.uebung ? "Übung starten" : "Einsatz starten", inhalt)
    }

    /// Einsatz beenden wie `/el/beenden`; `ressourcenGeprueft` muss wahr sein
    static func einsatzBeenden(ergebnis: String, mitTracks: Bool, kartenart: ELKartenart = .streets) -> ELAktion {
        neu("einsatz/beenden", "Einsatz beenden", [
            "ergebnis": .text(ergebnis),
            "ressourcenGeprueft": .wahr(true),
            "pdf": .objekt(["tracks": .wahr(mitTracks), "kartenart": .text(kartenart.rawValue), "teamIds": .null]),
        ])
    }

    static func notizLoeschen(_ notizId: String) -> ELAktion { neu("notizen/\(notizId)/loeschen", "Notiz löschen") }
    static func funk(_ text: String, teamId: String?, antwortAuf: String?) -> ELAktion {
        neu("funk", antwortAuf == nil ? "Funkspruch" : "Antwort", [
            "text": .text(text), "teamId": teamId.map(ELWert.text) ?? .null, "antwortAuf": antwortAuf.map(ELWert.text) ?? .null,
        ])
    }
}

/// Einfacher JSON-Wert für die Aktionskörper.
enum ELWert: Codable, Equatable, Sendable {
    case text(String)
    case zahl(Double)
    case wahr(Bool)
    case objekt([String: ELWert])
    case null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .wahr(b) }
        else if let o = try? c.decode([String: ELWert].self) { self = .objekt(o) }
        else if let d = try? c.decode(Double.self) { self = .zahl(d) }
        else { self = .text(try c.decode(String.self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .text(let s): try c.encode(s)
        case .zahl(let d): try c.encode(d)
        case .wahr(let b): try c.encode(b)
        case .objekt(let o): try c.encode(o)
        case .null: try c.encodeNil()
        }
    }
}

enum ELAktionsFehler: Error, Equatable {
    /// 409/400 mit Text des Servers, z. B. „Team läuft schon“, „Halsband vergeben“
    case abgelehnt(String)
}

/// Notiz bzw. Funkspruch (Abschnitt 6, `GET /notizen`), neueste zuerst.
struct ELNotiz: Decodable, Equatable, Identifiable, Sendable {
    struct Antwort: Decodable, Equatable, Identifiable, Sendable {
        var id: String
        var text: String
        var zeit: Date
        var von: String?
    }

    var id: String
    var art: String
    var text: String
    var zeit: Date
    var von: String?
    var teamId: String?
    var antworten: [Antwort]?
    /// Nur Notizen der Einsatzleitung dürfen gelöscht werden (Server Stufe 3)
    var loeschbar: Bool?
}

/// Antwort von `GET /notizen` (Server Stufe 3): Seite neueste zuerst und ob es ältere gibt.
struct ELNotizSeite: Decodable, Equatable, Sendable {
    var notizen: [ELNotiz]
    var weitere: Bool

    init(notizen: [ELNotiz], weitere: Bool) {
        self.notizen = notizen
        self.weitere = weitere
    }

    init(from decoder: Decoder) throws {
        // Erste Beschreibung: reine Liste; umgesetzt: { notizen, weitere }
        if let liste = try? [ELNotiz](from: decoder) {
            notizen = liste
            weitere = liste.count >= 50
            return
        }
        let c = try decoder.container(keyedBy: Schluessel.self)
        notizen = try c.decode([ELNotiz].self, forKey: .notizen)
        weitere = try c.decodeIfPresent(Bool.self, forKey: .weitere) ?? false
    }

    private enum Schluessel: String, CodingKey { case notizen, weitere }
}

/// Kartenart im PDF-Bericht (Server PdfBerichtOptionen.Kartenarten); Google bewusst nicht
enum ELKartenart: String, CaseIterable, Codable, Sendable {
    case streets = "Streets", topographic = "Topographic", satellite = "Satellite"

    var anzeige: String {
        switch self {
        case .streets: "Straßenkarte"
        case .topographic: "Topografisch"
        case .satellite: "Satellit"
        }
    }
}

/// Szenario des Einsatzes (Abschnitt 5)
enum ELSzenario: String, CaseIterable, Codable, Sendable {
    case unbestimmt = "Unbestimmt", mantrailer = "Mantrailer", flaeche = "Flaeche", truemmer = "Truemmer", sonstige = "Sonstige"

    var anzeige: String {
        switch self {
        case .unbestimmt: "Unbestimmt"
        case .mantrailer: "Mantrailer"
        case .flaeche: "Fläche"
        case .truemmer: "Trümmer"
        case .sonstige: "Sonstige"
        }
    }
}

/// Eingaben für „Einsatz starten“
struct ELEinsatzStart: Equatable, Sendable {
    var stichwort = ""
    var ort = ""
    var szenario = ELSzenario.unbestimmt
    var uebung = false
    var einsatzleiter = ""
    var fuehrungsassistent = ""
    var lat: Double?
    var lng: Double?

    var vollstaendig: Bool { !ort.trimmingCharacters(in: .whitespaces).isEmpty }
}

/// Erlaubtes Ergebnis für das Einsatzende (`GET /einsatz/ergebnisse`)
struct ELErgebnis: Decodable, Equatable, Identifiable, Hashable, Sendable {
    var wert: String
    var anzeige: String
    var id: String { wert }
}

/// Freie Halsbänder für die Rückfrage beim Teamstart (`GET /halsbaender`).
struct ELHalsbaender: Decodable, Equatable, Sendable {
    struct Halsband: Decodable, Equatable, Identifiable, Sendable {
        var id: String
        var name: String?
        var zeit: Date?
        var akku: Int?
    }
    struct Zuletzt: Decodable, Equatable, Sendable {
        var teamId: String
        var halsbandId: String?
    }

    var frei: [Halsband]
    var zuletzt: [Zuletzt]?

    init(from decoder: Decoder) throws {
        // Liste direkt oder Objekt mit „frei“/„halsbaender“ und „zuletzt“
        if let liste = try? [Halsband](from: decoder) {
            frei = liste
            zuletzt = nil
            return
        }
        let c = try decoder.container(keyedBy: Schluessel.self)
        frei = try c.decodeIfPresent([Halsband].self, forKey: .frei) ?? c.decodeIfPresent([Halsband].self, forKey: .halsbaender) ?? []
        zuletzt = try c.decodeIfPresent([Zuletzt].self, forKey: .zuletzt)
    }

    private enum Schluessel: String, CodingKey { case frei, halsbaender, zuletzt }
}

/// Was die Aktionen der Einsatzleitung brauchen (testbar ohne Netz).
protocol ELAktionsSchnittstelle: Sendable {
    func ausfuehren(_ aktion: ELAktion) async throws
    func notizen(vor: String?) async throws -> ELNotizSeite
    func halsbaender() async throws -> ELHalsbaender
    func ergebnisse() async throws -> [ELErgebnis]
}
