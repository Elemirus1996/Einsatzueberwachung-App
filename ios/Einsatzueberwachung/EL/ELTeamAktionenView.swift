import SwiftUI

/// Aktionen für ein Team (wie `/el`): Suche starten (mit Halsband-Rückfrage), Pause, Weiter, Stopp, Zurücksetzen,
/// Mitteilung an das Team.
struct ELTeamAktionenView: View {
    let team: ELLage.Team
    @ObservedObject var betrieb: ELBetrieb
    @Environment(\.dismiss) private var schliessen
    @State private var halsbaender: ELHalsbaender?
    @State private var halsbandWahl = false
    @State private var stoppFragen = false
    @State private var zuruecksetzenFragen = false
    @State private var mitteilung = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Status", value: statusText)
                    if let gebiet = team.suchgebiet?.name { LabeledContent("Suchgebiet", value: gebiet) }
                    if let halsband = team.halsband?.name ?? team.halsband?.id { LabeledContent("Halsband", value: halsband) }
                }
                Section("Suche") {
                    switch team.suche.status {
                    case .bereit, .beendet:
                        Button { Task { await halsbandFragen() } } label: { Label("Suche starten", systemImage: "play.fill") }
                        if team.suche.status == .beendet {
                            Button(role: .destructive) { zuruecksetzenFragen = true } label: {
                                Label("Suchzeit zurücksetzen", systemImage: "arrow.counterclockwise")
                            }
                        }
                    case .laeuft:
                        Button { aktion(.teamPause(team.teamId, name: team.teamName)) } label: { Label("Pause", systemImage: "pause.fill") }
                        Button(role: .destructive) { stoppFragen = true } label: { Label("Suche beenden", systemImage: "stop.fill") }
                    case .kurzpause:
                        Button { aktion(.teamWeiter(team.teamId, name: team.teamName)) } label: { Label("Weiter", systemImage: "play.fill") }
                        Button(role: .destructive) { stoppFragen = true } label: { Label("Suche beenden", systemImage: "stop.fill") }
                    }
                }
                Section {
                    TextField("Mitteilung an \(team.teamName)", text: $mitteilung, axis: .vertical)
                    Button("Senden") {
                        aktion(.mitteilung(team.teamId, name: team.teamName, text: mitteilung.trimmingCharacters(in: .whitespacesAndNewlines)))
                    }
                    .disabled(mitteilung.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } header: {
                    Text("Mitteilung")
                } footer: {
                    Text("Erscheint in der Team-App, auch auf dem Sperrbildschirm.")
                }
            }
            .navigationTitle(team.teamName)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Fertig") { schliessen() } } }
            .confirmationDialog("Suche von \(team.teamName) beenden?", isPresented: $stoppFragen, titleVisibility: .visible) {
                Button("Suche beenden", role: .destructive) { aktion(.teamStopp(team.teamId, name: team.teamName)) }
            } message: { Text("Der Track wird gespeichert. Für eine Kurzpause „Pause“ wählen.") }
            .confirmationDialog("Suchzeit von \(team.teamName) zurücksetzen?", isPresented: $zuruecksetzenFragen, titleVisibility: .visible) {
                Button("Zurücksetzen", role: .destructive) { aktion(.teamZuruecksetzen(team.teamId, name: team.teamName)) }
            }
            .sheet(isPresented: $halsbandWahl) { halsbandAuswahl }
        }
    }

    /// Halsband-Rückfrage wie im Browser: frei verfügbare Halsbänder, das zuletzt benutzte zuerst, oder ohne.
    private var halsbandAuswahl: some View {
        NavigationStack {
            List {
                let zuletzt = halsbaender?.zuletzt?.first { $0.teamId == team.teamId }?.halsbandId
                let liste = (halsbaender?.frei ?? []).sorted { a, _ in a.id == zuletzt }
                ForEach(liste) { h in
                    Button { starten(mit: h.id) } label: {
                        HStack {
                            Text(h.name ?? h.id)
                            if h.id == zuletzt { Text("zuletzt").font(.caption).foregroundStyle(.secondary) }
                            Spacer()
                            if let akku = h.akku { Text("\(akku) %").foregroundStyle(.secondary) }
                        }
                    }
                }
                Button("Ohne Halsband starten") { starten(mit: nil) }
            }
            .navigationTitle("Halsband für \(team.teamName)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { halsbandWahl = false } } }
        }
    }

    private func halsbandFragen() async {
        halsbaender = await betrieb.halsbaender()
        // Ohne Halsband-Liste (Server noch ohne Endpunkt oder kein freies Halsband): mit dem zugewiesenen starten
        if halsbaender == nil || halsbaender?.frei.isEmpty == true {
            starten(mit: team.halsband?.id)
        } else {
            halsbandWahl = true
        }
    }

    private func starten(mit halsbandId: String?) {
        halsbandWahl = false
        aktion(.teamStart(team.teamId, name: team.teamName, halsbandId: halsbandId))
    }

    private func aktion(_ aktion: ELAktion) {
        Task { await betrieb.ausfuehren(aktion) }
        schliessen()
    }

    private var statusText: String {
        switch team.suche.status {
        case .bereit: "Bereit"
        case .laeuft: "Sucht"
        case .kurzpause: "Kurzpause"
        case .beendet: "Beendet"
        }
    }
}
