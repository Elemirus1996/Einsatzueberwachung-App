import MapKit
import SwiftUI

/// Lagekarte der Einsatzleitung (wie die Karte in `/el`): Suchgebiete mit Abdeckung, Laufwege je Team,
/// Halsbänder, Team-Handys, Einsatzleitung und ELW. Positionen bewegen sich live.
struct ELKarteView: View {
    @ObservedObject var karte: ELKartenAnsicht
    let teams: [ELLage.Team]
    @State private var kamera: MapCameraPosition = .automatic
    @State private var satellit = false

    var body: some View {
        Map(position: $kamera) {
            ForEach(karte.karte?.suchgebiete ?? []) { gebiet in
                if gebiet.punkte.count > 2 {
                    let farbe = Color(hex: gebiet.farbe) ?? .blue
                    MapPolygon(coordinates: gebiet.punkte.map(\.koordinate))
                        .foregroundStyle(farbe.opacity(0.15))
                        .stroke(farbe, lineWidth: 2)
                    if let mitte = mitte(gebiet.punkte) {
                        Annotation("", coordinate: mitte) {
                            VStack(spacing: 0) {
                                Text(gebiet.name ?? "").font(.caption.bold())
                                if let abdeckung = gebiet.abdeckungProzent {
                                    Text("\(Int(abdeckung.rounded())) %").font(.caption2)
                                }
                            }
                            .padding(4)
                            .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 6))
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
        .navigationTitle("Lagekarte")
        .navigationBarTitleDisplayMode(.inline)
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
