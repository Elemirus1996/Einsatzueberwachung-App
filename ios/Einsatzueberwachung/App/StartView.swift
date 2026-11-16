import SwiftUI

struct StartView: View {
    @EnvironmentObject private var modell: AppModell
    @ObservedObject private var heimat: HeimatStaffel
    @State private var einstellungenOffen = false

    init(heimat: HeimatStaffel) {
        self.heimat = heimat
    }

    var body: some View {
        Group {
            if let ansicht = modell.elAnsicht, let ortung = modell.elOrtung, let betrieb = modell.elBetrieb,
               let karte = modell.elKarte, let vermisste = modell.elVermisste {
                // Einsatzleitung mit eigenen Reitern, jeder mit eigenem Navigationsstapel
                ELHauptView(ansicht: ansicht, ortung: ortung, betrieb: betrieb, karte: karte, vermisste: vermisste)
            } else {
                NavigationStack {
                    if let team = modell.team, let ansicht = modell.teamAnsicht, let sitzung = modell.teamSitzung {
                        TeamAnsichtView(ortung: team, ansicht: ansicht, sitzung: sitzung)
                    } else if let wahl = modell.teamWahl {
                        TeamAnmeldungView(vorgabe: wahl)
                            .toolbar {
                                ToolbarItem(placement: .cancellationAction) {
                                    Button("Abbrechen") { modell.teamWahlAbbrechen() }
                                }
                            }
                    } else {
                        rollenAuswahl
                    }
                }
            }
        }
        // Nach An- oder Abmeldung neu aufbauen, damit keine Anmeldeseite auf dem Stapel liegen bleibt
        .id("\(modell.team == nil)-\(modell.teamWahl == nil)-\(modell.elSitzung == nil)")
        // #148: zuerst Datenschutz, dann Heimatstaffel
        .sheet(item: Binding(get: { modell.datenschutzFrage }, set: { _ in })) { frage in
            DatenschutzAbfrageView(frage: frage)
        }
        .alert(modell.heimatFrage.map { $0.name.isEmpty ? "Ist das deine eigene Staffel?" : "Ist „\($0.name)“ deine eigene Staffel?" } ?? "",
               isPresented: Binding(get: { modell.heimatFrage != nil }, set: { _ in })) {
            Button("Ja, meine Staffel") { modell.heimatBeantworten(true) }
            Button("Nein", role: .cancel) { modell.heimatBeantworten(false) }
        } message: {
            Text("Dann zeigt EinsatzSpur ihren Namen und ihr Logo auf der Startseite. Bei einem gemeinsamen Einsatz mit einer anderen Staffel: Nein. Änderbar in den Einstellungen.")
        }
        .sheet(isPresented: $einstellungenOffen) {
            EinstellungenView(heimat: heimat)
                .environmentObject(modell)
        }
    }

    /// Langes Logo mit Schriftzug füllt die Breite bis zum Einstellungen-Knopf, das Staffel-Logo steht darunter
    private var kopfzeile: some View {
        HStack(spacing: 12) {
            Image("EinsatzSpurLogo")
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity, maxHeight: 72, alignment: .leading)
                .accessibilityLabel("EinsatzSpur")
                .accessibilityAddTraits(.isHeader)
            Button { einstellungenOffen = true } label: {
                Image(systemName: "gearshape")
                    .font(.title2)
                    .frame(width: 44, height: 44)
                    .background(.quaternary, in: Circle())
            }
            .foregroundStyle(.primary)
            .accessibilityLabel("Einstellungen")
        }
    }

    private var kopf: some View {
        VStack(spacing: 12) {
            if let logo = heimat.logo {
                Image(uiImage: logo)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 96, height: 96)
                    .accessibilityHidden(true)
            }
            if let staffel = heimat.eintrag?.staffel {
                Text(staffel)
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
    }

    private var rollenAuswahl: some View {
        VStack(spacing: 16) {
            kopfzeile
            Spacer()
            kopf
            Spacer()
            NavigationLink {
                // Läuft der Einsatz noch (Code gespeichert), direkt zur Teamauswahl
                TeamAnmeldungView(vorgabe: modell.einsatzZugang)
            } label: {
                RollenKnopf(titel: "Team", text: "QR-Code aus dem Einsatz scannen", symbol: "qrcode.viewfinder")
            }
            NavigationLink {
                ELAnmeldungView()
            } label: {
                RollenKnopf(titel: "Einsatzleitung", text: "Nur im VPN der Staffel", symbol: "lock.shield")
            }
            NavigationLink {
                PacklisteView(liste: modell.packliste)
            } label: {
                PacklisteKnopf(liste: modell.packliste)
            }
            Spacer()
        }
        // iPad: Knöpfe nicht über die ganze Breite ziehen
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .padding()
        .toolbar(.hidden, for: .navigationBar)
    }
}

/// Kleiner als die Rollen-Knöpfe: Packliste mit Stand „3 von 7“
private struct PacklisteKnopf: View {
    @ObservedObject var liste: Packliste

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: "checklist").frame(width: 44)
            Text("Packliste").font(.headline)
            Spacer()
            if liste.anzahl > 0 {
                Text("\(liste.anzahlErledigt) von \(liste.anzahl)").foregroundStyle(.secondary).monospacedDigit()
            }
            Image(systemName: "chevron.right").font(.footnote.weight(.semibold)).foregroundStyle(.tertiary)
        }
        .padding(.horizontal)
        .padding(.vertical, 12)
        .frame(maxWidth: .infinity)
        .background(.quaternary.opacity(0.6), in: RoundedRectangle(cornerRadius: 16))
        .foregroundStyle(.primary)
    }
}

private struct RollenKnopf: View {
    let titel: String
    let text: String
    let symbol: String

    var body: some View {
        HStack(spacing: 16) {
            Image(systemName: symbol)
                .font(.title)
                .frame(width: 44)
            VStack(alignment: .leading) {
                Text(titel).font(.title2.bold())
                Text(text).font(.subheadline).foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding()
        .frame(maxWidth: .infinity)
        .background(.quaternary, in: RoundedRectangle(cornerRadius: 16))
        .foregroundStyle(.primary)
    }
}
