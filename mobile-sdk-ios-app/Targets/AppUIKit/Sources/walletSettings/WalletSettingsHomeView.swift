import SwiftUI
import SpruceIDMobileSdk

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
    @Binding var path: NavigationPath
    var onBack: () -> Void
    
    let storageManager = StorageManager()

    @ViewBuilder
    var activityLogButton: some View {
        Button {
            path.append(WalletSettingsActivityLog())
        } label: {
            HStack(alignment: .top) {
                VStack {
                    HStack {
                        Image("List")
                        Text("Activity Log")
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .foregroundColor(Color("ColorStone950"))
                            .font(
                                .customFont(
                                    font: .inter, style: .bold, size: .h4))
                    }
                    Text("View and export verification history")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundColor(Color("ColorStone600"))
                        .font(
                            .customFont(font: .inter, style: .regular, size: .p)
                        )
                }
                Image("Chevron")
                    .rotationEffect(.degrees(-90))
            }
        }
    }

    @ViewBuilder
    var deleteAllCredentials: some View {
        Button {
            do {
                let credentialPacks = try CredentialPack.loadAll(storageManager: storageManager)
                try credentialPacks.forEach { credentialPack in
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
                }
            } catch {
                // TODO: display error message
                print(error)
            }
        }  label: {
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

    var body: some View {
        VStack {
            VStack {
                activityLogButton
                Spacer()
                deleteAllCredentials
            }
        }
        .padding(.vertical, 20)
        .padding(.horizontal, 30)
    }
}
