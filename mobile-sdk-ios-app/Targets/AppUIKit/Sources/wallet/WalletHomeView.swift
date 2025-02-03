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
    @Binding var path: NavigationPath
    @EnvironmentObject private var statusListObservable: StatusListObservable
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @State var loading = false

    func loadCredentials() async {
        loading = true
        do {
            let credentialPacks =
                try await credentialPackObservable.loadAndUpdateAll()
            Task {
                await statusListObservable.getStatusLists(
                    credentialPacks: credentialPacks)
            }
        } catch {
            // TODO: display error message
            print(error)
        }
        loading = false
    }

    func onDelete(credentialPack: CredentialPack) {
        Task {
            do {
                try await credentialPackObservable.delete(
                    credentialPack: credentialPack)
                credentialPack.list()
                    .forEach { credential in
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
            } catch {
                // TODO: display error message
                print(error)
            }
        }
    }

    var body: some View {
        ZStack {
            if loading {
                LoadingView(
                    loadingText: ""
                )
            } else if !credentialPackObservable.credentialPacks.isEmpty {
                ZStack {
                    ScrollView(.vertical, showsIndicators: false) {
                        Section {
                            ForEach(
                                credentialPackObservable.credentialPacks,
                                id: \.self.id
                            ) {
                                credentialPack in
                                AnyView(
                                    credentialDisplayerSelector(
                                        credentialPack: credentialPack,
                                        goTo: {
                                            path.append(
                                                CredentialDetails(
                                                    credentialPackId:
                                                        credentialPack.id
                                                        .uuidString))
                                        },
                                        onDelete: {
                                            onDelete(
                                                credentialPack: credentialPack)
                                        }
                                    )
                                )
                            }
                            //                    ShareableCredentialListItem(mdoc: mdocBase64)
                        }
                    }

                    .refreshable {
                        statusListObservable.hasConnection =
                            checkInternetConnection()
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
                statusListObservable.hasConnection = checkInternetConnection()
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
