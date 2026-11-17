import Foundation

/// Aktionen, Notizen und Funk der Einsatzleitung mit Warteschlange fürs Funkloch (wie die Statusmeldungen im Team).
@MainActor
final class ELBetrieb: ObservableObject {
    @Published private(set) var offen: [ELAktion]
    @Published private(set) var notizen: [ELNotiz] = []
    /// Ablehnung des Servers („Team läuft schon“, „Halsband vergeben“) oder Bestätigung, kurz anzeigen
    @Published private(set) var hinweis: (text: String, fehler: Bool, zeit: Date)?
    @Published private(set) var weitereNotizen = true
    /// Ergebnis von „Einsatz beenden“ (Nummer, PDF erstellt oder Fehler), bis die Einsatzleitung es bestätigt
    @Published var einsatzEnde: ELEinsatzEnde?

    private let schnittstelle: ELAktionsSchnittstelle
    private let speicher: UserDefaults
    private let jetzt: () -> Date
    private let beiStop: () -> Void
    private var laeuft = false

    /// Nach jeder ausgeführten Aktion (Lage neu laden)
    var nachAktion: (() async -> Void)?

    init(schnittstelle: ELAktionsSchnittstelle, speicher: UserDefaults = .standard,
         jetzt: @escaping () -> Date = Date.init, beiStop: @escaping () -> Void) {
        self.schnittstelle = schnittstelle
        self.speicher = speicher
        self.jetzt = jetzt
        self.beiStop = beiStop
        self.offen = speicher.data(forKey: "el.aktionen").flatMap { try? JSONDecoder().decode([ELAktion].self, from: $0) } ?? []
    }

    /// Bei Abmeldung: nichts zurücklassen
    func vergessen() {
        offen = []
        notizen = []
        speicher.removeObject(forKey: "el.aktionen")
    }

    func ausfuehren(_ aktion: ELAktion) async {
        var neu = aktion
        neu.zeit = jetzt()
        offen.append(neu)
        speichern()
        await senden()
    }

    /// Wartende Aktionen der Reihe nach; im Funkloch bleiben sie und gehen mit derselben `id` erneut raus.
    func senden() async {
        guard !laeuft else { return }
        laeuft = true
        defer { laeuft = false }
        var etwasGetan = false
        while let aktion = offen.first {
            do {
                let antwort = try await schnittstelle.ausfuehren(aktion)
                hinweis = ("\(aktion.beschreibung) ✓", false, jetzt())
                if aktion.pfad == "einsatz/beenden" {
                    var ende = (try? JSONDecoder.server.decode(ELEinsatzEnde.self, from: antwort)) ?? ELEinsatzEnde()
                    ende.berichtAbfrage = ELEinsatzEnde.abfrage(aus: aktion.inhalt)
                    einsatzEnde = ende
                }
                etwasGetan = true
            } catch SchnittstellenFehler.beendet {
                beiStop()
                return
            } catch ELAktionsFehler.abgelehnt(let text) {
                hinweis = (text, true, jetzt())
                etwasGetan = true
            } catch SchnittstellenFehler.server(let status) where (400..<500).contains(status) && status != 429 {
                hinweis = ("\(aktion.beschreibung): vom Server abgelehnt", true, jetzt())
            } catch {
                break
            }
            offen.removeAll { $0.id == aktion.id }
            speichern()
        }
        if etwasGetan { await nachAktion?() }
    }

    func notizenLaden() async {
        do {
            let seite = try await schnittstelle.notizen(vor: nil)
            notizen = seite.notizen
            weitereNotizen = seite.weitere
        } catch SchnittstellenFehler.beendet {
            beiStop()
        } catch {}
    }

    func aeltereNotizenLaden() async {
        guard weitereNotizen, let letzte = notizen.last else { return }
        if let seite = try? await schnittstelle.notizen(vor: letzte.id) {
            notizen += seite.notizen.filter { neu in !notizen.contains { $0.id == neu.id } }
            weitereNotizen = seite.weitere
        }
    }

    func ergebnisse() async -> [ELErgebnis] {
        (try? await schnittstelle.ergebnisse()) ?? []
    }

    /// Auswahllisten für Start und Ende; ohne Netz bzw. alter Server nil (dann freie Eingabe)
    func vorlage() async -> ELVorlage? {
        try? await schnittstelle.vorlage()
    }

    /// Abschlussangaben für das Einsatzende; ohne Netz bzw. Server vor 1.17.0 nil (dann Beenden wie bisher)
    func abschluss() async -> ELAbschluss? {
        try? await schnittstelle.abschluss()
    }

    /// Personal und Hunde für das Einsatzende; ohne Netz bzw. Server vor 1.14.1 nil (dann Vorauswahl des Servers)
    func vorOrt() async -> ELVorOrt? {
        try? await schnittstelle.vorOrt()
    }

    func halsbaender() async -> ELHalsbaender? {
        try? await schnittstelle.halsbaender()
    }

    /// Bericht laden und als Datei ablegen (nur bis zum Schließen der Vorschau, siehe `berichtLoeschen`).
    /// Fehlertext statt Datei, wenn es nicht geht.
    func berichtLaden(_ ende: ELEinsatzEnde) async -> Result<URL, ELBerichtFehler> {
        guard let pfad = ende.berichtPfad else { return .failure(.text("Kein Bericht vorhanden.")) }
        do {
            let daten = try await schnittstelle.bericht(pfad)
            let ordner = FileManager.default.temporaryDirectory.appending(path: "Bericht", directoryHint: .isDirectory)
            try? FileManager.default.removeItem(at: ordner)
            try FileManager.default.createDirectory(at: ordner, withIntermediateDirectories: true)
            let datei = ordner.appending(path: ELEinsatzEnde.dateiname(ende.einsatzNummer))
            try daten.write(to: datei, options: [.atomic, .completeFileProtection])
            return .success(datei)
        } catch SchnittstellenFehler.beendet {
            beiStop()
            return .failure(.text("Abgemeldet."))
        } catch SchnittstellenFehler.server(404) {
            return .failure(.text("Der Server bietet den Bericht noch nicht an (ab Version 1.15.0). Er liegt im Archiv am Server."))
        } catch {
            return .failure(.text("Bericht konnte nicht geladen werden. Netz prüfen und erneut versuchen."))
        }
    }

    /// Personenbezogene Daten nicht liegen lassen
    func berichtLoeschen() {
        try? FileManager.default.removeItem(at: FileManager.default.temporaryDirectory.appending(path: "Bericht", directoryHint: .isDirectory))
    }

    private func speichern() {
        speicher.set(try? JSONEncoder().encode(offen), forKey: "el.aktionen")
    }
}
