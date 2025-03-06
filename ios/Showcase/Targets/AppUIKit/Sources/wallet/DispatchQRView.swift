import SwiftUI

// The scheme for the OID4VP QR code.
let OID4VP_SCHEME = "openid4vp://"
// The scheme for the OID4VCI QR code.
let OID4VCI_SCHEME = "openid-credential-offer://"
// The scheme for the Mdoc OID4VP QR code.
let MDOC_OID4VP_SCHEME = "mdoc-openid4vp://"
// The schemes for HTTP/HTTPS QR code.
let HTTP_SCHEME = "http://"
let HTTPS_SCHEME = "https://"

struct DispatchQR: Hashable {}

struct DispatchQRView: View {
    @State var loading: Bool = false
    @State var err: String?
    @State var success: Bool?

    @Binding var path: NavigationPath

    func handleRequest(payload: String) {
        loading = true
        Task {
            if payload.hasPrefix(OID4VP_SCHEME) {
                path.append(HandleOID4VP(url: payload))
            } else if payload.hasPrefix(MDOC_OID4VP_SCHEME) {
                path.append(HandleMdocOID4VP(url: payload))
            } else if payload.hasPrefix(OID4VCI_SCHEME) {
                path.append(HandleOID4VCI(url: payload))
            } else if payload.hasPrefix(HTTPS_SCHEME)
                || payload.hasPrefix(HTTP_SCHEME) {
                if let url = URL(string: payload),
                    await UIApplication.shared.canOpenURL(url) {
                    await UIApplication.shared.open(url)
                    onBack()
                }
            } else {
                err =
                    "The QR code you have scanned is not supported. QR code payload: \(payload)"
            }
        }
    }

    func onBack() {
        path.removeLast()
    }

    var body: some View {
        VStack {
            if err != nil {
                ErrorView(
                    errorTitle: "Error Reading QR Code",
                    errorDetails: err!,
                    onClose: onBack
                )
            } else if loading {
                LoadingView(loadingText: "Loading...")
            } else {
                VStack {
                    ScanningComponent(
                        path: $path,
                        scanningParams: Scanning(
                            title: "Scan QR Code",
                            scanningType: .qrcode,
                            onCancel: onBack,
                            onRead: { code in
                                handleRequest(payload: code)
                            }
                        )
                    )
                }
            }
        }
    }
}
