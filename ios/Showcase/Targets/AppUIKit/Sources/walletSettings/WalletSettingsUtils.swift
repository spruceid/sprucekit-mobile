import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

@MainActor
public func generateMockMdl() async {
    await generateMockMdoc(
        displayName: "mDL",
        makeMdoc: { keyManager, keyAlias in
            try generateTestMdl(keyManager: keyManager, keyAlias: keyAlias)
        }
    )
}

@MainActor
public func generateMockPhotoId() async {
    await generateMockMdoc(
        displayName: "Photo ID",
        makeMdoc: { keyManager, keyAlias in
            try generateTestPhotoId(keyManager: keyManager, keyAlias: keyAlias)
        }
    )
}

/// Signing key used as the DeviceKey of every locally generated test mdoc.
///
/// It requires user authentication so testers can exercise protected device keys: the system
/// prompts when it signs. A key created before this change keeps its old (unprotected) settings.
let TEST_MDOC_KEY_ALIAS = "testMdl"

/// Issue a test mdoc against the test mdoc key, add it to the wallet, and log the activity.
@MainActor
private func generateMockMdoc(
    displayName: String,
    makeMdoc: (KeyManager, String) throws -> Mdoc
) async {
    do {
        if !KeyManager.keyExists(id: TEST_MDOC_KEY_ALIAS),
            !KeyManager.generateSigningKey(
                id: TEST_MDOC_KEY_ALIAS,
                userAuthentication: UserAuthenticationPolicy()
            ) {
            throw KeyManError.internalError("could not create the test mdoc key")
        }
        let mdoc = try makeMdoc(KeyManager(), TEST_MDOC_KEY_ALIAS)
        let mdocPack = CredentialPack()

        let credentials = try await mdocPack.addMDoc(mdoc: mdoc)

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
            message: "Test \(displayName) added to your wallet"
        )

    } catch {
        print(error.localizedDescription)
        ToastManager.shared.showError(
            message: "Error generating \(displayName)"
        )
    }
}
