import Foundation
import Testing
import UIKit
@testable import Einsatzueberwachung

@MainActor
struct HeimatStaffelTests {
    let speicher = UserDefaults(suiteName: "heimat-\(UUID().uuidString)")!
    let ordner = FileManager.default.temporaryDirectory.appending(path: "heimat-\(UUID().uuidString)")
    let eigene = ServerInfo(kennung: "7f3c", staffel: "RHS Musterstadt", logoVersion: "v1")
    let fremde = ServerInfo(kennung: "9a9a", staffel: "RHS Nachbarort", logoVersion: "x1")

    init() throws {
        try FileManager.default.createDirectory(at: ordner, withIntermediateDirectories: true)
    }

    func heimat() -> HeimatStaffel { HeimatStaffel(speicher: speicher, ordner: ordner) }

    static let png: Data = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).pngData { kontext in
        UIColor.orange.setFill()
        kontext.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
    }

    @Test func fragtNurOhneHeimatUndNichtNachAblehnung() {
        let h = heimat()
        #expect(h.sollFragen(eigene))
        h.ablehnen(fremde)
        #expect(!h.sollFragen(fremde))
        #expect(heimat().sollFragen(eigene))      // gespeichert, andere Server weiter fragen
        h.uebernehmen(eigene, name: "RHS Musterstadt")
        #expect(!h.sollFragen(eigene))
    }

    @Test func logoNurBeiNeuerVersionLaden() async {
        let h = heimat()
        h.uebernehmen(eigene, name: "RHS Musterstadt")
        var abrufe = 0
        await h.aktualisieren(eigene) { abrufe += 1; return Self.png }
        await h.aktualisieren(eigene) { abrufe += 1; return Self.png }
        #expect(abrufe == 1)
        #expect(h.logo != nil)
        #expect(heimat().logo != nil)             // nach Neustart von der Platte

        var neu = eigene
        neu.logoVersion = "v2"
        neu.staffel = "RHS Musterstadt e. V."
        await h.aktualisieren(neu) { abrufe += 1; return Self.png }
        #expect(abrufe == 2)
        #expect(h.eintrag?.staffel == "RHS Musterstadt e. V.")
    }

    @Test func fremdeStaffelAendertNichts() async {
        let h = heimat()
        h.uebernehmen(eigene, name: "RHS Musterstadt")
        var abrufe = 0
        await h.aktualisieren(fremde) { abrufe += 1; return Self.png }
        #expect(abrufe == 0)
        #expect(h.eintrag?.staffel == "RHS Musterstadt")
        #expect(h.logo == nil)
    }

    @Test func ohneLogoNurName() async {
        let h = heimat()
        h.uebernehmen(eigene, name: "RHS Musterstadt")
        var abrufe = 0
        await h.aktualisieren(eigene) { abrufe += 1; return nil }   // 404, kein Logo hinterlegt
        await h.aktualisieren(eigene) { abrufe += 1; return nil }
        #expect(abrufe == 1)                      // nicht bei jedem Start erneut fragen
        #expect(h.logo == nil)

        var ohne = eigene
        ohne.logoVersion = nil
        await h.aktualisieren(ohne) { abrufe += 1; return Self.png }
        #expect(abrufe == 1)
    }

    @Test func netzfehlerVersuchtEsSpaeterErneut() async {
        let h = heimat()
        h.uebernehmen(eigene, name: "RHS Musterstadt")
        await h.aktualisieren(eigene) { throw URLError(.notConnectedToInternet) }
        #expect(h.eintrag?.logoVersion == nil)
        await h.aktualisieren(eigene) { Self.png }
        #expect(h.logo != nil)
    }

    @Test func entfernenWaehrendDasLogoLaedtBleibtEntfernt() async {
        let h = heimat()
        h.uebernehmen(eigene, name: "RHS Musterstadt")
        await h.aktualisieren(eigene) {
            h.entfernen()        // Nutzer entfernt die Heimatstaffel in den Einstellungen
            return Self.png
        }
        #expect(h.eintrag == nil)
        #expect(heimat().eintrag == nil)
    }

    @Test func entfernenFragtWieder() async {
        let h = heimat()
        h.ablehnen(fremde)
        h.uebernehmen(eigene, name: "RHS Musterstadt")
        await h.aktualisieren(eigene) { Self.png }
        h.entfernen()
        #expect(h.eintrag == nil && h.logo == nil)
        #expect(h.sollFragen(fremde) && h.sollFragen(eigene))
        #expect(heimat().eintrag == nil)
    }

    @Test func serverObjektInConfigUndTeamliste() throws {
        let config = """
        { "apiVersion": 1, "teamId": "t", "teamName": "Team Rex", "expiresAt": "2026-10-29T10:00:00Z", "mode": "pause",
          "intervalSec": 20, "uploadEverySec": 60, "pauseCheckSec": 120, "staffel": "RHS Musterstadt",
          "server": { "kennung": "7f3c9a1e", "staffel": "RHS Musterstadt", "logoVersion": null } }
        """
        let c = try JSONDecoder.server.decode(TeamConfig.self, from: Data(config.utf8))
        #expect(c.server == ServerInfo(kennung: "7f3c9a1e", staffel: "RHS Musterstadt", logoVersion: nil))
        let teams = #"{ "einsatz": "x", "teams": [], "server": { "kennung": "k", "staffel": "S", "logoVersion": "a1b2c3d4e5f60718" } }"#
        #expect(try JSONDecoder.server.decode(TeamAuswahl.self, from: Data(teams.utf8)).server?.logoVersion == "a1b2c3d4e5f60718")
    }
}
