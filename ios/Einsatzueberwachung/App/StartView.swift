import SwiftUI

struct StartView: View {
    @EnvironmentObject private var modell: AppModell

    var body: some View {
        NavigationStack {
            if let team = modell.team, let sitzung = modell.teamSitzung {
                TeamStatusView(ortung: team, sitzung: sitzung)
            } else {
                rollenAuswahl
            }
        }
    }

    private var rollenAuswahl: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "pawprint.circle.fill")
                .font(.system(size: 72))
                .foregroundStyle(.tint)
            Text("Einsatzüberwachung")
                .font(.largeTitle.bold())
            Spacer()
            NavigationLink {
                TeamAnmeldungView()
            } label: {
                RollenKnopf(titel: "Team", text: "QR-Code aus dem Einsatz scannen", symbol: "qrcode.viewfinder")
            }
            RollenKnopf(titel: "Einsatzleitung", text: "Kommt in einer späteren Version", symbol: "lock.shield")
                .opacity(0.4)
            Spacer()
        }
        .padding()
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
