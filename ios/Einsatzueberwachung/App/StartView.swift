import SwiftUI

struct StartView: View {
    @EnvironmentObject private var modell: AppModell
    @ObservedObject private var heimat: HeimatStaffel
    @State private var einstellungenOffen = false

    init(heimat: HeimatStaffel) {
        self.heimat = heimat
    }

    var body: some View {
        NavigationStack {
            if let ansicht = modell.elAnsicht, let ortung = modell.elOrtung {
                ELUebersichtView(ansicht: ansicht, ortung: ortung)
            } else if let team = modell.team, let ansicht = modell.teamAnsicht, let sitzung = modell.teamSitzung {
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
        // Nach An- oder Abmeldung neu aufbauen, damit keine Anmeldeseite auf dem Stapel liegen bleibt
        .id("\(modell.team == nil)-\(modell.teamWahl == nil)-\(modell.elSitzung == nil)")
        .alert("Ist „\(modell.heimatFrage?.name ?? "")“ deine eigene Staffel?",
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

    private var kopf: some View {
        VStack(spacing: 12) {
            Group {
                if let logo = heimat.logo {
                    Image(uiImage: logo)
                        .resizable()
                        .scaledToFit()
                } else {
                    Image("AppSymbol")
                        .resizable()
                        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                }
            }
            .frame(width: 96, height: 96)
            .accessibilityHidden(true)
            Text("EinsatzSpur")
                .font(.largeTitle.bold())
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
            Spacer()
        }
        .padding()
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { einstellungenOffen = true } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel("Einstellungen")
            }
        }
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
