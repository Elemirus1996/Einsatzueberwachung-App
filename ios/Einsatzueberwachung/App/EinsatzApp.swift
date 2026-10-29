import SwiftUI

@main
struct EinsatzApp: App {
    @StateObject private var modell = AppModell()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            StartView(heimat: modell.heimat)
                .environmentObject(modell)
        }
        .onChange(of: phase) { _, neu in
            switch neu {
            case .active: Task { await modell.aktiviert() }
            case .background: modell.hintergrundAbfragePlanen()
            default: break
            }
        }
        .backgroundTask(.appRefresh(AppModell.statusAufgabe)) {
            await modell.hintergrundAbfrage()
        }
    }
}
