import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

let SPRUCEID_HAC_PROOFING_CLIENT = "https://proofing.haci.staging.spruceid.xyz"
let SPRUCEID_HAC_WALLET_SERVICE = "https://wallet.haci.staging.spruceid.xyz"
let SPRUCEID_HAC_ISSUANCE_SERVICE = "https://issuance.haci.staging.spruceid.xyz"

class HacApplicationObservable: ObservableObject {
    @Published var walletServiceClient = WalletServiceClient(
        baseUrl: SPRUCEID_HAC_WALLET_SERVICE)
    @Published var issuanceClient = IssuanceServiceClient(
        baseUrl: SPRUCEID_HAC_ISSUANCE_SERVICE)
    @Published var hacApplications: [HacApplication] = []

    init() {
        self.loadAll()
    }

    func loadAll() {
        self.hacApplications = HacApplicationDataStore.shared
            .getAllHacApplications()
    }

    @MainActor func getWalletAttestation() async -> String? {
        do {
            if walletServiceClient.isTokenValid() {
                return walletServiceClient.getToken()
            } else {
                let keyId = "reference-app/default-signing"
                _ = KeyManager.generateSigningKey(id: keyId)
                let jwk = KeyManager.getJwk(id: keyId)
                return try await walletServiceClient.login(jwk: jwk!)
            }
        } catch {
            print(error.localizedDescription)
        }
        return nil
    }
}
