import Foundation
import Testing
@testable import Einsatzueberwachung

/// Packliste der Staffel (APP_API Team-Rolle Abschnitt 14): nur lokal abhaken, Erinnerung
@MainActor
struct PacklisteTests {
    private func neueListe(_ geplant: Box? = nil) -> Packliste {
        let speicher = UserDefaults(suiteName: "packliste-test-\(UUID().uuidString)")!
        return Packliste(speicher: speicher) { geplant?.zeit = $0 }
    }

    final class Box { var zeit: Date? }

    private let vorlage = PacklisteVorlage(version: "v1", punkte: [
        .init(id: "a1", text: "Stirnlampe", gruppe: "Persönlich"),
        .init(id: "b2", text: "Napf", gruppe: "Hund"),
        .init(id: "c3", text: "Regenjacke", gruppe: "Persönlich"),
    ])

    @Test func vorlageNurBeiNeuerVersion() async {
        let liste = neueListe()
        var geladen = 0
        await liste.aktualisieren(version: "v1") { geladen += 1; return vorlage }
        await liste.aktualisieren(version: "v1") { geladen += 1; return vorlage }
        #expect(geladen == 1)
        #expect(liste.gruppen.map(\.name) == ["Persönlich", "Hund"])
        #expect(liste.gruppen[0].punkte.map(\.text) == ["Stirnlampe", "Regenjacke"])
    }

    @Test func netzfehlerBehaeltVorlage_versionNullEntfernt() async {
        struct Fehler: Error {}
        let liste = neueListe()
        await liste.aktualisieren(version: "v1") { vorlage }
        await liste.aktualisieren(version: "v2") { throw Fehler() }
        #expect(liste.vorlage?.version == "v1")
        await liste.aktualisieren(version: nil) { vorlage }
        #expect(liste.vorlage == nil)
    }

    @Test func abhakenBleibtNurFuerVorhandenePunkte() async {
        let liste = neueListe()
        await liste.aktualisieren(version: "v1") { vorlage }
        liste.umschalten(Packliste.schluessel(staffel: "a1"))
        liste.umschalten(Packliste.schluessel(staffel: "b2"))
        #expect(liste.anzahlErledigt == 2)
        // Staffel ändert „Napf“: Punkt b2 gibt es nicht mehr
        await liste.aktualisieren(version: "v2") {
            PacklisteVorlage(version: "v2", punkte: [.init(id: "a1", text: "Stirnlampe", gruppe: "Persönlich"),
                                                     .init(id: "d4", text: "Napf und Wasser", gruppe: "Hund")])
        }
        #expect(liste.erledigt == [Packliste.schluessel(staffel: "a1")])
    }

    @Test func eigenePunkte() {
        let liste = neueListe()
        liste.eigenenHinzufuegen("  Ersatzsocken  ")
        liste.eigenenHinzufuegen("   ")
        #expect(liste.eigene.map(\.text) == ["Ersatzsocken"])
        let schluessel = Packliste.schluessel(eigen: liste.eigene[0].id)
        liste.umschalten(schluessel)
        #expect(liste.anzahlErledigt == 1)
        liste.eigenenLoeschen(liste.eigene[0].id)
        #expect(liste.anzahl == 0)
        #expect(liste.erledigt.isEmpty)
    }

    @Test func durchgegangenLeertHakenUndPlantErinnerung() async {
        let geplant = Box()
        let liste = neueListe(geplant)
        await liste.aktualisieren(version: "v1") { vorlage }
        liste.umschalten(Packliste.schluessel(staffel: "a1"))
        let jetzt = Date(timeIntervalSince1970: 1_800_000_000)
        liste.durchgegangenMerken(jetzt: jetzt)
        #expect(liste.erledigt.isEmpty)
        #expect(geplant.zeit == Packliste.naechsteErinnerung(durchgegangen: jetzt, tage: 14, jetzt: jetzt))
        liste.erinnerungSetzen(tage: 0, jetzt: jetzt)
        #expect(geplant.zeit == nil)
    }

    @Test func erinnerungUm18UhrFruehestensMorgen() {
        var kalender = Calendar(identifier: .gregorian)
        kalender.timeZone = TimeZone(identifier: "Europe/Berlin")!
        let jetzt = kalender.date(from: DateComponents(year: 2026, month: 11, day: 16, hour: 21))!
        let zeit = Packliste.naechsteErinnerung(durchgegangen: jetzt, tage: 14, jetzt: jetzt, kalender: kalender)!
        #expect(kalender.dateComponents([.day, .hour], from: zeit) == DateComponents(day: 30, hour: 18))
        // Lange nicht durchgegangen: morgen 18 Uhr
        let alt = kalender.date(byAdding: .day, value: -40, to: jetzt)!
        let bald = Packliste.naechsteErinnerung(durchgegangen: alt, tage: 14, jetzt: jetzt, kalender: kalender)!
        #expect(kalender.dateComponents([.day, .hour], from: bald) == DateComponents(day: 17, hour: 18))
        #expect(Packliste.naechsteErinnerung(durchgegangen: nil, tage: 0, jetzt: jetzt) == nil)
    }
}
