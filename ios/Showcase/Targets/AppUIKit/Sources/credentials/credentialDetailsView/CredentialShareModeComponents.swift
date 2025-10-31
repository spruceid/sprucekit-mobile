import SpruceIDMobileSdk
import SwiftUI

struct ScanModeContent: View {
    @Binding var path: NavigationPath
    let credentialPackId: String

    var body: some View {
        ZStack {
            Color("ColorBase50")
                .edgesIgnoringSafeArea(.all)

            DispatchQRView(
                path: $path,
                credentialPackId: credentialPackId,
                supportedTypes: [SupportedQRTypes.oid4vp, SupportedQRTypes.http],
                backgroundColor: Color("ColorBase50"),
                hideCancelButton: true,
                useMinimalScanner: true
            )
        }
    }
}

struct ShareModeContent<Content: View>: View {
    let credentialPack: CredentialPack?
    let genericCredentialDetailsShareQRCode: (CredentialPack) -> Content

    var body: some View {
        ZStack(alignment: .center) {
            Color("ColorBase50")
                .edgesIgnoringSafeArea(.all)

            if let pack = credentialPack {
                genericCredentialDetailsShareQRCode(pack)
            }
        }
    }
}
