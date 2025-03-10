import SwiftUI
import SpruceIDMobileSdk

struct VerifierSettingsActivityLog: Hashable {}

struct VerificationActivityLog: Hashable {
    let id: Int64
    let credential_title: String
    let issuer: String
    let status: String
    let verification_date_time: String
    let additional_information: String
}

struct VerifierSettingsActivityLogView: View {
    @Binding var path: NavigationPath

    func onBack() {
        path.removeLast()
    }

    var body: some View {
        VStack {
            VerifierSettingsActivityLogHeader(onBack: onBack)
            VerifierSettingsActivityLogBody()
        }
        .navigationBarBackButtonHidden(true)
    }
}

struct VerifierSettingsActivityLogHeader: View {
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

struct VerifierSettingsActivityLogBody: View {
    let verificationActivityLogsReq: [VerificationActivityLog] = VerificationActivityLogDataStore.shared.getAllVerificationActivityLogs()

    @ViewBuilder
    var shareButton: some View {
        let activityLogs = verificationActivityLogsReq.map {
            "\($0.id),\($0.credential_title),\($0.issuer),\($0.status),\($0.verification_date_time.replaceCommas()),\($0.additional_information)\n"
        }.joined()
        let rows = generateCSV(
            heading:
                "ID,CredentialTitle,Issuer,Status,VerificationDateTime,AdditionalInformation\n",
            rows: activityLogs,
            filename: "activity_logs.csv"
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
            if verificationActivityLogsReq.isEmpty {
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
                        ForEach(verificationActivityLogsReq, id: \.self) { item in
                            Text(item.credential_title)
                                .font(
                                    .customFont(
                                        font: .inter, style: .bold, size: .h4)
                                )
                                .foregroundColor(Color("ColorStone950"))
                            if item.issuer != "" {
                                Text(item.issuer)
                                    .font(
                                        .customFont(
                                            font: .inter, style: .regular, size: .p)
                                    )
                                    .foregroundColor(Color("ColorStone600"))
                            }
                            CredentialStatusSmall(status: CredentialStatusList(rawValue: item.status))
                            Text("\(item.verification_date_time)")
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
