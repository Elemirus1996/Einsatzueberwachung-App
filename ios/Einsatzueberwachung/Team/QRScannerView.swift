import SwiftUI
import VisionKit

/// QR-Scanner von Apple (VisionKit). `gefunden` prüft den Text und gibt `true` zurück, wenn der Code passt (Team- bzw. EL-Code);
/// dann endet der Scan. Andere Codes werden übergangen.
struct QRScannerView: UIViewControllerRepresentable {
    let gefunden: @MainActor (String) -> Bool

    static var verfuegbar: Bool {
        DataScannerViewController.isSupported && DataScannerViewController.isAvailable
    }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            isHighlightingEnabled: true)
        scanner.delegate = context.coordinator
        return scanner
    }

    func updateUIViewController(_ scanner: DataScannerViewController, context: Context) {
        if !scanner.isScanning { try? scanner.startScanning() }
    }

    static func dismantleUIViewController(_ scanner: DataScannerViewController, coordinator: Koordinator) {
        scanner.stopScanning()
    }

    func makeCoordinator() -> Koordinator { Koordinator(self) }

    @MainActor
    final class Koordinator: NSObject, DataScannerViewControllerDelegate {
        private let ansicht: QRScannerView
        private var fertig = false

        init(_ ansicht: QRScannerView) { self.ansicht = ansicht }

        func dataScanner(_ scanner: DataScannerViewController, didAdd items: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !fertig else { return }
            for item in items {
                guard case .barcode(let code) = item, let text = code.payloadStringValue else { continue }
                if ansicht.gefunden(text) {
                    fertig = true
                    scanner.stopScanning()
                    return
                }
            }
        }
    }
}
