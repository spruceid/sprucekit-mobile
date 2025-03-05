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
                    let keyAlias = "mdoc_key"
                    if !KeyManager.keyExists(id: keyAlias) {
                        _ = KeyManager.generateSigningKey(id: keyAlias)
                    }
                    let mdl = try generateTestMdl(
                        keyManager: KeyManager(), keyAlias: keyAlias)

                    let credentialPacks = credentialPackObservable
                        .credentialPacks
                    let mdocPack =
                        credentialPacks.first { pack in
                            pack.list().contains(where: { credential in
                                credential.asMsoMdoc() != nil
                            })
                        } ?? CredentialPack()

                    if mdocPack.list().isEmpty {
                        _ = mdocPack.addMDoc(mdoc: mdl)
                        try await mdocPack.save(
                            storageManager: StorageManager())
                        ToastManager.shared.showSuccess(
                            message: "Test mDL added to your wallet")
                    } else {
                        ToastManager.shared.showWarning(
                            message: "You already have an mDL")
                    }
                } catch (_) {
                    ToastManager.shared.showError(
                        message: "Error generating mDL")
                }

            }
        } label: {
            SettingsHomeItem(
                image: "Unknown",
                title: "Generate mDL",
                description:
                    "Generate a fresh test mDL issued by the SpruceID Test CA"
            )
        }
    }

    var body: some View {
        VStack {
            VStack {
                activityLogButton
                generateMockMdlButton
                Spacer()
                deleteAllCredentials
            }
        }
        .padding(.vertical, 20)
        .padding(.horizontal, 30)
    }
}
