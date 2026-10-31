import Foundation

/// Datenschutzerklärung der Staffel vom Server (APP_API.md Team-Rolle Abschnitt 12, Server #148).
/// In `config`/`lage` kommt nur `version`, bei der Anmeldung und mit `GET datenschutz` das ganze Objekt.
struct Datenschutz: Codable, Equatable, Identifiable, Sendable {
    var version: String
    var titel: String?
    var text: String?
    var stand: Date?

    var id: String { version }
    var vollstaendig: Bool { text != nil }
}

/// Was der Server zur Datenschutzerklärung sagt: Feld fehlt (älterer Server), `null` (keine hinterlegt) oder eine Erklärung.
enum DatenschutzAngabe: Equatable, Sendable {
    case fehlt
    case keine
    case erklaerung(Datenschutz)

    init<K: CodingKey>(_ c: KeyedDecodingContainer<K>, _ schluessel: K) {
        guard c.contains(schluessel) else { self = .fehlt; return }
        if (try? c.decodeNil(forKey: schluessel)) == true { self = .keine; return }
        if let d = try? c.decode(Datenschutz.self, forKey: schluessel) { self = .erklaerung(d) } else { self = .fehlt }
    }

    var version: String? {
        if case .erklaerung(let d) = self { return d.version }
        return nil
    }
}

/// Offene Abfrage „Zur Kenntnis genommen“ bzw. Hinweis ohne Erklärung.
struct DatenschutzFrage: Identifiable, Equatable, Sendable {
    /// Server-Kennung (sonst der Servername), unter der die Bestätigung gespeichert wird
    var kennung: String
    /// `nil`: Die Staffel hat keine Erklärung hinterlegt (nur Hinweis, Anmeldung bleibt möglich)
    var erklaerung: Datenschutz?
    var id: String { "\(kennung)|\(erklaerung?.version ?? "-")" }
}

/// Bestätigungen und zuletzt gezeigte Erklärungen, nur auf dem Gerät (Entscheidung Paul: kein Protokoll auf dem Server).
/// Nicht geheim, darum in den UserDefaults.
struct DatenschutzSpeicher {
    var speicher: UserDefaults = .standard

    private static let bestaetigtSchluessel = "datenschutz.bestaetigt"
    private static let texteSchluessel = "datenschutz.texte"

    func bestaetigt(kennung: String, version: String) -> Bool {
        (speicher.stringArray(forKey: Self.bestaetigtSchluessel) ?? []).contains("\(kennung)|\(version)")
    }

    func bestaetigen(kennung: String, version: String) {
        var liste = speicher.stringArray(forKey: Self.bestaetigtSchluessel) ?? []
        let eintrag = "\(kennung)|\(version)"
        guard !liste.contains(eintrag) else { return }
        liste.append(eintrag)
        speicher.set(liste, forKey: Self.bestaetigtSchluessel)
    }

    /// Zum Nachlesen in den Einstellungen: zuletzt gezeigte Erklärung je Server
    func merken(_ erklaerung: Datenschutz, kennung: String) {
        guard erklaerung.vollstaendig else { return }
        var texte = alle()
        texte[kennung] = erklaerung
        speicher.set(try? JSONEncoder().encode(texte), forKey: Self.texteSchluessel)
    }

    func alle() -> [String: Datenschutz] {
        speicher.data(forKey: Self.texteSchluessel).flatMap { try? JSONDecoder().decode([String: Datenschutz].self, from: $0) } ?? [:]
    }

    /// Muss für diese Version gefragt werden?
    func offen(kennung: String, version: String?) -> Bool {
        guard let version else { return false }
        return !bestaetigt(kennung: kennung, version: version)
    }
}

/// Einfaches Markdown der Erklärung (Überschriften `#`/`##`, Absätze, Listen mit `-`, **fett**, Links) in Blöcke zerlegen.
enum DatenschutzBlock: Equatable {
    case ueberschrift(String, ebene: Int)
    case absatz(String)
    case punkt(String)

    static func zerlegen(_ text: String) -> [DatenschutzBlock] {
        var bloecke: [DatenschutzBlock] = []
        var absatz: [String] = []
        func absatzAbschliessen() {
            if !absatz.isEmpty { bloecke.append(.absatz(absatz.joined(separator: " "))) }
            absatz = []
        }
        for roh in text.replacingOccurrences(of: "\r\n", with: "\n").split(separator: "\n", omittingEmptySubsequences: false) {
            let zeile = roh.trimmingCharacters(in: .whitespaces)
            if zeile.isEmpty {
                absatzAbschliessen()
            } else if zeile.hasPrefix("#") {
                absatzAbschliessen()
                let ebene = zeile.prefix { $0 == "#" }.count
                bloecke.append(.ueberschrift(zeile.dropFirst(ebene).trimmingCharacters(in: .whitespaces), ebene: ebene))
            } else if zeile.hasPrefix("- ") || zeile.hasPrefix("* ") {
                absatzAbschliessen()
                bloecke.append(.punkt(String(zeile.dropFirst(2))))
            } else {
                absatz.append(zeile)
            }
        }
        absatzAbschliessen()
        return bloecke
    }
}
