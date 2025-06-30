import DeviceCheck
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
    @StateObject private var environmentConfig = EnvironmentConfig.shared
    @Binding var path: NavigationPath
    var onBack: () -> Void
    @State private var isApplyingForMdl = false

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
                            credentialPack: credentialPack
                        )
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
                    let _ = HacApplicationDataStore.shared.deleteAll()
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
                await generateMockMdl()
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
            isApplyingForMdl = true
            Task {
                do {
                    let walletAttestation =
                        try await hacApplicationObservable
                        .getWalletAttestation()
                        .unwrap()

                    let issuance =
                        try await hacApplicationObservable.issuanceClient
                        .newIssuance(walletAttestation: walletAttestation)

                    let hacApplication = HacApplicationDataStore.shared.insert(
                        issuanceId: issuance
                    )

                    let status = try await hacApplicationObservable.issuanceClient.checkStatus(
                        issuanceId: issuance,
                        walletAttestation: walletAttestation
                    )

                    switch status {
                        case .proofingRequired(let proofingUrl):
                            if let hacApplication = hacApplication {
                                if let url = URL(string: proofingUrl) {
                                    UIApplication.shared.open(
                                        url,
                                        options: [:],
                                        completionHandler: nil
                                    )
                                } else {
                                    print("Invalid proofing URL")
                                }
                            } else {
                                print("hacApplication is nil")
                            }

                        case .readyToProvision(_):
                            print("Expected ProofingRequired status")
                            ToastManager.shared.showError(
                                message:
                                    "Error during attestation: Expected ProofingRequired status"
                            )
                        case .awaitingManualReview:
                            print("Expected ProofingRequired status")
                            ToastManager.shared.showError(
                                message:
                                    "Error during attestation: Expected ProofingRequired status"
                            )
                        case .applicationDenied:
                            print("Expected ProofingRequired status")
                            ToastManager.shared.showError(
                                message:
                                    "Error during attestation: Expected ProofingRequired status"
                            )
                        }

                } catch let error as DCError {
                    ToastManager.shared.showError(
                        message:
                            "App Attestation failed: \(error.localizedDescription)"
                    )
                } catch {
                    ToastManager.shared.showError(
                        message:
                            "Error during attestation: \(error.localizedDescription)"
                    )
                }
                isApplyingForMdl = false
            }
        } label: {
            SettingsHomeItem(
                image: "ApplySpruceMdl",
                title: "Apply for Spruce mDL",
                description:
                    "Verify your identity in order to claim this high assurance credential"
            )
        }
        .disabled(isApplyingForMdl)
        .opacity(isApplyingForMdl ? 0.5 : 1.0)
    }

    @ViewBuilder
    var devModeButton: some View {
        Button {
            environmentConfig.toggleDevMode()
        } label: {
            SettingsHomeItem(
                image: "DevMode",
                title:
                    "\(environmentConfig.isDevMode ? "Disable" : "Enable") Dev Mode",
                description:
                    "Warning: Dev mode will use in development services and is not recommended for production use"
            )
        }
    }

    var body: some View {
        VStack {
            VStack {
                activityLogButton
                generateMockMdlButton
                applyForSpruceMdlButton
                devModeButton
                Spacer()
                deleteAllCredentials
            }
        }
        .padding(.vertical, 20)
        .padding(.horizontal, 30)
    }
}
