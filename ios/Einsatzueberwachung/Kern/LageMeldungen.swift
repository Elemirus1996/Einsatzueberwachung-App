import Foundation
import UIKit
import UserNotifications

/// Eine lokale Mitteilung über eine Änderung der Lage (kein APNs, die App ist durch die Grob-Ortung wach).
/// Texte kurz, ohne Namen von Vermissten und ohne Ortsangaben (Entscheidung 29.10.2026).
struct LageMeldung: Equatable, Sendable {
    var titel: String
    var text: String?

    static let einsatzBeendet = LageMeldung(titel: "Einsatz beendet – Ortung aus")

    /// Was sich zwischen zwei Lagen geändert hat. Je Zustandsänderung höchstens eine Meldung;
    /// kommt dieselbe Änderung über /lage und über ein Ereignis, ist sie beim zweiten Mal schon in `alt`.
    static func vergleichen(alt: TeamLage, neu: TeamLage) -> [LageMeldung] {
        var meldungen: [LageMeldung] = []
        if alt.suche.status != neu.suche.status {
            switch neu.suche.status {
            case .laeuft: meldungen.append(LageMeldung(titel: "Suche gestartet – Ortung läuft"))
            case .kurzpause: meldungen.append(LageMeldung(titel: "Suche pausiert"))
            case .beendet: meldungen.append(LageMeldung(titel: "Suche beendet"))
            case .bereit: break
            }
        }
        if let gebiet = neu.suchgebiet, gebiet.id != alt.suchgebiet?.id {
            meldungen.append(LageMeldung(titel: "Neues Suchgebiet: \(gebiet.name ?? "ohne Namen")"))
        }
        if let halsband = neu.halsband, halsband.id != nil, halsband.id != alt.halsband?.id {
            meldungen.append(LageMeldung(titel: "Halsband zugewiesen: \(halsband.name ?? halsband.id ?? "")"))
        }
        let bekannt = Set(alt.mitteilungen.map(\.id))
        for mitteilung in neu.mitteilungen where !bekannt.contains(mitteilung.id) {
            meldungen.append(LageMeldung(titel: "Mitteilung der Einsatzleitung", text: mitteilung.text))
        }
        return meldungen
    }
}

/// Zeigt Meldungen als lokale Mitteilung, aber nur, wenn die App nicht im Vordergrund ist.
@MainActor
protocol Mitteiler: AnyObject {
    func erlaubnisErfragen()
    func senden(_ meldung: LageMeldung)
}

@MainActor
final class LokaleMitteilungen: Mitteiler {
    private let zentrale = UNUserNotificationCenter.current()

    /// Beim ersten Anmelden. Ablehnen ändert nichts an Ortung oder Anzeige.
    func erlaubnisErfragen() {
        zentrale.requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    func senden(_ meldung: LageMeldung) {
        guard UIApplication.shared.applicationState != .active else { return }
        let inhalt = UNMutableNotificationContent()
        inhalt.title = meldung.titel
        if let text = meldung.text { inhalt.body = text }
        inhalt.sound = .default
        inhalt.threadIdentifier = "einsatz"
        // Tippen öffnet die App; angemeldet zeigt sie direkt die Team-Ansicht
        zentrale.add(UNNotificationRequest(identifier: UUID().uuidString, content: inhalt, trigger: nil))
    }
}
