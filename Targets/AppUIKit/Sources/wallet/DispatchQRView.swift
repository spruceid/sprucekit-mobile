import SpruceIDMobileSdkRs
import SwiftUI

struct DispatchQR: Hashable {}

struct DispatchQRView: View {
    @State var loading: Bool = false
    @State var verificationRequest: String?
    @State var err: String?
    @State var success: Bool?

    @Binding var path: NavigationPath

    func getVerificationRequest(verificationRequestOffer: String) {
        loading = true
        Task {
            do {
                print("DISPLAYING CREDENTIAL REQUEST")
                print(verificationRequestOffer)
                if verificationRequestOffer.hasPrefix("openid4vp://") {
                    // TODO for Joey: Implement OID4VP flow for verification request from user
                } else {
                    print(
                        "The QR code you have scanned is not recognized as a verification request")
                    // TODO for Juliano: Add UI component for "QR not recognized" error screen
                }
                loading = false
            }
        }
    }

    var body: some View {
        VStack {
            if loading {
                VStack {
                    Text("Loading...")
                }
            } else if err != nil {
                VStack {
                    Text(err!)
                }
            } else if verificationRequest == nil {
                VStack {
                    ScanningComponent(
                        path: $path,
                        scanningParams: Scanning(
                            title: "Scan to Share Credential",
                            scanningType: .qrcode,
                            onCancel: {
                                path.removeLast()
                            },
                            onRead: { code in
                                getVerificationRequest(verificationRequestOffer: code)
                            }
                        )
                    )
                    Text("Reading...")
                }
            } else {
                VStack {
                    // TODO: validate one of the user credentials against the request
                    Text(verificationRequest!)
                }
            }
        }
    }
}
