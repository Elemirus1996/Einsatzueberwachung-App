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
    /// Teamauswahl desselben Einsatzes nach „Team wechseln“ (gespeicherter Anmelde-Code, kein QR-Scan).
    @Published private(set) var teamWahl: TeamQRCode?
    /// Server und Anmelde-Code des laufenden Einsatzes (Schlüsselbund), solange der Einsatz läuft.
    @Published private(set) var einsatzZugang: TeamQRCode?

    /// Wie eine Ortung endet: entscheidet über Mitteilung und darüber, ob der Anmelde-Code bleibt.
    enum Abmeldung: Equatable {
        /// Server: Einsatzende, Team gelöscht, Token abgelaufen (410/stop)
        case server
        /// Nutzer wechselt das Team im selben Einsatz
        case teamWechsel
        /// Nutzer meldet sich vom Einsatz ab
        case einsatz

        var meldetEinsatzende: Bool { self == .server }
        var behaeltZugang: Bool { self == .teamWechsel }
    }

    let heimat = HeimatStaffel()
    private let mitteiler: Mitteiler = LokaleMitteilungen()
    private var abmeldung = Abmeldung.server

    private let schluesselbund = Schluesselbund()
    private let quelle = CoreLocationQuelle()
    private lazy var puffer = PositionsPuffer.standard()

    init() {
        einsatzZugang = schluesselbund.lesen(TeamQRCode.self, konto: Schluesselbund.kontoEinsatzZugang)
        if let sitzung = schluesselbund.lesen(TeamSitzung.self, konto: Schluesselbund.kontoTeam) {
            teamStarten(sitzung)
            Task { await teamKonfigurieren() }
        }
    }

    /// Nach erfolgreicher Anmeldung (native Anmeldung folgt mit Server-Phase 1c).
    func teamAnmelden(_ sitzung: TeamSitzung, zugang: TeamQRCode, server: ServerInfo?, staffel: String?) {
        mitteiler.erlaubnisErfragen()
        if schluesselbund.speichern(zugang, konto: Schluesselbund.kontoEinsatzZugang) { einsatzZugang = zugang }
        teamWahl = nil
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
        abmeldung = .teamWechsel
        team?.anwenden(.stop)
        guard schluesselbund.speichern(sitzung, konto: Schluesselbund.kontoTeam) else { return }
        teamStarten(sitzung)
        Task { await teamKonfigurieren() }
    }

    /// „Team wechseln“: Puffer hochladen, Ortung des alten Teams beenden, Token löschen, dann Teamauswahl
    /// desselben Einsatzes mit dem gespeicherten Code. `false`, wenn noch Positionen im Puffer liegen
    /// (kein Netz) und `trotzdem` nicht gesetzt ist; die App fragt dann nach.
    @discardableResult
    func teamWechseln(trotzdem: Bool = false) async -> Bool {
        guard let team, let zugang = einsatzZugang else { return true }
        if team.gepuffert > 0 { await team.hochladen() }
        guard trotzdem || team.gepuffert == 0 else { return false }
        abmeldung = .teamWechsel
        team.anwenden(.stop)
        teamEntfernen()
        teamWahl = zugang
        return true
    }

    /// „Vom Einsatz abmelden“: dauerhaft, löscht Token und Anmelde-Code, zurück zum Start.
    func vomEinsatzAbmelden() {
        abmeldung = .einsatz
        if let team, team.zustand != .beendet {
            team.anwenden(.stop)
        } else {
            zugangLoeschen()
        }
        teamEntfernen()
        teamWahl = nil
    }

    /// Der gespeicherte Code gilt nicht mehr (login/teams → 401, z. B. neuer Einsatz): zurück zum QR-Scan.
    func zugangUngueltig() {
        zugangLoeschen()
        teamWahl = nil
    }

    func teamWahlAbbrechen() {
        teamWahl = nil
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
        teamAnsicht?.beiMeldungen = { [weak self] meldungen in
            meldungen.forEach { self?.mitteiler.senden($0) }
        }
        team?.ablaufPruefen()
    }

    private func teamBeendet() {
        if abmeldung.meldetEinsatzende { mitteiler.senden(.einsatzBeendet) }
        if !abmeldung.behaeltZugang { zugangLoeschen() }
        abmeldung = .server
        teamAnsicht?.vergessen()
        schluesselbund.loeschen(konto: Schluesselbund.kontoTeam)
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.statusAufgabe)
    }

    /// Nach dem Ende bleibt die Anzeige „beendet“ stehen, bis der Nutzer zurück zum Start geht.
    func teamSchliessen() {
        guard team?.zustand == .beendet else { return }
        teamEntfernen()
    }

    private func teamEntfernen() {
        team = nil
        teamAnsicht = nil
        teamSitzung = nil
    }

    private func zugangLoeschen() {
        schluesselbund.loeschen(konto: Schluesselbund.kontoEinsatzZugang)
        einsatzZugang = nil
    }
}
