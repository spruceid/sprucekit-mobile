import SwiftUI

public struct ContentView: View {
    @State var path: NavigationPath = .init()

    public init() {}

    func handleSpruceIDUrl(url: URL) {
        let query = URLComponents(string: url.absoluteString)?
            .queryItems?
            .first(
                where: {
                    $0.name == "sd-jwt"
                }
            )?.value
        if query != nil {
            self.path.append(
                AddToWallet(rawCredential: query!)
            )
        }
    }

    public var body: some View {
        ZStack {
            // Bg color
            Rectangle()
                .foregroundColor(Color("Bg"))
                .edgesIgnoringSafeArea(.all)
            NavigationStack(path: $path.animation(.easeOut)) {
                HomeView(path: $path)
                    .navigationDestination(for: Scanning.self) { scanningParams in
                        ScanningView(path: $path, scanningParams: scanningParams)
                    }
                    .navigationDestination(for: VerifyDL.self) { _ in
                        VerifyDLView(path: $path)
                    }
                    .navigationDestination(for: VerifyEA.self) { _ in
                        VerifyEAView(path: $path)
                    }
                    .navigationDestination(for: VerifyVC.self) { _ in
                        VerifyVCView(path: $path)
                    }
                    .navigationDestination(for: VerifyMDoc.self) { _ in
                        VerifyMDocView(path: $path)
                    }
                    .navigationDestination(for: VerifierSettingsHome.self) { _ in
                        VerifierSettingsHomeView(path: $path)
                    }
                    .navigationDestination(for: WalletSettingsHome.self) { _ in
                        WalletSettingsHomeView(path: $path)
                    }
                    .navigationDestination(for: AddToWallet.self) { addToWalletParams in
                        AddToWalletView(
                            path: $path,
                            rawCredential: addToWalletParams.rawCredential
                        )
                    }
                    .navigationDestination(for: OID4VCI.self) { _ in
                        OID4VCIView(path: $path)
                    }
                    .navigationDestination(for: DispatchQR.self) { _ in
                        DispatchQRView(path: $path)
                    }
                    .navigationDestination(for: HandleOID4VP.self) { handleOID4VPParams in
                        HandleOID4VPView(
                            path: $path,
                            url: handleOID4VPParams.url
                        )
                    }
            }
        }
        .onOpenURL { url in
            let scheme = url.scheme

            switch scheme {
            case "spruceid":
                handleSpruceIDUrl(url: url)
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
