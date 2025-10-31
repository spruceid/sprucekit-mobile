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
            VStack {
                // Top row: Logo and status
                HStack(alignment: .top) {
                    Image("SpruceLogo")
                        .resizable()
                        .aspectRatio(contentMode: .fit)
                        .frame(width: 24, height: 24)

                    Spacer()

                    if issuanceStatus == nil {
                        ProgressView()
                            .progressViewStyle(CircularProgressViewStyle())
                            .scaleEffect(0.8)
                    } else {
                        ApplicationStatusSmall(status: issuanceStatus)
                    }
                }

                Spacer()

                // Bottom content
                VStack(alignment: .leading, spacing: 8) {
                    Text("Mobile Drivers License")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .semiBold,
                                size: .h2
                            )
                        )
                        .foregroundStyle(Color("ColorStone950"))
                        .shadow(
                            color: .black.opacity(0.1),
                            radius: 2,
                            x: 1,
                            y: 1
                        )

                    Text("Credential Application")
                        .font(
                            .customFont(
                                font: .inter,
                                style: .medium,
                                size: .p
                            )
                        )
                        .foregroundStyle(Color("ColorStone950").opacity(0.7))
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(16)
            .background(
                ZStack {
                    Image("CredentialBg")
                        .resizable()
                        .scaledToFill()
                        .opacity(0.6)

                    Color.white.opacity(0.75)
                }
            )
            .frame(height: 195)
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(
                        LinearGradient(
                            gradient: Gradient(
stops: [
    .init(
        color: Color(hex: "#C8BFAD"),
        location: 0.0
    ),
    .init(color: Color.white.opacity(0.2), location: 0.3),
    .init(color: Color.white.opacity(0.2), location: 0.8),
    .init(color: Color(hex: "#C8BFAD"), location: 1.0)
]
                            ),
                            startPoint: .top,
                            endPoint: .bottom
                        ),
                        lineWidth: 1
                    )
            )
            .shadow(color: .black.opacity(0.3), radius: 8, x: 0, y: 4)
            .padding(.horizontal, 12)
            .padding(.bottom, 15)
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
        }
    }
}
