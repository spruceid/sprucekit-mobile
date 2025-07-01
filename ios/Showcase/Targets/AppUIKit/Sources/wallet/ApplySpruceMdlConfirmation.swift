import SwiftUI

struct ApplySpruceMdlConfirmation: View {
    @Binding var applicationId: String?
    @Binding var sheetOpen: Bool
    @State var hacApplication: HacApplication?

    @EnvironmentObject var hacApplicationObservable: HacApplicationObservable

    var body: some View {
        ScrollView {
            VStack {
                Text("Submitted successfully")
                    .font(
                        .customFont(
                            font: .inter,
                            style: .regular,
                            size: .h0
                        )
                    )
                    .foregroundStyle(Color("ColorBlue600"))
                    .padding(.top, 24)

                if let hacApp = hacApplication {
                    HacApplicationListItem(
                        hacApplication: hacApp
                    )
                } else {
                    ProgressView()
                        .padding(.vertical, 20)
                }

                Text(
                    "Your information has been submitted. Approval can take between 20 minutes and 5 days."
                )
                .font(
                    .customFont(
                        font: .inter,
                        style: .regular,
                        size: .h4
                    )
                )
                .foregroundStyle(Color("ColorStone600"))
                .padding(.top, 12)

                Text(
                    "After being approved, your credential will be show a valid status and be available to use."
                )
                .font(
                    .customFont(
                        font: .inter,
                        style: .regular,
                        size: .h4
                    )
                )
                .foregroundStyle(Color("ColorStone600"))
                .padding(.top, 12)
            }
            .padding(.horizontal, 20)

            Button {
                sheetOpen = false
            } label: {
                Text("Okay, sounds good")
                    .frame(width: UIScreen.screenWidth)
                    .padding(.horizontal, -20)
                    .font(
                        .customFont(
                            font: .inter,
                            style: .regular,
                            size: .h4
                        )
                    )
                    .foregroundStyle(Color("ColorBase50"))
            }
            .padding(.vertical, 12)
            .background(Color("ColorStone700"))
            .clipShape(RoundedRectangle(cornerRadius: 100))
            .padding(.top, 30)
        }
        .onAppear(perform: {
            if let id = applicationId {
                hacApplication = hacApplicationObservable.getApplication(
                    issuanceId: id
                )
                if let app = hacApplication {
                    Task {
                        await hacApplicationObservable.updateIssuanceState(
                            applicationId: app.id,
                            issuanceId: app.issuanceId
                        )
                    }
                }
            }
        })
        .onChange(of: applicationId) { newValue in
            if let id = newValue {
                hacApplication = hacApplicationObservable.getApplication(
                    issuanceId: id
                )
                if let app = hacApplication {
                    Task {
                        await hacApplicationObservable.updateIssuanceState(
                            applicationId: app.id,
                            issuanceId: app.issuanceId
                        )
                    }
                }
            }
        }
    }
}
