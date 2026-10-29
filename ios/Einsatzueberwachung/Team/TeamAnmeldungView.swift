import SwiftUI

/// Anmeldung der Rolle Team: QR-Code scannen oder Server und Code eintippen, Server bestätigen, Team wählen.
struct TeamAnmeldungView: View {
    @EnvironmentObject private var modell: AppModell

    @State private var scannerOffen = false
    @State private var hinweis: String?
    @State private var serverEingabe = ""
    @State private var codeEingabe = ""
    @State private var serverBestaetigen: TeamQRCode?
    @State private var auswahl: (qr: TeamQRCode, teams: TeamAuswahl)?
    @State private var laedt = false
    @State private var fehler: String?

    var body: some View {
        Form {
            if let auswahl {
                teamListe(auswahl.qr, auswahl.teams)
            } else {
                scanBereich
                eingabeBereich
            }
        }
        .navigationTitle("Team")
        .disabled(laedt)
        .overlay { if laedt { ProgressView() } }
        .sheet(isPresented: $scannerOffen) { scanner }
        .confirmationDialog(
            "Am Server \(serverBestaetigen?.server.name ?? "") anmelden?",
            isPresented: Binding(get: { serverBestaetigen != nil }, set: { if !$0 { serverBestaetigen = nil } }),
            titleVisibility: .visible,
            presenting: serverBestaetigen
        ) { qr in
            Button("Anmelden") { Task { await teamsLaden(qr) } }
            Button("Abbrechen", role: .cancel) {}
        } message: { _ in
            Text("Die App sendet deinen Standort während der Suche nur an diesen Server.")
        }
        .alert("Anmeldung nicht möglich", isPresented: Binding(get: { fehler != nil }, set: { if !$0 { fehler = nil } })) {
            Button("OK") {}
        } message: {
            Text(fehler ?? "")
        }
    }

    private var scanBereich: some View {
        Section {
            Button {
                hinweis = nil
                scannerOffen = true
            } label: {
                Label("QR-Code scannen", systemImage: "qrcode.viewfinder")
            }
            .disabled(!QRScannerView.verfuegbar)
        } footer: {
            Text(QRScannerView.verfuegbar
                 ? "Den QR-Code zeigt die Einsatzleitung im Monitor unter „Team-Handys“."
                 : "Auf diesem Gerät ist kein Scanner verfügbar. Bitte Server und Code eingeben.")
        }
    }

    private var eingabeBereich: some View {
        Section {
            TextField("Server, z. B. einsatz.staffel.de", text: $serverEingabe)
                .textContentType(.URL)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Anmelde-Code", text: $codeEingabe)
                .textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            Button("Weiter") { eingabePruefen() }
                .disabled(serverEingabe.isEmpty || codeEingabe.isEmpty)
        } header: {
            Text("Oder von Hand")
        }
    }

    private var scanner: some View {
        NavigationStack {
            QRScannerView(
                gefunden: { qr in
                    scannerOffen = false
                    serverBestaetigen = qr
                },
                fremderCode: { hinweis = "Das ist kein Team-QR-Code des Einsatzes." })
                .ignoresSafeArea()
                .overlay(alignment: .bottom) {
                    if let hinweis {
                        Text(hinweis)
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

    private func teamListe(_ qr: TeamQRCode, _ auswahl: TeamAuswahl) -> some View {
        Group {
            Section {
                if let einsatz = auswahl.einsatz, !einsatz.isEmpty {
                    LabeledContent(auswahl.uebung == true ? "Übung" : "Einsatz", value: einsatz)
                }
                if let staffel = auswahl.staffel, !staffel.isEmpty { LabeledContent("Staffel", value: staffel) }
                LabeledContent("Server", value: qr.server.name)
            }
            Section("Welches Team bist du?") {
                if auswahl.teams.isEmpty {
                    Text("Noch keine Teams im Einsatz angelegt.").foregroundStyle(.secondary)
                }
                ForEach(auswahl.teams) { team in
                    Button { Task { await anmelden(qr, team) } } label: {
                        VStack(alignment: .leading) {
                            Text(team.teamName).font(.headline)
                            Text(teamZeile(team)).font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                    .foregroundStyle(.primary)
                }
            }
            Section {
                Button("Teamliste aktualisieren") { Task { await teamsLaden(qr) } }
                Button("Anderer Einsatz", role: .cancel) { self.auswahl = nil }
            }
        }
    }

    private func teamZeile(_ team: TeamAuswahl.Eintrag) -> String {
        let hund = (team.dogName ?? "").isEmpty ? "(ohne Hund)" : team.dogName!
        guard let fuehrer = team.hundefuehrerName, !fuehrer.isEmpty else { return hund }
        return "\(hund) · \(fuehrer)"
    }

    private func eingabePruefen() {
        var text = serverEingabe.trimmingCharacters(in: .whitespaces)
        if !text.lowercased().hasPrefix("https://") {
            guard !text.contains("://") else {
                fehler = "Nur sichere Adressen (https) sind erlaubt."
                return
            }
            text = "https://" + text
        }
        guard let url = URL(string: text), let server = ServerAdresse(url) else {
            fehler = "Die Server-Adresse ist ungültig."
            return
        }
        guard let code = TeamQRCode.bereinigt(codeEingabe) else {
            fehler = "Der Anmelde-Code ist ungültig."
            return
        }
        serverBestaetigen = TeamQRCode(server: server, code: code)
    }

    private func teamsLaden(_ qr: TeamQRCode) async {
        laedt = true
        defer { laedt = false }
        do {
            auswahl = (qr, try await TeamAnmeldung(qr: qr).teams())
        } catch {
            fehler = text(fuer: error)
        }
    }

    private func anmelden(_ qr: TeamQRCode, _ team: TeamAuswahl.Eintrag) async {
        laedt = true
        defer { laedt = false }
        do {
            let auswahl = self.auswahl?.teams
            modell.teamAnmelden(try await TeamAnmeldung(qr: qr).anmelden(teamId: team.teamId),
                                server: auswahl?.server, staffel: auswahl?.staffel)
        } catch {
            fehler = text(fuer: error)
        }
    }

    private func text(fuer fehler: Error) -> String {
        switch fehler {
        case AnmeldeFehler.codeUngueltig: "Der Anmelde-Code ist ungültig oder es ist gerade kein Einsatz aktiv."
        case AnmeldeFehler.teamUnbekannt: "Dieses Team gibt es nicht mehr. Bitte die Teamliste aktualisieren."
        case AnmeldeFehler.zuVieleVersuche: "Zu viele Versuche, bitte kurz warten (etwa 30 Sekunden)."
        case AnmeldeFehler.server(let status) where status == 404 || status == 405:
            "Der Server unterstützt die App-Anmeldung noch nicht. Bitte den Server aktualisieren."
        case AnmeldeFehler.server: "Der Server meldet einen Fehler. Bitte später erneut versuchen."
        case is URLError: "Keine Verbindung zum Server."
        default: "Die Antwort des Servers ist unverständlich."
        }
    }
}
