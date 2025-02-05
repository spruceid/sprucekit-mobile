import Foundation
import SpruceIDMobileSdk

class CredentialPackObservable: ObservableObject {
    @Published var credentialPacks: [CredentialPack]
    let storageManager = StorageManager()

    init(credentialPacks: [CredentialPack] = []) {
        self.credentialPacks = credentialPacks
    }

    @MainActor func loadAndUpdateAll() async throws -> [CredentialPack] {
        let credentialPacks = try await CredentialPack.loadAll(
            storageManager: storageManager)
        updateAll(credentialPacks: credentialPacks)
        return credentialPacks
    }

    func updateAll(credentialPacks: [CredentialPack]) {
        self.credentialPacks = credentialPacks
    }

    @MainActor func add(credentialPack: CredentialPack) async throws {
        try await credentialPack.save(storageManager: storageManager)
        self.credentialPacks.append(credentialPack)
    }

    func delete(credentialPack: CredentialPack) async throws {
        try await credentialPack.remove(storageManager: storageManager)
        self.credentialPacks.removeAll { credPack in
            credPack.id.uuidString == credentialPack.id.uuidString
        }
    }

    func getById(credentialPackId: String) -> CredentialPack? {
        return credentialPacks.first { credentialPack in
            credentialPack.id.uuidString == credentialPackId
        }
    }
}
