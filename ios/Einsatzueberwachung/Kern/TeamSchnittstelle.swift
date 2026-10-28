import Foundation

enum SchnittstellenFehler: Error, Equatable {
    /// HTTP 410: Token ungültig, abgelaufen, Einsatz vorbei oder Team gelöscht. Immer als `stop` behandeln.
    case beendet
    case server(status: Int)
    case antwortUngueltig
}

/// Endpunkte der Rolle Team unter `api/team-mobile/app/v1`.
protocol TeamSchnittstelle: Sendable {
    func config() async throws -> TeamConfig
    func positionenSenden(_ positionen: [Position]) async throws -> SteuerAntwort
    func status() async throws -> SteuerAntwort
}

struct TeamClient: TeamSchnittstelle {
    private let server: ServerAdresse
    private let token: String
    private let session: URLSession

    init(sitzung: TeamSitzung, session: URLSession = .einsatz) {
        self.server = sitzung.server
        self.token = sitzung.token
        self.session = session
    }

    func config() async throws -> TeamConfig {
        try await anfrage("config", methode: "GET", inhalt: nil)
    }

    func positionenSenden(_ positionen: [Position]) async throws -> SteuerAntwort {
        struct Inhalt: Encodable { let positions: [Position] }
        return try await anfrage("positions", methode: "POST", inhalt: try JSONEncoder().encode(Inhalt(positions: positionen)))
    }

    func status() async throws -> SteuerAntwort {
        try await anfrage("status", methode: "GET", inhalt: nil)
    }

    private func anfrage<T: Decodable>(_ endpunkt: String, methode: String, inhalt: Data?) async throws -> T {
        var request = URLRequest(url: server.pfad("api/team-mobile/app/v1/\(endpunkt)"))
        request.httpMethod = methode
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let inhalt {
            request.httpBody = inhalt
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (daten, antwort) = try await session.data(for: request)
        guard let http = antwort as? HTTPURLResponse else { throw SchnittstellenFehler.antwortUngueltig }
        if http.statusCode == 410 { throw SchnittstellenFehler.beendet }
        guard (200..<300).contains(http.statusCode) else { throw SchnittstellenFehler.server(status: http.statusCode) }
        do {
            return try JSONDecoder.server.decode(T.self, from: daten)
        } catch {
            throw SchnittstellenFehler.antwortUngueltig
        }
    }
}

enum AnmeldeFehler: Error, Equatable {
    /// 401: Code falsch oder kein laufender Einsatz
    case codeUngueltig
    /// 404: Team gibt es nicht mehr
    case teamUnbekannt
    case server(status: Int)
    case antwortUngueltig
}

struct TeamAuswahl: Decodable, Equatable, Sendable {
    struct Eintrag: Decodable, Equatable, Identifiable, Sendable {
        var teamId: String
        var teamName: String
        var dogName: String?
        var hundefuehrerName: String?
        var id: String { teamId }
    }

    var einsatz: String?
    var staffel: String?
    var teams: [Eintrag]
}

/// Native Anmeldung mit dem Anmelde-Code aus dem QR-Code (`login/teams`, `login`), ohne Token.
struct TeamAnmeldung: Sendable {
    let qr: TeamQRCode
    var session: URLSession = .einsatz

    func teams() async throws -> TeamAuswahl {
        try await anfrage("login/teams", inhalt: ["code": qr.code])
    }

    func anmelden(teamId: String) async throws -> TeamSitzung {
        struct Antwort: Decodable {
            var token: String
            var teamName: String
            var expiresAt: Date
        }
        let antwort: Antwort = try await anfrage("login", inhalt: ["code": qr.code, "teamId": teamId])
        return TeamSitzung(server: qr.server, token: antwort.token, teamName: antwort.teamName, expiresAt: antwort.expiresAt)
    }

    private func anfrage<T: Decodable>(_ endpunkt: String, inhalt: [String: String]) async throws -> T {
        var request = URLRequest(url: qr.server.pfad("api/team-mobile/app/v1/\(endpunkt)"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(inhalt)
        let (daten, antwort) = try await session.data(for: request)
        guard let http = antwort as? HTTPURLResponse else { throw AnmeldeFehler.antwortUngueltig }
        switch http.statusCode {
        case 200..<300: break
        case 401: throw AnmeldeFehler.codeUngueltig
        case 404 where endpunkt == "login": throw AnmeldeFehler.teamUnbekannt
        default: throw AnmeldeFehler.server(status: http.statusCode)
        }
        do {
            return try JSONDecoder.server.decode(T.self, from: daten)
        } catch {
            throw AnmeldeFehler.antwortUngueltig
        }
    }
}

extension URLSession {
    /// Ohne Cache und Cookies, damit nichts von Einsätzen auf dem Gerät liegen bleibt.
    static let einsatz: URLSession = {
        let konfiguration = URLSessionConfiguration.ephemeral
        konfiguration.timeoutIntervalForRequest = 20
        konfiguration.waitsForConnectivity = false
        return URLSession(configuration: konfiguration)
    }()
}
