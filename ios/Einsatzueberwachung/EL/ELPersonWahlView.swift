import SwiftUI

/// „Wer bin ich“ nach der Anmeldung der Einsatzleitung (APP_API EL Abschnitt 4a). Daraus folgt im Einsatz die Rolle:
/// Einsatzleiter steuert und sendet den Standort, Führungsassistent steuert ohne Standort, alle anderen lesen nur.
struct ELPersonWahlView: View {
    @EnvironmentObject private var modell: AppModell
    let personen: [ELPerson]
    @State private var suche = ""
    @State private var laedt: String?
    @State private var fehler: String?

    private var gefiltert: [ELPerson] {
        let s = suche.trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? personen : personen.filter { $0.name.localizedCaseInsensitiveContains(s) }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(gefiltert) { person in
                        Button {
                            Task { await waehlen(person) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(person.name).foregroundStyle(.primary)
                                    let rollen = [person.einsatzleiter == true ? "Einsatzleiter" : nil,
                                                  person.fuehrungsassistent == true ? "Führungsassistent" : nil].compactMap { $0 }
                                    if !rollen.isEmpty { Text(rollen.joined(separator: " · ")).font(.caption).foregroundStyle(.secondary) }
                                }
                                Spacer()
                                if laedt == person.personId { ProgressView() }
                            }
                        }
                        .disabled(laedt != nil)
                    }
                } footer: {
                    Text("Die Wahl gilt bis zur Abmeldung und steht bei jeder Aktion im Protokoll. Steuern und den Standort senden darf, wer im Einsatz als Einsatzleiter eingetragen ist. Der Führungsassistent steuert ohne Standort, alle anderen sehen die Lage nur an.")
                }
            }
            .searchable(text: $suche, prompt: "Name")
            .navigationTitle("Wer bist du?")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Kein Wechsel nach der Wahl; wer sich vertan hat, meldet sich ab und neu an
                ToolbarItem(placement: .cancellationAction) { Button("Abmelden") { Task { await modell.elAbmelden() } } }
            }
            .alert("Auswahl", isPresented: Binding(get: { fehler != nil }, set: { if !$0 { fehler = nil } })) {
                Button("OK", role: .cancel) { fehler = nil }
            } message: { Text(fehler ?? "") }
        }
    }

    private func waehlen(_ person: ELPerson) async {
        laedt = person.personId
        defer { laedt = nil }
        do { try await modell.elPersonWaehlen(person) } catch {
            fehler = (error as? ELAktionsFehler).map { if case .abgelehnt(let t) = $0 { t } else { "" } }
                ?? "Auswahl nicht gespeichert. Bitte erneut versuchen."
        }
    }
}
