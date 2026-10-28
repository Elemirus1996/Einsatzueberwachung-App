import SwiftUI
import VisionKit

/// QR-Scanner von Apple (VisionKit). Meldet nur Team-QR-Codes, alles andere wird übergangen.
struct QRScannerView: UIViewControllerRepresentable {
    let gefunden: (TeamQRCode) -> Void
    let fremderCode: () -> Void

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
                if let qr = TeamQRCode(text) {
                    fertig = true
                    scanner.stopScanning()
                    ansicht.gefunden(qr)
                    return
                }
                ansicht.fremderCode()
            }
        }
    }
}
