import Foundation

/// Daten der nativen Team-Ansicht: Lage, Laufwege, Mitteilungen, Statusmeldungen.
/// Läuft nur, solange die Ansicht im Vordergrund ist; im Hintergrund arbeitet allein die Ortung.
@MainActor
final class TeamAnsicht: ObservableObject {
    struct OffeneMeldung: Codable, Equatable, Identifiable {
        var id: String
        var code: Statusmeldung.Code
        var zeit: Date
    }

    static let pollTakt: Duration = .seconds(15)
    static let neuVerbindenNach: Duration = .seconds(5)
    /// Nach einem `stop`, das sich als unecht herausstellt (z. B. vom Server verdrängt, weil schon 3 Geräte
    /// des Teams verbunden sind): länger warten, damit sich die Geräte nicht gegenseitig hinauswerfen.
    static let nachVerdraengung: Duration = .seconds(60)
    static let trackTakt: Duration = .seconds(30)
    static let trackVollNach: TimeInterval = 300

    @Published private(set) var lage: TeamLage?
    @Published private(set) var lageAbgerufen: Date?
    @Published private(set) var tracks: TeamTracks?
    @Published private(set) var live = false
    @Published private(set) var verbindungsfehler = false
    @Published private(set) var offeneMeldungen: [OffeneMeldung]
    @Published private(set) var gelesen: Set<String>
    /// Zuletzt bestätigte Statusmeldung, für die kurze Rückmeldung unter den Knöpfen.
    @Published private(set) var gesendet: (code: Statusmeldung.Code, zeit: Date)?

    private let schnittstelle: TeamAnsichtSchnittstelle
    private let speicher: UserDefaults
    private let schluessel: String
    private let beiSteuerung: (Modus) -> Void
    private let jetzt: () -> Date
    private var zeitVersatz: TimeInterval = 0
    private var letzteVolleTracks: Date?
    private var aufgaben: [Task<Void, Never>] = []
    private var meldungenLaufen = false
    /// Wartezeit vor dem nächsten Verbindungsversuch nach einer Verdrängung (sonst `nil`).
    private var verdraengtWarten: Duration?

    init(schnittstelle: TeamAnsichtSchnittstelle, teamId: String, speicher: UserDefaults = .standard,
         jetzt: @escaping () -> Date = Date.init, beiSteuerung: @escaping (Modus) -> Void) {
        self.schnittstelle = schnittstelle
        self.speicher = speicher
        self.schluessel = "team.\(teamId)"
        self.jetzt = jetzt
        self.beiSteuerung = beiSteuerung
        self.gelesen = Set(speicher.stringArray(forKey: "\(schluessel).gelesen") ?? [])
        self.offeneMeldungen = (speicher.data(forKey: "\(schluessel).meldungen"))
            .flatMap { try? JSONDecoder().decode([OffeneMeldung].self, from: $0) } ?? []
    }

    // MARK: Ablauf

    func starten() {
        guard aufgaben.isEmpty else { return }
        aufgaben = [
            Task { await liveSchleife() },
            Task { await trackSchleife() },
        ]
    }

    func anhalten() {
        aufgaben.forEach { $0.cancel() }
        aufgaben = []
        live = false
    }

    /// Bei Abmeldung oder Einsatzende: nichts auf dem Gerät zurücklassen.
    func vergessen() {
        anhalten()
        speicher.removeObject(forKey: "\(schluessel).gelesen")
        speicher.removeObject(forKey: "\(schluessel).meldungen")
        gelesen = []
        offeneMeldungen = []
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
                beiSteuerung(.stop)
                return
            } catch {}
            live = false
            // Nach einem Abbruch schnell neu verbinden; ohne Live-Verbindung alle 15 s die Lage holen
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

    private func trackSchleife() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: Self.trackTakt)
            guard !Task.isCancelled else { return }
            await tracksLaden()
        }
    }

    func verarbeiten(_ ereignis: TeamEreignis) async {
        switch ereignis {
        case .halsband(let halsband):
            lage?.halsband = halsband
            if lage?.suche.status == .laeuft {
                tracks?.laufend.hund.append(.init(lat: halsband.lat, lng: halsband.lng,
                                                  t: Int64(halsband.zeit.timeIntervalSince1970 * 1000)))
            }
        case .mitteilung(let mitteilung):
            guard lage?.mitteilungen.contains(where: { $0.id == mitteilung.id }) == false else { return }
            lage?.mitteilungen.append(mitteilung)
            if let anzahl = lage?.mitteilungen.count, anzahl > 20 { lage?.mitteilungen.removeFirst(anzahl - 20) }
        case .lage:
            await neuLaden(tracksVoll: true)
        case .stop:
            // Nur ein Hinweis: Der Server schickt `stop` auch, wenn er die Verbindung verdrängt.
            // Ob wirklich Schluss ist, sagt /lage (410 → stop); sonst bleibt die Ortung, wie sie ist.
            if await lageBestaetigtStop() == false { verdraengtWarten = Self.nachVerdraengung }
        case .verdraengt(let sekunden):
            // Server ab #124: angemeldet bleiben, nicht sofort neu verbinden (höchstens 10 min warten)
            verdraengtWarten = .seconds(min(max(sekunden, 5), 600))
        }
    }

    /// `true`: 410, Ortung ist beendet. `false`: Token gilt noch. `nil`: kein Netz, weiter wie bisher.
    private func lageBestaetigtStop() async -> Bool? {
        do {
            let neu = try await schnittstelle.lage()
            lage = neu
            lageAbgerufen = jetzt()
            if let mode = neu.mode { beiSteuerung(mode) }
            return neu.mode == .stop
        } catch SchnittstellenFehler.beendet {
            beiSteuerung(.stop)
            return true
        } catch {
            return nil
        }
    }

    func neuLaden(tracksVoll: Bool = true) async {
        do {
            let neu = try await schnittstelle.lage()
            if let serverZeit = neu.serverZeit { zeitVersatz = serverZeit.timeIntervalSince(jetzt()) }
            lage = neu
            lageAbgerufen = jetzt()
            verbindungsfehler = false
            if let mode = neu.mode { beiSteuerung(mode) }
        } catch SchnittstellenFehler.beendet {
            beiSteuerung(.stop)
            return
        } catch {
            verbindungsfehler = true
        }
        await meldungenSenden()
        await tracksLaden(voll: tracksVoll)
    }

    private func tracksLaden(voll: Bool = false) async {
        let vollNoetig = voll || tracks == nil
            || letzteVolleTracks.map { jetzt().timeIntervalSince($0) >= Self.trackVollNach } ?? true
        do {
            if vollNoetig {
                tracks = try await schnittstelle.tracks(seit: nil)
                letzteVolleTracks = jetzt()
            } else if var bisher = tracks {
                let neu = try await schnittstelle.tracks(seit: Self.letzterZeitpunkt(bisher))
                bisher.laufend.hund.append(contentsOf: neu.laufend.hund)
                bisher.laufend.mensch.append(contentsOf: neu.laufend.mensch)
                tracks = bisher
            }
        } catch SchnittstellenFehler.beendet {
            beiSteuerung(.stop)
        } catch {}
    }

    nonisolated static func letzterZeitpunkt(_ tracks: TeamTracks) -> Int64? {
        (tracks.laufend.hund + tracks.laufend.mensch).compactMap(\.t).max()
    }

    // MARK: Statusmeldungen

    /// Legt die Meldung in die Warteschlange und sendet sie. Im Funkloch bleibt sie dort,
    /// bis der Server sie bestätigt; dieselbe `id` erzeugt nur eine Notiz.
    func melden(_ code: Statusmeldung.Code) async {
        offeneMeldungen.append(OffeneMeldung(id: UUID().uuidString, code: code, zeit: jetzt()))
        meldungenSpeichern()
        await meldungenSenden()
    }

    func meldungenSenden() async {
        guard !meldungenLaufen else { return }
        meldungenLaufen = true
        defer { meldungenLaufen = false }
        while let meldung = offeneMeldungen.first {
            do {
                try await schnittstelle.statusmeldung(id: meldung.id, code: meldung.code)
                gesendet = (meldung.code, jetzt())
            } catch SchnittstellenFehler.beendet {
                beiSteuerung(.stop)
                return
            } catch SchnittstellenFehler.server(let status) where (400..<500).contains(status) && status != 429 {
                // Vom Server abgelehnt (z. B. unbekannter Code): nicht endlos wiederholen
            } catch {
                return
            }
            offeneMeldungen.removeAll { $0.id == meldung.id }
            meldungenSpeichern()
        }
    }

    private func meldungenSpeichern() {
        speicher.set(try? JSONEncoder().encode(offeneMeldungen), forKey: "\(schluessel).meldungen")
    }

    // MARK: Mitteilungen

    var ungelesen: [Mitteilung] {
        (lage?.mitteilungen ?? []).filter { !gelesen.contains($0.id) }
    }

    func alsGelesen(_ mitteilung: Mitteilung) {
        gelesen.insert(mitteilung.id)
        speicher.set(Array(gelesen), forKey: "\(schluessel).gelesen")
    }

    // MARK: Anzeige

    /// Alter der Halsband-Position, mit der Uhr des Servers gerechnet (das Handy kann falsch gehen).
    func halsbandAlter(um zeitpunkt: Date) -> TimeInterval? {
        guard let zeit = lage?.halsband?.zeit else { return nil }
        return max(0, zeitpunkt.addingTimeInterval(zeitVersatz).timeIntervalSince(zeit))
    }

    /// Suchzeit wie im Monitor; läuft die Suche, zählt die App seit dem letzten Abruf weiter.
    func suchdauer(um zeitpunkt: Date) -> TimeInterval? {
        guard let suche = lage?.suche, let dauer = suche.dauerSec, let abgerufen = lageAbgerufen else { return nil }
        let basis = TimeInterval(dauer)
        return suche.status == .laeuft ? basis + max(0, zeitpunkt.timeIntervalSince(abgerufen)) : basis
    }
}
