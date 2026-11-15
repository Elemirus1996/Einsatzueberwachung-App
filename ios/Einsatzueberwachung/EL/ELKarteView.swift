import MapKit
import SwiftUI

/// Lagekarte der Einsatzleitung (wie die Karte in `/el`): Suchgebiete mit Abdeckung, Laufwege je Team,
/// Halsbänder, Team-Handys, Einsatzleitung und ELW. Positionen bewegen sich live.
struct ELKarteView: View {
    @ObservedObject var karte: ELKartenAnsicht
    let teams: [ELLage.Team]
    @ObservedObject var betrieb: ELBetrieb
    var lage: ELLage?
    /// Im iPad-Leitstand ohne eigenen Kopf
    var mitKopf = true
    @State private var kamera: MapCameraPosition = .automatic
    @State private var gewaehlterPunkt: ELKarte.Punkt?
    @State private var gewaehltesGebiet: ELKarte.Suchgebiet?
    @State private var satellit = false

    var body: some View {
        Map(position: $kamera) {
            ForEach(karte.karte?.suchgebiete ?? []) { gebiet in
                if gebiet.punkte.count > 2 {
                    let fertig = gebiet.abgeschlossen == true
                    let farbe = Color(hex: gebiet.farbe) ?? .blue
                    // Abgeschlossene Gebiete blasser, gestrichelt
                    MapPolygon(coordinates: gebiet.punkte.map(\.koordinate))
                        .foregroundStyle(farbe.opacity(fertig ? 0.06 : 0.15))
                        .stroke(farbe.opacity(fertig ? 0.6 : 1), style: StrokeStyle(lineWidth: 2, dash: fertig ? [6, 4] : []))
                    if let mitte = mitte(gebiet.punkte) {
                        // Antippen → Team zuweisen (Idee 6.4)
                        Annotation("", coordinate: mitte) {
                            Button { gewaehltesGebiet = gebiet } label: {
                                VStack(spacing: 0) {
                                    HStack(spacing: 3) {
                                        if fertig { Image(systemName: "checkmark.circle.fill").foregroundStyle(.green) }
                                        Text(gebiet.name ?? "")
                                    }
                                    .font(.caption.bold())
                                    if let team = teams.first(where: { $0.teamId == gebiet.teamId }) {
                                        Text(team.teamName).font(.caption2)
                                    }
                                    if let abdeckung = gebiet.abdeckungProzent {
                                        Text("\(Int(abdeckung.rounded())) %").font(.caption2)
                                    }
                                }
                                .foregroundStyle(ELStil.inschrift)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 4)
                                .background(ELStil.platte, in: RoundedRectangle(cornerRadius: 10))
                                .shadow(color: .black.opacity(0.15), radius: 2, y: 1)
                            }
                            .accessibilityLabel("Suchgebiet \(gebiet.name ?? "") – Team zuweisen")
                        }
                    }
                }
            }
            ForEach(Array(abgeschlossene.enumerated()), id: \.offset) { _, track in
                MapPolyline(coordinates: track.punkte.map(\.koordinate))
                    .stroke((Color(hex: track.farbe) ?? .gray).opacity(0.4), lineWidth: 2)
            }
            ForEach(karte.tracks?.laufend ?? [], id: \.teamId) { teil in
                MapPolyline(coordinates: teil.mensch.map(\.koordinate)).stroke(.blue, lineWidth: 3)
                MapPolyline(coordinates: teil.hund.map(\.koordinate)).stroke(.red, lineWidth: 3)
            }
            // Kartenpunkte (Variante C): antippen → an ein Team senden
            ForEach(karte.karte?.punkte ?? []) { punkt in
                Annotation(punkt.name ?? "Punkt", coordinate: CLLocationCoordinate2D(latitude: punkt.lat, longitude: punkt.lng)) {
                    Button { gewaehlterPunkt = punkt } label: {
                        Image(systemName: punkt.teamId == nil ? "mappin" : "mappin.and.ellipse")
                            .font(.caption.bold())
                            .foregroundStyle(.white)
                            .padding(6)
                            .background(Color(hex: punkt.farbe) ?? .blue, in: Circle())
                            .overlay(Circle().stroke(.white, lineWidth: 2))
                    }
                    .accessibilityLabel("Punkt \(punkt.name ?? "")")
                }
            }
            ForEach(karte.karte?.positionen ?? []) { p in
                Annotation(beschriftung(p), coordinate: CLLocationCoordinate2D(latitude: p.lat, longitude: p.lng)) {
                    Image(systemName: symbol(p.art))
                        .font(.caption)
                        .foregroundStyle(.white)
                        .padding(6)
                        .background(farbe(p.art), in: Circle())
                        .overlay(Circle().stroke(.white, lineWidth: 2))
                }
            }
        }
        .mapStyle(satellit ? .hybrid(elevation: .realistic) : .standard(elevation: .realistic))
        .mapControls {
            MapUserLocationButton()
            MapCompass()
            MapScaleView()
        }
        .overlay(alignment: .bottomTrailing) {
            Button { satellit.toggle() } label: {
                Image(systemName: satellit ? "map" : "globe.europe.africa")
                    .font(.title3).frame(width: 44, height: 44)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
            }
            .accessibilityLabel("Kartenart wechseln")
            .padding(12)
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            if mitKopf { ELReiterKopf(titel: "Karte", lage: lage) }
        }
        .toolbar(.hidden, for: .navigationBar)
        .sheet(item: $gewaehlterPunkt) { punkt in
            ELPunktView(punkt: punkt, teams: teams, betrieb: betrieb)
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $gewaehltesGebiet) { gebiet in
            ELSuchgebietView(gebiet: gebiet, teams: teams, betrieb: betrieb) {
                Task { await karte.karteNeuLaden() }
            }
            .presentationDetents([.medium, .large])
        }
        .onAppear { karte.starten() }
        .onDisappear { karte.anhalten() }
    }

    private var abgeschlossene: [TeamTracks.Track] { (karte.tracks?.abgeschlossen ?? []).flatMap(\.tracks) }

    private func beschriftung(_ p: ELPosition) -> String {
        switch p.art {
        case "el": return "EL \(p.name ?? p.id)"
        case "elw": return "ELW"
        default:
            let team = teams.first { $0.teamId == p.teamId }?.teamName
            return [team, p.art == "halsband" ? (p.name ?? p.id) : nil].compactMap { $0 }.joined(separator: " · ")
        }
    }

    private func symbol(_ art: String) -> String {
        switch art {
        case "halsband": "pawprint.fill"
        case "handy": "iphone"
        case "el": "person.badge.shield.checkmark.fill"
        case "elw": "car.fill"
        default: "mappin"
        }
    }

    private func farbe(_ art: String) -> Color {
        switch art {
        case "halsband": .red
        case "handy": .blue
        case "el": .purple
        case "elw": .black
        default: .gray
        }
    }

    private func mitte(_ punkte: [Koordinate]) -> CLLocationCoordinate2D? {
        guard !punkte.isEmpty else { return nil }
        return CLLocationCoordinate2D(latitude: punkte.map(\.lat).reduce(0, +) / Double(punkte.count),
                                      longitude: punkte.map(\.lng).reduce(0, +) / Double(punkte.count))
    }
}


/// Kartenpunkt ansehen und als Navigationsziel an ein Team senden (`/teams/{teamId}/ziel`).
struct ELPunktView: View {
    let punkt: ELKarte.Punkt
    let teams: [ELLage.Team]
    @ObservedObject var betrieb: ELBetrieb
    @Environment(\.dismiss) private var schliessen

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if let beschreibung = punkt.beschreibung, !beschreibung.isEmpty { Text(beschreibung) }
                    LabeledContent("Koordinate", value: String(format: "%.6f, %.6f", punkt.lat, punkt.lng))
                    if let zeit = punkt.zeit { LabeledContent("Gesetzt") { Text(zeit, style: .time) } }
                    if let teamId = punkt.teamId {
                        LabeledContent("Von", value: teams.first { $0.teamId == teamId }?.teamName ?? "Team")
                    }
                }
                Section {
                    if teams.isEmpty {
                        Text("Noch keine Teams im Einsatz.").foregroundStyle(.secondary)
                    }
                    ForEach(teams) { team in
                        Button {
                            let name = punkt.name ?? "Punkt"
                            Task { await betrieb.ausfuehren(.ziel(team.teamId, name: team.teamName, punktId: punkt.id, punktName: name)) }
                            schliessen()
                        } label: {
                            Label(team.teamName, systemImage: "paperplane")
                        }
                    }
                } header: {
                    Text("An Team senden")
                } footer: {
                    Text("Das Team bekommt den Punkt als Ziel und startet damit seine Navigation.")
                }
            }
            .navigationTitle(punkt.name ?? "Punkt")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { schliessen() } }
            }
        }
    }
}

/// Suchgebiet einem Team zuweisen oder die Zuweisung aufheben (`/teams/{teamId}/suchgebiet`, Idee 6.4).
struct ELSuchgebietView: View {
    let gebiet: ELKarte.Suchgebiet
    let teams: [ELLage.Team]
    @ObservedObject var betrieb: ELBetrieb
    /// Nach der Aktion: Karte neu laden (Zuweisung in `suchgebiete[].teamId`)
    let geaendert: () -> Void
    @Environment(\.dismiss) private var schliessen
    @State private var wechselFrage: ELLage.Team?

    private var bisher: ELLage.Team? { teams.first { $0.teamId == gebiet.teamId } }
    private var name: String { gebiet.name ?? "Suchgebiet" }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    if teams.isEmpty {
                        Text("Noch keine Teams im Einsatz.").foregroundStyle(.secondary)
                    }
                    ForEach(teams) { team in
                        Button {
                            if team.teamId == gebiet.teamId { schliessen() }
                            else if bisher != nil { wechselFrage = team }
                            else { zuweisen(team) }
                        } label: {
                            HStack {
                                Label(team.teamName, systemImage: team.teamId == gebiet.teamId ? "checkmark.circle.fill" : "circle")
                                Spacer()
                                if let anderes = team.suchgebiet, anderes.id != gebiet.id {
                                    Text(anderes.name ?? "anderes Gebiet").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                } header: {
                    Text("Team zuweisen")
                } footer: {
                    Text("Ein Team hat höchstens ein Suchgebiet – ein bisheriges wird frei. Die Team-App zeigt das neue Gebiet sofort.")
                }
                if let bisher {
                    Section {
                        Button("Zuweisung von \(bisher.teamName) aufheben", role: .destructive) {
                            Task {
                                await betrieb.ausfuehren(.suchgebiet(bisher.teamId, name: bisher.teamName, suchgebietId: nil, gebietName: name))
                                geaendert()
                            }
                            schliessen()
                        }
                    }
                }
            }
            .navigationTitle(name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Fertig") { schliessen() } }
            }
            .confirmationDialog("„\(name)“ gehört \(bisher?.teamName ?? "einem anderen Team")",
                                isPresented: Binding(get: { wechselFrage != nil }, set: { if !$0 { wechselFrage = nil } }),
                                titleVisibility: .visible) {
                if let team = wechselFrage {
                    Button("An \(team.teamName) geben") { zuweisen(team) }
                }
            } message: {
                Text("\(bisher?.teamName ?? "Das andere Team") hat danach kein Suchgebiet mehr.")
            }
        }
    }

    private func zuweisen(_ team: ELLage.Team) {
        Task {
            await betrieb.ausfuehren(.suchgebiet(team.teamId, name: team.teamName, suchgebietId: gebiet.id, gebietName: name))
            geaendert()
        }
        schliessen()
    }
}
