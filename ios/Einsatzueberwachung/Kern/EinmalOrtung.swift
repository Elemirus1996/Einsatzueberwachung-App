import CoreLocation

/// Genaue Position für „Punkt setzen“ (APP_API.md Abschnitt 13): nur auf Knopfdruck, eine einzelne Ortung.
/// Unabhängig von der laufenden Ortung des Teams, damit auch in der Pause (nur Grob-Ortung) ein genauer Punkt entsteht.
@MainActor
final class EinmalOrtung: NSObject, CLLocationManagerDelegate {
    struct Ergebnis: Equatable, Sendable {
        var lat: Double
        var lng: Double
        var genauigkeit: Double?
        var zeit: Date
    }

    enum Fehler: Error { case keineFreigabe, keinePosition }

    /// Höchstens so alt darf die Position sein
    nonisolated static let maxAlter: TimeInterval = 60

    private let manager = CLLocationManager()
    private var warten: CheckedContinuation<CLLocation, Error>?

    var freigegeben: Bool {
        [.authorizedAlways, .authorizedWhenInUse].contains(manager.authorizationStatus)
    }

    func position() async throws -> Ergebnis {
        guard freigegeben else { throw Fehler.keineFreigabe }
        if let ort = manager.location, Self.frisch(ort, jetzt: Date()) { return Self.ergebnis(ort) }
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        let ort = try await withCheckedThrowingContinuation { fortsetzung in
            warten?.resume(throwing: Fehler.keinePosition)
            warten = fortsetzung
            manager.requestLocation()
        }
        return Self.ergebnis(ort)
    }

    nonisolated static func frisch(_ ort: CLLocation, jetzt: Date) -> Bool {
        ort.horizontalAccuracy >= 0 && jetzt.timeIntervalSince(ort.timestamp) <= maxAlter
    }

    nonisolated static func ergebnis(_ ort: CLLocation) -> Ergebnis {
        Ergebnis(lat: ort.coordinate.latitude, lng: ort.coordinate.longitude,
                 genauigkeit: ort.horizontalAccuracy >= 0 ? ort.horizontalAccuracy : nil, zeit: ort.timestamp)
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let ort = locations.last else { return }
        MainActor.assumeIsolated {
            warten?.resume(returning: ort)
            warten = nil
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        MainActor.assumeIsolated {
            warten?.resume(throwing: Fehler.keinePosition)
            warten = nil
        }
    }
}
