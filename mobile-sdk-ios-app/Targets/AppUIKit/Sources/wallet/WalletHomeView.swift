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
                        .foregroundColor(Color("ColorStone400"))
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

    var body: some View {
        ZStack {
            if loading {
                LoadingView(
                    loadingText: ""
                )
            } else if !credentialPacks.isEmpty {
                ZStack {
                    ScrollView(.vertical, showsIndicators: false) {
                        Section {
                            ForEach(credentialPacks, id: \.self.id) { credentialPack in
                                GenericCredentialItem(
                                    credentialPack: credentialPack,
                                    onDelete: {
                                        do {
                                            try credentialPack.remove(storageManager: storageManager)
                                            credentialPack.list().forEach { credential in
                                                let credentialInfo = getCredentialIdTitleAndIssuer(
                                                    credentialPack: credentialPack,
                                                    credential: credential
                                                )
                                                _ = WalletActivityLogDataStore.shared.insert(
                                                    credentialPackId: credentialPack.id.uuidString,
                                                    credentialId: credentialInfo.0,
                                                    credentialTitle: credentialInfo.1,
                                                    issuer: credentialInfo.2,
                                                    action: "Deleted",
                                                    dateTime: Date(),
                                                    additionalInformation: ""
                                                )
                                            }
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
                loading = true
                do {
                    self.credentialPacks = try CredentialPack.loadAll(storageManager: storageManager)
                } catch {
                    // TODO: display error message
                    print(error)
                }
                loading = false
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
