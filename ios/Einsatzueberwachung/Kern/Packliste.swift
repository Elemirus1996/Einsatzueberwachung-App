import Foundation
import UserNotifications

/// Antwort von `GET /packliste` (APP_API.md, Team-Rolle Abschnitt 14).
struct PacklisteVorlage: Codable, Equatable, Sendable {
    struct Punkt: Codable, Equatable, Identifiable, Sendable {
        var id: String
        var text: String
        var gruppe: String?
    }

    var version: String
    var punkte: [Punkt]
}

/// Packliste „Was brauche ich für den Einsatz?“: Vorlage der Heimatstaffel (nur abhakbar) und eigene Punkte.
/// Alles bleibt auf dem Gerät, der Server erfährt nicht, was abgehakt ist. Nicht geheim, darum in den UserDefaults.
/// Eine lokale Mitteilung erinnert im eingestellten Abstand daran, die Liste durchzugehen.
@MainActor
final class Packliste: ObservableObject {
    struct EigenerPunkt: Codable, Equatable, Identifiable {
        var id: String
        var text: String
    }

    static let erinnerungKennung = "packliste-erinnerung"
    static let standardTage = 14
    /// Auswahl in der Ansicht; 0 = keine Erinnerung
    static let tageAuswahl = [0, 7, 14, 30]
    static let hoechsteEigene = 100
    static let hoechsteTextLaenge = 200

    @Published private(set) var vorlage: PacklisteVorlage?
    @Published private(set) var eigene: [EigenerPunkt]
    @Published private(set) var erledigt: Set<String>
    @Published private(set) var durchgegangen: Date?
    @Published private(set) var erinnerungTage: Int

    private let speicher: UserDefaults
    private let planer: ((Date?) -> Void)?

    /// `planer` ersetzt in Tests die lokale Mitteilung (nil = keine Erinnerung).
    init(speicher: UserDefaults = .standard, planer: ((Date?) -> Void)? = nil) {
        self.speicher = speicher
        self.planer = planer
        vorlage = speicher.data(forKey: "packliste.vorlage").flatMap { try? JSONDecoder().decode(PacklisteVorlage.self, from: $0) }
        eigene = speicher.data(forKey: "packliste.eigene").flatMap { try? JSONDecoder().decode([EigenerPunkt].self, from: $0) } ?? []
        erledigt = Set(speicher.stringArray(forKey: "packliste.erledigt") ?? [])
        durchgegangen = speicher.object(forKey: "packliste.durchgegangen") as? Date
        erinnerungTage = speicher.object(forKey: "packliste.erinnerungTage") as? Int ?? Self.standardTage
    }

    // MARK: Schlüssel für den Abhak-Stand (Staffel und eigene getrennt, damit sich Kennungen nie überschneiden)

    static func schluessel(staffel id: String) -> String { "s:" + id }
    static func schluessel(eigen id: String) -> String { "e:" + id }

    var anzahl: Int { (vorlage?.punkte.count ?? 0) + eigene.count }
    var anzahlErledigt: Int {
        (vorlage?.punkte.filter { erledigt.contains(Self.schluessel(staffel: $0.id)) }.count ?? 0)
            + eigene.filter { erledigt.contains(Self.schluessel(eigen: $0.id)) }.count
    }

    /// Punkte der Staffel in ihrer Reihenfolge, nach Gruppen zusammengefasst (Gruppe `nil` zuerst, wenn vorhanden)
    var gruppen: [(name: String?, punkte: [PacklisteVorlage.Punkt])] {
        var ergebnis: [(name: String?, punkte: [PacklisteVorlage.Punkt])] = []
        for punkt in vorlage?.punkte ?? [] {
            if let i = ergebnis.firstIndex(where: { $0.name == punkt.gruppe }) {
                ergebnis[i].punkte.append(punkt)
            } else {
                ergebnis.append((punkt.gruppe, [punkt]))
            }
        }
        return ergebnis
    }

    // MARK: Vorlage der Staffel

    /// Nach `/config` bzw. Anmeldung, nur für die Heimatstaffel: Vorlage nur laden, wenn sich `packlisteVersion` geändert hat.
    /// `laden` liefert die Vorlage oder `nil`, wenn der Server keine hat (404). Netzfehler: beim nächsten Mal erneut.
    func aktualisieren(version: String?, laden: () async throws -> PacklisteVorlage?) async {
        guard version != vorlage?.version else { return }
        guard version != nil else {
            vorlageSetzen(nil)
            return
        }
        do {
            // nil = 404 (keine Liste mehr): Vorlage weg
            vorlageSetzen(try await laden())
        } catch {
            // Netzfehler: beim nächsten Mal erneut versuchen
        }
    }

    /// Heimatstaffel entfernt: Ihre Vorlage gehört nicht mehr aufs Gerät, eigene Punkte bleiben.
    func vorlageEntfernen() { vorlageSetzen(nil) }

    private func vorlageSetzen(_ neu: PacklisteVorlage?) {
        vorlage = neu
        // Abhak-Stand nur für Punkte behalten, die es noch gibt
        let gueltig = Set((neu?.punkte ?? []).map { Self.schluessel(staffel: $0.id) } + eigene.map { Self.schluessel(eigen: $0.id) })
        erledigt.formIntersection(gueltig)
        speicher.set(try? JSONEncoder().encode(neu), forKey: "packliste.vorlage")
        if neu == nil { speicher.removeObject(forKey: "packliste.vorlage") }
        erledigtSpeichern()
        erinnerungPlanen()
    }

    // MARK: Abhaken und eigene Punkte

    func umschalten(_ schluessel: String) {
        if erledigt.contains(schluessel) { erledigt.remove(schluessel) } else { erledigt.insert(schluessel) }
        erledigtSpeichern()
    }

    func eigenenHinzufuegen(_ text: String) {
        let sauber = String(text.trimmingCharacters(in: .whitespacesAndNewlines).prefix(Self.hoechsteTextLaenge))
        guard !sauber.isEmpty, eigene.count < Self.hoechsteEigene else { return }
        eigene.append(EigenerPunkt(id: UUID().uuidString, text: sauber))
        eigeneSpeichern()
    }

    func eigenenLoeschen(_ id: String) {
        eigene.removeAll { $0.id == id }
        erledigt.remove(Self.schluessel(eigen: id))
        eigeneSpeichern()
        erledigtSpeichern()
    }

    /// „Liste durchgegangen“: Datum merken, Haken für die nächste Runde leeren, Erinnerung neu stellen.
    func durchgegangenMerken(jetzt: Date = Date()) {
        durchgegangen = jetzt
        erledigt = []
        speicher.set(jetzt, forKey: "packliste.durchgegangen")
        erledigtSpeichern()
        erinnerungPlanen(jetzt: jetzt)
    }

    // MARK: Erinnerung

    func erinnerungSetzen(tage: Int, jetzt: Date = Date()) {
        erinnerungTage = max(0, tage)
        speicher.set(erinnerungTage, forKey: "packliste.erinnerungTage")
        if erinnerungTage > 0 {
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
        }
        erinnerungPlanen(jetzt: jetzt)
    }

    /// Zeitpunkt der nächsten Erinnerung: `tage` nach dem letzten Durchgehen um 18 Uhr, frühestens morgen 18 Uhr.
    /// Ohne bisheriges Durchgehen zählt ab jetzt. `nil`, wenn ausgeschaltet oder die Liste leer ist.
    static func naechsteErinnerung(durchgegangen: Date?, tage: Int, jetzt: Date, kalender: Calendar = .current) -> Date? {
        guard tage > 0 else { return nil }
        let basis = durchgegangen ?? jetzt
        let faellig = kalender.date(byAdding: .day, value: tage, to: basis) ?? basis
        let abends = kalender.date(bySettingHour: 18, minute: 0, second: 0, of: faellig) ?? faellig
        let morgen = kalender.date(byAdding: .day, value: 1, to: jetzt) ?? jetzt
        let frueheste = kalender.date(bySettingHour: 18, minute: 0, second: 0, of: morgen) ?? morgen
        return max(abends, frueheste)
    }

    /// Beim Start und nach jeder Änderung: genau eine ausstehende Erinnerung.
    func erinnerungPlanen(jetzt: Date = Date()) {
        let zeit = anzahl == 0 ? nil : Self.naechsteErinnerung(durchgegangen: durchgegangen, tage: erinnerungTage, jetzt: jetzt)
        if let planer { planer(zeit); return }
        let zentrale = UNUserNotificationCenter.current()
        zentrale.removePendingNotificationRequests(withIdentifiers: [Self.erinnerungKennung])
        guard let zeit else { return }
        let inhalt = UNMutableNotificationContent()
        inhalt.title = "Packliste"
        inhalt.body = "Zeit, die Packliste für den Einsatz durchzugehen."
        inhalt.sound = .default
        let teile = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: zeit)
        zentrale.add(UNNotificationRequest(identifier: Self.erinnerungKennung, content: inhalt,
                                           trigger: UNCalendarNotificationTrigger(dateMatching: teile, repeats: false)))
    }

    private func eigeneSpeichern() {
        speicher.set(try? JSONEncoder().encode(eigene), forKey: "packliste.eigene")
        erinnerungPlanen()
    }

    private func erledigtSpeichern() {
        speicher.set(Array(erledigt), forKey: "packliste.erledigt")
    }
}
