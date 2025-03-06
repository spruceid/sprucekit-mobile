import SwiftUI
import SpruceIDMobileSdkRs

struct VerifyDelegatedOid4vp: Hashable {
    var id: Int64
}

public enum VerifyDelegatedOid4vpViewSteps {
    case loadingQrCode
    case presentingQrCode
    case gettingStatus
    case displayingCredential
}

struct VerifyDelegatedOid4vpView: View {
    @Binding var path: NavigationPath
    var verificationId: Int64
    var verificationMethod: VerificationMethod?
    var url: URL?
    var baseUrl: String

    @State var step = VerifyDelegatedOid4vpViewSteps.loadingQrCode
    @State var status = DelegatedVerifierStatus.initiated
    @State var loading: String?
    @State var errorTitle: String?
    @State var errorDescription: String?

    @State var verifier: DelegatedVerifier?
    @State var authQuery: String?
    @State var uri: String?
    @State var presentation: String?

    init(path: Binding<NavigationPath>, verificationId: Int64) {
        self._path = path
        self.verificationId = verificationId
        do {
            // Verification method from db
            verificationMethod = try VerificationMethodDataStore
                .shared
                .getVerificationMethod(rowId: verificationId)
                .unwrap()

            // Verification method base url
            url = URL(string: verificationMethod!.url)

            let unwrappedUrl = try url.unwrap()

            baseUrl = unwrappedUrl
                .absoluteString
                .replacingOccurrences(of: unwrappedUrl.path(), with: "")
        } catch {
            self.errorTitle = "Failed Initializing"
            self.errorDescription = error.localizedDescription
            self.verificationMethod = nil
            self.url = URL(string: "")
            self.baseUrl = ""
        }
    }

    func monitorStatus(status: DelegatedVerifierStatus) async {
        do {
            let res = try await verifier?.pollVerificationStatus(url: "\(uri.unwrap())?status=\(status)")

            if let newStatus = res?.status {
                switch newStatus {
                case DelegatedVerifierStatus.initiated:
                    await monitorStatus(status: status)
                case DelegatedVerifierStatus.pending:
                    // display loading view
                    loading = "Requesting data..."
                    step = VerifyDelegatedOid4vpViewSteps.gettingStatus
                    // call next status monitor
                    await monitorStatus(status: newStatus)
                case DelegatedVerifierStatus.failure:
                    // display error view
                    errorTitle = "Error Verifying Credential"
                    errorDescription = "\(try res.unwrap())"
                case DelegatedVerifierStatus.success:
                    // display credential
                    step = VerifyDelegatedOid4vpViewSteps.displayingCredential
                    presentation = res?.oid4vp?.vpToken
                }
            } else {
                // if can't find res.status, call monitorStatus
                // with the same parameters
                await monitorStatus(status: status)
            }
        } catch {
            errorTitle = "Error Verifying Credential"
            errorDescription = error.localizedDescription
        }
    }

    func initiateVerification() {
        Task {
            do {
                let unwrappedUrl = try url.unwrap()

                // Delegated Verifier
                verifier = try await DelegatedVerifier.newClient(baseUrl: baseUrl)

                // Get initial parameters to delegate verification
                let delegatedVerificationUrl = "\(unwrappedUrl.path())?\(unwrappedUrl.query() ?? "")"
                let delegatedInitializationResponse = try await verifier
                    .unwrap()
                    .requestDelegatedVerification(url: delegatedVerificationUrl)

                authQuery = "openid4vp://?\(delegatedInitializationResponse.authQuery)"

                uri = delegatedInitializationResponse.uri

                // Display QR Code
                step = VerifyDelegatedOid4vpViewSteps.presentingQrCode

                // Call method to start monitoring status
                await monitorStatus(status: status)
            } catch {
                errorTitle = "Failed getting QR Code"
                errorDescription = error.localizedDescription
            }
        }
    }

    func onBack() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    var body: some View {
        ZStack {
            if errorTitle != nil && errorDescription != nil {
                ErrorView(
                    errorTitle: errorTitle!,
                    errorDetails: errorDescription!,
                    onClose: onBack
                )
            } else {
                switch step {
                case .loadingQrCode:
                    LoadingView(
                        loadingText: "Getting QR Code",
                        cancelButtonLabel: "Cancel",
                        onCancel: onBack
                    )
                case .presentingQrCode:
                    if let authQueryUnwrapped = authQuery {
                        DelegatedVerifierDisplayQRCodeView(
                            payload: authQueryUnwrapped,
                            onClose: onBack
                        )
                    }
                case .gettingStatus:
                    LoadingView(
                        loadingText: loading ?? "Requesting data...",
                        cancelButtonLabel: "Cancel",
                        onCancel: onBack
                    )
                case .displayingCredential:
                    if let presentationUnwrapped = presentation {
                        VerifierCredentialSuccessView(
                            rawCredential: presentationUnwrapped,
                            onClose: onBack,
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
                    }
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .onAppear(perform: {
            initiateVerification()
        })
    }
}

struct DelegatedVerifierDisplayQRCodeView: View {
    var payload: Data
    var onClose: () -> Void

    init(payload: String, onClose: @escaping () -> Void) {
        self.payload = payload.data(using: .utf8)!
        self.onClose = onClose
    }

    var body: some View {
        ZStack {
            VStack {
                Image(uiImage: generateQRCode(from: payload))
                    .interpolation(.none)
                    .resizable()
                    .scaledToFit()
                    .aspectRatio(contentMode: .fit)
                    .padding(.horizontal, 20)
            }
            VStack {
                Spacer()
                Button {
                    onClose()
                }  label: {
                    Text("Cancel")
                        .frame(width: UIScreen.screenWidth)
                        .padding(.horizontal, -20)
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
                }
                .foregroundColor(.black)
                .padding(.vertical, 13)
                .cornerRadius(8)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )
            }
        }
        .navigationBarBackButtonHidden(true)
    }
}
