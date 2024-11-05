import SwiftUI

// The scheme for the OID4VP QR code.
let OPEN_ID4VP_SCHEME = "openid4vp://"

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
                print("Reading URL: \(verificationRequestOffer)")
                if verificationRequestOffer.hasPrefix(OPEN_ID4VP_SCHEME) {
                    path.append(HandleOID4VP(url: verificationRequestOffer))
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
