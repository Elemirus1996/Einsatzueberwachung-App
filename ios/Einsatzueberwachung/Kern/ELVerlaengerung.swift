import Foundation

/// Verlängert das Token der Einsatzleitung bei langen Einsätzen (#141, APP_API.md Einsatzleitung Abschnitt 4):
/// Läuft ein Einsatz und sind weniger als 6 h übrig, holt die App mit `POST /verlaengern` ein neues Token,
/// ohne das Staffel-Passwort erneut abzufragen. Lehnt der Server ab (409: kein Einsatz mehr oder 72 h erreicht),
/// fragt die App für dieses Token nicht noch einmal und zeigt „Anmeldung läuft um HH:mm ab“.
@MainActor
final class ELVerlaengerung: ObservableObject {
    static let schwelle: TimeInterval = 6 * 3600

    /// Hinweis nach einer Ablehnung, bis ein neues Token da ist
    @Published private(set) var hinweis: String?

    private let schnittstelle: ELVerlaengerSchnittstelle
    private let zugang: ELZugang
    private let jetzt: () -> Date
    /// Neue Sitzung in den Schlüsselbund und Ablauf an die Ortung
    private let beiNeuerSitzung: (ELSitzung) -> Void
    private var laeuft = false
    /// Für dieses Token hat der Server abgelehnt (409): nicht wiederholen
    private var abgelehntFuer: String?

    init(schnittstelle: ELVerlaengerSchnittstelle, zugang: ELZugang, jetzt: @escaping () -> Date = Date.init,
         beiNeuerSitzung: @escaping (ELSitzung) -> Void) {
        self.schnittstelle = schnittstelle
        self.zugang = zugang
        self.jetzt = jetzt
        self.beiNeuerSitzung = beiNeuerSitzung
    }

    /// Soll jetzt verlängert werden?
    func faellig(einsatzLaeuft: Bool) -> Bool {
        let sitzung = zugang.sitzung
        guard einsatzLaeuft, !laeuft, abgelehntFuer != sitzung.token else { return false }
        if let grenze = sitzung.verlaengerbarBis, grenze <= sitzung.expiresAt { return false }
        let rest = sitzung.expiresAt.timeIntervalSince(jetzt())
        return rest > 0 && rest < Self.schwelle
    }

    /// Nach jeder Lage, beim Öffnen der App und im Takt der Ortung aufrufen.
    func pruefen(einsatzLaeuft: Bool) async {
        guard faellig(einsatzLaeuft: einsatzLaeuft) else { return }
        let alt = zugang.sitzung
        laeuft = true
        zugang.verlaengerungBeginnt()
        defer {
            zugang.verlaengerungEndet()
            laeuft = false
        }
        do {
            let neu = try await schnittstelle.verlaengern(geraet: alt.geraet)
            zugang.ersetzen(neu)
            hinweis = nil
            beiNeuerSitzung(neu)
        } catch ELVerlaengerFehler.nichtMoeglich {
            abgelehntFuer = alt.token
            hinweis = "Anmeldung läuft um \(alt.expiresAt.formatted(date: .omitted, time: .shortened)) ab."
        } catch {
            // Funkloch oder 410: beim nächsten Mal erneut; ein ungültiges Token meldet /lage bzw. /status
        }
    }
}
