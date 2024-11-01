import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

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

struct WalletHomeHeader: View {
    @Binding var path: NavigationPath

    var body: some View {
        HStack {
            Text("Wallet")
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

    @State var credentialPacks: [CredentialPack] = []
    let storageManager = StorageManager()

    var body: some View {
        ZStack {
            if !credentialPacks.isEmpty {
                ZStack {
                    ScrollView(.vertical, showsIndicators: false) {
                        Section {
                            ForEach(credentialPacks, id: \.self.id) { credentialPack in
                                GenericCredentialItem(
                                    credentialPack: credentialPack,
                                    onDelete: {
                                        do {
                                            try credentialPack.remove(storageManager: storageManager)
                                            self.credentialPacks = try CredentialPack.loadAll(storageManager: storageManager)
                                        } catch {
                                            // TODO: display error message
                                            print(error)
                                        }
                                    }
                                )
                            }
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
                ZStack {
                    VStack {
                        Section {
                            Image("AddFirstCredential")
                        }
                        Spacer()
                    }
                    VStack {
                        Spacer()
                        Section {
                            Image("EmptyWallet")
                        }
                        Spacer()
                    }
                }
            }
        }
        .onAppear(perform: {
            Task {
                do {
                    self.credentialPacks = try CredentialPack.loadAll(storageManager: storageManager)
                } catch {
                    // TODO: display error message
                    print(error)
                }
            }
        })
    }
}

struct WalletHomeViewPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        WalletHomeView(path: $path)
    }
}
