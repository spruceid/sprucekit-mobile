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

        let credentials = try await mdocPack.addMDoc(mdoc: mdl)

        let bundle = Bundle.main
        let storageManager = StorageManager(
            appGroupId: bundle.object(forInfoDictionaryKey: "storageAppGroup") as? String)
        try await mdocPack.save(
            storageManager: storageManager
        )
        let credentialInfo = getCredentialIdTitleAndIssuer(
            credentialPack: mdocPack,
            credential: credentials[0]
        )
        _ = WalletActivityLogDataStore.shared.insert(
            credentialPackId: mdocPack.id.uuidString,
            credentialId: credentialInfo.0,
            credentialTitle: credentialInfo.1,
            issuer: credentialInfo.2,
            action: "Claimed",
            dateTime: Date(),
            additionalInformation: ""
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
