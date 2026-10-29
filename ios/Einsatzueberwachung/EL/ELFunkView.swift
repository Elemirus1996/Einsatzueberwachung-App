import SwiftUI

/// Notizen und Funk (wie `/el`): Liste neueste zuerst, neue Einträge, Antworten auf Funksprüche.
struct ELFunkView: View {
    @ObservedObject var betrieb: ELBetrieb
    let teams: [ELLage.Team]
    @State private var text = ""
    @State private var istFunk = true
    @State private var teamId: String?
    @State private var antwortAuf: ELNotiz?

    var body: some View {
        List {
            Section {
                Picker("Art", selection: $istFunk) {
                    Text("Funk").tag(true)
                    Text("Notiz").tag(false)
                }
                .pickerStyle(.segmented)
                if let antwortAuf {
                    HStack {
                        Text("Antwort auf: \(antwortAuf.text)").font(.caption).lineLimit(1)
                        Spacer()
                        Button("✕") { self.antwortAuf = nil }
                    }
                } else {
                    Picker("Team", selection: $teamId) {
                        Text("ohne Team").tag(String?.none)
                        ForEach(teams) { Text($0.teamName).tag(Optional($0.teamId)) }
                    }
                }
                TextField(istFunk ? "Funkspruch" : "Notiz", text: $text, axis: .vertical)
                Button("Eintragen") { Task { await eintragen() } }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            Section("Verlauf") {
                ForEach(betrieb.notizen) { notiz in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(artText(notiz.art)).font(.caption.bold()).foregroundStyle(artFarbe(notiz.art))
                            if let team = teams.first(where: { $0.teamId == notiz.teamId }) {
                                Text(team.teamName).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text(notiz.zeit, style: .time).font(.caption).foregroundStyle(.secondary)
                        }
                        Text(notiz.text)
                        if let von = notiz.von { Text(von).font(.caption2).foregroundStyle(.secondary) }
                        ForEach(notiz.antworten ?? []) { a in
                            Text("↳ \(a.text)").font(.subheadline).padding(.leading, 12)
                        }
                    }
                    .swipeActions {
                        if notiz.art == "funk" {
                            Button("Antworten") {
                                antwortAuf = notiz
                                istFunk = true
                            }
                            .tint(.blue)
                        }
                        if notiz.loeschbar == true {
                            Button("Löschen", role: .destructive) { Task { await betrieb.ausfuehren(.notizLoeschen(notiz.id)) } }
                        }
                    }
                    .onAppear { if notiz.id == betrieb.notizen.last?.id { Task { await betrieb.aeltereNotizenLaden() } } }
                }
            }
        }
        .navigationTitle("Funk & Notizen")
        .task { await betrieb.notizenLaden() }
        .refreshable { await betrieb.notizenLaden() }
    }

    private func eintragen() async {
        let inhalt = text.trimmingCharacters(in: .whitespacesAndNewlines)
        text = ""
        if istFunk {
            await betrieb.ausfuehren(.funk(inhalt, teamId: antwortAuf?.teamId ?? teamId, antwortAuf: antwortAuf?.id))
        } else {
            await betrieb.ausfuehren(.notiz(inhalt, teamId: teamId))
        }
        antwortAuf = nil
        await betrieb.notizenLaden()
    }

    private func artText(_ art: String) -> String {
        switch art {
        case "funk": "Funk"
        case "status": "Status"
        case "system": "System"
        default: "Notiz"
        }
    }

    private func artFarbe(_ art: String) -> Color {
        switch art {
        case "funk": .blue
        case "status": .green
        case "system": .secondary
        default: .orange
        }
    }
}
