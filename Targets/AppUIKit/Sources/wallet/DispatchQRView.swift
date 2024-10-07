import SpruceIDMobileSdkRs
import SwiftUI

struct DispatchQR: Hashable {}

struct DispatchQRView: View {

    @State var success: Bool?

    @Binding var path: NavigationPath

    var body: some View {
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
                            // TODO: Add other checks as necessary for
                            // validating OID4VP url and handle OID4VP flow
                            // try await dispatchQRcode(jwtVp: code)
                            success = true
                        } catch {
                            success = false
                            print(error)
                        }
                    }
                }
            )
        )
    }
}
