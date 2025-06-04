import SwiftUI
import SpruceIDMobileSdkRs
import SpruceIDMobileSdk

struct VerifyCwt: Hashable {}

struct VerifyCwtView: View {

    @State var success: Bool?
    @State var credentialPack: CredentialPack?
    @State var code: String?
    @State var error: Error?

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
                                credentialPack = CredentialPack()
                                let cwt = try Cwt.newFromBase10(payload: code)
                                _ = credentialPack!.addCwt(cwt: cwt)
                                try await cwt.verify(crypto: CryptoImpl())
                                self.code = code
                                success = true
                                // TODO: add log
                            } catch {
                                self.error = error
                                print(error)
                                success = false
                            }
                        }
                    }
                )
            )
        } else if success == true {
            VerifierCredentialSuccessView(
                rawCredential: self.code!,
                onClose: { path.removeLast() },
                logVerification: {_, _, _ in }
            )
        } else {
            VStack {
                Text("\(error!)")
            }
        }

    }
}
