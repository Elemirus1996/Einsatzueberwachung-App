import SwiftUI

/// Vermisste (wie `/el`): Szenario, Profile, Anlegen, Bearbeiten mit Versionsprüfung, Checkliste.
struct ELVermissteView: View {
    @ObservedObject var vermisste: ELVermissteAnsicht
    @ObservedObject var betrieb: ELBetrieb
    var lage: ELLage?
    @State private var neuesProfil = false

    var body: some View {
        VStack(spacing: 0) {
            ELReiterKopf(titel: "Vermisst", lage: lage)
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if let stand = vermisste.stand {
                        szenario(stand)
                        ELAbschnittTitel(titel: "Gesuchte Personen", anzahl: stand.profile.count)
                        if stand.profile.isEmpty {
                            Text("Noch kein Profil angelegt.").foregroundStyle(ELStil.vermerk).elKarte()
                        }
                        ForEach(stand.profile) { profil in
                            NavigationLink {
                                ELProfilView(original: profil, vermisste: vermisste, betrieb: betrieb)
                            } label: { ELSteckbrief(profil: profil) }
                            .buttonStyle(.plain)
                        }
                        if stand.mehrereErlaubt || stand.profile.isEmpty {
                            Button { neuesProfil = true } label: {
                                Label("Person hinzufügen", systemImage: "person.badge.plus")
                                    .font(.headline)
                                    .frame(maxWidth: .infinity, minHeight: 48)
                                    .foregroundStyle(ELStil.aktion)
                                    .background(ELStil.aktion.opacity(0.1), in: RoundedRectangle(cornerRadius: 12))
                            }
                            .buttonStyle(.plain)
                        }
                    } else if let fehler = vermisste.fehler {
                        Text(fehler).foregroundStyle(ELStil.vermerk)
                    } else {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                }
                .padding(16)
                .frame(maxWidth: 800)
                .frame(maxWidth: .infinity)
            }
            .refreshable { await vermisste.laden() }
        }
        .background(ELStil.papier.ignoresSafeArea())
        .toolbar(.hidden, for: .navigationBar)
        .task { await vermisste.laden() }
        .sheet(isPresented: $neuesProfil) {
            NavigationStack { ELProfilView(original: ELVermisstenprofil(), vermisste: vermisste, betrieb: betrieb) }
        }
    }

    private func szenario(_ stand: ELVermisste) -> some View {
        HStack {
            Label("Szenario", systemImage: "scope").foregroundStyle(ELStil.vermerk)
            Spacer()
            Picker("Szenario", selection: Binding(
                get: { ELSzenario(rawValue: stand.szenario ?? "") ?? .unbestimmt },
                set: { neu in Task { await betrieb.ausfuehren(.szenario(neu)); await vermisste.laden() } })) {
                ForEach(ELSzenario.allCases, id: \.self) { Text($0.anzeige).tag($0) }
            }
            .tint(ELStil.aktion)
        }
        .elKarte()
    }
}

/// Steckbrief einer vermissten Person: Name, Alter, Warnchips (Erkrankung, Medikamente, Mobilität), zuletzt gesehen, Checkliste
struct ELSteckbrief: View {
    let profil: ELVermisstenprofil

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Text(initialen)
                    .font(.headline)
                    .foregroundStyle(ELStil.aktion)
                    .frame(width: 46, height: 46)
                    .background(ELStil.aktion.opacity(0.12), in: Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(profil.vollerName ?? "Unbekannt").font(.title3.bold()).foregroundStyle(ELStil.inschrift)
                    if !unterzeile.isEmpty { Text(unterzeile).font(.subheadline).foregroundStyle(ELStil.vermerk) }
                }
                Spacer()
                Image(systemName: "chevron.right").foregroundStyle(ELStil.randnotiz)
            }
            if !warnungen.isEmpty {
                FlussLayout(abstand: 6) {
                    ForEach(warnungen, id: \.text) { w in ELStempel(text: w.text, farbe: w.farbe) }
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                zeile("mappin.and.ellipse", profil.text("zuletztGesehenOrt"))
                zeile("clock", profil.text("zuletztGesehenZeit"))
                zeile("tshirt", profil.text("kleidung"))
                zeile("person.text.rectangle", profil.text("beschreibung"))
            }
            if let liste = profil.checkliste, !liste.punkte.isEmpty {
                let erledigt = liste.punkte.filter { $0.erledigt == true }.count
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text("Checkliste \(liste.name ?? "")").font(.subheadline)
                        Spacer()
                        Text("\(erledigt) / \(liste.punkte.count)").font(ELStil.mono(14, .semibold)).foregroundStyle(ELStil.aktiv)
                    }
                    ProgressView(value: Double(erledigt), total: Double(liste.punkte.count)).tint(ELStil.aktiv)
                }
            }
        }
        .elKarte(rand: ELStil.aktion)
    }

    @ViewBuilder
    private func zeile(_ symbol: String, _ text: String) -> some View {
        if !text.isEmpty {
            Label { Text(text).foregroundStyle(ELStil.inschrift) } icon: { Image(systemName: symbol).foregroundStyle(ELStil.vermerk) }
                .font(.subheadline)
        }
    }

    private var initialen: String {
        let teile = [profil.text("vorname"), profil.text("nachname")].compactMap { $0.first.map(String.init) }
        return teile.isEmpty ? "?" : teile.joined()
    }

    private var unterzeile: String {
        [profil.text("alter").isEmpty ? nil : "\(profil.text("alter")) Jahre", profil.text("besonderheiten").isEmpty ? nil : profil.text("besonderheiten")]
            .compactMap { $0 }.joined(separator: " · ")
    }

    private var warnungen: [(text: String, farbe: Color)] {
        var w: [(String, Color)] = []
        if profil.auswahlen["suizidrisiko"] == "Ja" { w.append(("Suizidrisiko", ELStil.warnung2)) }
        if profil.auswahlen["bewaffnet"] == "Ja" { w.append(("Bewaffnet", ELStil.warnung2)) }
        for teil in profil.text("vorerkrankungen").split(separator: ",") { w.append((teil.trimmingCharacters(in: .whitespaces), ELStil.warnung1)) }
        for teil in profil.text("medikamente").split(separator: ",") { w.append((teil.trimmingCharacters(in: .whitespaces), ELStil.warnung1)) }
        if profil.auswahlen["orientierung"] == "Eingeschraenkt" { w.append(("Orientierung eingeschränkt", ELStil.warnung1)) }
        switch profil.auswahlen["mobilitaet"] {
        case "ZuFuss": w.append(("Zu Fuß", ELStil.pause))
        case let m? where m != "Unbekannt": w.append((m, ELStil.pause))
        default: break
        }
        return w.filter { !$0.0.isEmpty }.map { (text: $0.0, farbe: $0.1) }
    }
}

/// Einfacher Zeilenumbruch für Chips
struct FlussLayout: Layout {
    var abstand: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let breite = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, zeile: CGFloat = 0, maxX: CGFloat = 0
        for s in subviews {
            let g = s.sizeThatFits(.unspecified)
            if x > 0 && x + g.width > breite { x = 0; y += zeile + abstand; zeile = 0 }
            x += g.width + abstand; zeile = max(zeile, g.height); maxX = max(maxX, x)
        }
        return CGSize(width: min(maxX, breite), height: y + zeile)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, zeile: CGFloat = 0
        for s in subviews {
            let g = s.sizeThatFits(.unspecified)
            if x > bounds.minX && x + g.width > bounds.maxX { x = bounds.minX; y += zeile + abstand; zeile = 0 }
            s.place(at: CGPoint(x: x, y: y), proposal: .unspecified)
            x += g.width + abstand; zeile = max(zeile, g.height)
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
