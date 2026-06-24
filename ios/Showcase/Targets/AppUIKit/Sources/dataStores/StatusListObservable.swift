import Foundation
import Network
import SpruceIDMobileSdk

class StatusListObservable: ObservableObject {
    @Published var statusLists: [String: CredentialStatusList]
    @Published private(set) var hasConnection: Bool

    private let connectionMonitor = NWPathMonitor()
    private let connectionMonitorQueue = DispatchQueue(
        label: "com.spruceid.showcase.StatusListObservable.connectionMonitor")

    init(
        statusLists: [String: CredentialStatusList] = [:],
        hasConnection: Bool = true
    ) {
        self.statusLists = statusLists
        self.hasConnection = hasConnection
        startMonitoringConnection()
    }

    deinit {
        connectionMonitor.cancel()
    }

    private func startMonitoringConnection() {
        connectionMonitor.pathUpdateHandler = { [weak self] path in
            let isConnected = path.status == .satisfied
            Task { @MainActor [weak self] in
                self?.hasConnection = isConnected
            }
        }
        connectionMonitor.start(queue: connectionMonitorQueue)
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
