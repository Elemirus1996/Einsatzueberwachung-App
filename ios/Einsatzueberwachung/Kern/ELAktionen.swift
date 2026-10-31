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
    /// Einsatz starten wie `/el/start`; läuft schon einer → 409, unbekannter Divera-Alarm → 404.
    /// Leere Angaben gehen nicht mit, damit sie einen Divera-Alarm nicht überschreiben (der Server übernimmt sie dann aus dem Alarm).
    static func einsatzStart(_ daten: ELEinsatzStart) -> ELAktion {
        var inhalt: [String: ELWert] = ["szenario": .text(daten.szenario.rawValue), "uebung": .wahr(daten.uebung)]
        for (feld, wert) in [("stichwort", daten.stichwort), ("ort", daten.ort), ("einsatzleiter", daten.einsatzleiter),
                             ("fuehrungsassistent", daten.fuehrungsassistent)] {
            let text = wert.trimmingCharacters(in: .whitespacesAndNewlines)
            if !text.isEmpty { inhalt[feld] = .text(text) }
        }
        if let lat = daten.lat, let lng = daten.lng {
            inhalt["lat"] = .zahl(lat)
            inhalt["lng"] = .zahl(lng)
        }
        if let alarm = daten.diveraAlarmId { inhalt["diveraAlarmId"] = .ganz(alarm) }
        return neu("einsatz/start", daten.uebung ? "Übung starten" : "Einsatz starten", inhalt)
    }

    /// Einsatz beenden wie `/el/beenden`; `ressourcenGeprueft` muss wahr sein.
    /// `teamIds` nil = alle Teams im Bericht; `endzeit` nil = jetzt (Server), sonst als UTC mit `Z`.
    static func einsatzBeenden(ergebnis: String, mitTracks: Bool, kartenart: String = ELKartenart.streets.rawValue,
                               teamIds: [String]? = nil, endzeit: Date? = nil, bemerkungen: String? = nil) -> ELAktion {
        var inhalt: [String: ELWert] = [
            "ergebnis": .text(ergebnis),
            "ressourcenGeprueft": .wahr(true),
            "pdf": .objekt(["tracks": .wahr(mitTracks), "kartenart": .text(kartenart),
                            "teamIds": teamIds.map { .liste($0.map(ELWert.text)) } ?? .null]),
        ]
        if let endzeit { inhalt["endzeit"] = .text(ELEinsatzEnde.utc(endzeit)) }
        if let text = bemerkungen?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty { inhalt["bemerkungen"] = .text(text) }
        return neu("einsatz/beenden", "Einsatz beenden", inhalt)
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
    /// Ganzzahl, z. B. `diveraAlarmId` (der Server erwartet `int`, nicht `12.0`)
    case ganz(Int)
    case wahr(Bool)
    case liste([ELWert])
    case objekt([String: ELWert])
    case null

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let b = try? c.decode(Bool.self) { self = .wahr(b) }
        else if let l = try? c.decode([ELWert].self) { self = .liste(l) }
        else if let o = try? c.decode([String: ELWert].self) { self = .objekt(o) }
        else if let i = try? c.decode(Int.self) { self = .ganz(i) }
        else if let d = try? c.decode(Double.self) { self = .zahl(d) }
        else { self = .text(try c.decode(String.self)) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .text(let s): try c.encode(s)
        case .zahl(let d): try c.encode(d)
        case .ganz(let i): try c.encode(i)
        case .liste(let l): try c.encode(l)
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
    /// Divera-Alarm aus `GET /einsatz/vorlage`; dann übernimmt der Server Ort, Koordinate und Stichwort aus dem Alarm
    var diveraAlarmId: Int?

    /// Szenario immer Pflicht (nicht „Unbestimmt“), der Ort nur ohne Divera-Alarm
    var vollstaendig: Bool {
        szenario != .unbestimmt && (diveraAlarmId != nil || !ort.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
    }
}

/// Auswahllisten für Start und Ende (`GET /einsatz/vorlage`, Server #143).
struct ELVorlage: Decodable, Equatable, Sendable {
    struct DiveraAlarm: Decodable, Equatable, Identifiable, Hashable, Sendable {
        var id: Int
        var titel: String?
        var ort: String?
        var lat: Double?
        var lng: Double?
        var zeit: Date?
    }
    struct Wahl: Decodable, Equatable, Identifiable, Hashable, Sendable {
        var wert: String
        var anzeige: String
        var id: String { wert }
    }

    var aktiv: Bool
    var szenarien: [String]
    var divera: [DiveraAlarm]
    var einsatzleiter: [String]
    var fuehrungsassistenten: [String]
    var ergebnisse: [ELErgebnis]
    var kartenarten: [Wahl]

    init(aktiv: Bool = false, szenarien: [String] = [], divera: [DiveraAlarm] = [], einsatzleiter: [String] = [],
         fuehrungsassistenten: [String] = [], ergebnisse: [ELErgebnis] = [], kartenarten: [Wahl] = []) {
        self.aktiv = aktiv
        self.szenarien = szenarien
        self.divera = divera
        self.einsatzleiter = einsatzleiter
        self.fuehrungsassistenten = fuehrungsassistenten
        self.ergebnisse = ergebnisse
        self.kartenarten = kartenarten
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Schluessel.self)
        aktiv = try c.decodeIfPresent(Bool.self, forKey: .aktiv) ?? false
        szenarien = try c.decodeIfPresent([String].self, forKey: .szenarien) ?? []
        divera = try c.decodeIfPresent([DiveraAlarm].self, forKey: .divera) ?? []
        einsatzleiter = try c.decodeIfPresent([String].self, forKey: .einsatzleiter) ?? []
        fuehrungsassistenten = try c.decodeIfPresent([String].self, forKey: .fuehrungsassistenten) ?? []
        ergebnisse = try c.decodeIfPresent([ELErgebnis].self, forKey: .ergebnisse) ?? []
        kartenarten = try c.decodeIfPresent([Wahl].self, forKey: .kartenarten) ?? []
    }

    /// Szenarien zur Auswahl; ohne Liste vom Server alle außer „Unbestimmt“
    var szenarioWahl: [ELSzenario] {
        let liste = szenarien.compactMap(ELSzenario.init(rawValue:)).filter { $0 != .unbestimmt }
        return liste.isEmpty ? ELSzenario.allCases.filter { $0 != .unbestimmt } : liste
    }

    /// Kartenarten für den Bericht; ohne Liste vom Server die bekannten drei
    var kartenWahl: [Wahl] {
        kartenarten.isEmpty ? ELKartenart.allCases.map { Wahl(wert: $0.rawValue, anzeige: $0.anzeige) } : kartenarten
    }

    private enum Schluessel: String, CodingKey { case aktiv, szenarien, divera, einsatzleiter, fuehrungsassistenten, ergebnisse, kartenarten }
}

/// Antwort von `POST /einsatz/beenden`: Archiv-Eintrag und ob der PDF-Bericht erstellt wurde.
struct ELEinsatzEnde: Decodable, Equatable, Sendable {
    struct Pdf: Decodable, Equatable, Sendable {
        var erstellt: Bool
        var fehler: String?
    }

    var archivId: String?
    var einsatzNummer: String?
    var pdf: Pdf?

    init(archivId: String? = nil, einsatzNummer: String? = nil, pdf: Pdf? = nil) {
        self.archivId = archivId
        self.einsatzNummer = einsatzNummer
        self.pdf = pdf
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Schluessel.self)
        archivId = Self.text(c, .archivId)
        einsatzNummer = Self.text(c, .einsatzNummer)
        pdf = try? c.decodeIfPresent(Pdf.self, forKey: .pdf)
    }

    /// Kennungen als Text oder Zahl annehmen
    private static func text(_ c: KeyedDecodingContainer<Schluessel>, _ k: Schluessel) -> String? {
        if let s = try? c.decodeIfPresent(String.self, forKey: k) { return s }
        if let i = try? c.decodeIfPresent(Int.self, forKey: k) { return String(i) }
        return nil
    }

    private enum Schluessel: String, CodingKey { case archivId, einsatzNummer, pdf }

    /// Hinweis für die Einsatzleitung nach dem Beenden
    var meldung: String {
        var teile = [einsatzNummer.map { "Einsatz \($0) beendet und archiviert." } ?? "Einsatz beendet und archiviert."]
        switch pdf {
        case .some(let p) where p.erstellt: teile.append("Der Bericht (PDF) liegt im Archiv.")
        case .some(let p): teile.append("Bericht (PDF) nicht erstellt: \(p.fehler ?? "unbekannter Fehler"). Im Monitor im Archiv neu erzeugen.")
        case .none: break
        }
        return teile.joined(separator: " ")
    }

    /// Zeitpunkt in UTC mit `Z`, ohne Sekundenbruchteile (z. B. `2026-10-31T17:45:00Z`)
    static func utc(_ zeit: Date) -> String {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = TimeZone(identifier: "UTC")
        return f.string(from: zeit)
    }
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
    /// Liefert den Körper der Antwort (z. B. `archivId` beim Beenden)
    func ausfuehren(_ aktion: ELAktion) async throws -> Data
    func vorlage() async throws -> ELVorlage
    func notizen(vor: String?) async throws -> ELNotizSeite
    func halsbaender() async throws -> ELHalsbaender
    func ergebnisse() async throws -> [ELErgebnis]
}
