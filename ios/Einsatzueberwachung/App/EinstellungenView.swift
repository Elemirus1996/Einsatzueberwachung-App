import SwiftUI

/// Einstellungen: Heimatstaffel ansehen und entfernen.
struct EinstellungenView: View {
    @EnvironmentObject private var modell: AppModell
    @ObservedObject var heimat: HeimatStaffel
    @State private var einsatzAbmeldenFragen = false
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
                if modell.einsatzZugang != nil {
                    Section {
                        Button("Vom Einsatz abmelden", role: .destructive) { einsatzAbmeldenFragen = true }
                    } header: {
                        Text("Einsatz")
                    } footer: {
                        Text("Beendet die Ortung und vergisst den Anmelde-Code. Für den nächsten Einsatz wieder den QR-Code scannen.")
                    }
                }
                Section {
                    NavigationLink("Töne") { TonEinstellungenView(toene: Toene.geraet) }
                } header: {
                    Text("Töne")
                } footer: {
                    Text("Für jede Art von Meldung einen eigenen Ton wählen.")
                }
                if modell.elSitzung != nil {
                    Section {
                        NavigationLink("Mitteilungen der Einsatzleitung") {
                            ELMitteilungsEinstellungenView(einstellungen: .geraet)
                        }
                    } header: {
                        Text("Einsatzleitung")
                    } footer: {
                        Text("Warnungen aufs gesperrte Handy, solange ein Einsatz läuft und du Einsatzleiter bist.")
                    }
                }
                Section {
                    NavigationLink("Datenschutzerklärungen der Staffeln") {
                        DatenschutzListeView(erklaerungen: modell.datenschutz.alle())
                    }
                } header: {
                    Text("Datenschutz")
                } footer: {
                    Text("Verantwortlich ist die Staffel, die den Server betreibt. Hier steht die zuletzt gezeigte Fassung je Server.")
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
            .confirmationDialog("Vom Einsatz abmelden?", isPresented: $einsatzAbmeldenFragen, titleVisibility: .visible) {
                Button("Abmelden", role: .destructive) {
                    modell.vomEinsatzAbmelden()
                    schliessen()
                }
            }
            .confirmationDialog("Heimatstaffel entfernen?", isPresented: $entfernenFragen, titleVisibility: .visible) {
                Button("Entfernen", role: .destructive) { modell.heimatEntfernen() }
            } message: {
                Text("Bei der nächsten Anmeldung fragt die App erneut.")
            }
        }
    }
}

/// Ton je Art von Meldung (APP_API.md „Töne in den Apps“). „Aus“ gibt es für Warnung Stufe 2 nicht.
struct TonEinstellungenView: View {
    @ObservedObject var toene: Toene

    var body: some View {
        List {
            Section("Team") { zeilen(Tonart.allCases.filter(\.fuerTeam)) }
            Section {
                zeilen(Tonart.allCases.filter { !$0.fuerTeam })
            } header: {
                Text("Einsatzleitung")
            } footer: {
                Text("Warnung Stufe 2 lässt sich nicht ausschalten. Die Einstellung gilt für dieses Gerät.")
            }
        }
        .navigationTitle("Töne")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func zeilen(_ arten: [Tonart]) -> some View {
        ForEach(arten) { art in
            Picker(art.titel, selection: Binding(get: { toene.wahl(fuer: art) }, set: { neu in
                toene.setzen(neu, fuer: art)
                toene.probe(neu) // Probehören beim Auswählen
            })) {
                ForEach(toene.moeglich(fuer: art), id: \.self) { Text($0.name).tag($0) }
            }
            .pickerStyle(.navigationLink)
        }
    }
}

/// Mitteilungen der Einsatzleitung im Hintergrund (#79): Hauptschalter und je Art an oder aus.
struct ELMitteilungsEinstellungenView: View {
    @ObservedObject var einstellungen: ELMitteilungsEinstellungen

    var body: some View {
        List {
            Section {
                Toggle("Mitteilungen im Hintergrund", isOn: Binding(get: { einstellungen.an }, set: { einstellungen.setzen(an: $0) }))
            } footer: {
                Text("Nur während eines laufenden Einsatzes und nur als Einsatzleiter: Die Ortung hält die App wach, ohne Push. Wird die App weggewischt, kommt nichts mehr an. Die Texte nennen nur das Team, keine Vermissten, Orte oder Funkinhalte.")
            }
            Section("Arten") {
                ForEach(ELMitteilungsArt.allCases) { art in
                    Toggle(art.titel, isOn: Binding(get: { einstellungen.ist(art) }, set: { einstellungen.setzen($0, fuer: art) }))
                }
            }
            .disabled(!einstellungen.an)
        }
        .navigationTitle("Mitteilungen")
        .navigationBarTitleDisplayMode(.inline)
    }
}
