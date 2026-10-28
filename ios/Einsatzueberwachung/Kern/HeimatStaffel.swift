import Foundation
import UIKit

/// `server` in `/config` und `/login/teams` (APP_API.md, Abschnitt 10).
struct ServerInfo: Decodable, Equatable, Sendable {
    var kennung: String
    var staffel: String?
    var logoVersion: String?
}

/// Die eigene Staffel des Nutzers: Nur ihr Name und Logo erscheinen auf der Startseite.
/// Erkannt wird sie an der festen Server-Kennung, nicht an der Adresse. Eine fremde Staffel
/// im gemeinsamen Einsatz ändert nichts. Nicht geheim, darum in den UserDefaults.
@MainActor
final class HeimatStaffel: ObservableObject {
    struct Eintrag: Codable, Equatable {
        var kennung: String
        var staffel: String
        /// Fassung des gespeicherten Logos; `nil`, solange keins geladen ist.
        var logoVersion: String?
    }

    static let hoechsteLogoGroesse = 2 * 1024 * 1024

    @Published private(set) var eintrag: Eintrag?
    @Published private(set) var logo: UIImage?
    private var abgelehnt: Set<String>

    private let speicher: UserDefaults
    private let logoDatei: URL

    init(speicher: UserDefaults = .standard, ordner: URL? = nil) {
        self.speicher = speicher
        let basis = ordner ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        self.logoDatei = basis.appending(path: "heimatstaffel-logo.png")
        self.eintrag = speicher.data(forKey: "heimat.eintrag").flatMap { try? JSONDecoder().decode(Eintrag.self, from: $0) }
        self.abgelehnt = Set(speicher.stringArray(forKey: "heimat.abgelehnt") ?? [])
        if eintrag?.logoVersion != nil, let daten = try? Data(contentsOf: logoDatei) {
            logo = UIImage(data: daten)
        }
    }

    /// Fragen nur, solange keine Heimatstaffel gewählt ist und dieser Server nicht schon abgelehnt wurde.
    func sollFragen(_ info: ServerInfo) -> Bool {
        eintrag == nil && !abgelehnt.contains(info.kennung)
    }

    func istHeimat(_ info: ServerInfo) -> Bool {
        eintrag?.kennung == info.kennung
    }

    func uebernehmen(_ info: ServerInfo, name: String) {
        eintrag = Eintrag(kennung: info.kennung, staffel: name, logoVersion: nil)
        logoLoeschen()
        speichern()
    }

    func ablehnen(_ info: ServerInfo) {
        abgelehnt.insert(info.kennung)
        speicher.set(Array(abgelehnt), forKey: "heimat.abgelehnt")
    }

    /// In den Einstellungen: Heimatstaffel vergessen, beim nächsten Verbinden wieder fragen.
    func entfernen() {
        eintrag = nil
        abgelehnt = []
        logoLoeschen()
        speicher.removeObject(forKey: "heimat.eintrag")
        speicher.removeObject(forKey: "heimat.abgelehnt")
    }

    /// Nach `/config` bzw. Anmeldung: Name übernehmen und das Logo nur laden, wenn sich `logoVersion` geändert hat.
    /// `laden` liefert die PNG-Daten oder `nil`, wenn der Server kein Logo hat (404).
    func aktualisieren(_ info: ServerInfo, laden: () async throws -> Data?) async {
        guard var neu = eintrag, istHeimat(info) else { return }
        if let name = info.staffel, !name.isEmpty { neu.staffel = name }
        if info.logoVersion != neu.logoVersion {
            if info.logoVersion == nil {
                logoLoeschen()
                neu.logoVersion = nil
            } else {
                do {
                    if let daten = try await laden(), daten.count <= Self.hoechsteLogoGroesse, let bild = UIImage(data: daten) {
                        try? daten.write(to: logoDatei, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                        logo = bild
                    } else {
                        logoLoeschen()
                    }
                    // Auch ohne Bild merken, damit die App bei 404 nicht bei jedem Start neu fragt
                    neu.logoVersion = info.logoVersion
                } catch {
                    // Netzfehler: beim nächsten Mal erneut versuchen
                }
            }
        }
        guard eintrag == nil || eintrag?.kennung == neu.kennung else { return }
        eintrag = neu
        speichern()
    }

    private func logoLoeschen() {
        logo = nil
        try? FileManager.default.removeItem(at: logoDatei)
    }

    private func speichern() {
        speicher.set(try? JSONEncoder().encode(eintrag), forKey: "heimat.eintrag")
    }
}
