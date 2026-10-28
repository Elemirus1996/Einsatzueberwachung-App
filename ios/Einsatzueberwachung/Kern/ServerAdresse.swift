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

/// Inhalt des bestehenden Team-QR-Codes: `https://<server>/team/login?code=<Anmelde-Code>`.
struct TeamQRCode: Equatable, Sendable {
    let server: ServerAdresse
    let code: String

    init?(_ text: String) {
        guard let url = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines)),
              let server = ServerAdresse(url),
              let teile = URLComponents(url: url, resolvingAgainstBaseURL: false),
              teile.path.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() == "team/login",
              let code = teile.queryItems?.first(where: { $0.name == "code" })?.value?
                  .trimmingCharacters(in: .whitespaces),
              !code.isEmpty
        else { return nil }
        self.server = server
        self.code = code
    }
}
