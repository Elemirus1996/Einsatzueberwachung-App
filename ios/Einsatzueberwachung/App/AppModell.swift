import BackgroundTasks
import Foundation

/// Hält die angemeldete Rolle und verbindet Schlüsselbund, Schnittstelle und Ortung.
@MainActor
final class AppModell: ObservableObject {
    static let statusAufgabe = "io.github.elemirus1996.einsatzspur.status"

    @Published private(set) var team: TeamOrtung?
    @Published private(set) var teamAnsicht: TeamAnsicht?
    @Published private(set) var teamSitzung: TeamSitzung?
    /// Offene Frage „Ist das deine eigene Staffel?“ nach der ersten Anmeldung an einem Server.
    @Published private(set) var heimatFrage: (info: ServerInfo, name: String)?

    let heimat = HeimatStaffel()

    private let schluesselbund = Schluesselbund()
    private let quelle = CoreLocationQuelle()
    private lazy var puffer = PositionsPuffer.standard()

    init() {
        if let sitzung = schluesselbund.lesen(TeamSitzung.self, konto: Schluesselbund.kontoTeam) {
            teamStarten(sitzung)
            Task { await teamKonfigurieren() }
        }
    }

    /// Nach erfolgreicher Anmeldung (native Anmeldung folgt mit Server-Phase 1c).
    func teamAnmelden(_ sitzung: TeamSitzung, server: ServerInfo?, staffel: String?) {
        if let server, heimat.sollFragen(server) {
            heimatFrage = (server, server.staffel ?? staffel ?? sitzung.server.name)
        }
        teamAnmelden(sitzung)
    }

    func heimatBeantworten(_ eigeneStaffel: Bool) {
        guard let frage = heimatFrage else { return }
        heimatFrage = nil
        guard eigeneStaffel else {
            heimat.ablehnen(frage.info)
            return
        }
        heimat.uebernehmen(frage.info, name: frage.name)
        guard let sitzung = teamSitzung else { return }
        Task { await heimat.aktualisieren(frage.info) { try await TeamClient(sitzung: sitzung).logo() } }
    }

    private func teamAnmelden(_ sitzung: TeamSitzung) {
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
            let client = TeamClient(sitzung: sitzung)
            let config = try await client.config()
            team.anwenden(config.steuerung)
            if let server = config.server {
                await heimat.aktualisieren(server) { try await client.logo() }
            }
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
            beiSteuerung: { [weak self] modus in self?.team?.anwenden(SteuerAntwort(mode: modus), melden: false) })
        team?.beiModuswechsel = { [weak self] _ in self?.teamAnsicht?.modusGeaendert() }
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
