import SwiftUI
import SpruceIDMobileSdk

let DEFAULT_SIGNING_KEY_ID = "reference-app/default-signing"

public struct ContentView: View {
    @State var path: NavigationPath = .init()
    @State var sheetOpen: Bool = false
    @State var spruceMdlId: String?
    let keyManager = KeyManager()
    let credentialPackObservable: CredentialPackObservable

    public init() {
        let bundle = Bundle.main
        // Getting some entitlements values from the plist
        let appGroupId = bundle.object(forInfoDictionaryKey: "storageAppGroup") as? String
        let keychainAccessGroup = bundle.object(forInfoDictionaryKey: "keychainAccessGroup") as? String
        let keychainAccessGroupApp = bundle.object(forInfoDictionaryKey: "keychainAccessGroupAppOnly") as? String
        credentialPackObservable = CredentialPackObservable(appGroupId: appGroupId, credentialPacks: [])
        if keychainAccessGroup != nil && keychainAccessGroupApp != nil {
            do {
                try keyManager.migrateToAccessGroup(oldAccessGroup: keychainAccessGroupApp!, newAccessGroup: keychainAccessGroup!)
            } catch {
                print("Failed to run key manager migration: \(error)")
            }
        }
        if appGroupId != nil {
            do {
                try credentialPackObservable.storageManager.migrationToAppGroupFileManager()
            } catch {
                print("Failed to run storage manager migration: \(error)")
            }
        }
    }

    func handleSpruceIDUrl(url: URL) {
        // test if it is an sd-jwt query
        if let sdJwtQuery = URLComponents(string: url.absoluteString)?
            .queryItems?
            .first(
                where: {
                    $0.name == "sd-jwt"
                }
            )?.value
        {
            self.path.append(
                AddToWallet(rawCredential: sdJwtQuery)
            )

            // test if it is an apply for spruceid mdl callback query
        } else if let id = URLComponents(string: url.absoluteString)?
            .queryItems?
            .first(
                where: {
                    $0.name == "spruceid-mdl"
                }
            )?.value
        {
            sheetOpen = true
            spruceMdlId = id
        }
    }

    func handleOid4vpUrl(url: URL) {
        self.path.append(
            HandleOID4VP(url: url.absoluteString)
        )
    }

    func handleOid4vciUrl(url: URL) {
        self.path.append(
            HandleOID4VCI(url: url.absoluteString)
        )
    }

    func handleMdocOid4vpUrl(url: URL) {
        self.path.append(
            HandleMdocOID4VP(url: url.absoluteString)
        )
    }

    public var body: some View {
        ZStack {
            // Bg color
            Rectangle()
                .foregroundColor(Color("ColorBase1"))
                .edgesIgnoringSafeArea(.all)
            NavigationStack(path: $path.animation(.easeOut)) {
                HomeView(path: $path)
                    .navigationDestination(for: VerifyDL.self) { _ in
                        VerifyDLView(path: $path)
                    }
                    .navigationDestination(for: VerifyEA.self) { _ in
                        VerifyEAView(path: $path)
                    }
                    .navigationDestination(for: VerifyVC.self) { _ in
                        VerifyVCView(path: $path)
                    }
                    .navigationDestination(for: VerifyMDoc.self) {
                        verifyMDocParams in
                        VerifyMDocView(
                            path: $path,
                            checkAgeOver18: verifyMDocParams.checkAgeOver18
                        )
                    }
                    .navigationDestination(for: VerifyCwt.self) { _ in
                        VerifyCwtView(path: $path)
                    }
                    .navigationDestination(for: VerifyDelegatedOid4vp.self) {
                        verifyDelegatedOid4vpParams in
                        VerifyDelegatedOid4vpView(
                            path: $path,
                            verificationId: verifyDelegatedOid4vpParams.id
                        )
                    }
                    .navigationDestination(for: VerifierSettingsHome.self) {
                        _ in
                        VerifierSettingsHomeView(path: $path)
                    }
                    .navigationDestination(
                        for: VerifierSettingsActivityLog.self
                    ) { _ in
                        VerifierSettingsActivityLogView(path: $path)
                    }
                    .navigationDestination(for: AddVerificationMethod.self) {
                        _ in
                        AddVerificationMethodView(path: $path)
                    }
                    .navigationDestination(for: WalletSettingsHome.self) { _ in
                        WalletSettingsHomeView(path: $path)
                    }
                    .navigationDestination(
                        for: WalletSettingsActivityLog.self
                    ) { _ in
                        WalletSettingsActivityLogView(path: $path)
                    }
                    .navigationDestination(
                        for: VerifierSettingsTrustedCertificates.self
                    ) { _ in
                        VerifierSettingsTrustedCertificatesView(path: $path)
                    }
                    .navigationDestination(for: AddToWallet.self) {
                        addToWalletParams in
                        AddToWalletView(
                            path: $path,
                            rawCredential: addToWalletParams.rawCredential
                        )
                    }
                    .navigationDestination(for: HandleOID4VCI.self) {
                        handleOID4VCIParams in
                        HandleOID4VCIView(
                            path: $path,
                            url: handleOID4VCIParams.url,
                            onSuccess: handleOID4VCIParams.onSuccess
                        )
                    }
                    .navigationDestination(for: DispatchQR.self) { _ in
                        DispatchQRView(path: $path)
                    }
                    .navigationDestination(for: HandleOID4VP.self) {
                        handleOID4VPParams in
                        HandleOID4VPView(
                            path: $path,
                            credentialPackId: handleOID4VPParams.credentialPackId,
                            url: handleOID4VPParams.url
                        )
                    }
                    .navigationDestination(for: HandleMdocOID4VP.self) {
                        handleMdocOID4VPParams in
                        HandleMdocOID4VPView(
                            path: $path,
                            credentialPackId: handleMdocOID4VPParams.credentialPackId,
                            url: handleMdocOID4VPParams.url,
                        )
                    }
                    .navigationDestination(for: CredentialDetails.self) {
                        credentialDetailsParams in
                        CredentialDetailsView(
                            path: $path,
                            credentialPackId: credentialDetailsParams
                                .credentialPackId
                        )
                    }
            }
            .sheet(isPresented: $sheetOpen) {
                ApplySpruceMdlConfirmation(
                    applicationId: $spruceMdlId,
                    sheetOpen: $sheetOpen
                )
                .presentationDetents([.fraction(0.50)])
                .presentationDragIndicator(.hidden)
                .presentationBackgroundInteraction(.automatic)
            }
            .environmentObject(StatusListObservable())
            .environmentObject(credentialPackObservable)
            .environmentObject(keyManager)
            .environmentObject(HacApplicationObservable())
            Toast()
        }
        .onOpenURL { url in
            let scheme = url.scheme

            switch scheme {
            case "spruceid":
                handleSpruceIDUrl(url: url)
            case "openid4vp":
                handleOid4vpUrl(url: url)
            case "openid-credential-offer":
                handleOid4vciUrl(url: url)
            case "mdoc-openid4vp":
                handleMdocOid4vpUrl(url: url)
            default:
                return
            }
        }
    }
}

extension UIScreen {
    static let screenWidth = UIScreen.main.bounds.size.width
    static let screenHeight = UIScreen.main.bounds.size.height
    static let screenSize = UIScreen.main.bounds.size
}

#Preview {
    ContentView()
}
