import SwiftUI

/// Angaben für den Einsatzbericht im Beenden-Dialog (APP_API EL Abschnitt 10, Server 1.17.0): Alarmierung, Polizei,
/// Fund, „Eingesetzt als“ je Team, Abbruchgrund und Geruchsartikel je Trail. Steht innerhalb der `Form` des Dialogs.
struct ELAbschlussAbschnitte: View {
    let abschluss: ELAbschluss
    @Binding var eingabe: ELAbschlussEingabe
    /// Gewähltes Ergebnis ist ein Fund
    let fund: Bool

    var body: some View {
        Section {
            Picker("Alarmiert durch", selection: $eingabe.alarmiertDurch) {
                Text("bitte wählen").tag(ELAlarmiertDurch?.none)
                ForEach(ELAlarmiertDurch.allCases) { Text($0.anzeige).tag(Optional($0)) }
            }
            TextField("Stelle oder Name (optional)", text: $eingabe.melder)
            if !eingabe.uebung {
                Toggle("Keine Polizei beteiligt", isOn: $eingabe.keinePolizei)
            }
        } header: {
            Text("Alarmierung")
        }

        if !eingabe.keinePolizei {
            Section {
                TextField("Vorgangsnummer", text: $eingabe.vorgangsnummer)
                TextField("Ansprechpartner", text: $eingabe.polizeiName)
                TextField("Dienstnummer", text: $eingabe.dienstnummer)
                TextField("Telefon", text: $eingabe.telefon).keyboardType(.phonePad)
            } header: {
                Text("Polizei")
            } footer: {
                Text(eingabe.polizeiGefragt ? "Pflicht, weil die Polizei alarmiert hat. Sie nennt die Vorgangsnummer oft erst spät – nach dem Beenden ist nichts mehr änderbar."
                     : "Freiwillig, wenn die Polizei nicht alarmiert hat.")
            }
        }

        if fund {
            Section {
                Toggle("Fundzeit angeben", isOn: $eingabe.fundZeitGesetzt)
                if eingabe.fundZeitGesetzt {
                    DatePicker("Fundzeit", selection: $eingabe.fundZeit, in: ...Date())
                }
                TextField("Fundort (Beschreibung)", text: $eingabe.fundOrt)
                if let lat = eingabe.fundLat, let lng = eingabe.fundLng {
                    LabeledContent("Koordinate", value: String(format: "%.5f, %.5f", lat, lng))
                }
                if let vorschlag = abschluss.fund?.vorschlag {
                    Button {
                        eingabe.vorschlagUebernehmen(vorschlag)
                    } label: {
                        Label("Fund-Punkt übernehmen" + (vorschlag.zeit.map { " (\($0.formatted(date: .omitted, time: .shortened)))" } ?? ""),
                              systemImage: "mappin.and.ellipse")
                    }
                }
                Picker("Gefunden durch", selection: $eingabe.fundTeamId) {
                    Text("nicht angegeben").tag(String?.none)
                    ForEach(abschluss.teams ?? []) { Text($0.teamName).tag(Optional($0.teamId)) }
                }
                TextField("Übergabe an (z. B. Rettungsdienst)", text: $eingabe.uebergabeAn)
            } header: {
                Text("Fund")
            }
        }

        if let teams = abschluss.teams, !teams.isEmpty {
            Section {
                ForEach(teams) { team in
                    Picker(team.teamName, selection: Binding(
                        get: { eingabe.einsatzart[team.teamId] },
                        set: { eingabe.einsatzart[team.teamId] = $0 })) {
                        Text("nicht festgelegt").tag(ELEinsatzart?.none)
                        ForEach(team.wahl) { Text($0.anzeige).tag(Optional($0)) }
                    }
                }
            } header: {
                Text("Eingesetzt als")
            }
        }

        if let trails = abschluss.trails, !trails.isEmpty {
            Section {
                ForEach(trails) { trail in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(Self.trailText(trail)).font(.subheadline.weight(.semibold))
                        Picker("Abbruchgrund", selection: Binding(
                            get: { eingabe.abbruchgrund[trail.sucheId] ?? "" },
                            set: { eingabe.abbruchgrund[trail.sucheId] = $0 })) {
                            Text("bitte wählen").tag("")
                            ForEach(Self.gruende(abschluss, trail), id: \.self) { Text($0).tag($0) }
                        }
                        TextField("Geruchsartikel", text: Binding(
                            get: { eingabe.geruchsartikel[trail.sucheId] ?? "" },
                            set: { eingabe.geruchsartikel[trail.sucheId] = $0 }))
                    }
                }
            } header: {
                Text("Trails")
            }
        }
    }

    static func trailText(_ t: ELAbschluss.Trail) -> String {
        var text = t.teamName
        if let beginn = t.beginn { text += ", \(beginn.formatted(date: .omitted, time: .shortened))" }
        if let ende = t.ende { text += "–\(ende.formatted(date: .omitted, time: .shortened))" }
        return text
    }

    /// Liste aus den Einstellungen; ein schon gespeicherter Grund bleibt wählbar
    static func gruende(_ a: ELAbschluss, _ t: ELAbschluss.Trail) -> [String] {
        var liste = a.abbruchgruende ?? []
        if let alt = t.abbruchgrund, !alt.isEmpty, !liste.contains(alt) { liste.append(alt) }
        return liste
    }
}
