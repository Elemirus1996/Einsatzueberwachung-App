import Foundation
import Testing
@testable import Einsatzueberwachung

/// Navigationsziele und Punkte (APP_API.md Team-Rolle Abschnitt 13, Variante C) und Töne je Art.
@MainActor
struct NavigationspunkteTests {
    static let zielJson = """
    { "id": "m2", "text": "Ziel: Wegkreuzung Nord", "zeit": "2026-10-31T09:10:00Z",
      "ziel": { "punktId": "p1", "name": "Wegkreuzung Nord", "lat": 49.312345, "lng": 8.431234,
                "links": { "geo": "geo:49.312345,8.431234?q=49.312345,8.431234(Wegkreuzung%20Nord)",
                           "appleKarten": "https://maps.apple.com/?ll=49.312345,8.431234&q=Wegkreuzung%20Nord",
                           "googleMaps": "comgooglemaps://?daddr=49.312345,8.431234&directionsmode=walking",
                           "waze": "waze://?ll=49.312345,8.431234&navigate=yes" } } }
    """

    let speicher = UserDefaults(suiteName: "test-\(UUID().uuidString)")!
    let server = TestAnsichtSchnittstelle(lage: TeamAnsichtTests.lage)

    func ansicht(_ modi: @escaping (Modus) -> Void = { _ in }) -> TeamAnsicht {
        TeamAnsicht(schnittstelle: server, teamId: "t1", speicher: speicher, jetzt: { Date(timeIntervalSince1970: 1_800_000_000) },
                    beiSteuerung: modi)
    }

    // MARK: Ziel

    @Test func mitteilungMitUndOhneZiel() throws {
        let mitZiel = try JSONDecoder.server.decode(Mitteilung.self, from: Data(Self.zielJson.utf8))
        #expect(mitZiel.ziel?.name == "Wegkreuzung Nord")
        #expect(mitZiel.ziel?.links.waze == "waze://?ll=49.312345,8.431234&navigate=yes")
        let ohne = try JSONDecoder.server.decode(Mitteilung.self, from: Data(#"{"id":"m1","text":"Hallo","zeit":"2026-10-31T09:10:00Z","ziel":null}"#.utf8))
        #expect(ohne.ziel == nil)
    }

    @Test func naviAppsNachInstallation() throws {
        let ziel = try #require(try JSONDecoder.server.decode(Mitteilung.self, from: Data(Self.zielJson.utf8)).ziel)
        #expect(NaviApp.verfuegbar(fuer: ziel, kannOeffnen: { _ in false }) == [.appleKarten])
        #expect(NaviApp.verfuegbar(fuer: ziel, kannOeffnen: { $0.scheme == "waze" }) == [.appleKarten, .waze])
        #expect(NaviApp.verfuegbar(fuer: ziel, kannOeffnen: { _ in true }) == [.appleKarten, .googleMaps, .waze])
    }

    @Test func neuesZielAlsEigeneMeldung() throws {
        var neu = TeamAnsichtTests.lage
        neu.mitteilungen.append(try JSONDecoder.server.decode(Mitteilung.self, from: Data(Self.zielJson.utf8)))
        let meldungen = LageMeldung.vergleichen(alt: TeamAnsichtTests.lage, neu: neu)
        #expect(meldungen == [LageMeldung(titel: "Neues Ziel: Wegkreuzung Nord", text: "Zum Navigieren die App öffnen", tonart: .navigationsziel)])
    }

    // MARK: Punkt setzen

    @Test func punktWirdMitUtcZeitGesendet() async throws {
        let a = ansicht()
        await a.punktSetzen(text: "  Fund: Jacke ", lat: 49.3, lng: 8.4, genauigkeit: 8, zeit: Date(timeIntervalSince1970: 1_800_000_000))
        let punkt = try #require(server.punkte.first)
        #expect(punkt.text == "Fund: Jacke")
        #expect(a.offenePunkte.isEmpty)
        #expect(a.punktHinweis?.ok == true)
        let json = try #require(try JSONSerialization.jsonObject(with: JSONEncoder.server.encode(punkt)) as? [String: Any])
        #expect(json["zeit"] as? String == "2027-01-15T08:00:00Z")
        #expect(json["genauigkeit"] as? Double == 8)
    }

    @Test func punktImFunklochWartetUndGehtSpaeterRaus() async throws {
        server.punktFehler = URLError(.notConnectedToInternet)
        let a = ansicht()
        await a.punktSetzen(text: "Spur", lat: 49.3, lng: 8.4, genauigkeit: nil, zeit: Date())
        #expect(a.offenePunkte.count == 1)
        // Nach Neustart der Ansicht noch da (gespeichert), dieselbe id beim zweiten Versuch
        let zweite = ansicht()
        #expect(zweite.offenePunkte.count == 1)
        server.punktFehler = nil
        await zweite.punkteSenden()
        #expect(zweite.offenePunkte.isEmpty)
        #expect(server.punkte.map(\.id) == [a.offenePunkte[0].id, a.offenePunkte[0].id])
    }

    @Test func abgelehnterPunktWirdVerworfenMitHinweis() async {
        server.punktFehler = PunktFehler.abgelehnt(text: "Höchstens 50 Punkte je Team.")
        let a = ansicht()
        await a.punktSetzen(text: "Hinweis", lat: 49.3, lng: 8.4, genauigkeit: nil, zeit: Date())
        #expect(a.offenePunkte.isEmpty)
        #expect(a.punktHinweis?.text == "Höchstens 50 Punkte je Team.")
        #expect(a.punktHinweis?.ok == false)
    }

    @Test func punktBeiBeendetemEinsatzStopptOrtung() async {
        server.punktFehler = SchnittstellenFehler.beendet
        var modi: [Modus] = []
        let a = ansicht { modi.append($0) }
        await a.punktSetzen(text: "Fund", lat: 49.3, lng: 8.4, genauigkeit: nil, zeit: Date())
        #expect(modi == [.stop])
    }

    @Test func leererTextWirdNichtGesendet() async {
        let a = ansicht()
        await a.punktSetzen(text: "   ", lat: 49.3, lng: 8.4, genauigkeit: nil, zeit: Date())
        #expect(server.punkte.isEmpty)
    }

    // MARK: Einsatzleitung

    @Test func ereignisPunktUndTonFuerTeamPunkt() async throws {
        let roh = SSELeser.Ereignis(name: "punkt", daten: #"{"id":"p1","teamId":"t1","aenderung":"neu","text":"Team Rex: Fund"}"#)
        let ereignis = try #require(ELEreignis(roh))
        #expect(ereignis == .punkt(id: "p1", teamId: "t1", aenderung: "neu", text: "Team Rex: Fund"))

        let a = ELAnsicht(schnittstelle: TestELSchnittstelle(try ELAnsichtTests.lage())) { _ in }
        var toene: [Tonart] = []
        var neuGeladen = 0
        a.beiTon = { toene.append($0) }
        a.beiPunkt = { neuGeladen += 1 }
        await a.verarbeiten(ereignis)
        await a.verarbeiten(.punkt(id: "p2", teamId: nil, aenderung: "neu", text: nil)) // Punkt der EL: kein Ton
        await a.verarbeiten(.punkt(id: "p1", teamId: "t1", aenderung: "geloescht", text: nil))
        #expect(toene == [.punktVomTeam])
        #expect(neuGeladen == 3)
        #expect(a.teamPunkt?.text == "Team Rex: Fund")
    }

    @Test func karteMitPunkten() throws {
        let json = ##"{"suchgebiete":[],"positionen":[],"punkte":[{"id":"p1","name":"Team Rex: Fund","beschreibung":"x","farbe":"#E67E22","lat":49.3,"lng":8.4,"zeit":"2026-10-31T09:12:03Z","teamId":"t1"}]}"##
        let karte = try JSONDecoder.server.decode(ELKarte.self, from: Data(json.utf8))
        #expect(karte.punkte?.first?.teamId == "t1")
        let alt = try JSONDecoder.server.decode(ELKarte.self, from: Data(#"{"suchgebiete":[],"positionen":[]}"#.utf8))
        #expect(alt.punkte == nil)
    }

    @Test func zielUndBerichtMitPunkten() throws {
        let ziel = ELAktion.ziel("t1", name: "Team Rex", punktId: "p1", punktName: "Nord")
        #expect(ziel.pfad == "teams/t1/ziel")
        let zielJson = try JSONSerialization.jsonObject(with: JSONEncoder().encode(ziel.inhalt)) as? [String: Any]
        #expect(zielJson?["punktId"] as? String == "p1")

        let beenden = ELAktion.einsatzBeenden(ergebnis: "x", mitTracks: false, mitPunkten: true)
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(beenden.inhalt)) as? [String: Any]
        #expect((json?["pdf"] as? [String: Any])?["punkte"] as? Bool == true)
    }

    // MARK: Töne

    @Test func toeneJeArtUndStufeZweiNichtAus() {
        var gespielt: [TonWahl] = []
        let toene = Toene(speicher: speicher) { gespielt.append($0) }
        #expect(Set(Tonart.allCases.map { toene.wahl(fuer: $0) }).count == Tonart.allCases.count) // ab Werk unterscheidbar
        #expect(!toene.moeglich(fuer: .warnung2).contains(.aus))
        #expect(toene.moeglich(fuer: .warnung1).contains(.aus))

        toene.setzen(.aus, fuer: .warnung2)
        #expect(toene.wahl(fuer: .warnung2) == Tonart.warnung2.standard)
        toene.setzen(.vibration, fuer: .punktVomTeam)
        toene.setzen(.aus, fuer: .funk)
        toene.spielen(.punktVomTeam)
        #expect(gespielt == [.vibration])
        #expect(!toene.mitTon(.funk))

        // Gespeichert je Gerät; ein manipuliertes „aus“ für Stufe 2 gilt nicht
        speicher.set("aus", forKey: "ton.warnung2")
        let neu = Toene(speicher: speicher) { _ in }
        #expect(neu.wahl(fuer: .punktVomTeam) == .vibration)
        #expect(neu.wahl(fuer: .warnung2) == Tonart.warnung2.standard)
    }
}
