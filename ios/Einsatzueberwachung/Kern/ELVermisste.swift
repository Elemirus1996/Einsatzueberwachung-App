import Foundation

/// Vermisstenprofil (APP_API.md „native App“ Abschnitt 7, Server Stufe 4a). Texte nie `null`, leer = "".
struct ELVermisstenprofil: Codable, Equatable, Identifiable, Sendable {
    struct Checkliste: Codable, Equatable, Sendable {
        struct Punkt: Codable, Equatable, Identifiable, Sendable {
            var id: String
            var text: String
            /// `jaNein` · `auswahl` · `text`
            var typ: String
            var pflicht: Bool?
            var auswahl: [String]?
            var wert: String?
            var erledigt: Bool?
        }
        var name: String?
        var punkte: [Punkt]
    }

    var profilId: String?
    var version: String?
    var vollerName: String?
    var zuletztAktualisiert: Date?
    var checkliste: Checkliste?

    var texte: [String: String] = [:]
    var schalter: [String: Bool] = [:]
    var auswahlen: [String: String] = [:]

    var id: String { profilId ?? "neu" }

    // Feldnamen wie im Server-Modell
    static let textFelder = [
        "vorname", "nachname", "alter", "geburtsdatum", "beschreibung", "kleidung", "besonderheiten",
        "zuletztGesehenOrt", "zuletztGesehenZeit", "zuletztGesehenVon", "vorerkrankungen", "medikamente",
        "polizeiKontaktName", "polizeiDienstnummer", "polizeiTelefon",
        "bosEinheit", "bosZugfuehrer", "bosFunkrufname", "bosAufgabenteilung",
    ]
    static let schalterFelder = [
        "polizeiVermisstenmeldungAufgenommen", "polizeiKoordinationBesprochen", "polizeiSuchabschnittAbgestimmt",
        "polizeiRueckmeldepflichtVereinbart", "polizeiDatenschutzGeklaert", "bosAbschnittAbgestimmt", "bosRessourcenBesprochen",
    ]
    static let auswahlFelder: [String: [String]] = [
        "orientierung": ["Unbekannt", "Gut", "Eingeschraenkt"],
        "mobilitaet": ["Unbekannt", "ZuFuss", "Rollator", "Rollstuhl", "Fahrzeug"],
        "suizidrisiko": ["Unbekannt", "Nein", "Ja"],
        "bewaffnet": ["Unbekannt", "Nein", "Ja"],
    ]

    init() {
        for f in Self.auswahlFelder.keys { auswahlen[f] = "Unbekannt" }
    }

    func text(_ feld: String) -> String { texte[feld] ?? "" }

    /// Nur geänderte Felder (der Server übernimmt nur mitgeschickte; "" leert ein Textfeld)
    func aenderungen(gegenueber alt: ELVermisstenprofil) -> [String: ELWert] {
        var ergebnis: [String: ELWert] = [:]
        for f in Self.textFelder where text(f) != alt.text(f) { ergebnis[f] = .text(text(f)) }
        for f in Self.schalterFelder where (schalter[f] ?? false) != (alt.schalter[f] ?? false) { ergebnis[f] = .wahr(schalter[f] ?? false) }
        for f in Self.auswahlFelder.keys where auswahlen[f] != alt.auswahlen[f] { ergebnis[f] = .text(auswahlen[f] ?? "Unbekannt") }
        return ergebnis
    }

    // MARK: Codable mit dynamischen Feldnamen

    private struct Schluessel: CodingKey {
        var stringValue: String
        var intValue: Int? { nil }
        init(_ s: String) { stringValue = s }
        init?(stringValue: String) { self.stringValue = stringValue }
        init?(intValue: Int) { nil }
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Schluessel.self)
        profilId = try c.decodeIfPresent(String.self, forKey: .init("profilId"))
        version = try c.decodeIfPresent(String.self, forKey: .init("version"))
        vollerName = try c.decodeIfPresent(String.self, forKey: .init("vollerName"))
        zuletztAktualisiert = try c.decodeIfPresent(Date.self, forKey: .init("zuletztAktualisiert"))
        checkliste = try c.decodeIfPresent(Checkliste.self, forKey: .init("checkliste"))
        for f in Self.textFelder { texte[f] = try c.decodeIfPresent(String.self, forKey: .init(f)) ?? "" }
        for f in Self.schalterFelder { schalter[f] = try c.decodeIfPresent(Bool.self, forKey: .init(f)) ?? false }
        for f in Self.auswahlFelder.keys { auswahlen[f] = try c.decodeIfPresent(String.self, forKey: .init(f)) ?? "Unbekannt" }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: Schluessel.self)
        try c.encodeIfPresent(profilId, forKey: .init("profilId"))
        try c.encodeIfPresent(version, forKey: .init("version"))
        try c.encodeIfPresent(vollerName, forKey: .init("vollerName"))
        try c.encodeIfPresent(zuletztAktualisiert, forKey: .init("zuletztAktualisiert"))
        try c.encodeIfPresent(checkliste, forKey: .init("checkliste"))
        for (f, w) in texte { try c.encode(w, forKey: .init(f)) }
        for (f, w) in schalter { try c.encode(w, forKey: .init(f)) }
        for (f, w) in auswahlen { try c.encode(w, forKey: .init(f)) }
    }

    static func anzeige(_ wert: String) -> String {
        switch wert {
        case "Eingeschraenkt": "Eingeschränkt"
        case "ZuFuss": "Zu Fuß"
        default: wert
        }
    }
}

/// Antwort von `GET /vermisste`
struct ELVermisste: Decodable, Equatable, Sendable {
    var szenario: String?
    var mehrereErlaubt: Bool
    var profile: [ELVermisstenprofil]
}

enum ELVermissteFehler: Error, Equatable {
    /// 409 mit neuem Stand: inzwischen geändert (auch durch /el im Browser)
    case geaendert(ELVermisstenprofil)
    /// 400/409 ohne Profil (z. B. zweites Profil im Szenario mit nur einer Person)
    case abgelehnt(String)
}

protocol ELVermissteSchnittstelle: Sendable {
    func vermisste() async throws -> ELVermisste
    /// Anlegen (ohne `profilId`) oder ändern (mit `profilId` und `version`); Antwort neue Kennung und Version
    func vermissteSpeichern(id: String, profilId: String?, version: String?, felder: [String: ELWert]) async throws -> (profilId: String, version: String)
}

extension ELAktion {
    static func checkliste(_ profilId: String, punkt: ELVermisstenprofil.Checkliste.Punkt, erledigt: Bool? = nil, wert: String? = nil) -> ELAktion {
        var inhalt: [String: ELWert] = ["punktId": .text(punkt.id)]
        if let erledigt { inhalt["erledigt"] = .wahr(erledigt) }
        if let wert { inhalt["wert"] = .text(wert) }
        return neu("vermisste/\(profilId)/checkliste", "Checkliste: \(punkt.text)", inhalt)
    }

    static func vermisstenEntfernen(_ profilId: String, name: String) -> ELAktion {
        neu("vermisste/\(profilId)/entfernen", "\(name) entfernen")
    }

    static func szenario(_ szenario: ELSzenario) -> ELAktion {
        neu("szenario", "Szenario \(szenario.anzeige)", ["szenario": .text(szenario.rawValue)])
    }
}

/// Vermisste der Einsatzleitung: Liste laden (live über `vermisste`), Profil speichern mit Versionsprüfung.
@MainActor
final class ELVermissteAnsicht: ObservableObject {
    @Published private(set) var stand: ELVermisste?
    @Published private(set) var fehler: String?

    private let schnittstelle: ELVermissteSchnittstelle

    init(schnittstelle: ELVermissteSchnittstelle) {
        self.schnittstelle = schnittstelle
    }

    func laden() async {
        do {
            stand = try await schnittstelle.vermisste()
            fehler = nil
        } catch {
            fehler = "Vermisste konnten nicht geladen werden."
        }
    }

    /// Speichert nur die Änderungen. Bei Konflikt kommt der aktuelle Stand zurück (zum erneuten Bearbeiten).
    func speichern(_ neu: ELVermisstenprofil, original: ELVermisstenprofil) async throws -> ELVermisstenprofil {
        let felder = neu.aenderungen(gegenueber: original)
        let ergebnis = try await schnittstelle.vermissteSpeichern(
            id: UUID().uuidString, profilId: original.profilId, version: original.version, felder: felder)
        var gespeichert = neu
        gespeichert.profilId = ergebnis.profilId
        gespeichert.version = ergebnis.version
        await laden()
        return gespeichert
    }
}
