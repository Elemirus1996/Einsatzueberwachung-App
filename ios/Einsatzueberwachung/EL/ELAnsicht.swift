import Foundation

/// Übersicht der Einsatzleitung: Lage, Live-Ereignisse, Warnungen. Läuft, solange die Ansicht sichtbar ist.
/// Der Modus aus /lage steuert die Ortung der Einsatzleitung (Einsatz läuft → track, sonst pause).
@MainActor
final class ELAnsicht: ObservableObject {
    static let pollTakt: Duration = .seconds(15)
    static let neuVerbindenNach: Duration = .seconds(5)

    @Published private(set) var lage: ELLage?
    @Published private(set) var lageAbgerufen: Date?
    @Published private(set) var live = false
    @Published private(set) var verbindungsfehler = false
    /// Letzte Warnung (Warnstufe 1 oder 2) für Hinweis, Ton und Vibration.
    @Published private(set) var warnung: (text: String, stufe: Int, zeit: Date)?
    /// Letzter Punkt, den ein Team per App gesetzt hat (Hinweis)
    @Published private(set) var teamPunkt: (text: String, zeit: Date)?

    private let schnittstelle: ELLageSchnittstelle
    private let jetzt: () -> Date
    private let lageTakt: Duration
    private let beiSteuerung: (Modus) -> Void
    private var aufgaben: [Task<Void, Never>] = []
    private var zeitVersatz: TimeInterval = 0
    private var verdraengtWarten: Duration?

    /// Ton bzw. Vibration bei Eskalation (in der Ansicht gesetzt).
    var beiWarnung: ((Int) -> Void)?
    /// Neue Notiz bzw. neuer Funkspruch (Liste neu laden)
    var beiNotiz: (() async -> Void)?
    /// Vermisste geändert (auch durch /el im Browser)
    var beiVermisste: (() async -> Void)?
    /// Live-Position für die Karte
    var beiPosition: ((ELPosition) -> Void)?
    /// Kartenpunkt geändert (Karte neu laden)
    var beiPunkt: (() async -> Void)?
    /// Ton je Art (Punkt vom Team, Funk); Warnungen über `beiWarnung`
    var beiTon: ((Tonart) -> Void)?
    /// Nach jeder erfolgreich geladenen Lage (wartende Aktionen senden)
    var nachLage: (() async -> Void)?

    init(schnittstelle: ELLageSchnittstelle, jetzt: @escaping () -> Date = Date.init,
         lageTakt: Duration = .seconds(60), beiSteuerung: @escaping (Modus) -> Void) {
        self.schnittstelle = schnittstelle
        self.jetzt = jetzt
        self.lageTakt = lageTakt
        self.beiSteuerung = beiSteuerung
    }

    func starten() {
        guard aufgaben.isEmpty else { return }
        aufgaben = [
            Task { await liveSchleife() },
            Task { [weak self] in
                while !Task.isCancelled {
                    guard let takt = self?.lageTakt else { return }
                    try? await Task.sleep(for: takt)
                    guard !Task.isCancelled, let self, self.live else { continue }
                    await self.neuLaden()
                }
            },
        ]
    }

    func anhalten() {
        aufgaben.forEach { $0.cancel() }
        aufgaben = []
        live = false
    }

    private func liveSchleife() async {
        await neuLaden()
        while !Task.isCancelled {
            var warVerbunden = false
            do {
                let strom = try await schnittstelle.ereignisse()
                live = true
                verbindungsfehler = false
                warVerbunden = true
                for try await ereignis in strom {
                    await verarbeiten(ereignis)
                }
            } catch SchnittstellenFehler.beendet {
                live = false
                beiSteuerung(.stop)
                return
            } catch {}
            live = false
            if let warten = verdraengtWarten {
                verdraengtWarten = nil
                try? await Task.sleep(for: warten)
            } else {
                try? await Task.sleep(for: warVerbunden ? Self.neuVerbindenNach : Self.pollTakt)
            }
            guard !Task.isCancelled else { return }
            await neuLaden()
        }
    }

    func verarbeiten(_ ereignis: ELEreignis) async {
        switch ereignis {
        case .team, .einsatz:
            await neuLaden()
        case .warnung(_, let stufe, let text):
            warnung = (text, stufe, jetzt())
            beiWarnung?(stufe)
        case .stop:
            // Nur bei ungültigem Token; /lage bestätigt (410 → stop)
            await neuLaden()
        case .verdraengt(let sekunden):
            verdraengtWarten = .seconds(min(max(sekunden, 5), 600))
        case .notiz:
            await beiNotiz?()
        case .funk:
            beiTon?(.funk)
            await beiNotiz?()
        case .punkt(_, let teamId, let aenderung, let text):
            if aenderung == "neu", teamId != nil {
                teamPunkt = (text ?? "Neuer Punkt von einem Team", jetzt())
                beiTon?(.punktVomTeam)
            }
            await beiPunkt?()
        case .position(let p):
            beiPosition?(p)
        case .vermisste:
            await beiVermisste?()
        }
    }

    func neuLaden() async {
        do {
            let neu = try await schnittstelle.lage()
            if let serverZeit = neu.serverZeit { zeitVersatz = serverZeit.timeIntervalSince(jetzt()) }
            lage = neu
            lageAbgerufen = jetzt()
            verbindungsfehler = false
            if let mode = neu.mode { beiSteuerung(mode) }
            await nachLage?()
        } catch SchnittstellenFehler.beendet {
            beiSteuerung(.stop)
        } catch {
            verbindungsfehler = true
        }
    }

    // MARK: Anzeige

    /// Suchzeit eines Teams; läuft die Suche, zählt die App seit dem letzten Abruf weiter.
    func suchdauer(_ team: ELLage.Team, um zeitpunkt: Date) -> TimeInterval? {
        guard let dauer = team.suche.dauerSec, let abgerufen = lageAbgerufen else { return nil }
        let basis = TimeInterval(dauer)
        return team.suche.status == .laeuft ? basis + max(0, zeitpunkt.timeIntervalSince(abgerufen)) : basis
    }

    /// Alter einer Position mit der Uhr des Servers.
    func alter(_ zeit: Date?, um zeitpunkt: Date) -> TimeInterval? {
        guard let zeit else { return nil }
        return max(0, zeitpunkt.addingTimeInterval(zeitVersatz).timeIntervalSince(zeit))
    }
}
