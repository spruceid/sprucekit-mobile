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
/// It requires user authentication (`TEST_MDOC_KEY_POLICY`), so the prompt can be tried out. An
/// existing key keeps the settings it was created with.
let TEST_MDOC_KEY_ALIAS = "testMdl"

/// User-authentication policy of the test mdoc key. Screens that sign with it build their
/// `LAContext` from this policy.
let TEST_MDOC_KEY_POLICY = UserAuthenticationPolicy()

/// Issue a test mdoc against the test mdoc key, add it to the wallet, and log the activity.
@MainActor
private func generateMockMdoc(
    displayName: String,
    makeMdoc: (KeyManager, String) throws -> Mdoc
) async {
    do {
        if !KeyManager.keyExists(id: TEST_MDOC_KEY_ALIAS) {
            try KeyManager.generateSigningKeyThrowing(
                id: TEST_MDOC_KEY_ALIAS,
                userAuthentication: TEST_MDOC_KEY_POLICY
            )
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
