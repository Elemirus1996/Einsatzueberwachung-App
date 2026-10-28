import SwiftUI

/// Zeigt Team, Server und ob die Ortung läuft, pausiert oder beendet ist.
struct TeamStatusView: View {
    @EnvironmentObject private var modell: AppModell
    @ObservedObject var ortung: TeamOrtung
    let sitzung: TeamSitzung
    @State private var abmeldenFragen = false

    var body: some View {
        List {
            Section {
                LabeledContent("Team", value: sitzung.teamName)
                LabeledContent("Server", value: sitzung.server.name)
            }
            Section("Ortung") {
                Label(zustandText, systemImage: zustandSymbol)
                    .foregroundStyle(zustandFarbe)
                if let upload = ortung.letzterUpload {
                    LabeledContent("Letzter Upload") {
                        Text(upload, style: .relative)
                    }
                }
                if ortung.gepuffert > 0 {
                    LabeledContent("Im Puffer", value: "\(ortung.gepuffert) Positionen")
                }
            }
            Section {
                if ortung.zustand == .beendet {
                    Button("Zurück zum Start") { modell.teamSchliessen() }
                } else {
                    Button("Abmelden", role: .destructive) { abmeldenFragen = true }
                }
            }
        }
        .navigationTitle("Team")
        .confirmationDialog("Ortung beenden und abmelden?", isPresented: $abmeldenFragen, titleVisibility: .visible) {
            Button("Abmelden", role: .destructive) { modell.teamAbmelden() }
        }
    }

    private var zustandText: String {
        switch ortung.zustand {
        case .wartet: "Verbinde mit Server …"
        case .ortet: "Ortung läuft"
        case .pausiert: "Ortung pausiert – GPS aus"
        case .beendet: "Ortung beendet"
        }
    }

    private var zustandSymbol: String {
        switch ortung.zustand {
        case .wartet: "antenna.radiowaves.left.and.right"
        case .ortet: "location.fill"
        case .pausiert: "pause.circle"
        case .beendet: "checkmark.circle"
        }
    }

    private var zustandFarbe: Color {
        switch ortung.zustand {
        case .wartet: .secondary
        case .ortet: .green
        case .pausiert: .orange
        case .beendet: .secondary
        }
    }
}
