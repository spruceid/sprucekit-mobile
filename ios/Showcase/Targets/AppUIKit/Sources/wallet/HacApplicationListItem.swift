import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct HacApplicationListItem: View {
    @Binding var path: NavigationPath?
    @EnvironmentObject var hacApplicationObservable: HacApplicationObservable

    let hacApplication: HacApplication?
    @State var showDeleteDialog: Bool = false

    private var issuanceStatus: FlowState? {
        guard let application = hacApplication else { return nil }
        return hacApplicationObservable.getIssuanceState(for: application.id)
    }

    init(
        path: Binding<NavigationPath?> = .constant(nil),
        hacApplication: HacApplication?
    ) {
        self._path = path
        self.hacApplication = hacApplication
    }

    init(
        path: Binding<NavigationPath?> = .constant(nil),
        hacApplication: HacApplication
    ) {
        self._path = path
        self.hacApplication = hacApplication
    }

    func deleteApplication() {
        guard let application = hacApplication else { return }
        _ = HacApplicationDataStore.shared.delete(id: application.id)
        hacApplicationObservable.clearIssuanceState(for: application.id)
        hacApplicationObservable.loadAll()
    }

    var body: some View {
        if let application = hacApplication {
            HStack {
                VStack(alignment: .leading) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Mobile Drivers License")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .semiBold,
                                    size: .h1
                                )
                            )
                            .foregroundStyle(Color("ColorStone950"))
                        ApplicationStatusSmall(
                            status: issuanceStatus
                        )
                    }
                    .padding(.leading, 12)
                }
                Spacer()
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.white)
                    .shadow(color: .black.opacity(0.03), radius: 5)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorBase300"), lineWidth: 1)
            )
            .padding(12)
            .alert("Delete Application", isPresented: $showDeleteDialog) {
                Button("Cancel", role: .cancel) {}
                Button("Delete", role: .destructive) {
                    deleteApplication()
                }
            } message: {
                Text(
                    "Are you sure you want to delete this application? This action cannot be undone."
                )
            }
            .onTapGesture {
                if let status = issuanceStatus {
                    switch status {
                    case .proofingRequired(let proofingUrl):
                        if let url = URL(string: proofingUrl) {
                            UIApplication.shared.open(
                                url,
                                options: [:],
                                completionHandler: nil
                            )
                        }
                    case .awaitingManualReview:
                        return
                    case .readyToProvision(let openidCredentialOffer):
                        path?.append(
                            HandleOID4VCI(
                                url: openidCredentialOffer,
                                onSuccess: {
                                    deleteApplication()
                                }
                            )
                        )
                    case .applicationDenied:
                        showDeleteDialog = true
                    }
                }
            }
            .onAppear {
                if hacApplicationObservable.getIssuanceState(
                    for: application.id
                ) == nil {
                    Task {
                        await hacApplicationObservable.updateIssuanceState(
                            applicationId: application.id,
                            issuanceId: application.issuanceId
                        )
                    }
                }
            }
        } else {
            HStack {
                VStack(alignment: .leading) {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("Mobile Drivers License")
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .semiBold,
                                    size: .h1
                                )
                            )
                            .foregroundStyle(Color("ColorStone950"))
                        ApplicationStatusSmall(status: nil)
                    }
                    .padding(.leading, 12)
                }
                Spacer()
            }
            .padding(12)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.white)
                    .shadow(color: .black.opacity(0.03), radius: 5)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorBase300"), lineWidth: 1)
            )
            .padding(12)
            .redacted(reason: .placeholder)
        }
    }
}
