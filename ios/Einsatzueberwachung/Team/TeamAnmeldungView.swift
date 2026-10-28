import SwiftUI

/// Platzhalter: QR-Scan und native Teamauswahl folgen, sobald der Server die Anmelde-Endpunkte hat (Phase 1c).
struct TeamAnmeldungView: View {
    var body: some View {
        ContentUnavailableView(
            "Anmeldung folgt",
            systemImage: "qrcode.viewfinder",
            description: Text("Die Anmeldung per QR-Code kommt mit dem nächsten Server-Update."))
            .navigationTitle("Team")
    }
}
