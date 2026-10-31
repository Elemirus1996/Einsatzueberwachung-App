import Foundation

/// Zugangsdaten der Einsatzleitung. Nur im Schlüsselbund; gültig 24 h und bis das Staffel-Passwort geändert wird.
struct ELSitzung: Codable, Equatable, Sendable {
    var server: ServerAdresse
    var token: String
    var expiresAt: Date
    /// Gerätename aus der Anmeldung, geht beim Verlängern wieder mit (nur Anzeige im Audit-Log)
    var geraet: String?
    /// Bis wann `/verlaengern` höchstens geht (72 h ab Anmeldung mit Passwort), sobald bekannt
    var verlaengerbarBis: Date?
}

/// Hält das aktuelle Token der Einsatzleitung für alle Teile (Lage, Live, Aktionen, Ortung).
/// Nach `/verlaengern` (#141) gilt nur noch das neue Token; Anfragen mit dem alten bekommen 410 bzw. `stop`.
/// Das ist dann keine Abmeldung: Der Client wiederholt mit dem neuen Token.
final class ELZugang: @unchecked Sendable {
    private let sperre = NSLock()
    private var _sitzung: ELSitzung
    private var _verlaengerungLaeuft = false

    init(_ sitzung: ELSitzung) { _sitzung = sitzung }

    var sitzung: ELSitzung { sperre.withLock { _sitzung } }
    var verlaengerungLaeuft: Bool { sperre.withLock { _verlaengerungLaeuft } }

    func ersetzen(_ neu: ELSitzung) { sperre.withLock { _sitzung = neu } }
    func verlaengerungBeginnt() { sperre.withLock { _verlaengerungLaeuft = true } }
    func verlaengerungEndet() { sperre.withLock { _verlaengerungLaeuft = false } }

    /// Gibt es schon ein anderes Token als `token`? Läuft gerade eine Verlängerung, wird kurz auf ihr Ende gewartet,
    /// weil der Server das alte Token sperrt, bevor die Antwort mit dem neuen ankommt.
    func neueresToken(als token: String, warten: Duration = .seconds(20)) async -> Bool {
        var rest = warten
        while verlaengerungLaeuft && rest > .zero {
            try? await Task.sleep(for: .milliseconds(100))
            rest -= .milliseconds(100)
        }
        return sitzung.token != token
    }
}

enum ELVerlaengerFehler: Error, Equatable {
    /// 409: kein Einsatz läuft oder 72 h erreicht – nicht wiederholen, nach Ablauf neu anmelden
    case nichtMoeglich(String)
}

/// Was die Verlängerung braucht (testbar ohne Netz).
protocol ELVerlaengerSchnittstelle: Sendable {
    func verlaengern(geraet: String?) async throws -> ELSitzung
}

/// Antwort von `GET /lage` der Einsatzleitung (APP_API.md „Rolle Einsatzleitung – native App“, Abschnitt 5).
struct ELLage: Decodable, Equatable, Sendable {
    struct Einsatz: Decodable, Equatable, Sendable {
        var aktiv: Bool
        var text: String?
        var stichwort: String?
        var ort: String?
        var szenario: String?
        var uebung: Bool?
        var beginn: Date?
        var einsatzleiter: String?
        var fuehrungsassistent: String?
    }

    struct Team: Decodable, Equatable, Identifiable, Sendable {
        struct Gebiet: Decodable, Equatable, Sendable {
            var id: String?
            var name: String?
            var farbe: String?
        }
        struct Geraet: Decodable, Equatable, Sendable {
            var id: String?
            var name: String?
            var zeit: Date?
            var akku: Int?
        }
        struct Pause: Decodable, Equatable, Sendable {
            var erforderlichBis: Date?
        }

        var teamId: String
        var teamName: String
        var dogName: String?
        var hundefuehrerName: String?
        var helfer: [String]?
        var suche: TeamLage.Suche
        var warnstufe: Int?
        var warnung1Min: Int?
        var warnung2Min: Int?
        var suchgebiet: Gebiet?
        var halsband: Geraet?
        var handy: Geraet?
        var pause: Pause?

        var id: String { teamId }
    }

    struct Wetter: Decodable, Equatable, Sendable {
        var temperatur: Double?
        var wind: Double?
        var beschreibung: String?
        var zeit: Date?
    }

    var mode: Modus?
    var serverZeit: Date?
    var einsatz: Einsatz
    var teams: [Team]
    var wetter: Wetter?
}

/// Live-Ereignisse der Einsatzleitung (Abschnitt 8).
enum ELEreignis: Equatable, Sendable {
    case team(teamId: String?)
    case position(ELPosition)
    case notiz
    case funk
    case vermisste
    case einsatz(aktiv: Bool)
    case warnung(teamId: String?, stufe: Int, text: String)
    case stop
    case verdraengt(wiederholenNachSec: Int)

    init?(_ roh: SSELeser.Ereignis) {
        let json = (try? JSONSerialization.jsonObject(with: Data(roh.daten.utf8))) as? [String: Any] ?? [:]
        switch roh.name {
        case "team": self = .team(teamId: json["teamId"] as? String)
        case "position":
            guard let p = try? JSONDecoder.server.decode(ELPosition.self, from: Data(roh.daten.utf8)) else { return nil }
            self = .position(p)
        case "notiz": self = .notiz
        case "funk": self = .funk
        case "vermisste": self = .vermisste
        case "einsatz": self = .einsatz(aktiv: json["aktiv"] as? Bool ?? false)
        case "warnung":
            self = .warnung(teamId: json["teamId"] as? String, stufe: json["stufe"] as? Int ?? 1, text: json["text"] as? String ?? "Warnung")
        case "stop": self = .stop
        case "verdraengt": self = .verdraengt(wiederholenNachSec: json["wiederholenNachSec"] as? Int ?? 60)
        default: return nil
        }
    }
}

enum ELAnmeldeFehler: Error, Equatable {
    /// 401
    case passwortFalsch
    /// 429: gesperrt nach Fehlversuchen
    case gesperrt(sekunden: Int)
    /// 409: auf dem Server noch kein Staffel-Passwort eingerichtet
    case keinPasswort
    /// Netz nicht erreichbar: meist ist das VPN aus
    case nichtErreichbar
    case server(status: Int)
    case antwortUngueltig
}

/// Anmeldung der Einsatzleitung (`POST /login`), nur über die interne Adresse im VPN.
struct ELAnmeldung: Sendable {
    let server: ServerAdresse
    var session: URLSession = .einsatz

    struct Ergebnis: Decodable, Sendable {
        var token: String
        var expiresAt: Date
        var server: ServerInfo?
    }

    func anmelden(passwort: String, geraet: String?) async throws -> (ELSitzung, ServerInfo?) {
        var request = URLRequest(url: server.pfad("api/el/app/v1/login"))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        var inhalt = ["passwort": passwort]
        if let geraet, !geraet.isEmpty { inhalt["geraet"] = String(geraet.prefix(40)) }
        request.httpBody = try JSONEncoder().encode(inhalt)
        let daten: Data
        let antwort: URLResponse
        do {
            (daten, antwort) = try await session.data(for: request)
        } catch let fehler as URLError where Self.netzFehler.contains(fehler.code) {
            throw ELAnmeldeFehler.nichtErreichbar
        }
        guard let http = antwort as? HTTPURLResponse else { throw ELAnmeldeFehler.antwortUngueltig }
        switch http.statusCode {
        case 200..<300:
            guard let ergebnis = try? JSONDecoder.server.decode(Ergebnis.self, from: daten) else { throw ELAnmeldeFehler.antwortUngueltig }
            let name = geraet.flatMap { $0.isEmpty ? nil : String($0.prefix(40)) }
            return (ELSitzung(server: server, token: ergebnis.token, expiresAt: ergebnis.expiresAt, geraet: name), ergebnis.server)
        case 401: throw ELAnmeldeFehler.passwortFalsch
        case 429:
            let sekunden = ((try? JSONSerialization.jsonObject(with: daten)) as? [String: Any])?["wiederholenNachSec"] as? Int
            throw ELAnmeldeFehler.gesperrt(sekunden: sekunden ?? 300)
        case 409: throw ELAnmeldeFehler.keinPasswort
        // Öffentlicher Zugang: alle EL-Pfade 404 → nicht im VPN
        case 404: throw ELAnmeldeFehler.nichtErreichbar
        default: throw ELAnmeldeFehler.server(status: http.statusCode)
        }
    }

    static let netzFehler: Set<URLError.Code> = [
        .cannotFindHost, .cannotConnectToHost, .timedOut, .networkConnectionLost, .notConnectedToInternet, .dnsLookupFailed,
    ]
}

/// Endpunkte der Einsatzleitung mit Token. Erfüllt `TeamSchnittstelle`, damit dieselbe Ortungssteuerung
/// (`TeamOrtung`) den Standort der Einsatzleitung sendet (`POST /positions`, `GET /status`).
/// Was die Übersicht der Einsatzleitung braucht (testbar ohne Netz).
protocol ELLageSchnittstelle: Sendable {
    func lage() async throws -> ELLage
    func ereignisse() async throws -> AsyncThrowingStream<ELEreignis, Error>
}

struct ELClient: TeamSchnittstelle, ELLageSchnittstelle, ELAktionsSchnittstelle, ELKartenSchnittstelle, ELVermissteSchnittstelle,
                ELVerlaengerSchnittstelle {
    let zugang: ELZugang
    var session: URLSession = .einsatz
    var ereignisSession: URLSession = .ereignisse

    init(zugang: ELZugang, session: URLSession = .einsatz, ereignisSession: URLSession = .ereignisse) {
        self.zugang = zugang
        self.session = session
        self.ereignisSession = ereignisSession
    }

    init(sitzung: ELSitzung, session: URLSession = .einsatz) {
        self.init(zugang: ELZugang(sitzung), session: session)
    }

    var sitzung: ELSitzung { zugang.sitzung }

    func config() async throws -> TeamConfig { throw SchnittstellenFehler.server(status: 404) }

    func positionenSenden(_ positionen: [Position]) async throws -> SteuerAntwort {
        struct Inhalt: Encodable { let positions: [Position] }
        return try await anfrage("positions", methode: "POST", inhalt: try JSONEncoder().encode(Inhalt(positions: positionen)))
    }

    func status() async throws -> SteuerAntwort { try await anfrage("status", methode: "GET", inhalt: nil) }

    func lage() async throws -> ELLage { try await anfrage("lage", methode: "GET", inhalt: nil) }

    func logo() async throws -> Data? {
        let (daten, http) = try await roh("logo", methode: "GET", inhalt: nil)
        switch http.statusCode {
        case 200: return daten
        case 404: return nil
        case 410: throw SchnittstellenFehler.beendet
        default: throw SchnittstellenFehler.server(status: http.statusCode)
        }
    }

    func ausfuehren(_ aktion: ELAktion) async throws {
        var inhalt = aktion.inhalt
        inhalt["id"] = .text(aktion.id)
        let (daten, http) = try await roh(aktion.pfad, methode: "POST", inhalt: try JSONEncoder().encode(inhalt))
        switch http.statusCode {
        case 200..<300: return
        case 410: throw SchnittstellenFehler.beendet
        // 400 falsche Eingabe, 404 unbekanntes Team bzw. Notiz, 409 nicht erlaubt (auch: kein Einsatz läuft)
        case 400, 404, 409:
            let text = ((try? JSONSerialization.jsonObject(with: daten)) as? [String: Any])?["error"] as? String
            throw ELAktionsFehler.abgelehnt(text ?? (http.statusCode == 404 ? "Nicht gefunden." : "Nicht möglich."))
        default: throw SchnittstellenFehler.server(status: http.statusCode)
        }
    }

    func notizen(vor: String?) async throws -> ELNotizSeite {
        try await anfrage(vor.map { "notizen?vor=\($0)&anzahl=50" } ?? "notizen?anzahl=50", methode: "GET", inhalt: nil)
    }

    func vermisste() async throws -> ELVermisste { try await anfrage("vermisste", methode: "GET", inhalt: nil) }

    func vermissteSpeichern(id: String, profilId: String?, version: String?, felder: [String: ELWert]) async throws -> (profilId: String, version: String) {
        var inhalt = felder
        inhalt["id"] = .text(id)
        if let profilId { inhalt["profilId"] = .text(profilId) }
        if let version { inhalt["version"] = .text(version) }
        let (daten, http) = try await roh("vermisste", methode: "POST", inhalt: try JSONEncoder().encode(inhalt))
        struct Antwort: Decodable { var profilId: String; var version: String }
        struct Konflikt: Decodable { var error: String?; var profil: ELVermisstenprofil? }
        switch http.statusCode {
        case 200..<300:
            guard let a = try? JSONDecoder.server.decode(Antwort.self, from: daten) else { throw SchnittstellenFehler.antwortUngueltig }
            return (a.profilId, a.version)
        case 409:
            let k = try? JSONDecoder.server.decode(Konflikt.self, from: daten)
            if let profil = k?.profil { throw ELVermissteFehler.geaendert(profil) }
            throw ELVermissteFehler.abgelehnt(k?.error ?? "Nicht möglich.")
        case 400:
            throw ELVermissteFehler.abgelehnt((try? JSONDecoder.server.decode(Konflikt.self, from: daten))?.error ?? "Eingabe ungültig.")
        case 410: throw SchnittstellenFehler.beendet
        default: throw SchnittstellenFehler.server(status: http.statusCode)
        }
    }

    func karte() async throws -> ELKarte { try await anfrage("karte", methode: "GET", inhalt: nil) }

    func tracks(seit: Int64?) async throws -> ELTracks {
        try await anfrage(seit.map { "tracks?seit=\($0)" } ?? "tracks", methode: "GET", inhalt: nil)
    }

    func ergebnisse() async throws -> [ELErgebnis] { try await anfrage("einsatz/ergebnisse", methode: "GET", inhalt: nil) }

    func halsbaender() async throws -> ELHalsbaender { try await anfrage("halsbaender", methode: "GET", inhalt: nil) }

    /// Neues Token ohne Passwort (#141). Das alte ist danach gesperrt; die Antwort ersetzt die Sitzung nicht selbst.
    func verlaengern(geraet: String?) async throws -> ELSitzung {
        struct Antwort: Decodable { var token: String; var expiresAt: Date; var verlaengerbarBis: Date? }
        let alt = sitzung
        let inhalt = try JSONEncoder().encode(geraet.map { ["geraet": String($0.prefix(40))] } ?? [:])
        let (daten, http) = try await rohEinmal("verlaengern", methode: "POST", inhalt: inhalt, token: alt.token)
        switch http.statusCode {
        case 200..<300:
            guard let a = try? JSONDecoder.server.decode(Antwort.self, from: daten) else { throw SchnittstellenFehler.antwortUngueltig }
            return ELSitzung(server: alt.server, token: a.token, expiresAt: a.expiresAt, geraet: alt.geraet,
                             verlaengerbarBis: a.verlaengerbarBis)
        case 409:
            let text = ((try? JSONSerialization.jsonObject(with: daten)) as? [String: Any])?["error"] as? String
            throw ELVerlaengerFehler.nichtMoeglich(text ?? "Verlängern nicht möglich.")
        case 410: throw SchnittstellenFehler.beendet
        default: throw SchnittstellenFehler.server(status: http.statusCode)
        }
    }

    /// Token auf dem Server ungültig machen (best effort).
    func abmelden() async {
        _ = try? await roh("logout", methode: "POST", inhalt: nil)
    }

    /// Server-Sent Events wie in der Team-Rolle; 410 beim Verbinden → `beendet`.
    /// Gibt es inzwischen ein neueres Token (#141), ist 410 bzw. `stop` keine Abmeldung: Neu verbinden mit dem neuen Token.
    func ereignisse() async throws -> AsyncThrowingStream<ELEreignis, Error> {
        let erstesToken = sitzung.token
        let erster = try await verbinden(erstesToken)
        let neuVerbinden = erster.1.statusCode == 410 ? await zugang.neueresToken(als: erstesToken) : false
        let token = neuVerbinden ? sitzung.token : erstesToken
        let (bytes, http) = neuVerbinden ? try await verbinden(token) : erster
        if http.statusCode == 410 { throw SchnittstellenFehler.beendet }
        guard http.statusCode == 200 else { throw SchnittstellenFehler.server(status: http.statusCode) }
        let zugang = self.zugang
        let verbindungsToken = token
        return AsyncThrowingStream { fortsetzung in
            let aufgabe = Task {
                var leser = SSELeser()
                var zeile: [UInt8] = []
                do {
                    for try await byte in bytes {
                        guard byte == UInt8(ascii: "\n") else {
                            zeile.append(byte)
                            continue
                        }
                        if zeile.last == UInt8(ascii: "\r") { zeile.removeLast() }
                        if let ereignis = leser.zeile(String(decoding: zeile, as: UTF8.self)).flatMap(ELEreignis.init) {
                            if ereignis == .stop, await zugang.neueresToken(als: verbindungsToken) {
                                // Altes Token nach /verlaengern: Verbindung endet, die Ansicht verbindet sich neu
                                break
                            }
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

    private func verbinden(_ token: String) async throws -> (URLSession.AsyncBytes, HTTPURLResponse) {
        var request = request("ereignisse", methode: "GET", token: token)
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        let (bytes, antwort) = try await ereignisSession.bytes(for: request)
        guard let http = antwort as? HTTPURLResponse else { throw SchnittstellenFehler.antwortUngueltig }
        return (bytes, http)
    }

    private func request(_ endpunkt: String, methode: String, token: String) -> URLRequest {
        // Abfrageteil getrennt anhängen, sonst kodiert appending(path:) das „?“ mit
        let teile = endpunkt.split(separator: "?", maxSplits: 1).map(String.init)
        var url = sitzung.server.pfad("api/el/app/v1/\(teile[0])")
        if teile.count == 2 {
            url.append(queryItems: teile[1].split(separator: "&").map { paar in
                let kv = paar.split(separator: "=", maxSplits: 1).map(String.init)
                return URLQueryItem(name: kv[0], value: kv.count > 1 ? kv[1] : nil)
            })
        }
        var request = URLRequest(url: url)
        request.httpMethod = methode
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        return request
    }

    /// Wie `rohEinmal`, aber 410 mit einem inzwischen ersetzten Token (#141) wird einmal mit dem neuen wiederholt.
    private func roh(_ endpunkt: String, methode: String, inhalt: Data?) async throws -> (Data, HTTPURLResponse) {
        let token = sitzung.token
        let ergebnis = try await rohEinmal(endpunkt, methode: methode, inhalt: inhalt, token: token)
        guard ergebnis.1.statusCode == 410, await zugang.neueresToken(als: token) else { return ergebnis }
        return try await rohEinmal(endpunkt, methode: methode, inhalt: inhalt, token: sitzung.token)
    }

    private func rohEinmal(_ endpunkt: String, methode: String, inhalt: Data?, token: String) async throws -> (Data, HTTPURLResponse) {
        var request = request(endpunkt, methode: methode, token: token)
        if let inhalt {
            request.httpBody = inhalt
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (daten, antwort) = try await session.data(for: request)
        guard let http = antwort as? HTTPURLResponse else { throw SchnittstellenFehler.antwortUngueltig }
        return (daten, http)
    }

    private func anfrage<T: Decodable>(_ endpunkt: String, methode: String, inhalt: Data?) async throws -> T {
        let (daten, http) = try await roh(endpunkt, methode: methode, inhalt: inhalt)
        if http.statusCode == 410 { throw SchnittstellenFehler.beendet }
        guard (200..<300).contains(http.statusCode) else { throw SchnittstellenFehler.server(status: http.statusCode) }
        do {
            return try JSONDecoder.server.decode(T.self, from: daten)
        } catch {
            throw SchnittstellenFehler.antwortUngueltig
        }
    }
}
