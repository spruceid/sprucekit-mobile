import SwiftUI

struct WalletHomeView: View {
    @Binding var path: NavigationPath

    var body: some View {
        VStack {
            WalletHomeHeader()
            WalletHomeBody(path: $path)
        }
    }
}

struct WalletHomeHeader: View {
    var body: some View {
        HStack {
            Text("Spruce Wallet")
                .font(.customFont(font: .inter, style: .bold, size: .h0))
                .padding(.leading, 36)
                .foregroundStyle(Color("TextHeader"))
            Spacer()
//            Button {
//                // TODO
//            } label: {
//                ZStack {
//                    RoundedRectangle(cornerRadius: 8)
//                        .foregroundColor(Color("Primary"))
//                        .frame(width: 36, height: 36)
//                    Image("User")
//                }
//            }
//            .padding(.trailing, 20)
        }
        .padding(.top, 10)
    }
}

struct WalletHomeBody: View {
    @Binding var path: NavigationPath

    var body: some View {
        ZStack {
            ScrollView(.vertical, showsIndicators: false) {
                Section {
                    ForEach(vcs, id: \.self) { vc in
                        GenericCredentialListItem(vc: vc)
                    }
                    ShareableCredentialListItem(mdoc: mdocBase64)
                }
                .padding(.bottom, 50)
            }
//            VStack {
//                Spacer()
//                Button{
//                    path.append(Scanning(scanningType: .qrcode))
//                } label: {
//                    HStack(alignment: .center, spacing: 10) {
//                        Image("QRCodeReader")
//                            .resizable()
//                            .frame(width: CGFloat(18), height: CGFloat(18))
//                            .foregroundColor(.scanButton)
//                        Text("Scan to share")
//                            .font(.customFont(font: .inter, style: .medium, size: .h4))
//                    }
//                    .foregroundStyle(.white)
//                    .padding(.vertical, 13)
//                    .frame(width: UIScreen.screenWidth - 40)
//                    .background(.scanButton)
//                    .cornerRadius(100)
//                    .overlay(
//                        RoundedRectangle(cornerRadius: 100)
//                            .stroke(.scanButton, lineWidth: 2)
//                    )
//                    .padding(.bottom, 6)
//                }
//            }
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct WalletHomeViewPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        WalletHomeView(path: $path)
    }
}
