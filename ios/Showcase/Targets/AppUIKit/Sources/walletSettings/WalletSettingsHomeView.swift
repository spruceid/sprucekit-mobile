import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct WalletSettingsHome: Hashable {}

struct WalletSettingsHomeView: View {
    @Binding var path: NavigationPath

    func onBack() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    var body: some View {
        VStack {
            WalletSettingsHomeHeader(onBack: onBack)
            WalletSettingsHomeBody(
                path: $path,
                onBack: onBack
            )
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct WalletSettingsHomeHeader: View {
    var onBack: () -> Void

    var body: some View {
        HStack {
            Text("Preferences")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .padding(.leading, 30)
                .foregroundStyle(Color("ColorStone950"))
            Spacer()
            Button {
                onBack()
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .foregroundColor(Color("ColorStone950"))
                        .frame(width: 36, height: 36)
                    Image("User")
                        .foregroundColor(Color("ColorStone50"))
                }
            }
            .padding(.trailing, 20)
        }
        .padding(.top, 10)
    }
}

struct WalletSettingsHomeBody: View {
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @EnvironmentObject private var hacApplicationObservable:
        HacApplicationObservable
    @Binding var path: NavigationPath
    var onBack: () -> Void

    @ViewBuilder
    var activityLogButton: some View {
        Button {
            path.append(WalletSettingsActivityLog())
        } label: {
            SettingsHomeItem(
                image: "List",
                title: "Activity Log",
                description: "View and export activity history"
            )
        }
    }

    @ViewBuilder
    var deleteAllCredentials: some View {
        Button {
            Task {
                do {
                    let credentialPacks = credentialPackObservable
                        .credentialPacks
                    try await credentialPacks.asyncForEach { credentialPack in
                        try await credentialPackObservable.delete(
                            credentialPack: credentialPack)
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
                    }
                } catch {
                    // TODO: display error message
                    print(error)
                }
            }
        } label: {
            Text("Delete all added credentials")
                .frame(width: UIScreen.screenWidth)
                .padding(.horizontal, -20)
                .font(.customFont(font: .inter, style: .medium, size: .h4))
        }
        .foregroundColor(.white)
        .padding(.vertical, 13)
        .background(Color("ColorRose700"))
        .cornerRadius(8)
    }

    @ViewBuilder
    var generateMockMdlButton: some View {
        Button {
            Task {
                do {
                    let walletAttestation =
                        try await hacApplicationObservable.getWalletAttestation()
                        .unwrap()

                    let issuanceClient = IssuanceServiceClient(
                        baseUrl: SPRUCEID_HAC_ISSUANCE_SERVICE)
                    let issuance =
                        try await hacApplicationObservable.issuanceClient
                        .newIssuance(walletAttestation: walletAttestation)

                    if let url = URL(
                        string:
                            "\(SPRUCEID_HAC_PROOFING_CLIENT)?id=\(issuance)&redirect=spruceid"
                    ) {
                        UIApplication.shared.open(
                            url, options: [:], completionHandler: nil)
                    }
                } catch {
                    print(error.localizedDescription)
                    ToastManager.shared.showError(
                        message: "Error generating mDL")
                }
            }
        } label: {
            SettingsHomeItem(
                image: "GenerateMockMdl",
                title: "Generate mDL",
                description:
                    "Generate a fresh test mDL issued by the SpruceID Test CA"
            )
        }
    }

    @ViewBuilder
    var applyForSpruceMdlButton: some View {
        Button {
            Task {
                do {
                    let walletAttestation =
                        try await hacApplicationObservable.getWalletAttestation()
                        .unwrap()

                    let issuance =
                        try await hacApplicationObservable.issuanceClient
                        .newIssuance(walletAttestation: walletAttestation)

                    let hacApplication = HacApplicationDataStore.shared.insert(
                        issuanceId: issuance
                    )

                    if let url = URL(
                        string:
                            "\(SPRUCEID_HAC_PROOFING_CLIENT)?id=\(hacApplication!)&redirect=spruceid"
                    ) {
                        UIApplication.shared.open(
                            url, options: [:], completionHandler: nil)
                    }
                } catch {
                    print(error.localizedDescription)
                }
            }
        } label: {
            SettingsHomeItem(
                image: "ApplySpruceMdl",
                title: "Apply for Spruce mDL",
                description:
                    "Verify your identity in order to claim this high assurance credential"
            )
        }
    }

    var body: some View {
        VStack {
            VStack {
                activityLogButton
                generateMockMdlButton
                applyForSpruceMdlButton
                Spacer()
                deleteAllCredentials
            }
        }
        .padding(.vertical, 20)
        .padding(.horizontal, 30)
    }
}
