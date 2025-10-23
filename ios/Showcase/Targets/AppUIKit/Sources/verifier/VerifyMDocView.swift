import CoreBluetooth
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct VerifyMDoc: Hashable {
    var checkAgeOver18: Bool = false
}

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
        "age_over_21": false
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

let ageOver18Elements = [
    "org.iso.18013.5.1": [
        "age_over_18": false
    ]
]

public struct VerifyMDocView: View {
    @Binding var path: NavigationPath
    var checkAgeOver18: Bool = false

    @State private var scanned: String?

    var trustedCertificates = TrustedCertificatesDataStore.shared
        .getAllCertificates()

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
                requestedItems: !checkAgeOver18
                    ? defaultElements : ageOver18Elements,
                trustAnchorRegistry: trustedCertificates.map { $0.content },
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
                trustAnchorRegistry: trustAnchorRegistry,
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
            case .initializing:
                LoadingView(
                    loadingText: "Initializing...",
                    cancelButtonLabel: "Cancel",
                    onCancel: { self.cancel() }
                )
            case .connecting:
                LoadingView(
                    loadingText: "Waiting for holder...",
                    cancelButtonLabel: "Cancel",
                    onCancel: { self.cancel() }
                )
            case .connected:
                LoadingView(
                    loadingText: "Waiting for mdoc...",
                    cancelButtonLabel: "Cancel",
                    onCancel: { self.cancel() }
                )
            case .mdocDisconnected:
                ErrorView(
                    errorTitle: "The mdoc disconnected unexpectedly",
                    errorDetails: "",
                    onClose: { self.cancel() }
                )
            case .error:
                ErrorView(
                    errorTitle: "Error Verifying",
                    errorDetails: "",
                    onClose: { self.cancel() }
                )
            case .sendingRequest(let bytesSoFar, let outOfTotalBytes):
                LoadingView(
                    loadingText:
                        "Sending request... \(bytesSoFar / outOfTotalBytes * 100)%",
                    cancelButtonLabel: "Cancel",
                    onCancel: { self.cancel() }
                )
            case .sentRequest:
                LoadingView(
                    loadingText:
                        "Waiting for response...",
                    cancelButtonLabel: "Cancel",
                    onCancel: { self.cancel() }
                )
            case .receivingResponse:
                LoadingView(
                    loadingText:
                        "Receiving response...",
                    cancelButtonLabel: "Cancel",
                    onCancel: { self.cancel() }
                )
            case .receivedResponse(let r):
                VerifierMdocResultView(
                    result: r.data.verifiedResponse,
                    issuerAuthenticationStatus: r.data
                        .issuerAuthentication,
                    deviceAuthenticationStatus: r.data
                        .deviceAuthentication,
                    responseProcessingErrors: r.data.errors,
                    onClose: {
                        onCancel()
                    },
                    logVerification: { title, issuer, status in
                        _ = VerificationActivityLogDataStore.shared.insert(
                            credentialTitle: title,
                            issuer: issuer,
                            status: status,
                            verificationDateTime: Date(),
                            additionalInformation: ""
                        )
                    }
                )
            case .action(.authorizeBluetoothForApp):
                authorizeBluetooth
            case .action(.turnOnBluetooth):
                turnOnBluetoothView
            }
        }
        .padding(.all, 30)
        .navigationBarBackButtonHidden(true)
    }
    
    @ViewBuilder
    var authorizeBluetooth: some View {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            Button("Authorize bluetooth to continue") {
                UIApplication.shared.open(url)
            }
            .padding(10)
            .buttonStyle(.bordered)
            .tint(.blue)
            .foregroundColor(.blue)
        } else {
            Text("Open iPhone settings and allow bluetooth permissions for this app to continue.")
        }
    }
    
    @ViewBuilder
    var turnOnBluetoothView: some View {
        Text("Turn on bluetooth to continue.")
    }

    func cancel() {
        self.delegate.cancel()
        self.onCancel()
    }
}

class MDocScanViewDelegate: ObservableObject & MdocProximityReader.Delegate {
    @Published var state: MdocProximityReader.State = .initializing
    private var mdocReader: MdocProximityReader? = nil

    init(
        uri: String,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]?
    ) {
        self.mdocReader = MdocProximityReader(
            fromHolderQrCode: uri,
            delegate: self,
            requestedItems: requestedItems,
            trustAnchorRegistry: trustAnchorRegistry,
            l2capUsage: .disableL2CAP
        )
    }
    
    func reset() {
        self.mdocReader?.reset()
    }

    func cancel() {
        self.mdocReader?.disconnect()
    }
    
    func connectionState(changedTo: SpruceIDMobileSdk.MdocProximityReader.State) {
        DispatchQueue.main.async {
            self.state = changedTo
        }
    }
}
