import Foundation

/// Was die App als Nächstes tun soll (docs/APP_API.md im Server-Repo, Abschnitt 5).
enum Modus: String, Decodable, Sendable {
    case track, pause, stop
}

/// Antwort von `POST /positions` und `GET /status`.
struct SteuerAntwort: Decodable, Equatable, Sendable {
    var mode: Modus
    var intervalSec: Int?
    var uploadEverySec: Int?
    var pauseCheckSec: Int?
    var expiresAt: Date?
    var accepted: Int?

    static let stop = SteuerAntwort(mode: .stop)
}

/// Antwort von `GET /config`. `positionUrl` und `statusUrl` werden bewusst nicht übernommen:
/// Die App sendet nur an den Server, an dem sie sich angemeldet hat.
struct TeamConfig: Decodable, Equatable, Sendable {
    var apiVersion: Int
    var teamId: String
    var teamName: String
    var dogName: String?
    var einsatz: String?
    var staffel: String?
    var expiresAt: Date
    var mode: Modus
    var intervalSec: Int
    var uploadEverySec: Int
    var pauseCheckSec: Int
    var server: ServerInfo?
    /// Nur `version` (#148)
    var datenschutz: Datenschutz?

    var steuerung: SteuerAntwort {
        SteuerAntwort(mode: mode, intervalSec: intervalSec, uploadEverySec: uploadEverySec,
                      pauseCheckSec: pauseCheckSec, expiresAt: expiresAt)
    }
}

/// Eine Position, wie sie hochgeladen wird. `timestamp` ist die Aufnahmezeit in Unix-Millisekunden (UTC).
struct Position: Codable, Equatable, Sendable {
    var lat: Double
    var lng: Double
    var accuracy: Double?
    var timestamp: Int64
    var battery: Int?
}

/// Zugangsdaten der Rolle Team. Liegen ausschließlich im Schlüsselbund.
struct TeamSitzung: Codable, Equatable, Sendable {
    var server: ServerAdresse
    var token: String
    var teamId: String?
    var teamName: String
    var expiresAt: Date
}

extension JSONDecoder {
    /// Datumsangaben des Servers: ISO 8601, mit oder ohne Sekundenbruchteile.
    static let server: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let text = try decoder.singleValueContainer().decode(String.self)
            if let datum = try? Date(text, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)) {
                return datum
            }
            if let datum = try? Date(text, strategy: Date.ISO8601FormatStyle()) {
                return datum
            }
            throw DecodingError.dataCorrupted(.init(codingPath: decoder.codingPath, debugDescription: "Datum ungültig"))
        }
        return decoder
    }()
}

extension JSONEncoder {
    /// Zeiten an den Server: UTC mit `Z` (z. B. `2026-10-31T09:12:03Z`)
    static let server: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        return encoder
    }()
}
