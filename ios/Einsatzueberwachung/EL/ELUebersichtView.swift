import AudioToolbox
import SwiftUI

/// Übersicht der Einsatzleitung (Nachbau von `/el`): Einsatz, Teams mit Suchzeit und Warnstufe, Wetter.
/// iPhone: Liste; iPad: Raster mit mehreren Teams nebeneinander.
struct ELUebersichtView: View {
    @EnvironmentObject private var modell: AppModell
    @ObservedObject var ansicht: ELAnsicht
    @ObservedObject var ortung: TeamOrtung
    @ObservedObject var betrieb: ELBetrieb
    let karte: ELKartenAnsicht
    let vermisste: ELVermissteAnsicht
    @State private var gewaehltesTeam: ELLage.Team?
    @State private var startOffen = false
    @State private var beendenOffen = false
    @Environment(\.scenePhase) private var phase
    @Environment(\.horizontalSizeClass) private var breite
    @State private var abmeldenFragen = false

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { kontext in
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    if let offen = betrieb.offen.first {
                        Label("„\(offen.beschreibung)“ wartet auf Netz", systemImage: "antenna.radiowaves.left.and.right.slash")
                            .foregroundStyle(.orange)
                    } else if let hinweis = betrieb.hinweis, kontext.date.timeIntervalSince(hinweis.zeit) < 6 {
                        Label(hinweis.text, systemImage: hinweis.fehler ? "exclamationmark.circle" : "checkmark.circle")
                            .foregroundStyle(hinweis.fehler ? .red : .green)
                    }
                    if let warnung = ansicht.warnung, kontext.date.timeIntervalSince(warnung.zeit) < 30 {
                        Label(warnung.text, systemImage: "exclamationmark.triangle.fill")
                            .font(.headline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                            .background(warnung.stufe >= 2 ? Color.red.opacity(0.2) : Color.orange.opacity(0.2),
                                        in: RoundedRectangle(cornerRadius: 12))
                    }
                    if let punkt = ansicht.teamPunkt, kontext.date.timeIntervalSince(punkt.zeit) < 30 {
                        Label(punkt.text, systemImage: "mappin.and.ellipse")
                            .font(.headline)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding()
                            .background(Color.orange.opacity(0.2), in: RoundedRectangle(cornerRadius: 12))
                    }
                    if let verlaengerung = modell.elVerlaengerung { ELAblaufHinweis(verlaengerung: verlaengerung) }
                    einsatzKopf
                    if let lage = ansicht.lage, lage.einsatz.aktiv {
                        if lage.teams.isEmpty {
                            Text("Noch keine Teams im Einsatz.").foregroundStyle(.secondary)
                        }
                        LazyVGrid(columns: spalten, spacing: 12) {
                            ForEach(lage.teams) { team in
                                Button { gewaehltesTeam = team } label: {
                                    ELTeamKarte(team: team, ansicht: ansicht, zeitpunkt: kontext.date)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    fusszeile(kontext.date)
                }
                .padding()
            }
        }
        .navigationTitle("Einsatzleitung")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    ELVermissteView(vermisste: vermisste, betrieb: betrieb)
                } label: { Label("Vermisste", systemImage: "person.fill.questionmark") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    ELKarteView(karte: karte, teams: ansicht.lage?.teams ?? [], betrieb: betrieb)
                } label: { Label("Lagekarte", systemImage: "map") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink {
                    ELFunkView(betrieb: betrieb, teams: ansicht.lage?.teams ?? [])
                } label: { Label("Funk & Notizen", systemImage: "text.bubble") }
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    if ansicht.lage?.einsatz.aktiv == true {
                        Button("Einsatz beenden …") { beendenOffen = true }
                    }
                    Button("Abmelden", role: .destructive) { abmeldenFragen = true }
                } label: { Image(systemName: "ellipsis.circle") }
            }
        }
        .refreshable { await ansicht.neuLaden() }
        .onAppear {
            ansicht.beiWarnung = { stufe in
                // Warnton je Einstellung; Stufe 2 lässt sich nicht abschalten
                Toene.geraet.spielen(stufe >= 2 ? .warnung2 : .warnung1)
                UINotificationFeedbackGenerator().notificationOccurred(stufe >= 2 ? .error : .warning)
            }
            ansicht.starten()
        }
        .onDisappear { ansicht.anhalten() }
        .onChange(of: phase) { _, neu in
            if neu == .active { ansicht.starten() } else if neu == .background { ansicht.anhalten() }
        }
        .sheet(isPresented: $startOffen) {
            ELEinsatzStartView(betrieb: betrieb, eigenePosition: ortung.letztePosition)
        }
        .sheet(isPresented: $beendenOffen) {
            if let lage = ansicht.lage { ELEinsatzBeendenView(betrieb: betrieb, lage: lage) }
        }
        .sheet(item: $gewaehltesTeam) { team in
            ELTeamAktionenView(team: team, betrieb: betrieb)
        }
        .alert(betrieb.einsatzEnde?.pdf?.erstellt == false ? "Einsatz beendet, Bericht fehlt" : "Einsatz beendet",
               isPresented: Binding(get: { betrieb.einsatzEnde != nil }, set: { if !$0 { betrieb.einsatzEnde = nil } })) {
            Button("OK") { betrieb.einsatzEnde = nil }
        } message: {
            Text(betrieb.einsatzEnde?.meldung ?? "")
        }
        .confirmationDialog("Als Einsatzleitung abmelden?", isPresented: $abmeldenFragen, titleVisibility: .visible) {
            Button("Abmelden", role: .destructive) { Task { await modell.elAbmelden() } }
        } message: {
            Text("Die Ortung der Einsatzleitung endet, das Token wird gelöscht.")
        }
    }

    private var spalten: [GridItem] {
        breite == .regular ? [GridItem(.adaptive(minimum: 320), spacing: 12)] : [GridItem(.flexible())]
    }

    @ViewBuilder
    private var einsatzKopf: some View {
        if let lage = ansicht.lage {
            VStack(alignment: .leading, spacing: 4) {
                if lage.einsatz.aktiv {
                    HStack {
                        Text(lage.einsatz.text ?? "Einsatz").font(.title2.bold())
                        if lage.einsatz.uebung == true {
                            Text("ÜBUNG").font(.caption.bold()).padding(.horizontal, 6).padding(.vertical, 2)
                                .background(.orange, in: Capsule()).foregroundStyle(.white)
                        }
                    }
                    HStack(spacing: 12) {
                        if let szenario = lage.einsatz.szenario { Label(szenario, systemImage: "scope") }
                        if let beginn = lage.einsatz.beginn { Label { Text(beginn, style: .time) } icon: { Image(systemName: "clock") } }
                        if let el = lage.einsatz.einsatzleiter, !el.isEmpty { Label(el, systemImage: "person.badge.shield.checkmark") }
                    }
                    .font(.subheadline).foregroundStyle(.secondary)
                    if let wetter = lage.wetter {
                        Text([wetter.beschreibung,
                              wetter.temperatur.map { String(format: "%.0f °C", $0) },
                              wetter.wind.map { String(format: "Wind %.0f km/h", $0) }].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline).foregroundStyle(.secondary)
                    }
                } else {
                    Text("Kein Einsatz aktiv").font(.title2.bold())
                    Text("Die Ortung der Einsatzleitung ist aus und startet, sobald ein Einsatz läuft.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    Button { startOffen = true } label: { Label("Einsatz oder Übung starten", systemImage: "play.fill") }
                        .buttonStyle(.borderedProminent)
                        .padding(.top, 8)
                }
            }
        } else {
            Text(ansicht.verbindungsfehler ? "Keine Verbindung. Ist WireGuard eingeschaltet?" : "Lade …").foregroundStyle(.secondary)
        }
    }

    private func fusszeile(_ jetzt: Date) -> some View {
        HStack(spacing: 12) {
            Label(ortung.zustand == .ortet ? "EL-Standort wird gesendet" : "EL-Standort aus",
                  systemImage: ortung.zustand == .ortet ? "location.fill" : "location.slash")
            Spacer()
            Circle().fill(ansicht.live ? Color.green : (ansicht.verbindungsfehler ? Color.red : Color.orange)).frame(width: 8, height: 8)
        }
        .font(.caption).foregroundStyle(.secondary)
    }
}

/// Karte eines Teams in der Übersicht der Einsatzleitung.
struct ELTeamKarte: View {
    let team: ELLage.Team
    @ObservedObject var ansicht: ELAnsicht
    let zeitpunkt: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(team.teamName).font(.headline)
                Spacer()
                Text(statusText).font(.caption.bold()).foregroundStyle(statusFarbe)
            }
            Text([team.dogName, team.hundefuehrerName].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                .font(.subheadline).foregroundStyle(.secondary)
            if let dauer = ansicht.suchdauer(team, um: zeitpunkt), team.suche.status != .bereit {
                Text(TeamAnsichtView.dauer(dauer))
                    .font(.title2.monospacedDigit().bold())
                    .foregroundStyle(warnFarbe)
            }
            HStack(spacing: 12) {
                if let gebiet = team.suchgebiet?.name { Label(gebiet, systemImage: "square.dashed") }
                if let halsband = team.halsband { Label(geraeteText(halsband), systemImage: "dot.radiowaves.left.and.right") }
                if let handy = team.handy { Label(geraeteText(handy), systemImage: "iphone") }
            }
            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            if let bis = team.pause?.erforderlichBis, bis > zeitpunkt {
                Label { Text("Pause bis ") + Text(bis, style: .time) } icon: { Image(systemName: "cup.and.saucer") }
                    .font(.caption).foregroundStyle(.orange)
            }
        }
        .padding()
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(hintergrund, in: RoundedRectangle(cornerRadius: 14))
    }

    private var statusText: String {
        switch team.suche.status {
        case .bereit: "Bereit"
        case .laeuft: "Sucht"
        case .kurzpause: "Kurzpause"
        case .beendet: "Beendet"
        }
    }

    private var statusFarbe: Color {
        switch team.suche.status {
        case .laeuft: .green
        case .kurzpause: .orange
        default: .secondary
        }
    }

    private var warnFarbe: Color {
        switch team.warnstufe ?? 0 {
        case 2: .red
        case 1: .orange
        default: .primary
        }
    }

    private var hintergrund: Color {
        switch team.warnstufe ?? 0 {
        case 2: Color.red.opacity(0.15)
        case 1: Color.orange.opacity(0.15)
        default: Color(.secondarySystemBackground)
        }
    }

    private func geraeteText(_ g: ELLage.Team.Geraet) -> String {
        var teile: [String] = []
        if let alter = ansicht.alter(g.zeit, um: zeitpunkt) {
            teile.append(alter < 120 ? "\(Int(alter)) s" : "\(Int(alter / 60)) min")
        }
        if let akku = g.akku { teile.append("\(akku) %") }
        return teile.isEmpty ? (g.name ?? "–") : teile.joined(separator: " · ")
    }
}

/// „Anmeldung läuft um HH:mm ab“, wenn der Server das Verlängern abgelehnt hat (#141)
struct ELAblaufHinweis: View {
    @ObservedObject var verlaengerung: ELVerlaengerung

    var body: some View {
        if let text = verlaengerung.hinweis {
            Label(text, systemImage: "clock.badge.exclamationmark").foregroundStyle(.orange)
        }
    }
}
