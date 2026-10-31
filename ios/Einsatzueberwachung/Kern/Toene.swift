import AudioToolbox
import Foundation

/// Arten von Meldungen, für die der Benutzer einen eigenen Ton wählt (APP_API.md, Einsatzleitung Abschnitt 7 „Töne in den Apps“).
/// Der Server schickt nur eindeutige Ereignisse; welcher Ton dazu gehört, entscheidet das Gerät.
enum Tonart: String, CaseIterable, Identifiable, Sendable {
    case mitteilung, navigationsziel, warnung1, warnung2, punktVomTeam, funk

    var id: String { rawValue }

    var titel: String {
        switch self {
        case .mitteilung: "Mitteilung der Einsatzleitung"
        case .navigationsziel: "Navigationsziel"
        case .warnung1: "Warnung Stufe 1"
        case .warnung2: "Warnung Stufe 2"
        case .punktVomTeam: "Punkt von einem Team"
        case .funk: "Funk"
        }
    }

    /// Für die Rolle Team oder Einsatzleitung
    var fuerTeam: Bool { self == .mitteilung || self == .navigationsziel }

    /// Warnung Stufe 2 bleibt immer hörbar (Entscheidung 31.10.2026)
    var darfAus: Bool { self != .warnung2 }

    /// Ab Werk: jede Art ein eigener Ton
    var standard: TonWahl {
        switch self {
        case .mitteilung: .ton(1014)
        case .navigationsziel: .ton(1013)
        case .warnung1: .ton(1007)
        case .warnung2: .ton(1005)
        case .punktVomTeam: .ton(1010)
        case .funk: .ton(1009)
        }
    }
}

/// Ton aus den Systemtönen, nur Vibration oder aus.
enum TonWahl: Equatable, Hashable, Sendable {
    case ton(UInt32)
    case vibration
    case aus

    /// Auswahl in den Einstellungen (Systemtöne von iOS, keine eigenen Dateien)
    static let toene: [(name: String, id: UInt32)] = [
        ("Dreiklang", 1007), ("Glocke", 1013), ("Glas", 1009), ("Horn", 1010),
        ("Elektronisch", 1014), ("Alarm", 1005), ("Fanfare", 1025), ("Telegraf", 1033)
    ]

    var speicherWert: String {
        switch self {
        case .ton(let id): "\(id)"
        case .vibration: "vibration"
        case .aus: "aus"
        }
    }

    init?(speicherWert: String) {
        switch speicherWert {
        case "vibration": self = .vibration
        case "aus": self = .aus
        default:
            guard let id = UInt32(speicherWert), Self.toene.contains(where: { $0.id == id }) else { return nil }
            self = .ton(id)
        }
    }

    var name: String {
        switch self {
        case .ton(let id): Self.toene.first { $0.id == id }?.name ?? "Ton"
        case .vibration: "Nur Vibration"
        case .aus: "Aus"
        }
    }
}

/// Gewählte Töne je Art, gespeichert auf dem Gerät (nicht je Server).
@MainActor
final class Toene: ObservableObject {
    /// Einstellung dieses Geräts (Einstellungen, Team-Ansicht, Einsatzleitung)
    static let geraet = Toene()

    private let speicher: UserDefaults
    private let abspielen: (TonWahl) -> Void
    @Published private(set) var wahl: [Tonart: TonWahl] = [:]

    init(speicher: UserDefaults = .standard, abspielen: @escaping (TonWahl) -> Void = Toene.systemAbspielen) {
        self.speicher = speicher
        self.abspielen = abspielen
        for art in Tonart.allCases {
            let gespeichert = speicher.string(forKey: Self.schluessel(art)).flatMap(TonWahl.init(speicherWert:))
            wahl[art] = gespeichert.flatMap { $0 == .aus && !art.darfAus ? nil : $0 } ?? art.standard
        }
    }

    func wahl(fuer art: Tonart) -> TonWahl { wahl[art] ?? art.standard }

    /// Auswahlmöglichkeiten der Art („Aus“ nur, wenn erlaubt)
    func moeglich(fuer art: Tonart) -> [TonWahl] {
        TonWahl.toene.map { .ton($0.id) } + [.vibration] + (art.darfAus ? [.aus] : [])
    }

    func setzen(_ neu: TonWahl, fuer art: Tonart) {
        guard neu != .aus || art.darfAus else { return }
        wahl[art] = neu
        speicher.set(neu.speicherWert, forKey: Self.schluessel(art))
    }

    func spielen(_ art: Tonart) { abspielen(wahl(fuer: art)) }

    func probe(_ wahl: TonWahl) { abspielen(wahl) }

    /// Ton für lokale Mitteilungen im Hintergrund: bei „Aus“ ohne Ton
    func mitTon(_ art: Tonart) -> Bool { wahl(fuer: art) != .aus }

    nonisolated static func systemAbspielen(_ wahl: TonWahl) {
        switch wahl {
        case .ton(let id):
            AudioServicesPlaySystemSound(id)
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        case .vibration: AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        case .aus: break
        }
    }

    private static func schluessel(_ art: Tonart) -> String { "ton.\(art.rawValue)" }
}
