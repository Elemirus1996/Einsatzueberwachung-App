import Foundation

/// Aktionen, Notizen und Funk der Einsatzleitung mit Warteschlange fürs Funkloch (wie die Statusmeldungen im Team).
@MainActor
final class ELBetrieb: ObservableObject {
    @Published private(set) var offen: [ELAktion]
    @Published private(set) var notizen: [ELNotiz] = []
    /// Ablehnung des Servers („Team läuft schon“, „Halsband vergeben“) oder Bestätigung, kurz anzeigen
    @Published private(set) var hinweis: (text: String, fehler: Bool, zeit: Date)?
    @Published private(set) var weitereNotizen = true

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
                try await schnittstelle.ausfuehren(aktion)
                hinweis = ("\(aktion.beschreibung) ✓", false, jetzt())
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
            notizen = try await schnittstelle.notizen(vor: nil)
            weitereNotizen = notizen.count >= 50
        } catch SchnittstellenFehler.beendet {
            beiStop()
        } catch {}
    }

    func aeltereNotizenLaden() async {
        guard weitereNotizen, let letzte = notizen.last else { return }
        if let mehr = try? await schnittstelle.notizen(vor: letzte.id) {
            notizen += mehr.filter { neu in !notizen.contains { $0.id == neu.id } }
            weitereNotizen = mehr.count >= 50
        }
    }

    func halsbaender() async -> ELHalsbaender? {
        try? await schnittstelle.halsbaender()
    }

    private func speichern() {
        speicher.set(try? JSONEncoder().encode(offen), forKey: "el.aktionen")
    }
}
