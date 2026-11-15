import SwiftUI
import UIKit

/// Anmeldung der Einsatzleitung: interne Adresse (nur im VPN erreichbar) und Staffel-Passwort.
struct ELAnmeldungView: View {
    @EnvironmentObject private var modell: AppModell
    @State private var server = ""
    @State private var passwort = ""
    @State private var geraet = UIDevice.current.name
    @State private var laedt = false
    @State private var fehler: String?
    @State private var scannerOffen = false
    @State private var scanHinweis: String?
    /// Server aus dem QR-Code der Einstellungen, zur Bestätigung angezeigt
    @State private var ausQR: String?

    var body: some View {
        Form {
            if let hinweis = modell.elHinweis {
                Section { Label(hinweis, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
            }
            Section {
                Button {
                    scanHinweis = nil
                    scannerOffen = true
                } label: {
                    Label("QR-Code aus den Einstellungen scannen", systemImage: "qrcode.viewfinder")
                }
                .disabled(!QRScannerView.verfuegbar)
                if let ausQR {
                    Label("Server aus dem QR-Code: \(ausQR)", systemImage: "checkmark.shield")
                        .foregroundStyle(.green)
                }
            } footer: {
                Text("Der Code steht im Monitor unter Einstellungen → System → „EL-App anmelden“. Er enthält nur die Adresse, das Passwort gibt man hier ein.")
            }
            Section {
                TextField("Interne Adresse, z. B. einsatz.intern", text: $server)
                    .textContentType(.URL)
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("Staffel-Passwort", text: $passwort)
                    .textContentType(.password)
                TextField("Name dieses Geräts", text: $geraet)
            } footer: {
                Text("Die Einsatzleitung ist nur im WireGuard-VPN der Staffel erreichbar. Der Gerätename erscheint im Protokoll und als Marker „EL“ auf der Einsatzkarte.")
            }
            Section {
                Button {
                    Task { await anmelden() }
                } label: {
                    if laedt { ProgressView() } else { Text("Anmelden") }
                }
                .disabled(server.isEmpty || passwort.isEmpty || laedt)
            }
        }
        .navigationTitle("Einsatzleitung")
        .onChange(of: server) { _, neu in if neu != ausQR { ausQR = nil } }
        .sheet(isPresented: $scannerOffen) { scanner }
        .onAppear {
            if server.isEmpty { server = modell.elServerAdresse }
            #if DEBUG
            // Nur für Bildschirmfotos im Simulator gegen einen lokalen Testserver
            if passwort.isEmpty, let demo = ProcessInfo.processInfo.environment["EL_DEMO_PASSWORT"] { passwort = demo }
            #endif
        }
        .alert("Anmeldung nicht möglich", isPresented: Binding(get: { fehler != nil }, set: { if !$0 { fehler = nil } })) {
            Button("OK") {}
        } message: {
            Text(fehler ?? "")
        }
    }

    private var scanner: some View {
        NavigationStack {
            QRScannerView(gefunden: { text in
                guard let qr = ELQRCode(text) else {
                    scanHinweis = TeamQRCode(text) != nil
                        ? "Das ist ein Team-Code. Für die Einsatzleitung den Code aus den Einstellungen scannen."
                        : "Das ist kein QR-Code für die Einsatzleitung."
                    return false
                }
                scannerOffen = false
                server = qr.server.name
                ausQR = qr.server.name
                return true
            })
            .ignoresSafeArea()
            .overlay(alignment: .bottom) {
                if let scanHinweis {
                    Text(scanHinweis)
                        .padding()
                        .background(.regularMaterial, in: Capsule())
                        .padding(.bottom, 40)
                }
            }
            .navigationTitle("QR-Code scannen")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Abbrechen") { scannerOffen = false }
                }
            }
        }
    }

    private func anmelden() async {
        var text = server.trimmingCharacters(in: .whitespaces)
        if !text.lowercased().hasPrefix("https://") {
            guard !text.contains("://") else {
                fehler = "Nur sichere Adressen (https) sind erlaubt."
                return
            }
            text = "https://" + text
        }
        guard let url = URL(string: text), let adresse = ServerAdresse(url) else {
            fehler = "Die Adresse ist ungültig."
            return
        }
        laedt = true
        defer { laedt = false }
        do {
            try await modell.elAnmelden(server: adresse, passwort: passwort, geraet: geraet)
            passwort = ""
        } catch let e as ELAnmeldeFehler {
            fehler = e.meldung
        } catch {
            fehler = "Der Server meldet einen Fehler. Bitte später erneut versuchen."
        }
    }
}
