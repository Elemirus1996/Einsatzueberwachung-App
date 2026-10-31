import SwiftUI
import UIKit

/// Navi-Apps für ein Ziel der Einsatzleitung (APP_API.md Abschnitt 13). Die Links kommen fertig vom Server.
/// Apple Karten gibt es immer; Google Maps und Waze nur, wenn sie installiert sind (`LSApplicationQueriesSchemes`).
enum NaviApp: String, CaseIterable, Identifiable, Sendable {
    case appleKarten, googleMaps, waze

    var id: String { rawValue }

    var name: String {
        switch self {
        case .appleKarten: "Apple Karten"
        case .googleMaps: "Google Maps"
        case .waze: "Waze"
        }
    }

    func link(_ ziel: NavigationsZiel) -> URL? {
        let text: String? = switch self {
        case .appleKarten: ziel.links.appleKarten
        case .googleMaps: ziel.links.googleMaps
        case .waze: ziel.links.waze
        }
        return text.flatMap(URL.init(string:))
    }

    /// Apps mit Link, die auf dem Gerät geöffnet werden können
    @MainActor
    static func verfuegbar(fuer ziel: NavigationsZiel, kannOeffnen: (URL) -> Bool = { UIApplication.shared.canOpenURL($0) }) -> [NaviApp] {
        allCases.filter { app in
            guard let link = app.link(ziel) else { return false }
            // Apple Karten ist ein https-Link und immer da
            return app == .appleKarten || kannOeffnen(link)
        }
    }
}

/// Knopf „Navigation starten“: bei nur einer App direkt, sonst Auswahl.
struct NavigationStartenKnopf: View {
    let ziel: NavigationsZiel
    @State private var auswahl = false
    @Environment(\.openURL) private var oeffnen

    var body: some View {
        Button {
            let apps = NaviApp.verfuegbar(fuer: ziel)
            if apps.count == 1, let link = apps[0].link(ziel) { oeffnen(link) } else { auswahl = true }
        } label: {
            Label("Navigation starten", systemImage: "location.north.line.fill")
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.small)
        .confirmationDialog("Navigation zu „\(ziel.name)“", isPresented: $auswahl, titleVisibility: .visible) {
            ForEach(NaviApp.verfuegbar(fuer: ziel)) { app in
                Button(app.name) { if let link = app.link(ziel) { oeffnen(link) } }
            }
        }
    }
}

/// Punkt am eigenen Standort setzen (Fund, Hinweis). Position nur auf Knopfdruck.
struct PunktSetzenView: View {
    @ObservedObject var ansicht: TeamAnsicht
    @Environment(\.dismiss) private var schliessen
    @State private var text = ""
    @State private var ortet = false
    @State private var fehler: String?
    @State private var ortung = EinmalOrtung()

    private static let vorschlaege = ["Fund", "Spur", "Hinweis", "Gefahr"]

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("z. B. Fund: Jacke", text: $text, axis: .vertical)
                        .lineLimit(1...3)
                        .onChange(of: text) { _, neu in
                            if neu.count > TeamPunkt.maxText { text = String(neu.prefix(TeamPunkt.maxText)) }
                        }
                    HStack {
                        ForEach(Self.vorschlaege, id: \.self) { vorschlag in
                            Button(vorschlag) { text = text.isEmpty ? "\(vorschlag): " : text }
                                .buttonStyle(.bordered)
                                .controlSize(.small)
                        }
                    }
                } header: {
                    Text("Was ist hier?")
                } footer: {
                    Text("Der Punkt kommt mit deinem jetzigen Standort zur Einsatzleitung und erscheint dort auf der Karte und als Notiz.")
                }
                if let fehler {
                    Section { Text(fehler).foregroundStyle(.red) }
                }
            }
            .navigationTitle("Punkt setzen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Abbrechen") { schliessen() } }
                ToolbarItem(placement: .confirmationAction) {
                    if ortet {
                        ProgressView()
                    } else {
                        Button("Senden") { Task { await senden() } }
                            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
    }

    private func senden() async {
        ortet = true
        defer { ortet = false }
        do {
            let ort = try await ortung.position()
            await ansicht.punktSetzen(text: text, lat: ort.lat, lng: ort.lng, genauigkeit: ort.genauigkeit, zeit: ort.zeit)
            schliessen()
        } catch EinmalOrtung.Fehler.keineFreigabe {
            fehler = "Ohne Freigabe der Ortung kann die App keinen Punkt setzen. Bitte in den Einstellungen von iOS erlauben."
        } catch {
            fehler = "Keine Position gefunden. Bitte kurz unter freiem Himmel erneut versuchen."
        }
    }
}
