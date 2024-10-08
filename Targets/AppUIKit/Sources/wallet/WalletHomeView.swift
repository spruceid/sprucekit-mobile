import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

struct WalletHomeView: View {
    @Binding var path: NavigationPath

    var body: some View {
        VStack {
            WalletHomeHeader(path: $path)
            WalletHomeBody(path: $path)
        }
        .navigationBarBackButtonHidden(true)
    }
}

extension Data {
  var base64EncodedUrlSafe: String {
    let string = self.base64EncodedString()

    // Make this URL safe and remove padding
    return string
      .replacingOccurrences(of: "+", with: "-")
      .replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "=", with: "")
  }
}

struct WalletHomeHeader: View {
    @Binding var path: NavigationPath

    var body: some View {
        HStack {
            Text("SpruceKit Demo Wallet")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .padding(.leading, 36)
                .foregroundStyle(Color("TextHeader"))
            Spacer()
            Button {
                path.append(OID4VCI())
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("Primary"))
                        .frame(width: 36, height: 36)
                    Image("QRCodeReader")
                        .foregroundColor(Color("SecondaryIconButton"))
                }
            }
            .padding(.trailing, 4)
            Button {
                path.append(WalletSettingsHome())
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("Primary"))
                        .frame(width: 36, height: 36)
                    Image("User")
                }
            }
            .padding(.trailing, 20)
        }
        .padding(.top, 10)
    }
}

struct WalletHomeBody: View {
    @Binding var path: NavigationPath

    @State var credentials: [Credential] = []

    var body: some View {
        ZStack {
            if !credentials.isEmpty {
                ZStack {
                    ScrollView(.vertical, showsIndicators: false) {
                        Section {
                            ForEach(credentials, id: \.self.id) { credential in
                                AchievementCredentialItem(
                                    rawCredential: credential.rawCredential,
                                    onDelete: {
                                        _ = CredentialDataStore.shared.delete(id: credential.id)
                                        self.credentials = CredentialDataStore.shared
                                            .getAllCredentials()
                                    }
                                )
                            }
                            //                    ForEach(vcs, id: \.self) { vc in
                            //                        GenericCredentialListItem(vc: vc)
                            //                    }
                            //                    ShareableCredentialListItem(mdoc: mdocBase64)
                        }
                        .padding(.bottom, 60)
                    }
                    .padding(.top, 20)

                    VStack {
                        Spacer()
                        Button(action: {
                            path.append(DispatchQR())
                        }) {
                            HStack {
                                Image("QRCodeReader")
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 20, height: 20)
                                    .foregroundColor(.white)
                                Text("Scan to share")
                                    .font(.system(size: 15))
                                    .fontWeight(.regular)
                                    .foregroundColor(.white)
                            }
                            .padding(14)
                            .frame(maxWidth: .infinity)
                            .background(
                                Color("CTAButtonBlue")
                            )
                            .cornerRadius(100)
                        }
                        .padding()
                    }
                }
            } else {
                VStack {
                    Spacer()
                    Section {
                        Image("EmptyWallet")
                    }
                    Spacer()
                }
            }
        }
        .onAppear(perform: {
            self.credentials = CredentialDataStore.shared.getAllCredentials()
        })
    }
}

struct WalletHomeViewPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        WalletHomeView(path: $path)
    }
}
