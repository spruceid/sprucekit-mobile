import SwiftUI

public struct ContentView: View {
    @State var path: NavigationPath = .init()

    public init() {}
    
    func handleSpruceIDUrl(url: URL, path: String?, query: String?) {
        if(query != nil) {
            self.path.append(
                AddToWallet(rawCredential: query!.replacingOccurrences(of: "sd-jwt=", with: ""))
            )
        }
    }
    
    func handleOid4vpUrl(url: URL, path: String?, query: String?) {
        // @TODO: integrate with OID4VP flow
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
            }
        }
        .onOpenURL { url in
            let scheme = url.scheme
            let path = url.path
            let query = url.query
            
            switch scheme {
            case "spruceid":
                handleSpruceIDUrl(url: url, path: path, query: query)
            case "oid4vp":
                handleOid4vpUrl(url: url, path: path, query: query)
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
