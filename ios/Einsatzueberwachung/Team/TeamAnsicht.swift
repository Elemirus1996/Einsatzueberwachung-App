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

    struct OffeneAktion: Codable, Equatable, Identifiable {
        var id: String
        var aktion: Suchaktion
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
    /// Steuerung der Suche, die im Funkloch auf das Netz wartet (Abschnitt 11).
    @Published private(set) var offeneAktionen: [OffeneAktion]
    /// Kurzer Hinweis nach 409/403 oder Bestätigung, z. B. „Suche läuft bereits“.
    @Published private(set) var suchHinweis: (text: String, zeit: Date)?
    private var aktionenLaufen = false

    private let schnittstelle: TeamAnsichtSchnittstelle
    private let speicher: UserDefaults
    private let schluessel: String
    private let beiSteuerung: (Modus) -> Void
    private let jetzt: () -> Date
    private var zeitVersatz: TimeInterval = 0
    private var letzteVolleTracks: Date?
    private var aufgaben: [Task<Void, Never>] = []
    private var meldungenLaufen = false
    private var imHintergrund = false

    /// Lokale Mitteilungen bei Änderungen der Lage (Suche, Gebiet, Halsband, Mitteilungen der EL).
    var beiMeldungen: (([LageMeldung]) -> Void)?
    /// Wartezeit vor dem nächsten Verbindungsversuch nach einer Verdrängung (sonst `nil`).
    private var verdraengtWarten: Duration?

    /// Auch bei stehender Live-Verbindung die Lage regelmäßig holen: Ein verpasstes `lage`-Ereignis
    /// darf Anzeige und Ortung nicht dauerhaft auf altem Stand lassen (Fehler aus dem iPhone-Test 29.10.2026).
    private let lageTakt: Duration

    init(schnittstelle: TeamAnsichtSchnittstelle, teamId: String, speicher: UserDefaults = .standard,
         jetzt: @escaping () -> Date = Date.init, lageTakt: Duration = .seconds(60),
         beiSteuerung: @escaping (Modus) -> Void) {
        self.schnittstelle = schnittstelle
        self.lageTakt = lageTakt
        self.speicher = speicher
        self.schluessel = "team.\(teamId)"
        self.jetzt = jetzt
        self.beiSteuerung = beiSteuerung
        self.gelesen = Set(speicher.stringArray(forKey: "\(schluessel).gelesen") ?? [])
        self.offeneMeldungen = (speicher.data(forKey: "\(schluessel).meldungen"))
            .flatMap { try? JSONDecoder().decode([OffeneMeldung].self, from: $0) } ?? []
        self.offeneAktionen = (speicher.data(forKey: "\(schluessel).aktionen"))
            .flatMap { try? JSONDecoder().decode([OffeneAktion].self, from: $0) } ?? []
    }

    // MARK: Ablauf

    func starten() {
        if imHintergrund { anhalten() }
        guard aufgaben.isEmpty else { return }
        aufgaben = [
            Task { await liveSchleife() },
            Task { await trackSchleife() },
            Task { await lageSchleife() },
        ]
    }

    func anhalten() {
        aufgaben.forEach { $0.cancel() }
        aufgaben = []
        live = false
        imHintergrund = false
    }

    /// App im Hintergrund: keine Live-Verbindung und keine Laufwege, aber jede Minute die Lage,
    /// damit Änderungen und Mitteilungen der EL als lokale Mitteilung ankommen (die App ist durch die Ortung wach).
    func hintergrund() {
        anhalten()
        imHintergrund = true
        aufgaben = [Task { [weak self] in
            while !Task.isCancelled {
                guard let takt = self?.lageTakt else { return }
                try? await Task.sleep(for: takt)
                guard !Task.isCancelled else { return }
                await self?.neuLaden(tracksVoll: false, mitTracks: false)
            }
        }]
    }

    /// Bei Abmeldung oder Einsatzende: nichts auf dem Gerät zurücklassen.
    func vergessen() {
        anhalten()
        speicher.removeObject(forKey: "\(schluessel).gelesen")
        speicher.removeObject(forKey: "\(schluessel).meldungen")
        speicher.removeObject(forKey: "\(schluessel).aktionen")
        gelesen = []
        offeneMeldungen = []
        offeneAktionen = []
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

    private func lageSchleife() async {
        while !Task.isCancelled {
            try? await Task.sleep(for: lageTakt)
            guard !Task.isCancelled else { return }
            // Ohne Live-Verbindung holt liveSchleife die Lage ohnehin alle 15 s
            if live { await neuLaden(tracksVoll: false) }
        }
    }

    /// Die Ortung hat von /positions oder /status einen neuen Modus erfahren: Anzeige nachziehen.
    func modusGeaendert() {
        guard !aufgaben.isEmpty else { return }
        Task { await neuLaden(tracksVoll: true, mitTracks: !imHintergrund) }
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
        case .halsband(var halsband):
            // Das Ereignis enthält nur die Position; Kennung und Name bleiben aus der Lage
            halsband.id = lage?.halsband?.id
            halsband.name = lage?.halsband?.name
            lage?.halsband = halsband
            if lage?.suche.status == .laeuft {
                tracks?.laufend.hund.append(.init(lat: halsband.lat, lng: halsband.lng,
                                                  t: Int64(halsband.zeit.timeIntervalSince1970 * 1000)))
            }
        case .mitteilung(let mitteilung):
            guard var neu = lage, !neu.mitteilungen.contains(where: { $0.id == mitteilung.id }) else { return }
            neu.mitteilungen.append(mitteilung)
            if neu.mitteilungen.count > 20 { neu.mitteilungen.removeFirst(neu.mitteilungen.count - 20) }
            lageUebernehmen(neu)
        case .lage:
            await neuLaden(tracksVoll: true)
        case .stop:
            // Absicherung: Seit Server #124 heißt `stop` echtes Ende (Verdrängung kommt als `verdraengt`),
            // ältere Server schickten es auch bei Verdrängung. Ob wirklich Schluss ist, sagt /lage (410 → stop).
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
            lageUebernehmen(neu)
            if let mode = neu.mode { beiSteuerung(mode) }
            return neu.mode == .stop
        } catch SchnittstellenFehler.beendet {
            beiSteuerung(.stop)
            return true
        } catch {
            return nil
        }
    }

    /// Neue Lage übernehmen und Änderungen gegenüber der bisherigen melden. Die erste Lage meldet nichts.
    private func lageUebernehmen(_ neu: TeamLage) {
        let alt = lage
        lage = neu
        lageAbgerufen = jetzt()
        guard let alt else { return }
        let meldungen = LageMeldung.vergleichen(alt: alt, neu: neu)
        if !meldungen.isEmpty { beiMeldungen?(meldungen) }
    }

    func neuLaden(tracksVoll: Bool = true, mitTracks: Bool = true) async {
        do {
            let neu = try await schnittstelle.lage()
            if let serverZeit = neu.serverZeit { zeitVersatz = serverZeit.timeIntervalSince(jetzt()) }
            lageUebernehmen(neu)
            verbindungsfehler = false
            if let mode = neu.mode { beiSteuerung(mode) }
        } catch SchnittstellenFehler.beendet {
            beiSteuerung(.stop)
            return
        } catch {
            verbindungsfehler = true
        }
        await meldungenSenden()
        await aktionenSenden()
        if mitTracks { await tracksLaden(voll: tracksVoll) }
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

    // MARK: Steuerung der Suche

    /// Darf das Team steuern? Ohne Angabe des Servers (ältere Version): nein.
    var darfSteuern: Bool { lage?.suche.darfSteuern == true }

    /// Legt die Aktion in die Warteschlange und sendet sie; dieselbe `id` wird nur einmal ausgeführt.
    func steuern(_ aktion: Suchaktion) async {
        offeneAktionen.append(OffeneAktion(id: UUID().uuidString, aktion: aktion, zeit: jetzt()))
        aktionenSpeichern()
        await aktionenSenden()
    }

    func aktionenSenden() async {
        guard !aktionenLaufen else { return }
        aktionenLaufen = true
        defer { aktionenLaufen = false }
        while let offen = offeneAktionen.first {
            do {
                let antwort = try await schnittstelle.suche(id: offen.id, aktion: offen.aktion)
                sucheUebernehmen(antwort.suche)
                // Modus sofort an die Ortung (z. B. „Suche beendet“ → pause → Grob-Ortung)
                if let mode = antwort.mode { beiSteuerung(mode) }
            } catch SchnittstellenFehler.beendet {
                beiSteuerung(.stop)
                return
            } catch SuchFehler.abgelehnt(let text, let suche) {
                // Die EL kann inzwischen umgeschaltet haben: Stand übernehmen und kurz anzeigen
                sucheUebernehmen(suche)
                suchHinweis = (text ?? "Nicht möglich, der Stand hat sich geändert.", jetzt())
            } catch SuchFehler.verboten(let text) {
                if var neu = lage {
                    neu.suche.darfSteuern = false
                    lageUebernehmen(neu)
                }
                suchHinweis = (text ?? "Die Einsatzleitung steuert die Suche.", jetzt())
            } catch SchnittstellenFehler.server(let status) where (400..<500).contains(status) && status != 429 {
                // Vom Server abgelehnt: nicht endlos wiederholen
            } catch {
                return
            }
            offeneAktionen.removeAll { $0.id == offen.id }
            aktionenSpeichern()
        }
    }

    private func sucheUebernehmen(_ suche: TeamLage.Suche?) {
        guard let suche, var neu = lage else { return }
        neu.suche = suche
        lageUebernehmen(neu)
    }

    private func aktionenSpeichern() {
        speicher.set(try? JSONEncoder().encode(offeneAktionen), forKey: "\(schluessel).aktionen")
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
