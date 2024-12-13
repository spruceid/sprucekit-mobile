import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct WalletHomeView: View {
    @Binding var path: NavigationPath

    var body: some View {
        VStack {
            WalletHomeHeader(path: $path)
            WalletHomeBody()
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
                .foregroundStyle(Color("ColorStone950"))
            Spacer()
            Button {
                path.append(DispatchQR())
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("ColorBase150"))
                        .frame(width: 36, height: 36)
                    Image("QRCodeReader")
                        .foregroundColor(Color("ColorStone400"))
                }
            }
            .padding(.trailing, 4)
            Button {
                path.append(WalletSettingsHome())
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("ColorBase150"))
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
    @State var credentialPacks: [CredentialPack] = []
    let storageManager = StorageManager()
    @State var loading = false
    @State var hasConnection = true
    
    func loadCredentials() async {
        loading = true
        do {
            self.credentialPacks = try CredentialPack.loadAll(storageManager: storageManager)
        } catch {
            // TODO: display error message
            print(error)
        }
        loading = false
    }

    var body: some View {
        ZStack {
            if loading {
                LoadingView(
                    loadingText: ""
                )
            } else if credentialPacks.isEmpty {
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
                                    },
                                    hasConnection: $hasConnection
                                )
                            }
                            .id(UUID()) // make sure we are recreating all items when refresh
                            ShareableCredentialListItem(mdoc: mdocBase64)
                        }
                    }
                    
                    .refreshable {
                        hasConnection = checkInternetConnection()
                        await loadCredentials()
                    }
                    .padding(.top, 20)
                }
            } else {
                ZStack {
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
                hasConnection = checkInternetConnection()
                await loadCredentials()
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
