import SwiftUI

struct WalletSettingsActivityLog: Hashable {}

struct WalletActivityLog: Hashable {
    let id: Int64
    let credential_pack_id: String
    let credential_id: String
    let credential_title: String
    let issuer: String
    let action: String
    let date_time: String
    let additional_information: String
}

struct WalletSettingsActivityLogView: View {
    @Binding var path: NavigationPath

    func onBack() {
        path.removeLast()
    }

    var body: some View {
        VStack {
            WalletSettingsActivityLogHeader(onBack: onBack)
            WalletSettingsActivityLogBody()
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct WalletSettingsActivityLogHeader: View {
    var onBack: () -> Void

    var body: some View {
        HStack {
            Image("Chevron")
                .rotationEffect(.degrees(90))
                .padding(.leading, 30)
            Text("Activity Log")
                .font(.customFont(font: .inter, style: .bold, size: .h2))
                .foregroundStyle(Color("ColorStone950"))
                .padding(.leading, 10)
            Spacer()
        }
        .onTapGesture {
            onBack()
        }
        .padding(.top, 10)
    }
}

struct WalletSettingsActivityLogBody: View {
    let walletActivityLogsReq: [WalletActivityLog] =
        WalletActivityLogDataStore.shared.getAllWalletActivityLogs()

    @ViewBuilder
    var shareButton: some View {
        let activityLogs = walletActivityLogsReq.map {
            "\($0.id),\($0.credential_pack_id),\($0.credential_id),\($0.credential_title),\($0.issuer),\($0.action),\($0.date_time.replaceCommas()),\($0.additional_information)\n"
        }.joined()
        let rows = generateCSV(
            heading:
                "ID,CredentialPackId,CredentialId,CredentialTitle,Issuer,Action,DateTime,AdditionalInformation\n",
            rows: activityLogs,
            filename: "wallet_activity_logs.csv"
        )
        ShareLink(item: rows!) {
            HStack(alignment: .center, spacing: 10) {
                Image("Export")
                    .resizable()
                    .frame(width: CGFloat(18), height: CGFloat(18))
                    .foregroundColor(Color("ColorStone600"))
                Text("Export")
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
                    .foregroundColor(Color("ColorStone950"))
            }
            .foregroundColor(Color("ColorBlue600"))
            .padding(.vertical, 13)
            .frame(width: UIScreen.screenWidth - 40)
            .overlay(
                RoundedRectangle(cornerRadius: 100)
                    .stroke(Color("ColorStone300"), lineWidth: 2)
            )
        }
    }

    var body: some View {
        VStack {
            if walletActivityLogsReq.isEmpty {
                VStack {
                    Text("No Activity Log Found")
                        .font(
                            .customFont(
                                font: .inter, style: .regular, size: .h2)
                        )
                        .foregroundColor(Color("ColorStone400"))
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollView(.vertical, showsIndicators: false) {
                    VStack(alignment: .leading) {
                        ForEach(walletActivityLogsReq, id: \.self) {
                            item in
                            Text(item.credential_title)
                                .font(
                                    .customFont(
                                        font: .inter, style: .bold, size: .h4)
                                )
                                .foregroundColor(Color("ColorStone950"))
                            Text(item.action)
                                .font(
                                    .customFont(
                                        font: .inter, style: .regular, size: .p)
                                )
                                .foregroundColor(Color("ColorStone600"))
                            Text("\(item.date_time)")
                                .font(
                                    .customFont(
                                        font: .inter, style: .regular, size: .p)
                                )
                                .foregroundColor(Color("ColorStone600"))
                            Divider()
                        }
                    }
                    .padding(.bottom, 10.0)
                    .toolbar {
                        ToolbarItemGroup(placement: .bottomBar) {
                            shareButton
                        }
                    }
                }
                .padding(.top, 20)
                .padding(.horizontal, 30)
            }
        }
    }
}
