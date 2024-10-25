import SwiftUI

struct VerificationActivityLog: Hashable {
    let id: Int64
    let name: String
    let credential_title: String
    let expiration_date: String
    let status: String
    let date: String
}

struct VerificationActivityLogView: View {

    var onBack: () -> Void

    let verificationActivityLogsReq: [VerificationActivityLog] = VerificationActivityLogDataStore.shared.getAllVerificationActivityLogs()

    @ViewBuilder
    var shareButton: some View {
        let activityLogs = verificationActivityLogsReq.map {"\($0.name),\($0.credential_title),\($0.expiration_date),\($0.status),\($0.date)\n"}.joined()
        let rows = generateCSV(
            heading: "Name, Credential title, Permit Expiration, Status, Date\n",
            rows: activityLogs,
            filename: "verification_activity_logs.csv"
        )
        ShareLink(item: rows!) {
            HStack(alignment: .center, spacing: 10) {
                Image("Export")
                    .resizable()
                    .frame(width: CGFloat(18), height: CGFloat(18))
                    .foregroundColor(Color("CTAButtonBlue"))
                Text("Export")
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(Color("CTAButtonBlue"))
            .padding(.vertical, 13)
            .frame(width: UIScreen.screenWidth - 40)
            .overlay(
                RoundedRectangle(cornerRadius: 100)
                    .stroke(Color("CTAButtonBlue"), lineWidth: 2)
            )
        }
    }

    var body: some View {
        VStack {
            ScrollView(.vertical, showsIndicators: false) {
                VStack(alignment: .leading) {
                    Text("Coming Soon")
                        .font(.customFont(font: .inter, style: .medium, size: .h4))
                        .foregroundColor(Color("TextBody"))
                    ForEach(verificationActivityLogsReq, id: \.self) { item in
                        let formatter = DateFormatter()
                        let _ = formatter.dateFormat = "MM/dd/yyyy"
                        let expDate = formatter.date(from: item.expiration_date)!

                        Text(item.name)
                            .font(.customFont(font: .inter, style: .medium, size: .h4))
                            .foregroundColor(Color("TextBody"))
                        Text(item.name)
                            .font(.customFont(font: .inter, style: .medium, size: .h4))
                            .foregroundColor(Color("TextBody"))
                        HStack(alignment: .center) {
                            Text(item.status)
                                .font(.customFont(font: .inter, style: .regular, size: .h4))
                                .foregroundColor(Color("TextBody"))
                            Spacer()
                            Text("\(Date.now.compare(expDate) == .orderedDescending ? "expired" : "expires") on \(item.expiration_date)")
                                .font(.customFont(font: .inter, style: .medium, size: .h4))
                                .foregroundColor(Color("TextBody"))
                        }
                        Text("Scanned on \(item.date)")
                            .font(.customFont(font: .inter, style: .italic, size: .h4))
                            .foregroundColor(Color("CodeBorder"))
                            .padding(.top, 8)
                        Divider()
                    }
                }
                .padding(.bottom, 10.0)
                .padding(.horizontal, 20.0)
                .toolbar {
                    ToolbarItemGroup(placement: .bottomBar) {
                        shareButton
                    }
                }
            }
            .padding(.top, 20.0)
        }
    }
}
