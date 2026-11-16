import SwiftUI

/// Packliste: Punkte der Heimatstaffel (nur abhakbar), eigene Punkte, Erinnerung. Alles bleibt auf dem Gerät.
struct PacklisteView: View {
    @ObservedObject var liste: Packliste
    @State private var neuerPunkt = ""
    @State private var durchgegangenFragen = false
    @FocusState private var eingabeAktiv: Bool

    var body: some View {
        List {
            if liste.vorlage == nil {
                Section {
                    Text("Deine Staffel hat noch keine Packliste geschickt. Sie kommt bei der nächsten Anmeldung an einem Einsatz oder einer Übung deiner Heimatstaffel.")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
            ForEach(Array(liste.gruppen.enumerated()), id: \.offset) { _, gruppe in
                Section {
                    ForEach(gruppe.punkte) { punkt in
                        zeile(punkt.text, schluessel: Packliste.schluessel(staffel: punkt.id))
                    }
                } header: {
                    Text(gruppe.name ?? "Staffel")
                }
            }
            Section {
                ForEach(liste.eigene) { punkt in
                    zeile(punkt.text, schluessel: Packliste.schluessel(eigen: punkt.id))
                        .swipeActions {
                            Button("Löschen", role: .destructive) { liste.eigenenLoeschen(punkt.id) }
                        }
                }
                HStack {
                    TextField("Eigenen Punkt hinzufügen", text: $neuerPunkt)
                        .focused($eingabeAktiv)
                        .submitLabel(.done)
                        .onSubmit(hinzufuegen)
                    if !neuerPunkt.trimmingCharacters(in: .whitespaces).isEmpty {
                        Button("Hinzufügen", action: hinzufuegen)
                    }
                }
            } header: {
                Text("Eigene Punkte")
            } footer: {
                Text("Punkte der Staffel ändert nur die Staffel. Eigene Punkte zum Löschen nach links wischen.")
            }
            Section {
                Picker("Erinnern", selection: Binding(get: { liste.erinnerungTage }, set: { liste.erinnerungSetzen(tage: $0) })) {
                    ForEach(Packliste.tageAuswahl, id: \.self) { tage in
                        Text(tage == 0 ? "Nie" : "Alle \(tage) Tage").tag(tage)
                    }
                }
                if let datum = liste.durchgegangen {
                    LabeledContent("Zuletzt durchgegangen", value: datum.formatted(date: .abbreviated, time: .omitted))
                }
            } header: {
                Text("Erinnerung")
            } footer: {
                Text("Die Erinnerung kommt als Mitteilung auf diesem Gerät, um 18 Uhr. Was du abhakst, bleibt auf dem Handy; deine Staffel erfährt davon nichts.")
            }
        }
        .navigationTitle("Packliste")
        .toolbar {
            ToolbarItem(placement: .confirmationAction) {
                Button("Durchgegangen") { durchgegangenFragen = true }
                    .disabled(liste.anzahl == 0)
            }
        }
        .confirmationDialog("Liste durchgegangen?", isPresented: $durchgegangenFragen, titleVisibility: .visible) {
            Button("Ja, Haken für nächstes Mal leeren") { liste.durchgegangenMerken() }
        } message: {
            Text("Die nächste Erinnerung zählt ab heute.")
        }
    }

    private func zeile(_ text: String, schluessel: String) -> some View {
        let fertig = liste.erledigt.contains(schluessel)
        return Button { liste.umschalten(schluessel) } label: {
            HStack(spacing: 12) {
                Image(systemName: fertig ? "checkmark.circle.fill" : "circle")
                    .font(.title3)
                    .foregroundStyle(fertig ? Color.green : Color.secondary)
                Text(text)
                    .foregroundStyle(fertig ? .secondary : .primary)
                    .strikethrough(fertig)
                Spacer()
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(fertig ? .isSelected : [])
    }

    private func hinzufuegen() {
        liste.eigenenHinzufuegen(neuerPunkt)
        neuerPunkt = ""
    }
}
