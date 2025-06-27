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
            } else if !credentialPackObservable.credentialPacks.isEmpty
                || !hacApplicationObservable.hacApplications.isEmpty
            {
                ZStack {
                    ScrollView(.vertical, showsIndicators: false) {
                        Section {
                            ForEach(
                                hacApplicationObservable.hacApplications,
                                id: \.self.id
                            ) { hacApplication in
                                HacApplicationListItem(
                                    application: hacApplication,
                                    startIssuance: { credentialOfferUrl in
                                        path.append(
                                            HandleOID4VCI(
                                                url: credentialOfferUrl,
                                                onSuccess: {
                                                    _ = HacApplicationDataStore
                                                        .shared.delete(
                                                            id: hacApplication
                                                                .id)
                                                }
                                            )
                                        )
                                    }
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
                                                        .uuidString))
                                        },
                                        onDelete: {
                                            onDelete(
                                                credentialPack: credentialPack)
                                        }
                                    )
                                )
                            }
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
                WalletHomeViewNoCredentials(
                    onButtonClick: {
                        Task {
                            await generateMockMdl()
                            statusListObservable.hasConnection = checkInternetConnection()
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

struct WalletHomeViewNoCredentials: View {
    let onButtonClick: () async -> Void

    var body: some View {
        ZStack {
            VStack {
                Section {
                    // No-credential Card
                    VStack(spacing: 36) {
                        // Title and Subtitle
                        VStack(spacing: 8){
                            Text("Welcome!")
                                .font(.customFont(font: .inter, style: .bold, size: .h0))
                                .foregroundColor(Color("ColorBlue600"))
                            Text("You currently have no credentials in your wallet")
                                .font(.customFont(font: .inter, style: .medium, size: .p))
                                .foregroundColor(Color("ColorStone600"))
                                .lineLimit(1)
                                .truncationMode(.tail)
                                .minimumScaleFactor(0.8)
                        }
                        // MDL Image
                        Image("mDLImage")
                            .resizable()
                            .scaledToFit()
                        // Button
                        Button {
                            Task {
                                await onButtonClick()
                            }
                        } label: {
                            HStack(spacing: 6) {
                                Image("GenerateMockMdl")
                                    .renderingMode(.template)
                                    .foregroundColor(.white)
                                    .frame(width: 20, height: 20)

                                Text("Generate a Spruce mDL")
                                    .font(
                                        .customFont(font: .inter, style: .semiBold, size: .h4)
                                    )
                            }
                            .frame(maxWidth: .infinity, alignment: .center)
                            .frame(height: 25)
                            .padding(.vertical, 8)
                            .padding(.horizontal, 20)
                        }
                        .background(Color("ColorBlue600"))
                        .cornerRadius(100)
                        .foregroundColor(.white)
                        .overlay(
                            RoundedRectangle(cornerRadius: 100)
                            .strokeBorder(
                                LinearGradient(
                                    gradient: Gradient(stops: [
                                        .init(
                                            color: Color.white.opacity(0.2),
                                            location: 0.0
                                        ),
                                        .init(
                                            color: Color("ColorBlue800"),
                                            location: 0.4
                                        ),
                                        .init(
                                            color: Color("ColorBlue900"),
                                            location: 1.0
                                        ),
                                    ]),
                                    startPoint: .top,
                                    endPoint: .bottom
                                ),
                                lineWidth: 2
                            )
                        )
                    }
                    .padding(EdgeInsets(top: 24, leading: 20, bottom: 16, trailing: 20))
                    .background(
                        LinearGradient(
                            colors: [Color("ColorBase100"), Color("ColorBlue100")],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )
                    .cornerRadius(12)
                    .overlay(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color.white, lineWidth: 2)
                    )
                    .shadow(color: Color.black.opacity(0.05), radius: 6, x: 0, y: 4)
                    .shadow(color: Color.black.opacity(0.10), radius: 6, x: 0, y: 10)
                }
                Spacer()
            }
            .padding(.top, 20)
            .padding(.horizontal, 20)
        }
        .transition(
            .asymmetric(
                insertion: .identity,
                removal: .opacity
            )
        )
    }
}
