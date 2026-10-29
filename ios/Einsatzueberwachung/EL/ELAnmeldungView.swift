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

    var body: some View {
        Form {
            if let hinweis = modell.elHinweis {
                Section { Label(hinweis, systemImage: "exclamationmark.triangle").foregroundStyle(.orange) }
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
        .onAppear { if server.isEmpty { server = modell.elServerAdresse } }
        .alert("Anmeldung nicht möglich", isPresented: Binding(get: { fehler != nil }, set: { if !$0 { fehler = nil } })) {
            Button("OK") {}
        } message: {
            Text(fehler ?? "")
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
        } catch ELAnmeldeFehler.passwortFalsch {
            fehler = "Das Staffel-Passwort ist falsch."
        } catch ELAnmeldeFehler.gesperrt(let sekunden) {
            fehler = "Zu viele Fehlversuche. Bitte in \(max(1, sekunden / 60)) Minuten erneut versuchen."
        } catch ELAnmeldeFehler.keinPasswort {
            fehler = "Auf dem Server ist noch kein Staffel-Passwort eingerichtet (Einstellungen im Monitor)."
        } catch ELAnmeldeFehler.nichtErreichbar {
            fehler = "Server nicht erreichbar. Ist WireGuard eingeschaltet? Die Einsatzleitung geht nur über das VPN der Staffel."
        } catch ELAnmeldeFehler.server(let status) where status == 404 {
            fehler = "Der Server unterstützt die App-Anmeldung der Einsatzleitung noch nicht."
        } catch {
            fehler = "Der Server meldet einen Fehler. Bitte später erneut versuchen."
        }
    }
}
