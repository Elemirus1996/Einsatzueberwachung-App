import Foundation

/// Adresse eines Staffel-Servers. Nur HTTPS, ohne Benutzerdaten, Pfad oder Parameter.
struct ServerAdresse: Codable, Hashable, Sendable {
    let url: URL

    init?(_ url: URL) {
        guard var teile = URLComponents(url: url, resolvingAgainstBaseURL: false),
              teile.scheme?.lowercased() == "https",
              let host = teile.host, !host.isEmpty,
              teile.user == nil, teile.password == nil
        else { return nil }
        teile.scheme = "https"
        teile.host = host.lowercased()
        teile.path = ""
        teile.query = nil
        teile.fragment = nil
        guard let basis = teile.url else { return nil }
        self.url = basis
    }

    /// Anzeigename für Bestätigungsdialoge, z. B. „einsatz.example.org“.
    var name: String {
        let host = url.host() ?? url.absoluteString
        if let port = url.port { return "\(host):\(port)" }
        return host
    }

    func pfad(_ pfad: String) -> URL {
        url.appending(path: pfad)
    }
}

/// Inhalt des bestehenden Team-QR-Codes: `https://<server>/team/login/<Anmelde-Code>`
/// (ältere Form `https://<server>/team/login?code=<Anmelde-Code>`).
struct TeamQRCode: Equatable, Sendable {
    let server: ServerAdresse
    let code: String

    init(server: ServerAdresse, code: String) {
        self.server = server
        self.code = code
    }

    init?(_ text: String) {
        guard let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let server = ServerAdresse(url),
              let teile = URLComponents(url: url, resolvingAgainstBaseURL: false)
        else { return nil }
        let pfad = teile.path.split(separator: "/").map(String.init)
        guard pfad.count >= 2, pfad[0].lowercased() == "team", pfad[1].lowercased() == "login" else { return nil }
        let roh: String?
        switch pfad.count {
        case 3: roh = pfad[2]
        case 2: roh = teile.queryItems?.first(where: { $0.name == "code" })?.value
        default: roh = nil
        }
        guard let code = Self.bereinigt(roh) else { return nil }
        self.server = server
        self.code = code
    }

    /// Wie auf der Anmeldeseite: Leerzeichen raus, Großbuchstaben. Nur Buchstaben und Ziffern.
    static func bereinigt(_ code: String?) -> String? {
        let ergebnis = (code ?? "").filter { !$0.isWhitespace }.uppercased()
        guard !ergebnis.isEmpty, ergebnis.count <= 64,
              ergebnis.allSatisfy({ $0.isASCII && ($0.isLetter || $0.isNumber) })
        else { return nil }
        return ergebnis
    }
}
