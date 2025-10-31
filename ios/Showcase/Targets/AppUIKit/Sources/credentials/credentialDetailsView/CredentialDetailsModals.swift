import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct DetailsModal: View {
    let credentialItem: (any ICredentialView)?
    let statusList: CredentialStatusList?
    let onClose: () -> Void
    let onBack: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            // Content
            ScrollView {
                VStack {
                    if let item = credentialItem {
                        if statusList != .revoked {
                            AnyView(item.credentialDetails())
                        } else {
                            AnyView(item.credentialRevokedInfo(onClose: {
                                onClose()
                                onBack()
                            }))
                        }
                    }
                }
                .padding(.horizontal, 20)
                .padding(.top, 32)
            }

            // Bottom close button bar
            VStack {
                Divider()
                Button(action: onClose) {
                    HStack(spacing: 8) {
                        Image("Invalid")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 22, height: 22)
                            .rotationEffect(.degrees(180))
                            .foregroundColor(Color("ColorStone950"))
                        Text("Close")
                            .font(.customFont(font: .inter, style: .medium, size: .h4))
                            .foregroundColor(Color("ColorStone950"))
                    }
                    .padding(.vertical, 8)
                    .padding(.horizontal, 16)
                }
                .background(
                    RoundedRectangle(cornerRadius: 6)
                        .fill(Color.clear)
                )
                .padding(.vertical, 16)
            }
            .background(Color.white)
        }
    }
}

struct ActivityLogModal: View {
    let credentialPackId: String
    let onClose: () -> Void
    @State private var activityLogs: [WalletActivityLog] = []

    var body: some View {
        VStack(spacing: 0) {
            // Title
            HStack {
                Text("Activity Log")
                    .font(.customFont(font: .inter, style: .semiBold, size: .h2))
                    .foregroundColor(Color("ColorStone950"))
                    .padding(.horizontal, 20)
                    .padding(.vertical, 24)
                Spacer()
            }

            // Content
            ScrollView {
                WalletSettingsActivityLogScreenBody(
                    walletActivityLogs: activityLogs,
                    credentialPackId: credentialPackId
                )
                .padding(.horizontal, 20)
            }

            // Bottom close button bar
            VStack {
                Button(action: onClose) {
                    HStack(spacing: 8) {
                        Image("Invalid")
                            .resizable()
                            .aspectRatio(contentMode: .fit)
                            .frame(width: 22, height: 22)
                            .rotationEffect(.degrees(180))
                            .foregroundColor(Color("ColorStone950"))
                        Text("Close")
                            .font(.customFont(font: .inter, style: .medium, size: .h4))
                            .foregroundColor(Color("ColorStone950"))
                    }
                    .padding(.vertical, 8)
                    .padding(.horizontal, 16)
                }
                .background(
                    RoundedRectangle(cornerRadius: 6)
                        .fill(Color.clear)
                )
                .padding(.vertical, 16)
            }
            .background(Color.white)
        }
        .onAppear {
            activityLogs = WalletActivityLogDataStore.shared.getWalletActivityLogsByCredentialPackId(
                credentialPackId: credentialPackId
            )
        }
    }
}

struct WalletSettingsActivityLogScreenBody: View {
    let walletActivityLogs: [WalletActivityLog]
    let credentialPackId: String

    var body: some View {
        VStack {
            if walletActivityLogs.isEmpty {
                VStack {
                    Text("No Activity Log Found")
                        .font(.customFont(font: .inter, style: .regular, size: .h2))
                        .foregroundColor(Color("ColorStone400"))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                VStack(alignment: .leading) {
                    ForEach(walletActivityLogs, id: \.self) { item in
                        Text(item.credential_title)
                            .font(.customFont(font: .inter, style: .bold, size: .h4))
                            .foregroundColor(Color("ColorStone950"))
                        Text(item.action)
                            .font(.customFont(font: .inter, style: .regular, size: .p))
                            .foregroundColor(Color("ColorStone600"))
                        Text("\(item.date_time)")
                            .font(.customFont(font: .inter, style: .regular, size: .p))
                            .foregroundColor(Color("ColorStone600"))
                        Divider()
                    }
                }
                .padding(.bottom, 10)

            }
        }
    }
}
