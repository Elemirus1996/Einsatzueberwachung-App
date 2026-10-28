import CoreLocation
import UIKit

/// Liefert Positionen, solange sie gestartet ist. `beenden()` schaltet den Ortungsdienst wirklich ab.
@MainActor
protocol OrtungsQuelle: AnyObject {
    var beiPosition: ((Position) -> Void)? { get set }
    func starten(intervall: TimeInterval)
    func beenden()
}

/// Ortung über Core Location mit Berechtigung „Beim Verwenden“.
/// Im Hintergrund läuft sie weiter (blaue Statusanzeige), solange sie vorher im Vordergrund gestartet wurde.
@MainActor
final class CoreLocationQuelle: NSObject, OrtungsQuelle, CLLocationManagerDelegate {
    var beiPosition: ((Position) -> Void)?
    private(set) var berechtigung: CLAuthorizationStatus

    private let manager = CLLocationManager()
    private var hintergrund: CLBackgroundActivitySession?
    private var aktiv = false

    override init() {
        berechtigung = manager.authorizationStatus
        super.init()
        manager.delegate = self
        manager.activityType = .fitness
        // Akku: 10 m reichen für den Laufweg; ohne Bewegung kommen keine Positionen (Takt dafür in TeamOrtung)
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 5
        // Automatisches Pausieren aus: Danach startet iOS die Ortung im Hintergrund nicht wieder,
        // und Hundeführer stehen während der Suche oft eine Weile still.
        manager.pausesLocationUpdatesAutomatically = false
    }

    func starten(intervall: TimeInterval) {
        guard !aktiv else { return }
        aktiv = true
        UIDevice.current.isBatteryMonitoringEnabled = true
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
        manager.allowsBackgroundLocationUpdates = true
        manager.showsBackgroundLocationIndicator = true
        hintergrund = CLBackgroundActivitySession()
        manager.startUpdatingLocation()
    }

    func beenden() {
        guard aktiv else { return }
        aktiv = false
        manager.stopUpdatingLocation()
        manager.allowsBackgroundLocationUpdates = false
        hintergrund?.invalidate()
        hintergrund = nil
        UIDevice.current.isBatteryMonitoringEnabled = false
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        let positionen = locations
            .filter { $0.horizontalAccuracy >= 0 }
            .map { ort in
                Position(lat: ort.coordinate.latitude, lng: ort.coordinate.longitude,
                         accuracy: ort.horizontalAccuracy,
                         timestamp: Int64((ort.timestamp.timeIntervalSince1970 * 1000).rounded()),
                         battery: nil)
            }
        MainActor.assumeIsolated {
            guard aktiv else { return }
            let akku = UIDevice.current.batteryLevel
            for var position in positionen {
                position.battery = akku >= 0 ? Int((akku * 100).rounded()) : nil
                beiPosition?(position)
            }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        MainActor.assumeIsolated { berechtigung = status }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        // Kurzzeitige Fehler (kein Signal) ignorieren, Core Location versucht es weiter.
    }
}
