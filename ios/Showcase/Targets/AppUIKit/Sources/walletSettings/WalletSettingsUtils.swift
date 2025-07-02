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

        try await mdocPack.save(
            storageManager: StorageManager()
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
