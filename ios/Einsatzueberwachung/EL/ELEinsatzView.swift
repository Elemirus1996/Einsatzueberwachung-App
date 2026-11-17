import CoreLocation
import SwiftUI

/// Einsatz starten (wie `/el/start`): Divera-Alarm oder von Hand, Stichwort, Ort, Szenario, Einsatz/Übung,
/// Einsatzleiter und Führungsassistent. Die Auswahllisten kommen aus `GET /einsatz/vorlage`.
struct ELEinsatzStartView: View {
    @ObservedObject var betrieb: ELBetrieb
    let eigenePosition: Position?
    /// Gewählte Person (Abschnitt 4a): als Einsatzleiter vorgeschlagen, damit wer anlegt auch steuern darf
    var eigenerName: String? = nil
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
                // Offener Divera-Alarm: oben vorschlagen wie im Browser
                if daten.diveraAlarmId == nil, let neuester = vorlage?.divera.max(by: { ($0.zeit ?? .distantPast) < ($1.zeit ?? .distantPast) }) {
                    Section {
                        Button {
                            daten.diveraAlarmId = neuester.id
                        } label: {
                            Label {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text("Divera-Alarm übernehmen").font(.headline)
                                    Text(Self.alarmText(neuester)).font(.subheadline).foregroundStyle(.secondary)
                                }
                            } icon: { Image(systemName: "bell.badge.fill").foregroundStyle(.red) }
                        }
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
                Section {
                    Picker("Alarmiert durch (Pflicht)", selection: $daten.alarmiertDurch) {
                        Text("bitte wählen").tag(ELAlarmiertDurch?.none)
                        ForEach(ELAlarmiertDurch.allCases) { Text($0.anzeige).tag(Optional($0)) }
                    }
                    TextField("Stelle oder Name (optional)", text: $daten.melder)
                } header: {
                    Text("Alarmierung")
                } footer: {
                    if alarm != nil { Text("Den Melder aus dem Alarm übernimmt der Server, wer alarmiert hat, wählst du hier.") }
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
                if daten.einsatzleiter.isEmpty, let eigenerName { daten.einsatzleiter = eigenerName }
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

/// Einsatz beenden (wie `/el/beenden`): Ergebnis, Anwesenheit, Prüfung der Ressourcen, Endzeit, PDF mit Tracks und Teamauswahl.
struct ELEinsatzBeendenView: View {
    @ObservedObject var betrieb: ELBetrieb
    let lage: ELLage
    @Environment(\.dismiss) private var schliessen
    @State private var ergebnisse: [ELErgebnis] = []
    @State private var kartenarten: [ELVorlage.Wahl] = ELVorlage().kartenWahl
    @State private var ergebnis: ELErgebnis?
    @State private var geprueft = false
    @State private var mitTracks = true
    @State private var mitPunkten = false
    @State private var kartenart = ELKartenart.streets.rawValue
    @State private var alleTeams = true
    @State private var teamIds: Set<String> = []
    @State private var endzeitAngeben = false
    @State private var endzeit = Date()
    @State private var bemerkungen = ""
    @State private var fragen = false
    @State private var vorOrt: ELVorOrt?
    @State private var personalIds: Set<String> = []
    @State private var hundeIds: Set<String> = []
    // Server 1.17.0: Angaben für den Bericht und Abschnitte; nil = älterer Server, Beenden wie bisher
    @State private var abschluss: ELAbschluss?
    @State private var eingabe = ELAbschlussEingabe()
    @State private var abschnitte = Set(ELBerichtAbschnitt.allCases)
    @State private var trotzdem = false

    private var istFund: Bool { ergebnis?.fund == true }
    private var fehlend: [String] { abschluss.map { eingabe.fehlend(abschluss: $0, fund: istFund) } ?? [] }

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
                if let abschluss {
                    ELAbschlussAbschnitte(abschluss: abschluss, eingabe: $eingabe, fund: istFund)
                }
                if let vorOrt {
                    Section {
                        ELAnwesenheitListe(titel: "Personal", eintraege: vorOrt.personal, auswahl: $personalIds)
                        ELAnwesenheitListe(titel: "Hunde", eintraege: vorOrt.hunde, auswahl: $hundeIds)
                    } header: {
                        Text("Vor Ort")
                    } footer: {
                        Text("Vorausgewählt ist, wer in einem Team eingeteilt war.")
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
                    Toggle("Kartenpunkte in den Bericht", isOn: $mitPunkten)
                    Picker("Karte", selection: $kartenart) {
                        ForEach(kartenarten) { Text($0.anzeige).tag($0.wert) }
                    }
                    if abschluss != nil {
                        DisclosureGroup("Abschnitte (\(abschnitte.count) von \(ELBerichtAbschnitt.allCases.count))") {
                            ForEach(ELBerichtAbschnitt.allCases) { a in
                                Toggle(a.anzeige, isOn: Binding(
                                    get: { abschnitte.contains(a) },
                                    set: { if $0 { abschnitte.insert(a) } else { abschnitte.remove(a) } }))
                            }
                        }
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
            .safeAreaInset(edge: .bottom) {
                if !fehlend.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        Label("Es fehlen noch: \(fehlend.joined(separator: ", ")). Nach dem Beenden ist nichts mehr änderbar.",
                              systemImage: "exclamationmark.triangle.fill")
                            .font(.footnote)
                            .foregroundStyle(.orange)
                        Toggle("Trotzdem ohne diese Angaben beenden", isOn: $trotzdem).font(.footnote)
                    }
                    .padding(12)
                    .background(.regularMaterial)
                }
            }
            .navigationTitle("Einsatz beenden")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { schliessen() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Beenden") { fragen = true }
                        .disabled(ergebnis == nil || !geprueft || (!alleTeams && teamIds.isEmpty) || (!fehlend.isEmpty && !trotzdem))
                }
            }
            .confirmationDialog("Einsatz wirklich beenden?", isPresented: $fragen, titleVisibility: .visible) {
                Button("Einsatz beenden", role: .destructive) {
                    guard let ergebnis else { return }
                    // Reihenfolge der Teams wie in der Lage
                    let auswahl = alleTeams ? nil : lage.teams.map(\.teamId).filter(teamIds.contains)
                    // Reihenfolge wie in der Liste; ohne Liste (alter Server) nimmt der Server die Vorauswahl
                    let personal = vorOrt?.personal.map(\.id).filter(personalIds.contains)
                    let hunde = vorOrt?.hunde.map(\.id).filter(hundeIds.contains)
                    // Lücken hat die Einsatzleitung oben ausdrücklich bestätigt
                    let angaben = abschluss.map { eingabe.json(abschluss: $0, fund: istFund) }
                    let berichtAbschnitte: [String]? = abschluss == nil ? nil
                        : ELBerichtAbschnitt.allCases.filter(abschnitte.contains).map(\.rawValue) + (mitTracks ? ["tracks"] : [])
                    Task {
                        await betrieb.ausfuehren(.einsatzBeenden(
                            ergebnis: ergebnis.wert, mitTracks: mitTracks, kartenart: kartenart, teamIds: auswahl,
                            endzeit: endzeitAngeben ? min(endzeit, Date()) : nil, bemerkungen: bemerkungen,
                            mitPunkten: mitPunkten, personalIds: personal, hundeIds: hunde,
                            abschluss: angaben, unvollstaendigBestaetigt: abschluss == nil ? nil : true,
                            abschnitte: berichtAbschnitte))
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
                if let a = await betrieb.abschluss() {
                    eingabe = ELAbschlussEingabe(a)
                    abschluss = a
                }
                if let liste = await betrieb.vorOrt() {
                    personalIds = Set(liste.personal.filter(\.vorausgewaehlt).map(\.id))
                    hundeIds = Set(liste.hunde.filter(\.vorausgewaehlt).map(\.id))
                    vorOrt = liste
                }
            }
        }
    }
}

/// Aufklappbare Liste zum Ankreuzen (Personal oder Hunde vor Ort) mit Zähler wie in `/el/beenden`
private struct ELAnwesenheitListe: View {
    let titel: String
    let eintraege: [ELVorOrt.Eintrag]
    @Binding var auswahl: Set<String>

    var body: some View {
        DisclosureGroup("\(titel) (\(auswahl.count))") {
            if eintraege.isEmpty {
                Text("Keine aktiven Einträge in den Stammdaten.").foregroundStyle(.secondary)
            }
            ForEach(eintraege) { eintrag in
                Button {
                    if auswahl.contains(eintrag.id) { auswahl.remove(eintrag.id) } else { auswahl.insert(eintrag.id) }
                } label: {
                    Label(eintrag.name, systemImage: auswahl.contains(eintrag.id) ? "checkmark.circle.fill" : "circle")
                }
            }
        }
    }
}
