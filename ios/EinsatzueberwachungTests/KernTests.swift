import Foundation
import Testing
@testable import Einsatzueberwachung

struct ServerAdresseTests {
    @Test func qrCodeMitHttpsWirdAngenommen() throws {
        let qr = try #require(TeamQRCode("https://Einsatz.Example.org/team/login?code=AB12CD"))
        #expect(qr.code == "AB12CD")
        #expect(qr.server.url.absoluteString == "https://einsatz.example.org")
        #expect(qr.server.name == "einsatz.example.org")
    }

    @Test(arguments: [
        "http://einsatz.example.org/team/login?code=AB12",
        "https://einsatz.example.org/el?code=AB12",
        "https://einsatz.example.org/team/login",
        "https://einsatz.example.org/team/login?code=",
        "https://nutzer:pw@einsatz.example.org/team/login?code=AB12",
        "einsatz.example.org/team/login?code=AB12",
        "kein QR-Code",
    ])
    func ungueltigeQrCodesWerdenAbgelehnt(_ text: String) {
        #expect(TeamQRCode(text) == nil)
    }

    @Test func portBleibtErhalten() throws {
        let adresse = try #require(ServerAdresse(URL(string: "https://einsatz.intern:8443/irgendwas?x=1")!))
        #expect(adresse.url.absoluteString == "https://einsatz.intern:8443")
        #expect(adresse.pfad("api/team-mobile/app/v1/status").absoluteString
                == "https://einsatz.intern:8443/api/team-mobile/app/v1/status")
    }
}

struct ModellTests {
    @Test func configAusApiBeschreibung() throws {
        let json = """
        { "apiVersion": 1, "teamId": "t1", "teamName": "Team Rex", "dogName": "Rex",
          "einsatz": "Vermisste Person · Testwald", "staffel": "RHS Musterstadt",
          "positionUrl": "https://fremd.example/positions", "statusUrl": "https://fremd.example/status",
          "expiresAt": "2026-10-29T10:00:00Z", "mode": "track",
          "intervalSec": 20, "uploadEverySec": 60, "pauseCheckSec": 120, "neuesFeld": true }
        """
        let config = try JSONDecoder.server.decode(TeamConfig.self, from: Data(json.utf8))
        #expect(config.teamName == "Team Rex")
        #expect(config.mode == .track)
        #expect(config.expiresAt == Date(timeIntervalSince1970: 1_793_268_000))
    }

    /// .NET liefert DateTime mit 0 bis 7 Nachkommastellen
    @Test(arguments: ["", ".1", ".12", ".123", ".123456", ".1234567"])
    func zeitenMitBeliebigenNachkommastellen(_ bruch: String) throws {
        let json = #"{ "mode": "track", "expiresAt": "2026-10-28T20:51:03\#(bruch)Z" }"#
        let antwort = try JSONDecoder.server.decode(SteuerAntwort.self, from: Data(json.utf8))
        let sekunden = try #require(antwort.expiresAt).timeIntervalSince1970
        #expect(abs(sekunden - (1_793_220_663 + (Double("0" + bruch) ?? 0))) < 0.001)
    }

    @Test func antwortMitSekundenbruchteilen() throws {
        let json = #"{ "mode": "pause", "expiresAt": "2026-10-29T10:00:00.123Z", "accepted": 2 }"#
        let antwort = try JSONDecoder.server.decode(SteuerAntwort.self, from: Data(json.utf8))
        #expect(antwort.mode == .pause)
        #expect(antwort.accepted == 2)
        #expect(antwort.intervalSec == nil)
    }
}

@MainActor
struct PufferTests {
    let puffer = PositionsPuffer(datei: FileManager.default.temporaryDirectory
        .appending(path: "puffer-\(UUID().uuidString).sqlite"))

    @Test func reihenfolgeUndLoeschen() {
        for i in 1...5 {
            puffer.hinzufuegen(Position(lat: 49, lng: 8, accuracy: i == 2 ? nil : 5, timestamp: Int64(i), battery: i == 3 ? nil : 80))
        }
        #expect(puffer.anzahl() == 5)
        let erste = puffer.aelteste(3)
        #expect(erste.map(\.position.timestamp) == [1, 2, 3])
        #expect(erste[1].position.accuracy == nil)
        #expect(erste[2].position.battery == nil)
        puffer.loeschen(bisEinschliesslich: erste[2].id)
        #expect(puffer.aelteste(10).map(\.position.timestamp) == [4, 5])
        puffer.leeren()
        #expect(puffer.anzahl() == 0)
    }
}
