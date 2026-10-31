import SwiftUI

/// Datenschutzerklärung der Staffel lesen (#148). Mit `bestaetigen` als Abfrage „Zur Kenntnis genommen“.
struct DatenschutzTextView: View {
    let erklaerung: Datenschutz

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let titel = erklaerung.titel, !titel.isEmpty {
                Text(titel).font(.title2.bold())
            }
            if let stand = erklaerung.stand {
                Text("Stand: \(stand.formatted(date: .long, time: .omitted))").font(.footnote).foregroundStyle(.secondary)
            }
            ForEach(Array(DatenschutzBlock.zerlegen(erklaerung.text ?? "").enumerated()), id: \.offset) { _, block in
                switch block {
                case .ueberschrift(let text, let ebene):
                    Text(Self.inline(text)).font(ebene <= 1 ? .title3.bold() : .headline).padding(.top, 6)
                case .absatz(let text):
                    Text(Self.inline(text))
                case .punkt(let text):
                    HStack(alignment: .firstTextBaseline, spacing: 6) {
                        Text("•")
                        Text(Self.inline(text))
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .textSelection(.enabled)
    }

    /// **fett** und Links; misslingt das, bleibt der Text lesbar
    static func inline(_ text: String) -> AttributedString {
        (try? AttributedString(markdown: text, options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)))
            ?? AttributedString(text)
    }
}

/// Abfrage bei der ersten Anmeldung und bei neuer Version: zuerst Datenschutz, dann Heimatstaffel.
struct DatenschutzAbfrageView: View {
    @EnvironmentObject private var modell: AppModell
    let frage: DatenschutzFrage

    var body: some View {
        NavigationStack {
            ScrollView {
                if let erklaerung = frage.erklaerung {
                    DatenschutzTextView(erklaerung: erklaerung).padding()
                } else {
                    Label("Diese Staffel hat noch keine Datenschutzerklärung hinterlegt.", systemImage: "info.circle")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                }
            }
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 8) {
                    Button {
                        modell.datenschutzBestaetigen()
                    } label: {
                        Text(frage.erklaerung == nil ? "OK" : "Zur Kenntnis genommen").frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.borderedProminent)
                    if modell.datenschutzAbbrechenMoeglich {
                        Button("Nicht anmelden", role: .cancel) { modell.datenschutzAbbrechen() }
                    }
                }
                .padding()
                .background(.bar)
            }
            .navigationTitle("Datenschutz")
            .navigationBarTitleDisplayMode(.inline)
        }
        .interactiveDismissDisabled()
    }
}

/// Einstellungen: zuletzt gezeigte Erklärungen je Server zum Nachlesen.
struct DatenschutzListeView: View {
    let erklaerungen: [String: Datenschutz]

    var body: some View {
        List {
            if erklaerungen.isEmpty {
                Text("Noch keine. Die Erklärung der Staffel erscheint bei der Anmeldung.").foregroundStyle(.secondary)
            }
            ForEach(erklaerungen.sorted { ($0.value.titel ?? $0.key) < ($1.value.titel ?? $1.key) }, id: \.key) { _, erklaerung in
                NavigationLink(erklaerung.titel ?? "Datenschutzerklärung") {
                    ScrollView { DatenschutzTextView(erklaerung: erklaerung).padding() }
                        .navigationTitle("Datenschutz")
                        .navigationBarTitleDisplayMode(.inline)
                }
            }
        }
        .navigationTitle("Datenschutzerklärungen")
    }
}
