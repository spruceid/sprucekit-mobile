import SwiftUI

struct VerifierSettingsHome: Hashable {}

struct VerifierSettingsHomeView: View {
    @Binding var path: NavigationPath

    func onBack() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    var body: some View {
        VStack {
            VerifierSettingsHomeHeader(onBack: onBack)
            VerifierSettingsHomeBody(
                path: $path,
                onBack: onBack
            )
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct VerifierSettingsHomeHeader: View {
    var onBack: () -> Void

    var body: some View {
        HStack {
            Text("Settings")
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
                    Image("Cog")
                        .foregroundColor(Color("ColorStone50"))
                }
            }
            .padding(.trailing, 20)
        }
        .padding(.top, 10)
    }
}

struct VerifierSettingsHomeBody: View {
    @Binding var path: NavigationPath
    var onBack: () -> Void

    @ViewBuilder
    var activityLogButton: some View {
        Button {
            path.append(VerifierSettingsActivityLog())
        } label: {
            SettingsHomeItem(
                image: "List",
                title: "Activity Log",
                description: "View and export verification history."
            )
        }
    }

    @ViewBuilder
    var deleteAllVerificationMethodsButton: some View {
        Button {
            _ = VerificationMethodDataStore.shared.deleteAll()
        } label: {
            Text("Delete all added verification methods")
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
    var trustedCertificatesButton: some View {
        Button {
            path.append(VerifierSettingsTrustedCertificates())
        } label: {
            SettingsHomeItem(
                image: "Unknown",
                title: "Trusted Certificates",
                description:
                    "Manage trusted certificates used during mDoc verification."
            )
        }
    }

    var body: some View {
        VStack {
            VStack {
                activityLogButton
                trustedCertificatesButton
                Spacer()
                deleteAllVerificationMethodsButton
            }
        }
        .padding(.vertical, 20)
        .padding(.horizontal, 30)
    }
}
