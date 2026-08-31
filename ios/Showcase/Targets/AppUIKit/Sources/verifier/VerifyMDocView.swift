import Combine
import CoreBluetooth
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct VerifyMDoc: Hashable {
    var profile: MDocVerificationProfile = .mdl
}

/// What the mdoc reader asks for: which document type, and which data elements.
public enum MDocVerificationProfile: Hashable {
    /// ISO 18013-5 mobile driver's license.
    case mdl
    /// ISO 18013-5 mobile driver's license, requesting only `age_over_18`.
    case mdlAgeOver18
    /// ISO/IEC TS 23220-4 Annex C Photo ID.
    case photoId

    /// The document type to request.
    var docType: String {
        switch self {
        case .mdl, .mdlAgeOver18: return mdlDocType
        case .photoId: return photoIdDocType
        }
    }

    /// Requested data elements, keyed by namespace then element identifier. The value is the
    /// reader's intent to retain the element.
    var requestedItems: [String: [String: Bool]] {
        switch self {
        case .mdl: return defaultElements
        case .mdlAgeOver18: return ageOver18Elements
        case .photoId: return photoIdElements
        }
    }
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

/// ISO 18013-5 mobile driver's license doctype.
let mdlDocType = "org.iso.18013.5.1.mDL"

/// ISO/IEC TS 23220-4 Annex C Photo ID doctype (spelled with a lowercase `id` in the spec).
let photoIdDocType = "org.iso.23220.photoid.1"

/// Photo ID data elements, split between the ISO/IEC 23220-2 common namespace (Annex C table 1)
/// and the Photo ID specific namespace (Annex C table 2).
let photoIdElements = [
    "org.iso.23220.1": [
        // Mandatory
        "family_name_unicode": false,
        "given_name_unicode": false,
        "birth_date": false,
        "portrait": false,
        "issue_date": false,
        "expiry_date": false,
        "issuing_authority_unicode": false,
        "issuing_country": false,
        "age_over_18": false,
        // Recommended
        "age_in_years": false,
        "age_birth_year": false,
        // Optional
        "portrait_capture_date": false,
        "birthplace": false,
        "name_at_birth": false,
        "resident_address_unicode": false,
        "resident_city_unicode": false,
        "resident_postal_code": false,
        "resident_country": false,
        "resident_city_latin1": false,
        "sex": false,
        "nationality": false,
        "document_number": false,
        "issuing_subdivision": false,
        "family_name_latin1": false,
        "given_name_latin1": false
    ],
    "org.iso.23220.photoid.1": [
        "person_id": false,
        "birth_country": false,
        "birth_state": false,
        "birth_city": false,
        "administrative_number": false,
        "resident_street": false,
        "resident_house_number": false,
        "travel_document_number": false,
        "resident_state": false
    ]
]

private enum EngagementTab: Int, Hashable {
    case qr = 0
    case nfc = 1
}

public struct VerifyMDocView: View {
    @Binding var path: NavigationPath
    var profile: MDocVerificationProfile = .mdl

    @State private var handover: ReaderHandover?
    @State private var selectedTab: EngagementTab = .qr
    @StateObject private var nfcObservable = NfcReaderObservable(
        alertMessage: "Hold near the holder phone to share their credential"
    )

    var trustedCertificates = TrustedCertificatesDataStore.shared
        .getAllCertificates()

    public init(path: Binding<NavigationPath>, profile: MDocVerificationProfile = .mdl) {
        self._path = path
        self.profile = profile
    }

    public var body: some View {
        Group {
            if let handover {
                MDocReaderView(
                    handover: handover,
                    requestedItems: profile.requestedItems,
                    trustAnchorRegistry: trustedCertificates.map { $0.content },
                    docType: profile.docType,
                    onCancel: onCancel,
                    path: $path
                )
            } else {
                scanningView
            }
        }
        .onReceive(nfcObservable.$pendingHandover) { newHandover in
            guard let newHandover else { return }
            handover = newHandover
            nfcObservable.consumeHandover()
        }
        .onChange(of: selectedTab) { _ in
            updateNfcActive()
        }
        .onAppear {
            updateNfcActive()
        }
        .onDisappear {
            nfcObservable.setActive(false)
        }
    }

    private var scanningView: some View {
        VStack(spacing: 0) {
            TabView(selection: $selectedTab) {
                ScanningComponent(
                    path: $path,
                    scanningParams: Scanning(
                        scanningType: .qrcode,
                        onCancel: onCancel,
                        onRead: { code in
                            handover = ReaderHandover.newQr(qr: code)
                        }
                    )
                )
                .tag(EngagementTab.qr)

                VerifyMDocNfcTab(
                    phase: nfcObservable.phase,
                    onCancel: onCancel,
                    onRetry: { nfcObservable.setActive(true) }
                )
                .tag(EngagementTab.nfc)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            engagementTabBar
        }
    }

    private var engagementTabBar: some View {
        HStack(spacing: 0) {
            tabButton(
                icon: "qrcode.viewfinder",
                tab: .qr,
                accessibilityLabel: "QR code engagement"
            )
            tabButton(
                icon: "wave.3.right",
                tab: .nfc,
                accessibilityLabel: "NFC engagement"
            )
        }
        .frame(height: 56)
        .background(Color("ColorBase50"))
    }

    private func tabButton(icon: String, tab: EngagementTab, accessibilityLabel: String) -> some View {
        let active = selectedTab == tab
        return Button(action: { selectedTab = tab }) {
            VStack(spacing: 0) {
                Rectangle()
                    .fill(active ? Color("ColorBlue600") : .clear)
                    .frame(height: 3)
                Image(systemName: icon)
                    .font(.system(size: 24, weight: .regular))
                    .foregroundColor(active ? Color("ColorBlue600") : Color("ColorBase600"))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .accessibilityLabel(accessibilityLabel)
        .buttonStyle(.plain)
    }

    private func updateNfcActive() {
        // NFC engagement should only be soliciting taps while the user is on
        // the NFC tab and we have not yet captured a handover.
        let shouldBeActive = selectedTab == .nfc && handover == nil
        nfcObservable.setActive(shouldBeActive)
    }

    private func onCancel() {
        handover = nil
        nfcObservable.setActive(false)
        if !path.isEmpty {
            path.removeLast()
        }
    }
}

public struct MDocReaderView: View {
    @StateObject var delegate: MDocScanViewDelegate
    @Binding var path: NavigationPath
    var onCancel: () -> Void

    init(
        handover: ReaderHandover,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]?,
        docType: String,
        onCancel: @escaping () -> Void,
        path: Binding<NavigationPath>
    ) {
        self._delegate = StateObject(
            wrappedValue: MDocScanViewDelegate(
                handover: handover,
                requestedItems: requestedItems,
                trustAnchorRegistry: trustAnchorRegistry,
                docType: docType,
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
                    docTypes: r.data.docTypes,
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
    private var mdocReader: MdocProximityReader?

    init(
        handover: ReaderHandover,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]?,
        docType: String
    ) {
        self.mdocReader = MdocProximityReader(
            fromHandover: handover,
            delegate: self,
            requestedItems: requestedItems,
            trustAnchorRegistry: trustAnchorRegistry,
            docType: docType,
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
