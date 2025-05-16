import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct AddToWallet: Hashable {
    var rawCredential: String
}

struct AddToWalletView: View {
    @EnvironmentObject private var credentialPackObservable:
        CredentialPackObservable
    @Binding var path: NavigationPath
    var rawCredential: String
    var credential: GenericJSON?
    @State var presentError: Bool
    @State var errorDetails: String
    @State var storing = false

    let credentialItem: (any ICredentialView)?

    init(path: Binding<NavigationPath>, rawCredential: String) {
        self._path = path
        self.rawCredential = rawCredential

        do {
            credentialItem = try credentialDisplayerSelector(
                rawCredential: rawCredential
            )
            errorDetails = ""
            presentError = false
        } catch {
            print(error)
            errorDetails = "Error: \(error)"
            presentError = true
            credentialItem = nil
        }
    }

    func back() {
        while !path.isEmpty {
            path.removeLast()
        }
    }

    func addToWallet() async {
        storing = true
        do {
            let credentialPack = CredentialPack()
            _ = try credentialPack.tryAddAnyFormat(
                rawCredential: rawCredential,
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
            back()
        } catch {
            print(error)
            errorDetails = "Error: \(error)"
            presentError = true
        }
        storing = false
    }

    var body: some View {
        ZStack {
            if presentError {
                ErrorView(
                    errorTitle: "Unable to Parse Credential",
                    errorDetails: errorDetails
                ) {
                    back()
                }
            } else if storing {
                LoadingView(
                    loadingText: "Storing credential..."
                )
            } else if credentialItem != nil {
                AnyView(credentialItem!.credentialReviewInfo())
                    .padding(.bottom, 120)
                VStack {
                    Spacer()
                    Button {
                        Task {
                            await addToWallet()
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
                        back()
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
        .navigationBarBackButtonHidden(true)
    }
}

struct AddToWalletPreview: PreviewProvider {
    @State static var path: NavigationPath = .init()

    static var previews: some View {
        AddToWalletView(path: $path, rawCredential: "")
    }
}
