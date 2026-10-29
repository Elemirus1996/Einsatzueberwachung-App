import SwiftUI

/// Vermisste (wie `/el`): Szenario, Profile, Anlegen, Bearbeiten mit Versionsprüfung, Checkliste.
struct ELVermissteView: View {
    @ObservedObject var vermisste: ELVermissteAnsicht
    @ObservedObject var betrieb: ELBetrieb
    @State private var neuesProfil = false

    var body: some View {
        List {
            if let stand = vermisste.stand {
                Section {
                    Picker("Szenario", selection: Binding(
                        get: { ELSzenario(rawValue: stand.szenario ?? "") ?? .unbestimmt },
                        set: { neu in Task { await betrieb.ausfuehren(.szenario(neu)); await vermisste.laden() } })) {
                        ForEach(ELSzenario.allCases, id: \.self) { Text($0.anzeige).tag($0) }
                    }
                } footer: {
                    Text(stand.mehrereErlaubt ? "In diesem Szenario sind mehrere Personen möglich." : "In diesem Szenario wird eine Person gesucht.")
                }
                Section("Vermisste Personen") {
                    if stand.profile.isEmpty { Text("Noch kein Profil angelegt.").foregroundStyle(.secondary) }
                    ForEach(stand.profile) { profil in
                        NavigationLink {
                            ELProfilView(original: profil, vermisste: vermisste, betrieb: betrieb)
                        } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(profil.vollerName ?? "Unbekannt").font(.headline)
                                Text([profil.text("alter").isEmpty ? nil : "\(profil.text("alter")) Jahre",
                                      profil.text("kleidung").isEmpty ? nil : profil.text("kleidung")]
                                    .compactMap { $0 }.joined(separator: " · "))
                                    .font(.subheadline).foregroundStyle(.secondary)
                                if !profil.text("zuletztGesehenOrt").isEmpty {
                                    Label(profil.text("zuletztGesehenOrt"), systemImage: "eye").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                    if stand.mehrereErlaubt || stand.profile.isEmpty {
                        Button { neuesProfil = true } label: { Label("Person hinzufügen", systemImage: "person.badge.plus") }
                    }
                }
            } else if let fehler = vermisste.fehler {
                Text(fehler).foregroundStyle(.secondary)
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Vermisste")
        .task { await vermisste.laden() }
        .refreshable { await vermisste.laden() }
        .sheet(isPresented: $neuesProfil) {
            NavigationStack { ELProfilView(original: ELVermisstenprofil(), vermisste: vermisste, betrieb: betrieb) }
        }
    }
}

/// Ein Profil bearbeiten. Gespeichert werden nur Änderungen mit der `version`, auf der die Bearbeitung beruht;
/// hat jemand anderes inzwischen gespeichert (409), zeigt die App den neuen Stand zum erneuten Bearbeiten.
struct ELProfilView: View {
    @ObservedObject var vermisste: ELVermissteAnsicht
    @ObservedObject var betrieb: ELBetrieb
    @Environment(\.dismiss) private var schliessen
    @State private var original: ELVermisstenprofil
    @State private var profil: ELVermisstenprofil
    @State private var speichert = false
    @State private var meldung: String?
    @State private var entfernenFragen = false

    init(original: ELVermisstenprofil, vermisste: ELVermissteAnsicht, betrieb: ELBetrieb) {
        _original = State(initialValue: original)
        _profil = State(initialValue: original)
        self.vermisste = vermisste
        self.betrieb = betrieb
    }

    var body: some View {
        Form {
            Section("Person") {
                feld("Vorname", "vorname"); feld("Nachname", "nachname")
                feld("Alter", "alter"); feld("Geburtsdatum", "geburtsdatum")
            }
            Section("Erscheinung") {
                feld("Beschreibung", "beschreibung", lang: true); feld("Kleidung", "kleidung", lang: true)
                feld("Besonderheiten", "besonderheiten", lang: true)
            }
            Section("Zuletzt gesehen") {
                feld("Ort", "zuletztGesehenOrt"); feld("Zeit (Freitext)", "zuletztGesehenZeit"); feld("Von", "zuletztGesehenVon")
            }
            Section("Gesundheit und Einschätzung") {
                feld("Vorerkrankungen", "vorerkrankungen", lang: true); feld("Medikamente", "medikamente", lang: true)
                auswahl("Orientierung", "orientierung"); auswahl("Mobilität", "mobilitaet")
                auswahl("Suizidrisiko", "suizidrisiko"); auswahl("Bewaffnet", "bewaffnet")
            }
            Section("Polizei") {
                feld("Kontakt", "polizeiKontaktName"); feld("Dienstnummer", "polizeiDienstnummer"); feld("Telefon", "polizeiTelefon")
                schalter("Vermisstenmeldung aufgenommen", "polizeiVermisstenmeldungAufgenommen")
                schalter("Koordination besprochen", "polizeiKoordinationBesprochen")
                schalter("Suchabschnitt abgestimmt", "polizeiSuchabschnittAbgestimmt")
                schalter("Rückmeldepflicht vereinbart", "polizeiRueckmeldepflichtVereinbart")
                schalter("Datenschutz geklärt", "polizeiDatenschutzGeklaert")
            }
            Section("Andere BOS") {
                feld("Einheit", "bosEinheit"); feld("Zugführer", "bosZugfuehrer"); feld("Funkrufname", "bosFunkrufname")
                feld("Aufgabenteilung", "bosAufgabenteilung", lang: true)
                schalter("Abschnitt abgestimmt", "bosAbschnittAbgestimmt"); schalter("Ressourcen besprochen", "bosRessourcenBesprochen")
            }
            if let liste = original.checkliste, let profilId = original.profilId {
                Section(liste.name.map { "Checkliste \($0)" } ?? "Checkliste") {
                    ForEach(liste.punkte) { punkt in checklistenPunkt(punkt, profilId: profilId) }
                }
            }
            if let profilId = original.profilId {
                Section {
                    Button("Profil entfernen", role: .destructive) { entfernenFragen = true }
                        .confirmationDialog("Profil entfernen?", isPresented: $entfernenFragen, titleVisibility: .visible) {
                            Button("Entfernen", role: .destructive) {
                                Task {
                                    await betrieb.ausfuehren(.vermisstenEntfernen(profilId, name: original.vollerName ?? "Profil"))
                                    await vermisste.laden()
                                    schliessen()
                                }
                            }
                        }
                }
            }
        }
        .navigationTitle(original.profilId == nil ? "Neue Person" : (original.vollerName ?? "Profil"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if original.profilId == nil {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { schliessen() } }
            }
            ToolbarItem(placement: .confirmationAction) {
                Button("Speichern") { Task { await speichern() } }
                    .disabled(speichert || (original.profilId != nil && profil.aenderungen(gegenueber: original).isEmpty))
            }
        }
        .alert("Hinweis", isPresented: Binding(get: { meldung != nil }, set: { if !$0 { meldung = nil } })) {
            Button("OK") {}
        } message: { Text(meldung ?? "") }
    }

    private func speichern() async {
        speichert = true
        defer { speichert = false }
        do {
            let gespeichert = try await vermisste.speichern(profil, original: original)
            original = gespeichert
            profil = gespeichert
            schliessen()
        } catch ELVermissteFehler.geaendert(let aktuell) {
            // Neuer Stand vom Server; eigene Eingaben gehen nicht stillschweigend verloren: Hinweis und neu bearbeiten
            original = aktuell
            profil = aktuell
            meldung = "Das Profil wurde inzwischen geändert. Der aktuelle Stand wird angezeigt, bitte die Änderung erneut eintragen."
        } catch ELVermissteFehler.abgelehnt(let text) {
            meldung = text
        } catch {
            meldung = "Speichern nicht möglich (keine Verbindung?). Bitte erneut versuchen."
        }
    }

    private func feld(_ titel: String, _ name: String, lang: Bool = false) -> some View {
        TextField(titel, text: Binding(get: { profil.text(name) }, set: { profil.texte[name] = $0 }), axis: lang ? .vertical : .horizontal)
    }

    private func schalter(_ titel: String, _ name: String) -> some View {
        Toggle(titel, isOn: Binding(get: { profil.schalter[name] ?? false }, set: { profil.schalter[name] = $0 }))
    }

    private func auswahl(_ titel: String, _ name: String) -> some View {
        Picker(titel, selection: Binding(get: { profil.auswahlen[name] ?? "Unbekannt" }, set: { profil.auswahlen[name] = $0 })) {
            ForEach(ELVermisstenprofil.auswahlFelder[name] ?? [], id: \.self) { Text(ELVermisstenprofil.anzeige($0)).tag($0) }
        }
    }

    @ViewBuilder
    private func checklistenPunkt(_ punkt: ELVermisstenprofil.Checkliste.Punkt, profilId: String) -> some View {
        switch punkt.typ {
        case "jaNein":
            Toggle(isOn: Binding(get: { punkt.erledigt ?? false }, set: { neu in
                Task { await betrieb.ausfuehren(.checkliste(profilId, punkt: punkt, erledigt: neu)); await vermisste.laden() }
            })) { Text(punkt.text + (punkt.pflicht == true ? " *" : "")) }
        case "auswahl":
            Picker(punkt.text, selection: Binding(get: { punkt.wert ?? "" }, set: { neu in
                Task { await betrieb.ausfuehren(.checkliste(profilId, punkt: punkt, wert: neu)); await vermisste.laden() }
            })) {
                Text("–").tag("")
                ForEach(punkt.auswahl ?? [], id: \.self) { Text($0).tag($0) }
            }
        default:
            LabeledContent(punkt.text, value: punkt.wert ?? "")
        }
    }
}
