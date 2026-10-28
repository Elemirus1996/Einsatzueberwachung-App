import Foundation
import Security

/// Speichert Zugangsdaten im iOS-Schlüsselbund, nur auf diesem Gerät und auch im Hintergrund lesbar
/// (nach dem ersten Entsperren), weil die Ortung bei gesperrtem Bildschirm weiterläuft.
struct Schluesselbund: Sendable {
    let dienst: String

    init(dienst: String = Bundle.main.bundleIdentifier ?? "io.github.elemirus1996.einsatzspur") {
        self.dienst = dienst
    }

    func lesen<T: Decodable>(_ typ: T.Type, konto: String) -> T? {
        var abfrage = basis(konto)
        abfrage[kSecReturnData as String] = true
        abfrage[kSecMatchLimit as String] = kSecMatchLimitOne
        var ergebnis: CFTypeRef?
        guard SecItemCopyMatching(abfrage as CFDictionary, &ergebnis) == errSecSuccess,
              let daten = ergebnis as? Data
        else { return nil }
        return try? JSONDecoder().decode(T.self, from: daten)
    }

    @discardableResult
    func speichern<T: Encodable>(_ wert: T, konto: String) -> Bool {
        guard let daten = try? JSONEncoder().encode(wert) else { return false }
        loeschen(konto: konto)
        var eintrag = basis(konto)
        eintrag[kSecValueData as String] = daten
        eintrag[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(eintrag as CFDictionary, nil) == errSecSuccess
    }

    func loeschen(konto: String) {
        SecItemDelete(basis(konto) as CFDictionary)
    }

    private func basis(_ konto: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: dienst,
            kSecAttrAccount as String: konto,
        ]
    }
}

extension Schluesselbund {
    static let kontoTeam = "team"
}
