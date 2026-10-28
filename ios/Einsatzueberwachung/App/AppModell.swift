import BackgroundTasks
import Foundation

/// Hält die angemeldete Rolle und verbindet Schlüsselbund, Schnittstelle und Ortung.
@MainActor
final class AppModell: ObservableObject {
    static let statusAufgabe = "io.github.elemirus1996.einsatzueberwachung.status"

    @Published private(set) var team: TeamOrtung?
    @Published private(set) var teamAnsicht: TeamAnsicht?
    @Published private(set) var teamSitzung: TeamSitzung?

    private let schluesselbund = Schluesselbund()
    private let quelle = CoreLocationQuelle()
    private lazy var puffer = PositionsPuffer.standard()

    init() {
        if let sitzung = schluesselbund.lesen(TeamSitzung.self, konto: Schluesselbund.kontoTeam) {
            teamStarten(sitzung)
        }
    }

    /// Nach erfolgreicher Anmeldung (native Anmeldung folgt mit Server-Phase 1c).
    func teamAnmelden(_ sitzung: TeamSitzung) {
        team?.anwenden(.stop)
        guard schluesselbund.speichern(sitzung, konto: Schluesselbund.kontoTeam) else { return }
        teamStarten(sitzung)
        Task { await teamKonfigurieren() }
    }

    func teamAbmelden() {
        team?.anwenden(.stop)
    }

    /// App kommt in den Vordergrund: Ablauf prüfen und Status holen.
    func aktiviert() async {
        guard let team else { return }
        team.ablaufPruefen()
        await team.statusPruefen()
    }

    /// Aufwecken im Hintergrund (Modus `pause`), bis die stille Push-Mitteilung kommt.
    func hintergrundAbfrage() async {
        await aktiviert()
        hintergrundAbfragePlanen()
    }

    func hintergrundAbfragePlanen() {
        guard let team, team.zustand == .pausiert else { return }
        let anfrage = BGAppRefreshTaskRequest(identifier: Self.statusAufgabe)
        anfrage.earliestBeginDate = Date(timeIntervalSinceNow: TimeInterval(team.steuerung.pauseCheckSec ?? 120))
        try? BGTaskScheduler.shared.submit(anfrage)
    }

    private func teamKonfigurieren() async {
        guard let sitzung = teamSitzung, let team else { return }
        do {
            let config = try await TeamClient(sitzung: sitzung).config()
            team.anwenden(config.steuerung)
        } catch SchnittstellenFehler.beendet {
            team.anwenden(.stop)
        } catch {
            await team.statusPruefen()
        }
    }

    private func teamStarten(_ sitzung: TeamSitzung) {
        teamSitzung = sitzung
        let client = TeamClient(sitzung: sitzung)
        team = TeamOrtung(
            schnittstelle: client, quelle: quelle, puffer: puffer,
            ablauf: sitzung.expiresAt,
            beimBeenden: { [weak self] in self?.teamBeendet() })
        // Die Ansicht erfährt über /lage und /ereignisse sofort, wenn eine Suche beginnt oder endet
        teamAnsicht = TeamAnsicht(
            schnittstelle: client, teamId: sitzung.teamId ?? sitzung.teamName,
            beiSteuerung: { [weak self] modus in self?.team?.anwenden(SteuerAntwort(mode: modus)) })
        team?.ablaufPruefen()
    }

    private func teamBeendet() {
        teamAnsicht?.vergessen()
        schluesselbund.loeschen(konto: Schluesselbund.kontoTeam)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.statusAufgabe)
    }

    /// Nach dem Ende bleibt die Anzeige „beendet“ stehen, bis der Nutzer zurück zum Start geht.
    func teamSchliessen() {
        guard team?.zustand == .beendet else { return }
        team = nil
        teamAnsicht = nil
        teamSitzung = nil
    }
}
