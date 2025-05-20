import Combine
import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

class HacApplicationObservable: ObservableObject {
    @Published private(set) var walletServiceClient: WalletServiceClient
    @Published private(set) var issuanceClient: IssuanceServiceClient
    @Published var hacApplications: [HacApplication] = []
    @Published private(set) var walletAttestation: String?
    private var isFetchingWalletAttestation = false
    private var cancellables = Set<AnyCancellable>()

    init() {
        // Initialize with current URLs
        self.walletServiceClient = WalletServiceClient(
            baseUrl: EnvironmentConfig.shared.walletServiceUrl
        )
        self.issuanceClient = IssuanceServiceClient(
            baseUrl: EnvironmentConfig.shared.issuanceServiceUrl
        )

        // Observe environment changes
        EnvironmentConfig.shared.isDevModePublisher
            .sink { [weak self] _ in
                self?.updateClients()
            }
            .store(in: &cancellables)

        self.loadAll()
    }

    private func updateClients() {
        self.walletServiceClient = WalletServiceClient(
            baseUrl: EnvironmentConfig.shared.walletServiceUrl
        )
        self.issuanceClient = IssuanceServiceClient(
            baseUrl: EnvironmentConfig.shared.issuanceServiceUrl
        )
    }

    func loadAll() {
        self.hacApplications = HacApplicationDataStore.shared
            .getAllHacApplications()
    }

    func getSigningJwk() -> String? {
        if !KeyManager.keyExists(id: DEFAULT_SIGNING_KEY_ID) {
            _ = KeyManager.generateSigningKey(id: DEFAULT_SIGNING_KEY_ID)
        }
        return KeyManager.getJwk(id: DEFAULT_SIGNING_KEY_ID)
    }

    @MainActor func getNonce() async -> String? {
        do {
            return try await walletServiceClient.nonce()
        } catch {
            print(error.localizedDescription)
        }
        return nil
    }

    @MainActor func getWalletAttestation() async -> String? {
        // If we already have a valid attestation, return it
        if let attestation = walletAttestation,
            walletServiceClient.isTokenValid()
        {
            return attestation
        }

        // If we're already fetching, wait for the result
        if isFetchingWalletAttestation {
            while walletAttestation == nil {
                try? await Task.sleep(nanoseconds: 100_000_000)  // 100ms
            }
            return walletAttestation
        }

        // Start fetching
        isFetchingWalletAttestation = true
        defer { isFetchingWalletAttestation = false }

        do {
            let attestation = AppAttestation()
            let jwk = try getSigningJwk().unwrap()
            let nonce = try await getNonce().unwrap()

            let appAttestation = try await attestation.appAttest(
                jwk: jwk,
                nonce: nonce
            )

            let token = try await walletServiceClient.login(
                appAttestation: appAttestation
            )

            walletAttestation = token
            return token
        } catch {
            ToastManager.shared.showError(
                message: error.localizedDescription,
                duration: 5.0
            )
            print(error.localizedDescription)
            return nil
        }
    }
}
