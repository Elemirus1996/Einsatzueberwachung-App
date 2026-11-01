import SwiftUI
import UIKit

/// Farben und Schrift der Einsatzleitung wie `/el` im Browser (Server `EinsatzLeitung.razor.css`, „Funkgerät“-Stil):
/// Anthrazit-Kopfleiste, papierfarbene Fläche, gedämpfte Signalfarben, Monospace für Zeiten und Beschriftungen.
/// Hell und dunkel wie im Server; die Akzentfarbe ist das Rot der Staffel (Vorgabe NRW).
enum ELStil {
    static let anthrazit = Color(hell: 0x14181F, dunkel: 0x050810)
    static let aufAnthrazit = Color(hell: 0xF0E9D8, dunkel: 0xE6DFCC)
    static let papier = Color(hell: 0xE6E3DC, dunkel: 0x0A0D12)
    static let platte = Color(hell: 0xFAF7F0, dunkel: 0x141A23)
    static let platteTief = Color(hell: 0xEDE9DF, dunkel: 0x0F141C)
    static let inschrift = Color(hell: 0x14181F, dunkel: 0xE6DFCC)
    static let vermerk = Color(hell: 0x4A525E, dunkel: 0x98A0AD)
    static let randnotiz = Color(hell: 0x7D8590, dunkel: 0x5A6470)
    static let rahmen = Color(hell: 0x14181F, dunkel: 0xE8DCC3, deckkraftHell: 0.22, deckkraftDunkel: 0.10)
    static let rahmenStark = Color(hell: 0x14181F, dunkel: 0xE8DCC3, deckkraftHell: 0.40, deckkraftDunkel: 0.20)

    static let aktiv = Color(hell: 0x2D7A4D, dunkel: 0x5DC88A)
    static let pause = Color(hell: 0x5A6470, dunkel: 0x788292)
    static let bereit = Color(hell: 0x4E4A46, dunkel: 0xB9AC98)
    /// Aktionsfarbe: Staffelrot, wie `--funk-aktion`
    static let aktion = Color(hell: 0x7E2B2D, dunkel: 0xA63C39)
    static let signalRot = Color(hell: 0xE3000F, dunkel: 0xFF4D57)
    static let warnung1 = Color(hell: 0xD97706, dunkel: 0xF59E0B)
    static let warnung2 = Color(hell: 0xE12431, dunkel: 0xFF5A63)
    static let kreide = Color(hell: 0xC8A850, dunkel: 0xE0D29A)

    static let radius: CGFloat = 6

    /// Monospace wie JetBrains Mono im Browser (auf iOS SF Mono)
    static func mono(_ groesse: CGFloat, _ gewicht: Font.Weight = .medium) -> Font {
        .system(size: groesse, weight: gewicht, design: .monospaced)
    }
}

/// Überschrift eines Abschnitts: „AKTIV (2)“ in gesperrten Großbuchstaben mit Trennlinie
struct ELAbschnittTitel: View {
    let titel: String
    var anzahl: Int?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Text(titel.uppercased()).foregroundStyle(ELStil.vermerk)
                if let anzahl { Text("(\(anzahl))").foregroundStyle(ELStil.randnotiz) }
            }
            .font(ELStil.mono(13, .semibold))
            .tracking(2.5)
            Rectangle().fill(ELStil.rahmen).frame(height: 1)
        }
        .accessibilityElement(children: .combine)
    }
}

/// Umrandete Kennung wie „AKTIV“ oder „ÜBUNG“
struct ELStempel: View {
    let text: String
    let farbe: Color
    var gefuellt = false

    var body: some View {
        Text(text.uppercased())
            .font(ELStil.mono(11, .bold))
            .tracking(1.5)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .foregroundStyle(gefuellt ? ELStil.aufAnthrazit : farbe)
            .background(gefuellt ? farbe : farbe.opacity(0.08), in: RoundedRectangle(cornerRadius: 2))
            .overlay(RoundedRectangle(cornerRadius: 2).stroke(farbe.opacity(gefuellt ? 0 : 0.8), lineWidth: 1))
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
                .background(ELStil.platteTief, in: RoundedRectangle(cornerRadius: 3))
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(ELStil.rahmen, lineWidth: 1))
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
