import Combine
import Foundation

class EnvironmentConfig: ObservableObject {
    static let shared = EnvironmentConfig()

    private let PROD_WALLET_SERVICE = "https://wallet.grove.spruceid.xyz"
    private let PROD_ISSUANCE_SERVICE = "https://issuance.grove.spruceid.xyz"

    private let DEV_WALLET_SERVICE = "https://wallet.grove.staging.spruceid.xyz"
    private let DEV_ISSUANCE_SERVICE = "https://issuance.grove.staging.spruceid.xyz"

    private let _isDevMode = CurrentValueSubject<Bool, Never>(false)

    var isDevMode: Bool {
        get { _isDevMode.value }
        set { _isDevMode.send(newValue) }
    }

    var isDevModePublisher: AnyPublisher<Bool, Never> {
        _isDevMode.eraseToAnyPublisher()
    }

    private init() {}

    func toggleDevMode() {
        isDevMode = !isDevMode
    }

    var walletServiceUrl: String {
        _isDevMode.value ? DEV_WALLET_SERVICE : PROD_WALLET_SERVICE
    }

    var issuanceServiceUrl: String {
        _isDevMode.value ? DEV_ISSUANCE_SERVICE : PROD_ISSUANCE_SERVICE
    }
}
