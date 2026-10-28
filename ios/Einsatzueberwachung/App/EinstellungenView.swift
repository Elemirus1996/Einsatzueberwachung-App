import SwiftUI

/// Einstellungen: Heimatstaffel ansehen und entfernen.
struct EinstellungenView: View {
    @ObservedObject var heimat: HeimatStaffel
    @Environment(\.dismiss) private var schliessen
    @State private var entfernenFragen = false

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let eintrag = heimat.eintrag {
                        HStack(spacing: 12) {
                            if let logo = heimat.logo {
                                Image(uiImage: logo).resizable().scaledToFit().frame(width: 44, height: 44)
                            }
                            Text(eintrag.staffel)
                        }
                        Button("Heimatstaffel entfernen", role: .destructive) { entfernenFragen = true }
                    } else {
                        Text("Keine gewählt").foregroundStyle(.secondary)
                    }
                } header: {
                    Text("Heimatstaffel")
                } footer: {
                    Text(heimat.eintrag == nil
                         ? "Bei der nächsten Anmeldung fragt die App, ob der Server zu deiner Staffel gehört."
                         : "Nur die Heimatstaffel erscheint auf der Startseite. Einsätze mit anderen Staffeln ändern daran nichts.")
                }
                Section("App") {
                    LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "–")
                }
            }
            .navigationTitle("Einstellungen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { schliessen() } }
            }
            .confirmationDialog("Heimatstaffel entfernen?", isPresented: $entfernenFragen, titleVisibility: .visible) {
                Button("Entfernen", role: .destructive) { heimat.entfernen() }
            } message: {
                Text("Bei der nächsten Anmeldung fragt die App erneut.")
            }
        }
    }
}
