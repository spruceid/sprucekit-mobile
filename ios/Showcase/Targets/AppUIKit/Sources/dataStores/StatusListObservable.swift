import Foundation
import SpruceIDMobileSdk

class StatusListObservable: ObservableObject {
    @Published var statusLists: [String: CredentialStatusList]
    @Published var hasConnection: Bool

    init(
        statusLists: [String: CredentialStatusList] = [:],
        hasConnection: Bool = true
    ) {
        self.statusLists = statusLists
        self.hasConnection = hasConnection
    }

    @MainActor func fetchAndUpdateStatus(credentialPack: CredentialPack) async
        -> CredentialStatusList {
        let statusLists = await credentialPack.getStatusListsAsync(
            hasConnection: hasConnection)
        if statusLists.isEmpty {
            self.statusLists[credentialPack.id.uuidString] = .undefined
        } else {
            self.statusLists[credentialPack.id.uuidString] = statusLists.values
                .first!
        }
        return self.statusLists[credentialPack.id.uuidString] ?? .undefined
    }

    func getStatusLists(credentialPacks: [CredentialPack]) async {
        await credentialPacks.asyncForEach { credentialPack in
            _ = await fetchAndUpdateStatus(credentialPack: credentialPack)
        }
    }
}
