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

    private func request(_ endpunkt: String, methode: String = "GET", query: [URLQueryItem] = []) -> URLRequest {
        var url = server.pfad("api/team-mobile/app/v1/\(endpunkt)")
        if !query.isEmpty { url.append(queryItems: query) }
        var request = URLRequest(url: url)
        request.httpMethod = methode
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    private func anfrage<T: Decodable>(_ endpunkt: String, methode: String, inhalt: Data?, query: [URLQueryItem] = []) async throws -> T {
        var request = request(endpunkt, methode: methode, query: query)
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

/// Endpunkte der nativen Team-Ansicht (APP_API.md, Abschnitte 6–9).
protocol TeamAnsichtSchnittstelle: Sendable {
    func lage() async throws -> TeamLage
    func tracks(seit: Int64?) async throws -> TeamTracks
    func statusmeldung(id: String, code: Statusmeldung.Code) async throws
    func suche(id: String, aktion: Suchaktion) async throws -> SuchAntwort
    func ereignisse() async throws -> AsyncThrowingStream<TeamEreignis, Error>
    func punkt(_ punkt: TeamPunkt) async throws
}

extension TeamClient: TeamAnsichtSchnittstelle {
    func lage() async throws -> TeamLage {
        try await anfrage("lage", methode: "GET", inhalt: nil)
    }

    /// Ganze Erklärung zum Nachlesen bzw. bei neuer Version; 404 = keine hinterlegt
    func datenschutz() async throws -> Datenschutz? {
        do {
            return try await anfrage("datenschutz", methode: "GET", inhalt: nil) as Datenschutz
        } catch SchnittstellenFehler.server(let status) where status == 404 {
            return nil
        }
    }

    func tracks(seit: Int64?) async throws -> TeamTracks {
        try await anfrage("tracks", methode: "GET", inhalt: nil,
                          query: seit.map { [URLQueryItem(name: "seit", value: String($0))] } ?? [])
    }

    func statusmeldung(id: String, code: Statusmeldung.Code) async throws {
        struct Antwort: Decodable { var doppelt: Bool? }
        let _: Antwort = try await anfrage("statusmeldung", methode: "POST",
                                           inhalt: try JSONEncoder().encode(["id": id, "code": code.rawValue]))
    }

    /// Staffellogo als PNG (Abschnitt 10, der Server wandelt andere Formate um). `nil`, wenn keins hinterlegt ist (404).
    func logo() async throws -> Data? {
        var request = request("logo")
        request.setValue("image/png", forHTTPHeaderField: "Accept")
        let (daten, antwort) = try await session.data(for: request)
        guard let http = antwort as? HTTPURLResponse else { throw SchnittstellenFehler.antwortUngueltig }
        switch http.statusCode {
        case 200: return daten
        case 404: return nil
        case 410: throw SchnittstellenFehler.beendet
        default: throw SchnittstellenFehler.server(status: http.statusCode)
        }
    }

    /// `POST /suche` (Abschnitt 11). 409 → `SuchFehler.abgelehnt` mit Stand, 403 → `SuchFehler.verboten`.
    func suche(id: String, aktion: Suchaktion) async throws -> SuchAntwort {
        var request = request("suche", methode: "POST")
        request.httpBody = try JSONEncoder().encode(["id": id, "aktion": aktion.rawValue])
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let (daten, antwort) = try await session.data(for: request)
        guard let http = antwort as? HTTPURLResponse else { throw SchnittstellenFehler.antwortUngueltig }
        let inhalt = try? JSONDecoder.server.decode(SuchAntwort.self, from: daten)
        switch http.statusCode {
        case 200..<300:
            guard let inhalt else { throw SchnittstellenFehler.antwortUngueltig }
            return inhalt
        case 409: throw SuchFehler.abgelehnt(text: inhalt?.error, suche: inhalt?.suche)
        case 403: throw SuchFehler.verboten(text: inhalt?.error)
        case 410: throw SchnittstellenFehler.beendet
        default: throw SchnittstellenFehler.server(status: http.statusCode)
        }
    }

    /// `POST /punkt` (Abschnitt 13). 400/409 → `PunktFehler.abgelehnt` mit Text des Servers (nicht wiederholen).
    func punkt(_ punkt: TeamPunkt) async throws {
        var request = request("punkt", methode: "POST")
        request.httpBody = try JSONEncoder.server.encode(punkt)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let (daten, antwort) = try await session.data(for: request)
        guard let http = antwort as? HTTPURLResponse else { throw SchnittstellenFehler.antwortUngueltig }
        struct Fehler: Decodable { var error: String? }
        switch http.statusCode {
        case 200..<300: return
        case 400, 409: throw PunktFehler.abgelehnt(text: (try? JSONDecoder().decode(Fehler.self, from: daten))?.error)
        case 410: throw SchnittstellenFehler.beendet
        default: throw SchnittstellenFehler.server(status: http.statusCode)
        }
    }

    /// Server-Sent Events. Endet, wenn die Verbindung abbricht; 410 beim Verbinden → `beendet`.
    func ereignisse() async throws -> AsyncThrowingStream<TeamEreignis, Error> {
        var request = request("ereignisse")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        let (bytes, antwort) = try await URLSession.ereignisse.bytes(for: request)
        guard let http = antwort as? HTTPURLResponse else { throw SchnittstellenFehler.antwortUngueltig }
        if http.statusCode == 410 { throw SchnittstellenFehler.beendet }
        guard http.statusCode == 200 else { throw SchnittstellenFehler.server(status: http.statusCode) }
        return AsyncThrowingStream { fortsetzung in
            let aufgabe = Task {
                var leser = SSELeser()
                var zeile: [UInt8] = []
                do {
                    // Eigene Zeilenaufteilung: `bytes.lines` verschluckt Leerzeilen, die bei SSE ein Ereignis abschließen
                    for try await byte in bytes {
                        guard byte == UInt8(ascii: "\n") else {
                            zeile.append(byte)
                            continue
                        }
                        if zeile.last == UInt8(ascii: "\r") { zeile.removeLast() }
                        if let ereignis = leser.zeile(String(decoding: zeile, as: UTF8.self)).flatMap(TeamEreignis.init) {
                            fortsetzung.yield(ereignis)
                        }
                        zeile.removeAll(keepingCapacity: true)
                    }
                    fortsetzung.finish()
                } catch {
                    fortsetzung.finish(throwing: error)
                }
            }
            fortsetzung.onTermination = { _ in aufgabe.cancel() }
        }
    }
}

enum PunktFehler: Error, Equatable {
    /// Vom Server abgelehnt (ungültig oder 50 Punkte erreicht); Text des Servers zum Anzeigen
    case abgelehnt(text: String?)
}

enum SuchFehler: Error, Equatable {
    /// 409: Übergang nicht erlaubt (z. B. EL hat inzwischen umgeschaltet); Stand des Servers anbei
    case abgelehnt(text: String?, suche: TeamLage.Suche?)
    /// 403: Teams dürfen die Suche auf diesem Server nicht steuern
    case verboten(text: String?)
}

enum AnmeldeFehler: Error, Equatable {
    /// 401: Code falsch oder kein laufender Einsatz
    case codeUngueltig
    /// 404: Team gibt es nicht mehr
    case teamUnbekannt
    /// 429: Limit von nginx, frühestens nach 30 s erneut versuchen
    case zuVieleVersuche
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
    var uebung: Bool?
    var staffel: String?
    var server: ServerInfo?
    var teams: [Eintrag]
    /// Vor dem Token: Erklärung der Staffel, `keine` bei `null`, `fehlt` bei älterem Server (#148)
    var datenschutz: DatenschutzAngabe = .fehlt

    private enum Schluessel: String, CodingKey { case einsatz, uebung, staffel, server, teams, datenschutz }

    init(einsatz: String? = nil, uebung: Bool? = nil, staffel: String? = nil, server: ServerInfo? = nil,
         teams: [Eintrag], datenschutz: DatenschutzAngabe = .fehlt) {
        self.einsatz = einsatz
        self.uebung = uebung
        self.staffel = staffel
        self.server = server
        self.teams = teams
        self.datenschutz = datenschutz
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Schluessel.self)
        einsatz = try c.decodeIfPresent(String.self, forKey: .einsatz)
        uebung = try c.decodeIfPresent(Bool.self, forKey: .uebung)
        staffel = try c.decodeIfPresent(String.self, forKey: .staffel)
        server = try c.decodeIfPresent(ServerInfo.self, forKey: .server)
        teams = try c.decode([Eintrag].self, forKey: .teams)
        datenschutz = DatenschutzAngabe(c, .datenschutz)
    }
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
            var teamId: String?
            var teamName: String
            var expiresAt: Date
        }
        let antwort: Antwort = try await anfrage("login", inhalt: ["code": qr.code, "teamId": teamId])
        return TeamSitzung(server: qr.server, token: antwort.token, teamId: antwort.teamId,
                           teamName: antwort.teamName, expiresAt: antwort.expiresAt)
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
        case 429: throw AnmeldeFehler.zuVieleVersuche
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

    /// Für `/ereignisse`: Der Server schickt alle 20 s ein `: ping`, nach 60 s Stille gilt die Verbindung als tot.
    static let ereignisse: URLSession = {
        let konfiguration = URLSessionConfiguration.ephemeral
        konfiguration.timeoutIntervalForRequest = 60
        konfiguration.timeoutIntervalForResource = 24 * 3600
        konfiguration.waitsForConnectivity = false
        return URLSession(configuration: konfiguration)
    }()
}
