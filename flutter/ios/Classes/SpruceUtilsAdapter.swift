import Flutter
import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Adapter implementing the SpruceUtils Pigeon protocol
class SpruceUtilsAdapter: NSObject, SpruceUtils {
    private let credentialPackAdapter: CredentialPackAdapter

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
        super.init()
    }

    func generateMockMdl(
        keyAlias: String?,
        completion: @escaping (Result<GenerateMockMdlResult, Error>) -> Void
    ) {
        let alias = keyAlias ?? "testMdl"

        Task {
            do {
                // Generate or retrieve the signing key
                if !KeyManager.keyExists(id: alias) {
                    _ = KeyManager.generateSigningKey(id: alias)
                }

                // Generate the test mDL
                let mdl = try generateTestMdl(keyManager: KeyManager(), keyAlias: alias)

                // Create a new CredentialPack and add the mDL
                let packId = try credentialPackAdapter.createPack()
                guard let pack = credentialPackAdapter.getNativePack(packId: packId) else {
                    completion(.success(GenerateMockMdlError(message: "Failed to create credential pack")))
                    return
                }

                // Add the mDL to the pack (async)
                let credentials = try await pack.addMDoc(mdoc: mdl)
                guard let credential = credentials.first else {
                    completion(.success(GenerateMockMdlError(message: "Failed to add mDL to pack")))
                    return
                }

                completion(.success(GenerateMockMdlSuccess(
                    packId: packId,
                    credentialId: credential.id(),
                    keyAlias: alias
                )))
            } catch {
                completion(.success(GenerateMockMdlError(message: "Failed to generate mock mDL: \(error.localizedDescription)")))
            }
        }
    }
}
