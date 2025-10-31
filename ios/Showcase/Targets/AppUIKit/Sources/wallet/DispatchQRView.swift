import SpruceIDMobileSdk
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

enum SupportedQRTypes {
    case oid4vp
    case oid4vci
    case http
}

let allSupportedQRTypes: [SupportedQRTypes] = [.oid4vp, .oid4vci, .http]

struct DispatchQR: Hashable {}

struct DispatchQRView: View {
    @State var err: String?
    @State var success: Bool?

    @Binding var path: NavigationPath
    var credentialPackId: String?
    var supportedTypes: [SupportedQRTypes] = allSupportedQRTypes
    var backgroundColor: Color = .white
    var hideCancelButton: Bool = false
    var useMinimalScanner: Bool = false

    func handleRequest(payload: String) {
        Task {
            let success = await handleScannedPayload(payload)
            if !success {
                err =
                    "This QRCode is not supported by the selection: \(supportedTypes). Payload: \(payload)"
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
            } else {
                VStack {
                    if useMinimalScanner {
                        MinimalScanningComponent(
                            backgroundColor: backgroundColor,
                            onRead: { code in
                                handleRequest(payload: code)
                            },
                            onCancel: onBack
                        )
                    } else {
                        ScanningComponent(
                            path: $path,
                            scanningParams: Scanning(
                                title: "Scan QR Code",
                                scanningType: .qrcode,
                                onCancel: onBack,
                                hideCancelButton: hideCancelButton,
                                onRead: { code in
                                    handleRequest(payload: code)
                                },
                                backgroundColor: backgroundColor
                            )
                        )
                    }
                }
            }
        }
    }

    func handleScannedPayload(_ payload: String) async -> Bool {
        // Analyze payload and determine QR code type
        let qrType: SupportedQRTypes? = {
            if payload.hasPrefix(OID4VP_SCHEME)
                || payload.hasPrefix(MDOC_OID4VP_SCHEME)
            {
                return .oid4vp
            } else if payload.hasPrefix(OID4VCI_SCHEME) {
                return .oid4vci
            } else if payload.hasPrefix(HTTP_SCHEME)
                || payload.hasPrefix(HTTPS_SCHEME)
            {
                return .http
            }
            return nil
        }()

        // Check if detected type is in supported types list
        guard let detectedType = qrType, supportedTypes.contains(detectedType)
        else {
            return false
        }

        // Process based on detected type
        switch detectedType {
        case .oid4vp:
            if payload.hasPrefix(OID4VP_SCHEME) {
                path.append(
                    HandleOID4VP(
                        url: payload,
                        credentialPackId: credentialPackId
                    )
                )
                return true
            } else if payload.hasPrefix(MDOC_OID4VP_SCHEME) {
                path.append(
                    HandleMdocOID4VP(
                        url: payload,
                        credentialPackId: credentialPackId
                    )
                )
                return true
            }

        case .oid4vci:
            path.append(HandleOID4VCI(url: payload))
            return true

        case .http:
            if let url = URL(string: payload),
                UIApplication.shared.canOpenURL(url)
            {
                await UIApplication.shared.open(url)
                onBack()
                return true
            }
        }

        return false
    }
}
