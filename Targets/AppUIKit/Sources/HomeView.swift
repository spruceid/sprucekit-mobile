import SwiftUI

struct HomeView: View {

    @Binding var path: NavigationPath

    var body: some View {
        TabView {
            WalletHomeView(path: $path)
                .tabItem {
                    Text("Wallet")
                }
            VerifierHomeView(path: $path)
                .tabItem {
                    Text("Verifier")
                }
        }
    }
}

struct HomeViewPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        HomeView(path: $path)
    }
}
