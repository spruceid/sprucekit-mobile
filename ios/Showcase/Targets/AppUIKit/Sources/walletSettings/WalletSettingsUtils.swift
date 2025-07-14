import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

@MainActor
public func generateMockMdl() async {
    do {
        if !KeyManager.keyExists(id: DEFAULT_SIGNING_KEY_ID) {
            _ = KeyManager.generateSigningKey(
                id: DEFAULT_SIGNING_KEY_ID
            )
        }
        let mdl = try generateTestMdl(
            keyManager: KeyManager(),
            keyAlias: DEFAULT_SIGNING_KEY_ID
        )
        let mdocPack = CredentialPack()

        _ = try await mdocPack.addMDoc(mdoc: mdl)

        let bundle = Bundle.main
        let storageManager = StorageManager(
            appGroupId: bundle.object(forInfoDictionaryKey: "storageAppGroup") as? String)
        try await mdocPack.save(
            storageManager: storageManager
        )
        ToastManager.shared.showSuccess(
            message: "Test mDL added to your wallet"
        )

    } catch {
        print(error.localizedDescription)
        ToastManager.shared.showError(
            message: "Error generating mDL"
        )
    }
}
