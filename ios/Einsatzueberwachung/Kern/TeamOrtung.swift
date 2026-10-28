import Foundation

/// Steuert die Ortung der Rolle Team nach den Antworten des Servers (`mode`).
/// Kern der App: Bei `pause` und `stop` wird der Ortungsdienst beendet, nicht nur das Senden.
@MainActor
final class TeamOrtung: ObservableObject {
    enum Zustand: Equatable {
        case wartet, ortet, pausiert, beendet
    }

    static let hoechstensJeUpload = 500

    @Published private(set) var zustand: Zustand = .wartet
    @Published private(set) var letzterUpload: Date?
    @Published private(set) var gepuffert = 0

    private let schnittstelle: TeamSchnittstelle
    private let quelle: OrtungsQuelle
    private let puffer: PositionsSpeicher
    private let jetzt: () -> Date
    private let beimBeenden: () -> Void

    private(set) var steuerung: SteuerAntwort
    private var letzteAufnahme: Date?
    private var letzteAnfrage: Date?
    private var anfrageLaeuft = false
    private var takt: Task<Void, Never>?

    init(schnittstelle: TeamSchnittstelle, quelle: OrtungsQuelle, puffer: PositionsSpeicher,
         ablauf: Date, jetzt: @escaping () -> Date = Date.init, beimBeenden: @escaping () -> Void) {
        self.schnittstelle = schnittstelle
        self.quelle = quelle
        self.puffer = puffer
        self.jetzt = jetzt
        self.beimBeenden = beimBeenden
        self.steuerung = SteuerAntwort(mode: .pause, expiresAt: ablauf)
        self.gepuffert = puffer.anzahl()
        quelle.beiPosition = { [weak self] position in
            self?.positionErhalten(position)
        }
    }

    var intervall: TimeInterval { TimeInterval(max(steuerung.intervalSec ?? 20, 1)) }
    var uploadTakt: TimeInterval { TimeInterval(max(steuerung.uploadEverySec ?? 60, 5)) }

    /// Übernimmt eine Antwort des Servers und schaltet die Ortung entsprechend.
    func anwenden(_ antwort: SteuerAntwort) {
        guard zustand != .beendet else { return }
        steuerung.mode = antwort.mode
        steuerung.intervalSec = antwort.intervalSec ?? steuerung.intervalSec
        steuerung.uploadEverySec = antwort.uploadEverySec ?? steuerung.uploadEverySec
        steuerung.pauseCheckSec = antwort.pauseCheckSec ?? steuerung.pauseCheckSec
        steuerung.expiresAt = antwort.expiresAt ?? steuerung.expiresAt

        if abgelaufen {
            beenden()
            return
        }
        switch antwort.mode {
        case .track:
            quelle.starten(intervall: intervall)
            zustand = .ortet
            taktStarten()
        case .pause:
            quelle.beenden()
            taktBeenden()
            zustand = .pausiert
        case .stop:
            beenden()
        }
    }

    /// Sicherheitsnetz: Ist `expiresAt` erreicht, endet die Ortung auch ohne Netz.
    func ablaufPruefen() {
        if zustand != .beendet, abgelaufen { beenden() }
    }

    func positionErhalten(_ position: Position) {
        ablaufPruefen()
        guard zustand == .ortet else { return }
        let zeit = Date(timeIntervalSince1970: TimeInterval(position.timestamp) / 1000)
        if let letzte = letzteAufnahme, zeit.timeIntervalSince(letzte) < intervall { return }
        letzteAufnahme = zeit
        puffer.hinzufuegen(position)
        gepuffert = puffer.anzahl()
        if istFaellig { Task { await hochladen() } }
    }

    /// Lädt den Puffer hoch (in Paketen zu höchstens 500). Ist er leer, wird nur der Status abgefragt,
    /// damit die App auch ohne neue Positionen erfährt, wann die Suche endet.
    func hochladen() async {
        guard !anfrageLaeuft, zustand != .beendet else { return }
        anfrageLaeuft = true
        defer { anfrageLaeuft = false }
        letzteAnfrage = jetzt()
        do {
            var paket = puffer.aelteste(Self.hoechstensJeUpload)
            if paket.isEmpty {
                anwenden(try await schnittstelle.status())
                return
            }
            while !paket.isEmpty, zustand != .beendet {
                let antwort = try await schnittstelle.positionenSenden(paket.map(\.position))
                puffer.loeschen(bisEinschliesslich: paket[paket.count - 1].id)
                gepuffert = puffer.anzahl()
                letzterUpload = jetzt()
                anwenden(antwort)
                paket = puffer.aelteste(Self.hoechstensJeUpload)
            }
        } catch SchnittstellenFehler.beendet {
            beenden()
        } catch {
            // Netzfehler oder 5xx: Positionen bleiben im Puffer und gehen beim nächsten Takt mit.
        }
    }

    /// Leichte Abfrage ohne GPS (Modus `pause`, App-Start, Aufwecken im Hintergrund).
    func statusPruefen() async {
        ablaufPruefen()
        guard zustand != .beendet else { return }
        if puffer.anzahl() > 0 {
            await hochladen()
            return
        }
        guard !anfrageLaeuft else { return }
        anfrageLaeuft = true
        defer { anfrageLaeuft = false }
        letzteAnfrage = jetzt()
        do {
            anwenden(try await schnittstelle.status())
        } catch SchnittstellenFehler.beendet {
            beenden()
        } catch {}
    }

    private var istFaellig: Bool {
        guard let letzte = letzteAnfrage else { return true }
        return jetzt().timeIntervalSince(letzte) >= uploadTakt
    }

    private var abgelaufen: Bool {
        guard let ablauf = steuerung.expiresAt else { return false }
        return jetzt() >= ablauf
    }

    /// Während der Ortung regelmäßig hochladen bzw. den Status fragen, auch wenn der Hundeführer stillsteht
    /// und darum keine Positionen kommen. Sonst bliebe das GPS nach Suchende an.
    private func taktStarten() {
        guard takt == nil else { return }
        takt = Task { [weak self] in
            while !Task.isCancelled {
                guard let pause = self?.uploadTakt else { return }
                try? await Task.sleep(for: .seconds(pause))
                guard !Task.isCancelled, let self, self.zustand == .ortet else { return }
                if self.istFaellig { await self.hochladen() }
            }
        }
    }

    private func taktBeenden() {
        takt?.cancel()
        takt = nil
    }

    private func beenden() {
        taktBeenden()
        quelle.beenden()
        puffer.leeren()
        gepuffert = 0
        steuerung.mode = .stop
        zustand = .beendet
        beimBeenden()
    }
}
