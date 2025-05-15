import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

let SPRUCEID_HAC_PROOFING_CLIENT = "https://proofing.haci.staging.spruceid.xyz"
let SPRUCEID_HAC_WALLET_SERVICE = "https://wallet.haci.staging.spruceid.xyz"
let SPRUCEID_HAC_ISSUANCE_SERVICE = "https://issuance.haci.staging.spruceid.xyz"

class HacApplicationObservable: ObservableObject {
    @Published var walletServiceClient = WalletServiceClient(
        baseUrl: SPRUCEID_HAC_WALLET_SERVICE
    )
    @Published var issuanceClient = IssuanceServiceClient(
        baseUrl: SPRUCEID_HAC_ISSUANCE_SERVICE
    )
    @Published var hacApplications: [HacApplication] = []
    @Published private(set) var walletAttestation: String?
    private var isFetchingWalletAttestation = false

    init() {
        self.loadAll()
    }

    func loadAll() {
        self.hacApplications = HacApplicationDataStore.shared
            .getAllHacApplications()
    }

    func getSigningJwk() -> String? {
        let keyId = "reference-app/default-signing"
        if !KeyManager.keyExists(id: keyId) {
            _ = KeyManager.generateSigningKey(id: keyId)
        }
        return KeyManager.getJwk(id: keyId)
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
            ToastManager.shared.showError(message: error.localizedDescription, duration: 5.0)
            print(error.localizedDescription)
            return nil
        }
    }
}
