import SwiftUI
import SpruceIDMobileSdkRs
import SpruceIDMobileSdk

struct VerifyCwt: Hashable {}

struct VerifyCwtView: View {
    
    @State var success: Bool?
    @State var credentialPack: CredentialPack?
    @State var code: String?
    
    @Binding var path: NavigationPath
    
    var body: some View {
        if success == nil {
            ScanningComponent(
                path: $path,
                scanningParams: Scanning(
                    subtitle: "Scan the QR Code",
                    scanningType: .qrcode,
                    onCancel: {
                        path.removeLast()
                    },
                    onRead: { code in
                        Task {
                            do {
                                credentialPack = CredentialPack()
                                _ = credentialPack!.addCwt(cwt: try Cwt.newFromBase10(payload: code))
                                self.code = code
                                success = true
                                // TODO: add log
                            } catch {
                                print(error)
                                success = false
                            }
                        }
                    }
                )
            )
        } else {
            VerifierCredentialSuccessView(
                rawCredential: self.code!,
                onClose: { path.removeLast() },
                logVerification: {_,_,_ in }
            )
        }
        
    }
}
