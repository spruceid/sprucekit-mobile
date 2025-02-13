import CoreBluetooth
import SwiftUI
import SpruceIDMobileSdk

struct VerifyMDoc: Hashable {}

let trustAnchorCerts = [
            """
-----BEGIN CERTIFICATE-----
MIIB0zCCAXqgAwIBAgIJANVHM3D1VFaxMAoGCCqGSM49BAMCMCoxCzAJBgNVBAYT
AlVTMRswGQYDVQQDDBJTcHJ1Y2VJRCBUZXN0IElBQ0EwHhcNMjUwMTA2MTA0MDUy
WhcNMzAwMTA1MTA0MDUyWjAqMQswCQYDVQQGEwJVUzEbMBkGA1UEAwwSU3BydWNl
SUQgVGVzdCBJQUNBMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEmAZFZftRxWrl
Iuf1ZY4DW7QfAfTu36RumpvYZnKVFUNmyrNxGrtQlp2Tbit+9lUzjBjF9R8nvdid
mAHOMg3zg6OBiDCBhTAdBgNVHQ4EFgQUJpZofWBt6ci5UVfOl8E9odYu8lcwDgYD
VR0PAQH/BAQDAgEGMBIGA1UdEwEB/wQIMAYBAf8CAQAwGwYDVR0SBBQwEoEQdGVz
dEBleGFtcGxlLmNvbTAjBgNVHR8EHDAaMBigFqAUhhJodHRwOi8vZXhhbXBsZS5j
b20wCgYIKoZIzj0EAwIDRwAwRAIgJFSMgE64Oiq7wdnWA3vuEuKsG0xhqW32HdjM
LNiJpAMCIG82C+Kx875VNhx4hwfqReTRuFvZOTmFDNgKN0O/1+lI
-----END CERTIFICATE-----
"""
]

let defaultElements = [
    "org.iso.18013.5.1": [
        // Mandatory
        "family_name": false,
        "given_name": false,
        "birth_date": false,
        "issue_date": false,
        "expiry_date": false,
        "issuing_country": false,
        "issuing_authority": false,
        "document_number": false,
        "portrait": false,
        "driving_privileges": false,
        // Optional
        "middle_name": false,
        "birth_place": false,
        "resident_address": false,
        "height": false,
        "weight": false,
        "eye_colour": false,
        "hair_colour": false,
        "organ_donor": false,
        "sex": false,
        "nationality": false,
        "place_of_issue": false,
        "signature": false,
        "phone_number": false,
        "email_address": false,
        "emergency_contact": false,
        "vehicle_class": false,
        "endorsements": false,
        "restrictions": false,
        "barcode_data": false,
        "card_design_issuer": false,
        "card_expiry_date": false,
        "time_of_issue": false,
        "time_of_expiry": false,
        "portrait_capture_date": false,
        "signature_capture_date": false,
        "document_discriminator": false,
        "audit_information": false,
        "compliance_type": false,
        "permit_identifier": false,
        "veteran_indicator": false,
        "resident_city": false,
        "resident_postal_code": false,
        "resident_state": false,
        "issuing_jurisdiction": false,
        "age_over_18": false,
        "age_over_21": false,
    ],
    "org.iso.18013.5.1.aamva": [
        "DHS_compliance": false,
        "DHS_temporary_lawful_status": false,
        "real_id": false,
        "jurisdiction_version": false,
        "jurisdiction_id": false,
        "organ_donor": false,
        "domestic_driving_privileges": false,
        "veteran": false,
        "sex": false,
        "name_suffix": false
    ]
]

public struct VerifyMDocView: View {
    @Binding var path: NavigationPath
    
    @State private var scanned: String?
    
    public var body: some View {
        if scanned == nil {
            ScanningComponent(
                path: $path,
                scanningParams: Scanning(
                    scanningType: .qrcode,
                    onCancel: onCancel,
                    onRead: { code in
                        self.scanned = code
                    }
                )
            )
        } else {
            MDocReaderView(
                uri: scanned!,
                requestedItems: defaultElements,
                trustAnchorRegistry: trustAnchorCerts,
                onCancel: onCancel,
                path: $path
            )
        }
    }
    
    func onCancel() {
        self.scanned = nil
        path.removeLast()
    }
}

public struct MDocReaderView: View {
    @StateObject var delegate: MDocScanViewDelegate
    @Binding var path: NavigationPath
    var onCancel: () -> Void
    
    init(
        uri: String,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]?,
        onCancel: @escaping () -> Void,
        path: Binding<NavigationPath>
    ) {
        self._delegate = StateObject(
            wrappedValue: MDocScanViewDelegate(
                uri: uri,
                requestedItems: requestedItems,
                trustAnchorRegistry: trustAnchorRegistry
            )
        )
        self.onCancel = onCancel
        self._path = path
    }
    
    @ViewBuilder
    var cancelButton: some View {
        Button("Cancel") {
            self.cancel()
        }
        .padding(10)
        .buttonStyle(.bordered)
        .tint(.red)
        .foregroundColor(.red)
    }
    
    public var body: some View {
        VStack {
            switch self.delegate.state {
            case .advertizing:
                Text("Waiting for holder...")
                cancelButton
            case .connected:
                Text("Connected to holder!")
                cancelButton
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
                cancelButton
            case .downloadProgress(let index):
                ProgressView(label: {
                    Text("Downloading... \(index) chunks received so far.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                })
                .progressViewStyle(.circular)
                cancelButton
            case .success(let items):
                VerifierSuccessView(
                    path: $path,
                    success: true,
                    content: Text("\(items["org.iso.18013.5.1"]!)")
                        .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                        .foregroundStyle(Color("ColorStone950"))
                        .padding(.top, 20)
                )
            }
        }
        .padding(.all, 30)
        .navigationBarBackButtonHidden(true)
    }
    
    func cancel() {
        self.delegate.cancel()
        self.onCancel()
    }
}

class MDocScanViewDelegate: ObservableObject {
    @Published var state: BLEReaderSessionState = .advertizing
    private var mdocReader: MDocReader?
    
    init(
        uri: String,
        requestedItems: [String: [String: Bool]], 
        trustAnchorRegistry: [String]?
    ) {
        self.mdocReader = MDocReader(
            callback: self,
            uri: uri,
            requestedItems: requestedItems,
            trustAnchorRegistry: trustAnchorRegistry
        )
    }
    
    func cancel() {
        self.mdocReader?.cancel()
    }
}

extension MDocScanViewDelegate: BLEReaderSessionStateDelegate {
    public func update(state: BLEReaderSessionState) {
        // TODO: add logs when refactor the verifier
        self.state = state
    }
}
