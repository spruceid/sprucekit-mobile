import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct AddToWallet: Hashable {
    var rawCredential: String
}

/// A single step in the acceptance flow: either the credential parsed
/// successfully and is ready to review, or it didn't and the step can only
/// be skipped.
enum CredentialStepItem {
    case parsed(any ICredentialView)
    case failed(String)
}

struct AddToWalletView: View {
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @Binding var path: NavigationPath
    var rawCredentials: [String]
    @State var storing = false

    @State var stepItems: [CredentialStepItem] = []
    @State var currentIndex: Int = 0
    @State var acceptedCount: Int = 0
    // Swiping the TabView back to an already-decided step shouldn't let the
    // user accept/decline it again.
    @State var decidedIndices: Set<Int> = []

    init(path: Binding<NavigationPath>, rawCredentials: [String]) {
        self._path = path
        self.rawCredentials = rawCredentials
    }

    func back() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    // Opens the `any ICredentialView` existential outside of a ViewBuilder
    // context, since Swift can't open it implicitly inside a ForEach closure.
    func reviewCard(for item: any ICredentialView) -> AnyView {
        AnyView(item.credentialReviewInfo())
    }

    // Finishes the whole flow once every credential has a decision,
    // regardless of the order they were decided in. Until then, the user
    // can freely swipe between offers and deciding one doesn't move them
    // off the current page.
    func finishIfAllDecided() {
        if decidedIndices.count >= stepItems.count {
            ToastManager.shared.showSuccess(
                message: "\(acceptedCount) of \(stepItems.count) credentials accepted"
            )
            back()
        }
    }

    func acceptCurrent() async {
        guard case .parsed = stepItems[currentIndex],
              !decidedIndices.contains(currentIndex)
        else { return }
        decidedIndices.insert(currentIndex)
        storing = true
        do {
            let credentialPack = CredentialPack()
            _ = try await credentialPack.tryAddAnyFormat(
                rawCredential: rawCredentials[currentIndex],
                mdocKeyAlias: DEFAULT_SIGNING_KEY_ID
            )
            try await credentialPackObservable.add(
                credentialPack: credentialPack
            )
            let credentialInfo = getCredentialIdTitleAndIssuer(
                credentialPack: credentialPack
            )
            _ = WalletActivityLogDataStore.shared.insert(
                credentialPackId: credentialPack.id.uuidString,
                credentialId: credentialInfo.0,
                credentialTitle: credentialInfo.1,
                issuer: credentialInfo.2,
                action: "Claimed",
                dateTime: Date(),
                additionalInformation: ""
            )
            acceptedCount += 1
        } catch {
            // Treat a save failure like a decline for this credential rather
            // than blocking the rest of the flow.
            print(error)
        }
        storing = false
        finishIfAllDecided()
    }

    func declineCurrent() {
        guard !decidedIndices.contains(currentIndex) else { return }
        decidedIndices.insert(currentIndex)
        finishIfAllDecided()
    }

    var body: some View {
        ZStack {
            if storing {
                LoadingView(
                    loadingText: "Storing credential..."
                )
            } else if !stepItems.isEmpty {
                let step = stepItems[currentIndex]

                VStack(spacing: 0) {
                    StepProgressView(current: currentIndex, total: stepItems.count)
                        .padding(.top, 12)
                        .padding(.bottom, 12)
                        .padding(.horizontal, 20)

                    TabView(selection: $currentIndex) {
                        ForEach(Array(stepItems.enumerated()), id: \.offset) { index, item in
                            Group {
                                switch item {
                                case .parsed(let credentialItem):
                                    ScrollView {
                                        reviewCard(for: credentialItem)
                                    }
                                    .padding(.bottom, 120)
                                case .failed(let message):
                                    ErrorView(
                                        errorTitle: "Unable to Parse Credential",
                                        errorDetails: message,
                                        closeButtonLabel: "Skip"
                                    ) {
                                        declineCurrent()
                                    }
                                }
                            }
                            .tag(index)
                        }
                    }
                    .tabViewStyle(PageTabViewStyle(indexDisplayMode: .never))
                }

                if case .parsed = step, !decidedIndices.contains(currentIndex) {
                    VStack {
                        Spacer()
                        Button {
                            Task {
                                await acceptCurrent()
                            }
                        } label: {
                            Text("Add to Wallet")
                                .frame(width: UIScreen.screenWidth)
                                .padding(.horizontal, -20)
                                .font(
                                    .customFont(
                                        font: .inter,
                                        style: .medium,
                                        size: .h4
                                    )
                                )
                        }
                        .foregroundColor(.white)
                        .padding(.vertical, 13)
                        .background(Color("ColorEmerald700"))
                        .cornerRadius(8)
                        Button {
                            declineCurrent()
                        } label: {
                            Text("Decline")
                                .frame(width: UIScreen.screenWidth)
                                .padding(.horizontal, -20)
                                .font(
                                    .customFont(
                                        font: .inter,
                                        style: .medium,
                                        size: .h4
                                    )
                                )
                        }
                        .foregroundColor(Color("ColorRose600"))
                        .padding(.vertical, 13)
                        .cornerRadius(8)
                    }
                }
            }
        }
        .navigationBarBackButtonHidden(true)
        .task {
            var items: [CredentialStepItem] = []
            for rawCredential in rawCredentials {
                do {
                    let item = try await credentialDisplayerSelector(
                        rawCredential: rawCredential
                    )
                    items.append(.parsed(item))
                } catch {
                    print(error)
                    items.append(.failed("Error: \(error)"))
                }
            }
            stepItems = items
            if stepItems.isEmpty {
                back()
            }
        }
    }
}

/// Shows "Credential X of Y" plus a row of segments indicating progress
/// through a multi-credential acceptance flow.
struct StepProgressView: View {
    let current: Int
    let total: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if total > 1 {
                Text("Credential \(current + 1) of \(total)")
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
                    .foregroundColor(Color("ColorStone950"))
                HStack(spacing: 4) {
                    ForEach(0..<total, id: \.self) { idx in
                        RoundedRectangle(cornerRadius: 2)
                            .fill(idx <= current ? Color("ColorEmerald700") : Color("ColorBase150"))
                            .frame(height: 4)
                    }
                }
            }
        }
    }
}

struct AddToWalletPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        AddToWalletView(path: $path, rawCredentials: [])
    }
}
