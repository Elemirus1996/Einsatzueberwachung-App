import CoreLocation
import SwiftUI

/// Einsatz starten (wie `/el/start`): Stichwort, Ort, Szenario, Einsatz/Übung, Einsatzleiter, Führungsassistent.
struct ELEinsatzStartView: View {
    @ObservedObject var betrieb: ELBetrieb
    let eigenePosition: Position?
    @Environment(\.dismiss) private var schliessen
    @State private var daten = ELEinsatzStart()
    @State private var standortUebernehmen = true

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker("Art", selection: $daten.uebung) {
                        Text("Einsatz").tag(false)
                        Text("Übung").tag(true)
                    }
                    .pickerStyle(.segmented)
                    TextField("Stichwort", text: $daten.stichwort)
                    TextField("Einsatzort (Pflicht)", text: $daten.ort)
                    Picker("Szenario", selection: $daten.szenario) {
                        ForEach(ELSzenario.allCases, id: \.self) { Text($0.anzeige).tag($0) }
                    }
                }
                Section("Führung") {
                    TextField("Einsatzleiter", text: $daten.einsatzleiter)
                    TextField("Führungsassistent", text: $daten.fuehrungsassistent)
                }
                if let p = eigenePosition {
                    Section {
                        Toggle("Eigenen Standort als Einsatzort", isOn: $standortUebernehmen)
                    } footer: {
                        Text("Die Koordinate hilft beim Wetter und als Mittelpunkt der Karte. Genauigkeit \(TeamAnsichtView.genauigkeit(p.accuracy)).")
                    }
                }
            }
            .navigationTitle(daten.uebung ? "Übung starten" : "Einsatz starten")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { schliessen() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Starten") {
                        var start = daten
                        if standortUebernehmen, let p = eigenePosition {
                            start.lat = p.lat
                            start.lng = p.lng
                        }
                        Task { await betrieb.ausfuehren(.einsatzStart(start)) }
                        schliessen()
                    }
                    .disabled(!daten.vollstaendig)
                }
            }
        }
    }
}

/// Einsatz beenden (wie `/el/beenden`): Ergebnis, Prüfung der Ressourcen, PDF mit Tracks.
struct ELEinsatzBeendenView: View {
    @ObservedObject var betrieb: ELBetrieb
    let lage: ELLage
    @Environment(\.dismiss) private var schliessen
    @State private var ergebnisse: [ELErgebnis] = []
    @State private var ergebnis: ELErgebnis?
    @State private var geprueft = false
    @State private var mitTracks = true
    @State private var kartenart = ELKartenart.streets
    @State private var fragen = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Ergebnis") {
                    if ergebnisse.isEmpty {
                        Text("Lade …").foregroundStyle(.secondary)
                    }
                    Picker("Ergebnis", selection: $ergebnis) {
                        Text("bitte wählen").tag(ELErgebnis?.none)
                        ForEach(ergebnisse) { Text($0.anzeige).tag(Optional($0)) }
                    }
                }
                Section {
                    let aktive = lage.teams.filter { $0.suche.status == .laeuft || $0.suche.status == .kurzpause }
                    if aktive.isEmpty {
                        Label("Keine Suche läuft mehr", systemImage: "checkmark.circle").foregroundStyle(.green)
                    } else {
                        ForEach(aktive) { Label("\($0.teamName) sucht noch", systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
                    }
                    Toggle("Alle Teams und Halsbänder sind zurück", isOn: $geprueft)
                } header: {
                    Text("Ressourcen")
                } footer: {
                    Text("Erst beenden, wenn alle Teams und Geräte zurück sind.")
                }
                Section("Bericht (PDF)") {
                    Toggle("Tracks in den Bericht", isOn: $mitTracks)
                    Picker("Karte", selection: $kartenart) {
                        ForEach(ELKartenart.allCases, id: \.self) { Text($0.anzeige).tag($0) }
                    }
                }
            }
            .navigationTitle("Einsatz beenden")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { schliessen() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Beenden") { fragen = true }.disabled(ergebnis == nil || !geprueft)
                }
            }
            .confirmationDialog("Einsatz wirklich beenden?", isPresented: $fragen, titleVisibility: .visible) {
                Button("Einsatz beenden", role: .destructive) {
                    guard let ergebnis else { return }
                    Task { await betrieb.ausfuehren(.einsatzBeenden(ergebnis: ergebnis.wert, mitTracks: mitTracks, kartenart: kartenart)) }
                    schliessen()
                }
            } message: {
                Text("Alle Team-Handys werden abgemeldet, ihre Ortung endet.")
            }
            .task { ergebnisse = await betrieb.ergebnisse() }
        }
    }
}
