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

    init() {
        self.loadAll()
    }

    func loadAll() {
        self.hacApplications = HacApplicationDataStore.shared
            .getAllHacApplications()
    }

    func getSigningJwk() -> String? {
        let keyId = "reference-app/default-signing"
        _ = KeyManager.generateSigningKey(id: keyId)
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
        do {
            if walletServiceClient.isTokenValid() {
                return walletServiceClient.getToken()
            } else {
                let attestation = AppAttestation()

                let jwk = try getSigningJwk()
                    .unwrap()
                let nonce = try await getNonce()
                    .unwrap()

                let appAttestation = try await attestation.appAttest(
                    jwk: jwk,
                    nonce: nonce
                )

                return try await walletServiceClient.login(
                    appAttestation: appAttestation
                )
            }
        } catch {
            print(error.localizedDescription)
        }
        return nil
    }
}
