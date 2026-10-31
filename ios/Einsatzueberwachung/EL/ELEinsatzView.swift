import CoreLocation
import SwiftUI

/// Einsatz starten (wie `/el/start`): Divera-Alarm oder von Hand, Stichwort, Ort, Szenario, Einsatz/Übung,
/// Einsatzleiter und Führungsassistent. Die Auswahllisten kommen aus `GET /einsatz/vorlage`.
struct ELEinsatzStartView: View {
    @ObservedObject var betrieb: ELBetrieb
    let eigenePosition: Position?
    @Environment(\.dismiss) private var schliessen
    @State private var daten = ELEinsatzStart()
    @State private var standortUebernehmen = true
    @State private var vorlage: ELVorlage?
    @State private var geladen = false

    private var alarm: ELVorlage.DiveraAlarm? {
        daten.diveraAlarmId.flatMap { id in vorlage?.divera.first { $0.id == id } }
    }

    var body: some View {
        NavigationStack {
            Form {
                if vorlage?.aktiv == true {
                    Section {
                        Label("Es läuft bereits ein Einsatz. Erst beenden, dann neu starten.", systemImage: "exclamationmark.triangle")
                            .foregroundStyle(.orange)
                    }
                }
                if let alarme = vorlage?.divera, !alarme.isEmpty {
                    Section {
                        Picker("Alarm", selection: $daten.diveraAlarmId) {
                            Text("ohne Alarm (von Hand)").tag(Int?.none)
                            ForEach(alarme) { Text(Self.alarmText($0)).tag(Optional($0.id)) }
                        }
                        .onChange(of: daten.diveraAlarmId) { _, _ in
                            // Koordinate des Alarms nicht mit dem eigenen Standort überschreiben
                            if alarm?.lat != nil { standortUebernehmen = false }
                        }
                    } header: {
                        Text("Divera")
                    } footer: {
                        if alarm != nil { Text("Ort, Stichwort und Koordinate übernimmt der Server aus dem Alarm. Eigene Angaben gehen vor.") }
                    }
                }
                Section {
                    Picker("Art", selection: $daten.uebung) {
                        Text("Einsatz").tag(false)
                        Text("Übung").tag(true)
                    }
                    .pickerStyle(.segmented)
                    TextField(alarm?.titel.map { "Stichwort (aus Alarm: \($0))" } ?? "Stichwort", text: $daten.stichwort)
                    TextField(alarm.map { "Einsatzort (aus Alarm: \($0.ort ?? "–"))" } ?? "Einsatzort (Pflicht)", text: $daten.ort)
                    Picker("Szenario (Pflicht)", selection: $daten.szenario) {
                        Text("bitte wählen").tag(ELSzenario.unbestimmt)
                        ForEach((vorlage ?? ELVorlage()).szenarioWahl, id: \.self) { Text($0.anzeige).tag($0) }
                    }
                }
                Section("Führung") {
                    ELNamensFeld(titel: "Einsatzleiter", text: $daten.einsatzleiter, vorschlaege: vorlage?.einsatzleiter ?? [])
                    ELNamensFeld(titel: "Führungsassistent", text: $daten.fuehrungsassistent, vorschlaege: vorlage?.fuehrungsassistenten ?? [])
                }
                if let p = eigenePosition {
                    Section {
                        Toggle("Eigenen Standort als Einsatzort", isOn: $standortUebernehmen)
                    } footer: {
                        Text("Die Koordinate hilft beim Wetter und als Mittelpunkt der Karte. Genauigkeit \(TeamAnsichtView.genauigkeit(p.accuracy)).")
                    }
                }
                if geladen && vorlage == nil {
                    Section {
                        Text("Auswahllisten nicht geladen, Eingabe von Hand.").font(.footnote).foregroundStyle(.secondary)
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
                    .disabled(!daten.vollstaendig || vorlage?.aktiv == true)
                }
            }
            .task {
                vorlage = await betrieb.vorlage()
                geladen = true
            }
        }
    }

    static func alarmText(_ a: ELVorlage.DiveraAlarm) -> String {
        var teile = [a.titel ?? "Alarm \(a.id)"]
        if let ort = a.ort, !ort.isEmpty { teile.append(ort) }
        if let zeit = a.zeit { teile.append(zeit.formatted(date: .omitted, time: .shortened)) }
        return teile.joined(separator: " · ")
    }
}

/// Textfeld mit Auswahl aus den Stammdaten; freie Eingabe bleibt möglich.
struct ELNamensFeld: View {
    let titel: String
    @Binding var text: String
    let vorschlaege: [String]

    var body: some View {
        HStack {
            TextField(titel, text: $text)
            if !vorschlaege.isEmpty {
                Menu {
                    ForEach(vorschlaege, id: \.self) { name in Button(name) { text = name } }
                } label: {
                    Image(systemName: "person.crop.circle.badge.plus")
                }
                .accessibilityLabel("\(titel) auswählen")
            }
        }
    }
}

/// Einsatz beenden (wie `/el/beenden`): Ergebnis, Prüfung der Ressourcen, Endzeit, PDF mit Tracks und Teamauswahl.
struct ELEinsatzBeendenView: View {
    @ObservedObject var betrieb: ELBetrieb
    let lage: ELLage
    @Environment(\.dismiss) private var schliessen
    @State private var ergebnisse: [ELErgebnis] = []
    @State private var kartenarten: [ELVorlage.Wahl] = ELVorlage().kartenWahl
    @State private var ergebnis: ELErgebnis?
    @State private var geprueft = false
    @State private var mitTracks = true
    @State private var kartenart = ELKartenart.streets.rawValue
    @State private var alleTeams = true
    @State private var teamIds: Set<String> = []
    @State private var endzeitAngeben = false
    @State private var endzeit = Date()
    @State private var bemerkungen = ""
    @State private var fragen = false

    private var endzeitBereich: ClosedRange<Date> {
        let jetzt = Date()
        return min(lage.einsatz.beginn ?? jetzt, jetzt)...jetzt
    }

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
                    TextField("Bemerkungen (optional)", text: $bemerkungen, axis: .vertical)
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
                Section {
                    Toggle("Andere Endzeit", isOn: $endzeitAngeben)
                    if endzeitAngeben {
                        DatePicker("Ende", selection: $endzeit, in: endzeitBereich)
                    }
                } header: {
                    Text("Endzeit")
                } footer: {
                    Text(endzeitAngeben ? "Zwischen Einsatzbeginn und jetzt." : "Ohne Angabe gilt der Zeitpunkt des Beendens.")
                }
                Section("Bericht (PDF)") {
                    Toggle("Tracks in den Bericht", isOn: $mitTracks)
                    Picker("Karte", selection: $kartenart) {
                        ForEach(kartenarten) { Text($0.anzeige).tag($0.wert) }
                    }
                    if !lage.teams.isEmpty {
                        Toggle("Alle Teams im Bericht", isOn: $alleTeams)
                        if !alleTeams {
                            ForEach(lage.teams) { team in
                                Button {
                                    if teamIds.contains(team.teamId) { teamIds.remove(team.teamId) } else { teamIds.insert(team.teamId) }
                                } label: {
                                    Label(team.teamName, systemImage: teamIds.contains(team.teamId) ? "checkmark.circle.fill" : "circle")
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Einsatz beenden")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { schliessen() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Beenden") { fragen = true }
                        .disabled(ergebnis == nil || !geprueft || (!alleTeams && teamIds.isEmpty))
                }
            }
            .confirmationDialog("Einsatz wirklich beenden?", isPresented: $fragen, titleVisibility: .visible) {
                Button("Einsatz beenden", role: .destructive) {
                    guard let ergebnis else { return }
                    // Reihenfolge der Teams wie in der Lage
                    let auswahl = alleTeams ? nil : lage.teams.map(\.teamId).filter(teamIds.contains)
                    Task {
                        await betrieb.ausfuehren(.einsatzBeenden(
                            ergebnis: ergebnis.wert, mitTracks: mitTracks, kartenart: kartenart, teamIds: auswahl,
                            endzeit: endzeitAngeben ? min(endzeit, Date()) : nil, bemerkungen: bemerkungen))
                    }
                    schliessen()
                }
            } message: {
                Text("Alle Team-Handys werden abgemeldet, ihre Ortung endet.")
            }
            .task {
                let vorlage = await betrieb.vorlage()
                if let v = vorlage { kartenarten = v.kartenWahl }
                ergebnisse = vorlage?.ergebnisse.isEmpty == false ? vorlage!.ergebnisse : await betrieb.ergebnisse()
            }
        }
    }
}
