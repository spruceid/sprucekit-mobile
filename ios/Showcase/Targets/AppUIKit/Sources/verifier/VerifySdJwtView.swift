import SwiftUI
import SpruceIDMobileSdkRs

struct VerifySdJwt: Hashable {}

/// Maximum consecutive scan-then-verify failures before surfacing the error.
/// At V40 QR density, Vision occasionally returns a "successfully decoded"
/// string with flipped bytes that pass QR-level CRC but fail downstream
/// signature verification — those get retried silently. Anything that fails
/// this many times in a row is almost certainly a real problem (wrong QR,
/// expired credential, corrupted issuer signature, etc.).
private let kMaxScanAttempts = 5

/// Scan + verify a compact SD-JWT VP (the QR payload produced by
/// `generateCredentialVpToken`). Validates issuer signature via DID
/// resolution (`AnyDidMethod`) — for `did:jwk` issuers this is fully offline.
struct VerifySdJwtView: View {

    @State var success: Bool?
    @State var failureCount: Int = 0

    @Binding var path: NavigationPath

    var body: some View {
        if success == nil {
            ScanningComponent(
                path: $path,
                scanningParams: Scanning(
                    scanningType: .qrcode,
                    onCancel: {
                        path.removeLast()
                    },
                    onRead: { code in
                        Task {
                            do {
                                try await verifySdJwtVp(input: code)
                                failureCount = 0
                                success = true
                            } catch {
                                failureCount += 1
                                print(error)
                                if failureCount >= kMaxScanAttempts {
                                    failureCount = 0
                                    success = false
                                }
                                // Otherwise: silent retry — scanner stays
                                // open, next frame gets a shot. User just
                                // sees a slightly-longer scan.
                            }
                        }
                    }
                )
            )
        } else {
            VerifierSuccessView(
                path: $path,
                success: success!,
                content: Text(success! ? "Valid SD-JWT VP" : "Invalid SD-JWT VP")
                    .font(.customFont(font: .inter, style: .semiBold, size: .h1))
                    .foregroundStyle(Color("ColorStone950"))
                    .padding(.top, 20)
            )
        }
    }
}
