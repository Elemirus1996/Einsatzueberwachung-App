import SwiftUI
import UIKit

/// Farben und Schrift der Einsatzleitung (Gestaltung Punkt 9, Vorbild ruhiger Material-Stil):
/// Kopfleiste in Staffelrot, hellgraue Fläche, weiße Karten mit runden Ecken, weiche Status-Chips,
/// normale Schrift mit gleich breiten Ziffern für Zeiten. Hell und dunkel; Akzent ist das Rot der Staffel (Vorgabe NRW).
enum ELStil {
    /// Kopfleiste (Name aus der /el-Zeit beibehalten): Staffelrot
    static let anthrazit = Color(hell: 0xA32D2D, dunkel: 0x6E1F21)
    static let aufAnthrazit = Color(hell: 0xFFFFFF, dunkel: 0xF5EEEE)
    static let papier = Color(hell: 0xF2F2F4, dunkel: 0x000000)
    static let platte = Color(hell: 0xFFFFFF, dunkel: 0x1C1C1E)
    static let platteTief = Color(hell: 0xF2F2F4, dunkel: 0x2C2C2E)
    static let inschrift = Color(hell: 0x1C1C1E, dunkel: 0xF2F2F4)
    static let vermerk = Color(hell: 0x5C5C63, dunkel: 0xA5A5AD)
    static let randnotiz = Color(hell: 0x8E8E93, dunkel: 0x6E6E75)
    static let rahmen = Color(hell: 0x000000, dunkel: 0xFFFFFF, deckkraftHell: 0.08, deckkraftDunkel: 0.12)
    static let rahmenStark = Color(hell: 0x000000, dunkel: 0xFFFFFF, deckkraftHell: 0.16, deckkraftDunkel: 0.22)

    static let aktiv = Color(hell: 0x1E7A3C, dunkel: 0x5DC88A)
    static let pause = Color(hell: 0x5A6470, dunkel: 0x788292)
    static let bereit = Color(hell: 0x4E4A46, dunkel: 0xB9AC98)
    /// Aktionsfarbe: Staffelrot, wie `--funk-aktion`
    static let aktion = Color(hell: 0x7E2B2D, dunkel: 0xA63C39)
    static let signalRot = Color(hell: 0xE3000F, dunkel: 0xFF4D57)
    static let warnung1 = Color(hell: 0xD97706, dunkel: 0xF59E0B)
    static let warnung2 = Color(hell: 0xE12431, dunkel: 0xFF5A63)
    static let kreide = Color(hell: 0xC8A850, dunkel: 0xE0D29A)

    static let radius: CGFloat = 12

    /// Name aus der /el-Zeit: inzwischen normale Schrift mit gleich breiten Ziffern (Zeiten springen nicht)
    static func mono(_ groesse: CGFloat, _ gewicht: Font.Weight = .medium) -> Font {
        .system(size: groesse, weight: gewicht).monospacedDigit()
    }

    /// Leichter Schatten unter Karten
    static let schatten = Color.black.opacity(0.07)
}

/// Überschrift eines Abschnitts: „Aktiv 2“
struct ELAbschnittTitel: View {
    let titel: String
    var anzahl: Int?

    var body: some View {
        HStack(spacing: 6) {
            Text(titel).foregroundStyle(ELStil.inschrift)
            if let anzahl {
                Text("\(anzahl)")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(ELStil.vermerk)
                    .padding(.horizontal, 7)
                    .padding(.vertical, 1)
                    .background(ELStil.platteTief, in: Capsule())
            }
        }
        .font(.headline)
        .accessibilityElement(children: .combine)
    }
}

/// Weicher Status-Chip wie „Aktiv“ oder „Übung“ (Name aus der /el-Zeit)
struct ELStempel: View {
    let text: String
    let farbe: Color
    var gefuellt = false

    var body: some View {
        // Alte Versalien („AKTIV“) als „Aktiv“, sonst unverändert
        Text(text == text.uppercased() && text.count > 1 ? text.prefix(1) + text.dropFirst().lowercased() : text)
            .font(.caption.weight(.semibold))
            .padding(.horizontal, 9)
            .padding(.vertical, 3)
            .foregroundStyle(gefuellt ? .white : farbe)
            .background(gefuellt ? farbe : farbe.opacity(0.14), in: Capsule())
    }
}

/// Status aller Teams als Chips, auf jedem Reiter gleich: „2 suchen · 1 Pause · 1 bereit“, Warnungen, Verbindung
struct ELStatusChips: View {
    let teams: [ELLage.Team]
    var live: Bool? = nil

    var body: some View {
        let sucht = teams.filter { $0.suche.status == .laeuft }.count
        let pause = teams.filter { $0.suche.status == .kurzpause }.count
        let bereit = teams.filter { $0.suche.status == .bereit }.count
        let warnung = teams.filter { ($0.warnstufe ?? 0) > 0 }
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                ELStempel(text: "\(sucht) \(sucht == 1 ? "sucht" : "suchen")", farbe: ELStil.aktiv)
                if pause > 0 { ELStempel(text: "\(pause) Pause", farbe: ELStil.pause) }
                if bereit > 0 { ELStempel(text: "\(bereit) bereit", farbe: ELStil.bereit) }
                ForEach(warnung) { team in
                    Label(team.teamName, systemImage: "exclamationmark.triangle.fill")
                        .font(.caption.weight(.semibold))
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .foregroundStyle((team.warnstufe ?? 0) >= 2 ? ELStil.warnung2 : ELStil.warnung1)
                        .background(((team.warnstufe ?? 0) >= 2 ? ELStil.warnung2 : ELStil.warnung1).opacity(0.14), in: Capsule())
                }
                if let live {
                    Spacer(minLength: 4)
                    Circle().fill(live ? ELStil.aktiv : ELStil.warnung1).frame(width: 8, height: 8)
                        .accessibilityLabel(live ? "Live verbunden" : "Keine Live-Verbindung")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
        }
        .background(ELStil.platte)
        .overlay(alignment: .bottom) { Rectangle().fill(ELStil.rahmen).frame(height: 1) }
    }
}

/// Kopfleiste der übrigen Reiter: Titel und Uhr auf Staffelrot, darunter die Status-Chips
struct ELReiterKopf: View {
    let titel: String
    let lage: ELLage?

    var body: some View {
        VStack(spacing: 0) {
            TimelineView(.periodic(from: .now, by: 1)) { kontext in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(titel).font(.title3.bold())
                    if lage?.einsatz.uebung == true { ELStempel(text: "Übung", farbe: .white) }
                    Spacer(minLength: 8)
                    if let beginn = lage?.einsatz.beginn, lage?.einsatz.aktiv == true {
                        Text(TeamAnsichtView.dauer(max(0, kontext.date.timeIntervalSince(beginn))))
                            .font(ELStil.mono(13, .medium)).opacity(0.8)
                    }
                    Text(kontext.date, format: .dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
                        .font(ELStil.mono(17, .semibold))
                }
                .foregroundStyle(ELStil.aufAnthrazit)
                .padding(.horizontal, 16)
                .padding(.top, 6)
                .padding(.bottom, 12)
                .background(ELStil.anthrazit.ignoresSafeArea(edges: .top))
            }
            if let lage, lage.einsatz.aktiv { ELStatusChips(teams: lage.teams) }
        }
    }
}

/// Weiße Karte mit runden Ecken und leichtem Schatten
extension View {
    func elKarte(rand: Color? = nil, breite: CGFloat = 1.5) -> some View {
        self
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(ELStil.platte, in: RoundedRectangle(cornerRadius: ELStil.radius))
            .overlay { if let rand { RoundedRectangle(cornerRadius: ELStil.radius).stroke(rand, lineWidth: breite) } }
            .shadow(color: ELStil.schatten, radius: 4, y: 1)
    }
}

/// Bedienknopf an der Teamkarte, groß genug für Handschuhe und Regen
struct ELTaste: View {
    let symbol: String
    let titel: String
    var farbe: Color = ELStil.inschrift
    let aktion: () -> Void

    var body: some View {
        Button(action: aktion) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(farbe)
                .frame(width: 52, height: 48)
                .background(ELStil.platteTief, in: RoundedRectangle(cornerRadius: 10))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(titel)
    }
}

extension Color {
    /// Farbe, die mit hellem bzw. dunklem Erscheinungsbild wechselt
    init(hell: UInt32, dunkel: UInt32, deckkraftHell: CGFloat = 1, deckkraftDunkel: CGFloat = 1) {
        self.init(uiColor: UIColor { merkmale in
            merkmale.userInterfaceStyle == .dark
                ? UIColor(hex: dunkel, alpha: deckkraftDunkel)
                : UIColor(hex: hell, alpha: deckkraftHell)
        })
    }
}

private extension UIColor {
    convenience init(hex: UInt32, alpha: CGFloat) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: alpha)
    }
}

/// Darf die Einsatzleitung auf diesem Handy steuern (Rolle nicht `lesen`, APP_API EL Abschnitt 4a)
private struct ELDarfSteuernKey: EnvironmentKey { static let defaultValue = true }

extension EnvironmentValues {
    var elDarfSteuern: Bool {
        get { self[ELDarfSteuernKey.self] }
        set { self[ELDarfSteuernKey.self] = newValue }
    }
}
