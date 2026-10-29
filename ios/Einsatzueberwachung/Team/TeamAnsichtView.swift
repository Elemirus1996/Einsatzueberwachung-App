import MapKit
import SwiftUI

/// Native Team-Ansicht (Nachbau von `/team`): Kopfzeile, Karte, Mitteilungen der EL, Statusknöpfe, Fußzeile.
struct TeamAnsichtView: View {
    @EnvironmentObject private var modell: AppModell
    @ObservedObject var ortung: TeamOrtung
    @ObservedObject var ansicht: TeamAnsicht
    let sitzung: TeamSitzung

    @Environment(\.scenePhase) private var phase
    @State private var kamera: MapCameraPosition = .automatic
    @State private var satellit = false
    @State private var infoOffen = false
    @State private var beendenFragen = false

    var body: some View {
        Group {
            if ortung.zustand == .beendet {
                beendet
            } else {
                VStack(spacing: 0) {
                    kopf
                    karte
                        .overlay(alignment: .top) { mitteilungen }
                    statusKnoepfe
                    fusszeile
                }
            }
        }
        .navigationTitle(ansicht.lage?.team.teamName ?? sitzung.teamName)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { infoOffen = true } label: { Image(systemName: "info.circle") }
                    .accessibilityLabel("Team-Info")
            }
        }
        .sheet(isPresented: $infoOffen) {
            TeamInfoView(ortung: ortung, ansicht: ansicht, sitzung: sitzung)
        }
        .onAppear { ansicht.starten() }
        .onDisappear { ansicht.anhalten() }
        .onChange(of: phase) { _, neu in
            if neu == .active { ansicht.starten() } else if neu == .background { ansicht.hintergrund() }
        }
        .sensoryFeedback(.warning, trigger: ansicht.ungelesen.count) { alt, neu in neu > alt }
    }

    // MARK: Kopf

    private var kopf: some View {
        TimelineView(.periodic(from: .now, by: 1)) { kontext in
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 6) {
                        Text(hundText).font(.headline)
                        if ansicht.lage?.einsatz.uebung == true {
                            Text("ÜBUNG")
                                .font(.caption.bold())
                                .padding(.horizontal, 6).padding(.vertical, 2)
                                .background(.orange, in: Capsule())
                                .foregroundStyle(.white)
                        }
                    }
                    Text(ansicht.lage?.einsatz.text ?? " ")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                Spacer()
                VStack(alignment: .trailing, spacing: 2) {
                    Text(sucheText)
                        .font(.caption.bold())
                        .foregroundStyle(sucheFarbe)
                    if let dauer = ansicht.suchdauer(um: kontext.date) {
                        Text(Self.dauer(dauer))
                            .font(.title3.monospacedDigit().bold())
                    }
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        .background(.bar)
    }

    private var hundText: String {
        let hund = ansicht.lage?.team.dogName ?? ""
        return hund.isEmpty ? "(ohne Hund)" : hund
    }

    private var sucheText: String {
        switch ansicht.lage?.suche.status {
        case .laeuft: "Suche läuft"
        case .kurzpause: "Kurzpause"
        case .beendet: "Suche beendet"
        case .bereit: "Bereit"
        case nil: ansicht.verbindungsfehler ? "Keine Verbindung" : "Lade …"
        }
    }

    private var sucheFarbe: Color {
        switch ansicht.lage?.suche.status {
        case .laeuft: .green
        case .kurzpause: .orange
        default: .secondary
        }
    }

    // MARK: Karte

    private var karte: some View {
        Map(position: $kamera) {
            if let gebiet = ansicht.lage?.suchgebiet, gebiet.punkte.count > 2 {
                let farbe = Color(hex: gebiet.farbe) ?? .blue
                MapPolygon(coordinates: gebiet.punkte.map(\.koordinate))
                    .foregroundStyle(farbe.opacity(0.15))
                    .stroke(farbe, lineWidth: 2)
            }
            ForEach(Array(abgeschlosseneTracks.enumerated()), id: \.offset) { _, track in
                MapPolyline(coordinates: track.punkte.map(\.koordinate))
                    .stroke((Color(hex: track.farbe) ?? .gray).opacity(0.45), lineWidth: 2)
            }
            if let laufend = ansicht.tracks?.laufend {
                MapPolyline(coordinates: laufend.mensch.map(\.koordinate))
                    .stroke(.blue, lineWidth: 3)
                MapPolyline(coordinates: laufend.hund.map(\.koordinate))
                    .stroke(.red, lineWidth: 3)
            }
            if let halsband = ansicht.lage?.halsband {
                Annotation(hundText, coordinate: CLLocationCoordinate2D(latitude: halsband.lat, longitude: halsband.lng)) {
                    Image(systemName: "pawprint.fill")
                        .font(.caption)
                        .foregroundStyle(.white)
                        .padding(6)
                        .background(.red, in: Circle())
                        .overlay(Circle().stroke(.white, lineWidth: 2))
                }
            }
            UserAnnotation()
        }
        .mapStyle(satellit ? .hybrid(elevation: .realistic) : .standard(elevation: .realistic))
        .mapControls {
            MapUserLocationButton()
            MapCompass()
            MapScaleView()
        }
        .overlay(alignment: .bottomTrailing) {
            VStack(spacing: 8) {
                if let halsband = ansicht.lage?.halsband {
                    KartenKnopf(symbol: "pawprint", beschriftung: "Auf Hund zentrieren") {
                        withAnimation {
                            kamera = .camera(MapCamera(
                                centerCoordinate: CLLocationCoordinate2D(latitude: halsband.lat, longitude: halsband.lng),
                                distance: 800))
                        }
                    }
                }
                KartenKnopf(symbol: satellit ? "map" : "globe.europe.africa", beschriftung: "Kartenart wechseln") {
                    satellit.toggle()
                }
            }
            .padding(12)
        }
    }

    private var abgeschlosseneTracks: [TeamTracks.Track] {
        (ansicht.tracks?.abgeschlossen ?? []).flatMap(\.tracks)
    }

    // MARK: Mitteilungen

    private var mitteilungen: some View {
        VStack(spacing: 6) {
            ForEach(ansicht.ungelesen.suffix(3)) { mitteilung in
                HStack(alignment: .top) {
                    Image(systemName: "megaphone.fill").foregroundStyle(.orange)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(mitteilung.text).font(.subheadline.bold())
                        Text(mitteilung.zeit, style: .time).font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                    Button("OK") { ansicht.alsGelesen(mitteilung) }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.small)
                }
                .padding(10)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
            }
        }
        .padding(8)
        .animation(.default, value: ansicht.ungelesen)
    }

    // MARK: Statusknöpfe

    private var statusKnoepfe: some View {
        VStack(spacing: 4) {
            if ansicht.darfSteuern, let status = ansicht.lage?.suche.status {
                HStack(spacing: 8) {
                    ForEach(Suchaktion.moeglich(bei: status), id: \.self) { aktion in
                        Button {
                            if aktion == .beenden {
                                beendenFragen = true
                            } else {
                                Task { await ansicht.steuern(aktion) }
                            }
                        } label: {
                            Label(aktion.titel, systemImage: aktion.symbol)
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(aktion.farbe)
                        .disabled(!ansicht.offeneAktionen.isEmpty)
                        .sensoryFeedback(.impact, trigger: ansicht.offeneAktionen.count)
                    }
                }
            }
            HStack(spacing: 8) {
                // Seit #133 nur noch Meldungen; Pause und Ende steuert das Team über die Suche
                ForEach([Statusmeldung.Code.imGebiet, .funk], id: \.self) { code in
                    Button {
                        Task { await ansicht.melden(code) }
                    } label: {
                        VStack(spacing: 2) {
                            Image(systemName: code.symbol).font(.title3)
                            Text(code.titel).font(.caption2)
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .tint(code.farbe)
                    .sensoryFeedback(.impact, trigger: ansicht.offeneMeldungen.count)
                }
            }
            TimelineView(.periodic(from: .now, by: 1)) { kontext in
                Text(meldungsHinweis(kontext.date))
                    .font(.caption)
                    .foregroundStyle(ansicht.offeneMeldungen.isEmpty && ansicht.offeneAktionen.isEmpty ? Color.green : Color.orange)
                    .frame(height: 14)
            }
        }
        .padding(.horizontal)
        .padding(.top, 8)
        .background(.bar)
        .confirmationDialog("Suche wirklich beenden?", isPresented: $beendenFragen, titleVisibility: .visible) {
            Button("Suche beenden", role: .destructive) { Task { await ansicht.steuern(.beenden) } }
        } message: {
            Text("Die Ortung schaltet danach auf Pause. Für eine Kurzpause „Pause“ wählen.")
        }
    }

    private func meldungsHinweis(_ jetzt: Date) -> String {
        if let offen = ansicht.offeneAktionen.first {
            return "„\(offen.aktion.titel)“ wartet auf Netz"
        }
        if let hinweis = ansicht.suchHinweis, jetzt.timeIntervalSince(hinweis.zeit) < 6 {
            return hinweis.text
        }
        if !ansicht.offeneMeldungen.isEmpty {
            let anzahl = ansicht.offeneMeldungen.count
            return anzahl == 1 ? "1 Meldung wartet auf Netz" : "\(anzahl) Meldungen warten auf Netz"
        }
        if let gesendet = ansicht.gesendet, jetzt.timeIntervalSince(gesendet.zeit) < 4 {
            return "„\(gesendet.code.titel)“ gesendet"
        }
        return ""
    }

    // MARK: Fußzeile

    private var fusszeile: some View {
        TimelineView(.periodic(from: .now, by: 1)) { kontext in
            HStack(spacing: 14) {
                Label(halsbandText(kontext.date), systemImage: "dot.radiowaves.left.and.right")
                    .foregroundStyle(ansicht.lage?.halsband == nil ? Color.secondary : Color.green)
                Label(ortungText, systemImage: ortung.zustand == .ortet ? "location.fill" : "location.slash")
                    .foregroundStyle(ortung.zustand == .ortet ? Color.blue : Color.secondary)
                if let entfernung = entfernungZumGebiet {
                    Label(entfernung, systemImage: "arrow.up.and.down.and.arrow.left.and.right")
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
                Circle()
                    .fill(ansicht.live ? Color.green : (ansicht.verbindungsfehler ? Color.red : Color.orange))
                    .frame(width: 8, height: 8)
                    .accessibilityLabel(ansicht.live ? "Live verbunden" : "Nicht live verbunden")
            }
            .font(.caption)
            .lineLimit(1)
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
        .background(.bar)
    }

    private func halsbandText(_ jetzt: Date) -> String {
        guard ansicht.lage?.halsband != nil, let alter = ansicht.halsbandAlter(um: jetzt) else { return "kein GPS" }
        return alter < 120 ? "GPS \(Int(alter)) s" : "GPS \(Int(alter / 60)) min"
    }

    private var ortungText: String {
        switch ortung.zustand {
        case .ortet: "Handy ortet"
        case .pausiert: "wartet auf neue Suche"
        case .wartet: "verbinde …"
        case .beendet: "beendet"
        }
    }

    private var entfernungZumGebiet: String? {
        guard let gebiet = ansicht.lage?.suchgebiet, gebiet.punkte.count > 2,
              let ich = ortung.letztePosition else { return nil }
        let mitte = CLLocation(latitude: gebiet.punkte.map(\.lat).reduce(0, +) / Double(gebiet.punkte.count),
                               longitude: gebiet.punkte.map(\.lng).reduce(0, +) / Double(gebiet.punkte.count))
        let meter = CLLocation(latitude: ich.lat, longitude: ich.lng).distance(from: mitte)
        return meter < 1000 ? "\(Int(meter)) m zum Gebiet" : String(format: "%.1f km zum Gebiet", meter / 1000)
    }

    // MARK: Beendet

    private var beendet: some View {
        ContentUnavailableView {
            Label("Einsatz beendet", systemImage: "checkmark.circle")
        } description: {
            Text("Die Ortung ist aus. Die App fragt keinen Standort mehr ab.")
        } actions: {
            Button("Zurück zum Start") { modell.teamSchliessen() }
                .buttonStyle(.borderedProminent)
        }
    }

    static func dauer(_ sekunden: TimeInterval) -> String {
        let s = Int(sekunden)
        return String(format: "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }
}

private struct KartenKnopf: View {
    let symbol: String
    let beschriftung: String
    let aktion: () -> Void

    var body: some View {
        Button(action: aktion) {
            Image(systemName: symbol)
                .font(.title3)
                .frame(width: 44, height: 44)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
        }
        .accessibilityLabel(beschriftung)
    }
}

/// Team-Info mit Abmelden.
private struct TeamInfoView: View {
    @EnvironmentObject private var modell: AppModell
    @Environment(\.dismiss) private var schliessen
    @ObservedObject var ortung: TeamOrtung
    @ObservedObject var ansicht: TeamAnsicht
    let sitzung: TeamSitzung
    @State private var einsatzAbmeldenFragen = false
    @State private var trotzdemWechselnFragen = false

    var body: some View {
        NavigationStack {
            List {
                if let lage = ansicht.lage {
                    Section("Team") {
                        LabeledContent("Team", value: lage.team.teamName)
                        if let hund = lage.team.dogName, !hund.isEmpty { LabeledContent("Hund", value: hund) }
                        if let fuehrer = lage.team.hundefuehrerName, !fuehrer.isEmpty {
                            LabeledContent("Hundeführer", value: fuehrer)
                        }
                        if let helfer = lage.team.helfer, !helfer.isEmpty {
                            LabeledContent("Helfer", value: helfer.joined(separator: ", "))
                        }
                        if let gebiet = lage.suchgebiet?.name, !gebiet.isEmpty { LabeledContent("Suchgebiet", value: gebiet) }
                    }
                    Section("Einsatz") {
                        if let text = lage.einsatz.text, !text.isEmpty { LabeledContent("Einsatz", value: text) }
                        if lage.einsatz.uebung == true { LabeledContent("Art", value: "Übung") }
                        if let staffel = lage.einsatz.staffel, !staffel.isEmpty { LabeledContent("Staffel", value: staffel) }
                    }
                }
                Section("Ortung") {
                    LabeledContent("Server", value: sitzung.server.name)
                    if let upload = ortung.letzterUpload {
                        LabeledContent("Letzter Upload") { Text(upload, style: .relative) }
                    }
                    if ortung.gepuffert > 0 {
                        LabeledContent("Im Puffer", value: "\(ortung.gepuffert) Positionen")
                    }
                    LabeledContent("Gültig bis") { Text(sitzung.expiresAt, style: .time) }
                }
                if !(ansicht.lage?.mitteilungen.isEmpty ?? true) {
                    Section("Mitteilungen der Einsatzleitung") {
                        ForEach((ansicht.lage?.mitteilungen ?? []).reversed()) { mitteilung in
                            VStack(alignment: .leading) {
                                Text(mitteilung.text)
                                Text(mitteilung.zeit, style: .time).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                Section {
                    if modell.einsatzZugang != nil {
                        Button {
                            Task { await wechseln(trotzdem: false) }
                        } label: {
                            Label("Team wechseln", systemImage: "arrow.left.arrow.right")
                        }
                    }
                } footer: {
                    Text("Beendet die Ortung für dieses Team und zeigt die Teams desselben Einsatzes, ohne neuen QR-Scan.")
                }
                Section {
                    Button("Vom Einsatz abmelden", role: .destructive) { einsatzAbmeldenFragen = true }
                        .font(.footnote)
                }
            }
            .navigationTitle("Team-Info")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { schliessen() } }
            }
            .confirmationDialog("Vom Einsatz abmelden?", isPresented: $einsatzAbmeldenFragen, titleVisibility: .visible) {
                Button("Abmelden", role: .destructive) {
                    schliessen()
                    modell.vomEinsatzAbmelden()
                }
            } message: {
                Text("Die Ortung endet und der Anmelde-Code wird gelöscht. Für den Einsatz wieder den QR-Code scannen.")
            }
            .confirmationDialog("\(ortung.gepuffert) Positionen sind noch nicht gesendet (kein Netz).",
                                isPresented: $trotzdemWechselnFragen, titleVisibility: .visible) {
                Button("Trotzdem wechseln", role: .destructive) { Task { await wechseln(trotzdem: true) } }
            } message: {
                Text("Beim Wechsel gehen sie verloren. Besser kurz warten, bis wieder Netz da ist.")
            }
        }
    }

    private func wechseln(trotzdem: Bool) async {
        if await modell.teamWechseln(trotzdem: trotzdem) {
            schliessen()
        } else {
            trotzdemWechselnFragen = true
        }
    }
}

extension Suchaktion {
    var symbol: String {
        switch self {
        case .starten: "play.fill"
        case .pause: "pause.fill"
        case .weiter: "play.fill"
        case .beenden: "stop.fill"
        }
    }

    var farbe: Color {
        switch self {
        case .starten, .weiter: .green
        case .pause: .orange
        case .beenden: .red
        }
    }
}

extension Statusmeldung.Code {
    var titel: String {
        switch self {
        case .imGebiet: "Im Gebiet"
        case .pause: "Pause"
        case .funk: "Funk"
        case .beendet: "Beendet"
        }
    }

    var symbol: String {
        switch self {
        case .imGebiet: "square.dashed"
        case .pause: "pause.circle"
        case .funk: "antenna.radiowaves.left.and.right"
        case .beendet: "checkmark.circle"
        }
    }

    var farbe: Color {
        switch self {
        case .imGebiet: .blue
        case .pause: .orange
        case .funk: .teal
        case .beendet: .green
        }
    }
}

extension Koordinate {
    var koordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lng) }
}

extension TeamTracks.Punkt {
    var koordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: lat, longitude: lng) }
}

extension Color {
    /// `#rrggbb` aus den Farben des Servers.
    init?(hex: String?) {
        guard var text = hex?.trimmingCharacters(in: .whitespaces), !text.isEmpty else { return nil }
        if text.hasPrefix("#") { text.removeFirst() }
        guard text.count == 6, let wert = UInt32(text, radix: 16) else { return nil }
        self.init(red: Double((wert >> 16) & 0xFF) / 255,
                  green: Double((wert >> 8) & 0xFF) / 255,
                  blue: Double(wert & 0xFF) / 255)
    }
}
