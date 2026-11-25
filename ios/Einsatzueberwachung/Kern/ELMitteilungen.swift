import Foundation

/// Mitteilungen für die Einsatzleitung im Hintergrund ohne Push (#79, APP_API.md native EL Abschnitt 11).
/// Der Server meldet in `/status` und `/positions` einen `mitteilungStand`; nur wenn er sich ändert, holt die App
/// `/mitteilungen?seit=` und zeigt die eingeschalteten Arten als lokale Mitteilung.
struct ELMitteilung: Decodable, Equatable, Sendable {
    var id: String
    var art: String
    var stufe: Int
    var teamId: String?
    var text: String
}

struct ELMitteilungSeite: Decodable, Equatable, Sendable {
    var stand: String
    var mitteilungen: [ELMitteilung]
}

protocol ELMitteilungsSchnittstelle: Sendable {
    func mitteilungen(seit: String?) async throws -> ELMitteilungSeite
}

/// Einstellbare Arten. Zeitwarnung ist nach Stufe getrennt, damit Stufe 1 aus und Stufe 2 an sein kann.
enum ELMitteilungsArt: String, CaseIterable, Identifiable, Sendable {
    case zeitwarnung2, funkstille, gebiet, fund, zeitwarnung1, funk, status, akku, einsatzende

    var id: String { rawValue }

    var titel: String {
        switch self {
        case .zeitwarnung2: "Zeitwarnung Stufe 2"
        case .zeitwarnung1: "Zeitwarnung Stufe 1"
        case .funkstille: "Funkstille Handy oder Halsband"
        case .gebiet: "Hund außerhalb des Suchgebiets"
        case .fund: "Fund gemeldet"
        case .funk: "Neuer Funkspruch"
        case .status: "Statusmeldung eines Teams"
        case .akku: "Akku niedrig"
        case .einsatzende: "Einsatzende"
        }
    }

    /// Standard: nur kritische Warnungen (Paul, 18.11.2026)
    var standard: Bool {
        switch self {
        case .zeitwarnung2, .funkstille, .gebiet, .fund: true
        default: false
        }
    }

    var tonart: Tonart {
        switch self {
        case .zeitwarnung2, .funkstille, .gebiet, .fund: .warnung2
        case .funk: .funk
        default: .warnung1
        }
    }

    /// Art einer Mitteilung des Servers; unbekannte Arten (neuere Server) werden nicht gezeigt.
    init?(_ m: ELMitteilung) {
        if m.art == "zeitwarnung" { self = m.stufe >= 2 ? .zeitwarnung2 : .zeitwarnung1; return }
        guard let art = ELMitteilungsArt(rawValue: m.art), art != .zeitwarnung1, art != .zeitwarnung2 else { return nil }
        self = art
    }
}

/// Je Art an oder aus, dazu der Hauptschalter. Nicht geheim, darum in den UserDefaults.
@MainActor
final class ELMitteilungsEinstellungen: ObservableObject {
    static let geraet = ELMitteilungsEinstellungen()
    private static let hauptSchluessel = "el.mitteilungen"

    private let speicher: UserDefaults
    @Published private(set) var an: Bool
    @Published private(set) var arten: [ELMitteilungsArt: Bool] = [:]

    init(speicher: UserDefaults = .standard) {
        self.speicher = speicher
        an = speicher.object(forKey: Self.hauptSchluessel) as? Bool ?? true
        for art in ELMitteilungsArt.allCases {
            arten[art] = speicher.object(forKey: Self.schluessel(art)) as? Bool ?? art.standard
        }
    }

    func ist(_ art: ELMitteilungsArt) -> Bool { arten[art] ?? art.standard }

    func setzen(an neu: Bool) {
        an = neu
        speicher.set(neu, forKey: Self.hauptSchluessel)
    }

    func setzen(_ neu: Bool, fuer art: ELMitteilungsArt) {
        arten[art] = neu
        speicher.set(neu, forKey: Self.schluessel(art))
    }

    /// Soll diese Mitteilung erscheinen?
    func zeigen(_ m: ELMitteilung) -> Bool {
        guard an, let art = ELMitteilungsArt(m) else { return false }
        return ist(art)
    }

    private static func schluessel(_ art: ELMitteilungsArt) -> String { "el.mitteilung.\(art.rawValue)" }
}

/// Merkt sich den letzten Stand und holt bei Änderung die neuen Mitteilungen.
/// Der erste Stand nach der Anmeldung gilt nur als Ausgangspunkt (keine Flut alter Mitteilungen).
@MainActor
final class ELMitteilungsAbruf {
    private let schnittstelle: ELMitteilungsSchnittstelle
    private let einstellungen: ELMitteilungsEinstellungen
    private let senden: (LageMeldung) -> Void
    private(set) var letzterStand: String?
    private var laeuft = false

    init(schnittstelle: ELMitteilungsSchnittstelle, einstellungen: ELMitteilungsEinstellungen,
         senden: @escaping (LageMeldung) -> Void) {
        self.schnittstelle = schnittstelle
        self.einstellungen = einstellungen
        self.senden = senden
    }

    func standGemeldet(_ stand: String) async {
        guard let alt = letzterStand else { letzterStand = stand; return }
        guard stand != alt else { return }
        // Läuft schon ein Abruf, meldet der nächste Takt den Stand erneut
        guard !laeuft else { return }
        laeuft = true
        defer { laeuft = false }
        do {
            let seite = try await schnittstelle.mitteilungen(seit: alt)
            letzterStand = seite.stand
            for m in seite.mitteilungen where einstellungen.zeigen(m) {
                senden(Self.meldung(m))
            }
        } catch {
            // Netzfehler: Stand bleibt, beim nächsten Takt erneut
        }
    }

    nonisolated static func meldung(_ m: ELMitteilung) -> LageMeldung {
        let art = ELMitteilungsArt(m)
        return LageMeldung(titel: m.text, text: nil, tonart: art?.tonart ?? .warnung1)
    }
}
