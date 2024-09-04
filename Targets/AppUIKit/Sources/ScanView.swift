import CoreBluetooth
import SwiftUI
import SpruceIDMobileSdk

extension String: Identifiable {
    public typealias ID = Int
    public var id: Int {
        return hash
    }
}

public struct ScanView: View {
    @State private var scanned: String?

    public var body: some View {
        let issuer_cert = """
            -----BEGIN CERTIFICATE-----
            MIIChjCCAiygAwIBAgIUPgwgeCSsRYiU8iN6KHqKED3w/AAwCgYIKoZIzj0EAwIw
            bjELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAk5ZMRswGQYDVQQKDBJTcHJ1Y2VJRCBU
            ZXN0IFJvb3QxNTAzBgNVBAMMLFNwcnVjZUlEIFRlc3QgQ2VydGlmaWNhdGUgUm9v
            dCBPSUQ0VkNJV2FsbGV0MB4XDTI0MDgzMDE0MzQyM1oXDTM0MDgyODE0MzQyM1ow
            bjELMAkGA1UEBhMCVVMxCzAJBgNVBAgMAk5ZMRswGQYDVQQKDBJTcHJ1Y2VJRCBU
            ZXN0IFJvb3QxNTAzBgNVBAMMLFNwcnVjZUlEIFRlc3QgQ2VydGlmaWNhdGUgUm9v
            dCBPSUQ0VkNJV2FsbGV0MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEPX/59+YO
            meAfOugnUqfRi8TCHD1GC/R2Xip83aNdmM9DVP7NFiURpxIgBNDmj4VZodDoiz/B
            GSO9l6Sypv1hYKOBpzCBpDAdBgNVHQ4EFgQU5xn7tyLS9unEonXA5D2Jm/9ERcAw
            EgYDVR0TAQH/BAgwBgEB/wIBADA+BgNVHR8ENzA1MDOgMaAvhi1odHRwczovL2lu
            dGVyb3BldmVudC5zcHJ1Y2VpZC5jb20vaW50ZXJvcC5jcmwwDgYDVR0PAQH/BAQD
            AgEGMB8GA1UdEgQYMBaBFGludGVyb3BAc3BydWNlaWQuY29tMAoGCCqGSM49BAMC
            A0gAMEUCIQCqFHqCM5NTgkiSJbOeFvGKJKBbordnzOVzb7UrFGQL5gIgKgh9gMKh
            VixVit4VpJnYkcJXhQpTba/kWPCCfJh06kU=
            -----END CERTIFICATE-----
            """
        QRCodeScanner(onRead: onRead, onCancel: onCancel)
            .sheet(item: $scanned, onDismiss: onCancel) {
                scanned in
                MDocReaderView(uri: scanned, requestedItems: ["org.iso.18013.5.1": ["given_name": true]], trustAnchorRegistry: [issuer_cert])
            }
    }

    func onCancel() {
        self.scanned = nil
    }
    
    func onRead(code: String) {
        self.scanned = code
        
    }
}

public struct MDocReaderView: View {
    @StateObject var delegate: MDocScanViewDelegate
    @Environment(\.presentationMode) var presentationMode
    
    init(uri: String, requestedItems: [String: [String: Bool]], trustAnchorRegistry: [String]?) {
        self._delegate = StateObject(wrappedValue: MDocScanViewDelegate(uri: uri, requestedItems: requestedItems, trustAnchorRegistry: trustAnchorRegistry))
    }
    
    public var body: some View {
        VStack {
            switch self.delegate.state {
            case .advertizing:
                Text("Waiting for holder...")
            case .connected:
                Text("Connected to holder!")
            case .error(let error):
                let message = switch error {
                case .bluetooth(let central):
                    switch central.state {
                            case .poweredOff:
                                "Is Powered Off."
                            case .unsupported:
                                "Is Unsupported."
                            case .unauthorized:
                                switch CBManager.authorization {
                                case .denied:
                                    "Authorization denied"
                                case .restricted:
                                    "Authorization restricted"
                                case .allowedAlways:
                                    "Authorized"
                                case .notDetermined:
                                    "Authorization not determined"
                                @unknown default:
                                    "Unknown authorization error"
                                }
                            case .unknown:
                                "Unknown"
                            case .resetting:
                                "Resetting"
                    case .poweredOn:
                       "Impossible"
                    @unknown default:
                                "Error"
                            }
                case .server(let error):
                    error
                case .generic(let error):
                    error
                }
                Text(message)
            case .downloadProgress(let index):
                ProgressView(label: {
                    Text("Downloading... \(index) chunks received so far.").font(.caption).foregroundStyle(.secondary)
                }).progressViewStyle(.circular)
            case .success(let items):
                Text("Success! Received: \(items)")
            }
            Button("Cancel") {
                self.cancel()
            }.padding(10).buttonStyle(.bordered).tint(.red).foregroundColor(.red)
        }
    }
    
    func cancel() {
        self.delegate.cancel()
        presentationMode.wrappedValue.dismiss()
    }
}

class MDocScanViewDelegate: ObservableObject {
    @Published var state: BLEReaderSessionState = .advertizing
    private var mdocReader: MDocReader?
    
    init(uri: String, requestedItems: [String: [String: Bool]], trustAnchorRegistry: [String]?) {
        self.mdocReader = MDocReader(callback: self, uri: uri, requestedItems: requestedItems, trustAnchorRegistry: trustAnchorRegistry)
    }
    
    func cancel() {
        self.mdocReader?.cancel()
    }
}

extension MDocScanViewDelegate: BLEReaderSessionStateDelegate {
    public func update(state: BLEReaderSessionState) {
        self.state = state
    }
}
