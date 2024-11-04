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
    @State var loading: String? = nil
    @State var errorTitle: String? = nil
    @State var errorDescription: String? = nil
    
    @State var verifier: DelegatedVerifier? = nil
    @State var authQuery: String? = nil
    @State var uri: String? = nil
    @State var presentation: String? = nil

    
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
                // monitorStatus(status)
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
                        Text(presentationUnwrapped)
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
                        .stroke(Color("CodeBorder"), lineWidth: 1)
                )
            }
        }
        .navigationBarBackButtonHidden(true)
    }
}
