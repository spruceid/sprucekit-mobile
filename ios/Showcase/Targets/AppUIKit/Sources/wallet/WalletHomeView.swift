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
    
    var gradientColors = [Color("ColorBlue600"), Color("ColorBase1")]

    var body: some View {
        let buttons = [
            HeaderButton(
                icon: Image("QRCodeReader"),
                contentDescription: "Universal QRCode Reader"
            ) {
                path.append(DispatchQR())
            },
            HeaderButton(
                icon: Image("User"),
                contentDescription: "Wallet settings"
            ) {
                path.append(WalletSettingsHome())
            }
        ]
        HomeHeader(title: "Wallet", gradientColors: gradientColors, buttons: buttons)
    }
}

struct WalletHomeBody: View {
    @Binding var path: NavigationPath
    @EnvironmentObject private var statusListObservable: StatusListObservable
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @EnvironmentObject private var hacApplicationObservable:
        HacApplicationObservable
    @State var loading = false

    func loadCredentials() async {
        loading = true
        do {
            hacApplicationObservable.loadAll()
            let credentialPacks =
                try await credentialPackObservable.loadAndUpdateAll()
            Task {
                await statusListObservable.getStatusLists(
                    credentialPacks: credentialPacks
                )
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
                    credentialPack: credentialPack
                )
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
            } else if !credentialPackObservable.credentialPacks.isEmpty
                || !hacApplicationObservable.hacApplications.isEmpty
            {
                ZStack {
                    ScrollView(.vertical, showsIndicators: false) {
                        VStack(spacing: 0) {
                            ForEach(
                                hacApplicationObservable.hacApplications,
                                id: \.self.id
                            ) { hacApplication in
                                HacApplicationListItem(
                                    path: Binding<NavigationPath?>($path),
                                    hacApplication: hacApplication
                                )
                            }
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
                                                        .uuidString
                                                )
                                            )
                                        },
                                        onDelete: {
                                            onDelete(
                                                credentialPack: credentialPack
                                            )
                                        }
                                    )
                                )
                            }
                        }
                        .padding(.bottom, 120)
                    }
                    .refreshable {
                        statusListObservable.hasConnection =
                            checkInternetConnection()
                        await loadCredentials()
                        await hacApplicationObservable.updateAllIssuanceStates()
                    }
                    .padding(.top, 0)
                }
            } else {
                WalletHomeViewNoCredentials(
                    onButtonClick: {
                        Task {
                            await generateMockMdl()
                            statusListObservable.hasConnection =
                                checkInternetConnection()
                            await loadCredentials()
                        }
                    }
                )
            }
        }
        .animation(.easeInOut(duration: 0.3), value: loading)
        .onAppear(perform: {
            Task {
                statusListObservable.hasConnection = checkInternetConnection()
                await loadCredentials()
                do {
                    try await credentialPackObservable.registerUnregisteredIDProviderDocuments()
                } catch {
                    print("Failed to register unregistered id provider documents", error)
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
