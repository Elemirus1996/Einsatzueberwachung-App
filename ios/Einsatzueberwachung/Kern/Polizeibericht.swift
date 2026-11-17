import Foundation

/// „Eingesetzt als“ je Team (APP_API EL Abschnitt 10, Server 1.17.0)
enum ELEinsatzart: String, CaseIterable, Identifiable, Sendable {
    case flaeche = "Flaeche", truemmer = "Truemmer", mantrailer = "Mantrailer", wasser = "Wasser", lawine = "Lawine"

    var id: String { rawValue }

    var anzeige: String {
        switch self {
        case .flaeche: "Fläche"
        case .truemmer: "Trümmer"
        case .mantrailer: "Mantrailer"
        case .wasser: "Wasser"
        case .lawine: "Lawine"
        }
    }

    /// Anzeige zum Wert aus der Schnittstelle; unbekannte Werte so, wie sie kommen
    static func anzeige(_ wert: String?) -> String? {
        guard let wert, !wert.isEmpty else { return nil }
        return ELEinsatzart(rawValue: wert)?.anzeige ?? wert
    }
}

/// Wer die Staffel alarmiert hat (Pflicht beim Anlegen ab Server 1.17.0)
enum ELAlarmiertDurch: String, CaseIterable, Identifiable, Sendable {
    case polizei = "Polizei", feuerwehr = "Feuerwehr", rettungsdienst = "Rettungsdienst", andereStaffel = "AndereStaffel"

    var id: String { rawValue }

    var anzeige: String {
        switch self {
        case .polizei: "Polizei"
        case .feuerwehr: "Feuerwehr"
        case .rettungsdienst: "Rettungsdienst"
        case .andereStaffel: "andere Rettungshundestaffel"
        }
    }
}

/// Abwählbare Abschnitte des Berichts (`pdf.abschnitte`); Kopf, Ergebnis und Polizei sind immer dabei
enum ELBerichtAbschnitt: String, CaseIterable, Identifiable, Sendable {
    case vermisste, zeitleiste, teams, karten, funk, vorort

    var id: String { rawValue }

    var anzeige: String {
        switch self {
        case .vermisste: "Vermisste Person"
        case .zeitleiste: "Zeitlicher Ablauf"
        case .teams: "Suche je Team und Trails"
        case .karten: "Karten"
        case .funk: "Funk und Notizen"
        case .vorort: "Vor Ort"
        }
    }
}

/// `GET /einsatz/abschluss`: alles, was der Beenden-Dialog abfragt, mit den aktuellen Werten
struct ELAbschluss: Decodable, Equatable, Sendable {
    struct Polizei: Decodable, Equatable, Sendable {
        var name: String?
        var dienstnummer: String?
        var telefon: String?
    }
    struct Vorschlag: Decodable, Equatable, Sendable {
        var lat: Double
        var lng: Double
        var zeit: Date?
        var teamId: String?
    }
    struct Fund: Decodable, Equatable, Sendable {
        var zeit: Date?
        var lat: Double?
        var lng: Double?
        var ort: String?
        var uebergabeAn: String?
        var teamId: String?
        var vorschlag: Vorschlag?
    }
    struct Team: Decodable, Equatable, Identifiable, Sendable {
        var teamId: String
        var teamName: String
        var einsatzart: String?
        var erlaubt: [String]?
        var id: String { teamId }

        /// Auswahl: Spezialisierungen des Hundes, ohne Angabe alle fünf; eine schon gesetzte Art bleibt wählbar
        var wahl: [ELEinsatzart] {
            var liste = (erlaubt ?? []).compactMap(ELEinsatzart.init(rawValue:))
            if liste.isEmpty { liste = ELEinsatzart.allCases }
            if let art = einsatzart.flatMap(ELEinsatzart.init(rawValue:)), !liste.contains(art) { liste.append(art) }
            return liste
        }
    }
    struct Trail: Decodable, Equatable, Identifiable, Sendable {
        var sucheId: String
        var teamId: String?
        var teamName: String
        var beginn: Date?
        var ende: Date?
        var abbruchgrund: String?
        var geruchsartikel: String?
        var id: String { sucheId }
    }

    var uebung: Bool?
    var alarmiertDurch: String?
    var keinePolizei: Bool?
    var vorgangsnummer: String?
    var melder: String?
    var polizei: Polizei?
    var fund: Fund?
    var teams: [Team]?
    var trails: [Trail]?
    var abbruchgruende: [String]?
    var fehlend: [String]?
}

/// Eingaben des Beenden-Dialogs; daraus wird `abschluss` für `POST /einsatz/beenden`
struct ELAbschlussEingabe: Equatable, Sendable {
    var uebung = false
    var alarmiertDurch: ELAlarmiertDurch?
    var melder = ""
    var keinePolizei = false
    var vorgangsnummer = ""
    var polizeiName = ""
    var dienstnummer = ""
    var telefon = ""
    var fundZeit = Date()
    var fundZeitGesetzt = false
    var fundOrt = ""
    var fundLat: Double?
    var fundLng: Double?
    var fundTeamId: String?
    var uebergabeAn = ""
    var einsatzart: [String: ELEinsatzart] = [:]
    var abbruchgrund: [String: String] = [:]
    var geruchsartikel: [String: String] = [:]

    init() {}

    init(_ a: ELAbschluss) {
        uebung = a.uebung ?? false
        alarmiertDurch = a.alarmiertDurch.flatMap(ELAlarmiertDurch.init(rawValue:))
        melder = a.melder ?? ""
        keinePolizei = a.keinePolizei ?? false
        vorgangsnummer = a.vorgangsnummer ?? ""
        polizeiName = a.polizei?.name ?? ""
        dienstnummer = a.polizei?.dienstnummer ?? ""
        telefon = a.polizei?.telefon ?? ""
        if let zeit = a.fund?.zeit { fundZeit = zeit; fundZeitGesetzt = true }
        fundOrt = a.fund?.ort ?? ""
        fundLat = a.fund?.lat
        fundLng = a.fund?.lng
        fundTeamId = a.fund?.teamId
        uebergabeAn = a.fund?.uebergabeAn ?? ""
        for t in a.teams ?? [] { if let art = t.einsatzart.flatMap(ELEinsatzart.init(rawValue:)) { einsatzart[t.teamId] = art } }
        for t in a.trails ?? [] {
            abbruchgrund[t.sucheId] = t.abbruchgrund ?? ""
            geruchsartikel[t.sucheId] = t.geruchsartikel ?? ""
        }
    }

    /// Polizeiangaben gefragt: Einsatz, von der Polizei alarmiert, nicht „Keine Polizei beteiligt“ (wie der Server)
    var polizeiGefragt: Bool { !uebung && alarmiertDurch == .polizei && !keinePolizei }

    mutating func vorschlagUebernehmen(_ v: ELAbschluss.Vorschlag) {
        fundLat = v.lat
        fundLng = v.lng
        if !fundZeitGesetzt, let zeit = v.zeit { fundZeit = zeit; fundZeitGesetzt = true }
        if let team = v.teamId { fundTeamId = team }
    }

    /// Fehlende Angaben in Worten, gleiche Regeln wie `fehlend` des Servers
    func fehlend(abschluss: ELAbschluss, fund: Bool) -> [String] {
        var liste: [String] = []
        if !uebung && alarmiertDurch == nil { liste.append("Alarmiert durch") }
        if polizeiGefragt && Self.leer(vorgangsnummer) { liste.append("Vorgangsnummer der Polizei") }
        if polizeiGefragt && Self.leer(polizeiName) { liste.append("Polizeikontakt") }
        if fund && (!fundZeitGesetzt || (fundLat == nil && Self.leer(fundOrt))) { liste.append("Fundzeit und Fundort") }
        if (abschluss.teams ?? []).contains(where: { einsatzart[$0.teamId] == nil }) { liste.append("„Eingesetzt als“ je Team") }
        if (abschluss.trails ?? []).contains(where: { Self.leer(abbruchgrund[$0.sucheId] ?? "") || Self.leer(geruchsartikel[$0.sucheId] ?? "") }) {
            liste.append("Abbruchgrund und Geruchsartikel je Trail")
        }
        return liste
    }

    /// `abschluss` für `POST /einsatz/beenden`; Fund nur bei einem Ergebnis mit Fund
    func json(abschluss: ELAbschluss, fund: Bool) -> ELWert {
        var o: [String: ELWert] = [
            "keinePolizei": .wahr(keinePolizei),
            "melder": .text(melder.trimmingCharacters(in: .whitespacesAndNewlines)),
            "vorgangsnummer": .text(vorgangsnummer.trimmingCharacters(in: .whitespacesAndNewlines)),
            "polizei": .objekt(["name": .text(polizeiName), "dienstnummer": .text(dienstnummer), "telefon": .text(telefon)]),
            "teams": .liste((abschluss.teams ?? []).map { t in
                .objekt(["teamId": .text(t.teamId), "einsatzart": einsatzart[t.teamId].map { .text($0.rawValue) } ?? .null])
            }),
            "trails": .liste((abschluss.trails ?? []).map { t in
                .objekt(["sucheId": .text(t.sucheId), "abbruchgrund": .text(abbruchgrund[t.sucheId] ?? ""),
                         "geruchsartikel": .text(geruchsartikel[t.sucheId] ?? "")])
            }),
        ]
        if let alarmiertDurch { o["alarmiertDurch"] = .text(alarmiertDurch.rawValue) }
        if fund {
            var f: [String: ELWert] = ["ort": .text(fundOrt), "uebergabeAn": .text(uebergabeAn), "teamId": .text(fundTeamId ?? "")]
            if fundZeitGesetzt { f["zeit"] = .text(ELEinsatzEnde.utc(min(fundZeit, Date()))) }
            if let lat = fundLat, let lng = fundLng { f["lat"] = .zahl(lat); f["lng"] = .zahl(lng) }
            o["fund"] = .objekt(f)
        }
        return .objekt(o)
    }

    private static func leer(_ s: String) -> Bool { s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}

/// Rolle der EL-App im laufenden Einsatz (APP_API EL Abschnitt 4a)
enum ELRolle: String, Sendable {
    case einsatzleiter, fuehrungsassistent, lesen

    var anzeige: String {
        switch self {
        case .einsatzleiter: "Einsatzleiter"
        case .fuehrungsassistent: "Führungsassistent"
        case .lesen: "Nur lesen"
        }
    }
}

/// `ich` aus `/lage` und `POST /ich`
struct ELIch: Decodable, Equatable, Sendable {
    var personId: String?
    var name: String?
    var rolle: String?

    var rollenWert: ELRolle? { rolle.flatMap(ELRolle.init(rawValue:)) }
}

/// Eintrag aus `GET /personen`
struct ELPerson: Decodable, Equatable, Identifiable, Hashable, Sendable {
    var personId: String
    var name: String
    var einsatzleiter: Bool?
    var fuehrungsassistent: Bool?
    var id: String { personId }
}
