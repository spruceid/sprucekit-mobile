import SwiftUI

enum HomeTabs {
    case wallet
    case verifier
}

struct HomeView: View {
    @Binding var path: NavigationPath
    @State var tab = HomeTabs.wallet

    var body: some View {
        VStack {
            ZStack {
                switch tab {
                case .wallet:
                    WalletHomeView(path: $path)
                case .verifier:
                    VerifierHomeView(path: $path)
                }
            }
            .frame(maxWidth: .infinity)

            HomeBottomTabs(
                tab: $tab
            )
        }
    }
}

struct HomeBottomTabs: View {
    @Binding var tab: HomeTabs

    var body: some View {
        HStack {
            HStack {
                Button {
                    tab = HomeTabs.wallet
                } label: {
                    Image("Wallet")
                        .foregroundColor(
                            tab == HomeTabs.wallet
                                ? Color.white : Color("ColorBlue300")
                        )
                    Text("Wallet")
                        .foregroundColor(
                            tab == HomeTabs.wallet
                                ? Color.white : Color("ColorBlue300")
                        )
                        .font(
                            .customFont(font: .inter, style: .medium, size: .p))
                }
                .frame(height: 40)
                .padding(.horizontal, 12)
                .background(
                    tab == HomeTabs.wallet
                        ? Color("ColorBlue500") : Color("ColorBlue900")
                )
                .cornerRadius(10)

                Button {
                    tab = HomeTabs.verifier
                } label: {
                    Image("QRCodeReader")
                        .foregroundColor(
                            tab == HomeTabs.verifier
                                ? Color.white : Color("ColorBlue300")
                        )
                    Text("Verifier")
                        .foregroundColor(
                            tab == HomeTabs.verifier
                                ? Color.white : Color("ColorBlue300")
                        )
                        .font(
                            .customFont(font: .inter, style: .medium, size: .p))
                }
                .frame(height: 40)
                .padding(.horizontal, 12)
                .background(
                    tab == HomeTabs.verifier
                        ? Color("ColorBlue500") : Color("ColorBlue900")
                )
                .cornerRadius(10)
            }
            .frame(height: 52)
            .padding(.horizontal, 4)
            .background(Color("ColorBlue900"))
            .cornerRadius(14)
        }
        .frame(maxWidth: .infinity)
    }

}

struct HomeViewPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        HomeView(path: $path)
    }
}
