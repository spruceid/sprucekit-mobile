import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

struct AddToWallet: Hashable {
    var rawCredential: String
}

struct AddToWalletView: View {
    @Binding var path: NavigationPath
    var rawCredential: String
    var credential: GenericJSON?
    @State var presentError: Bool
    @State var errorDetails: String

    let credentialItem: (any ICredentialView)?

    init(path: Binding<NavigationPath>, rawCredential: String) {
        self._path = path
        self.rawCredential = rawCredential

        do {
            credentialItem = try credentialDisplayerSelector(rawCredential: rawCredential)
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

    func addToWallet() {
        do {
            let credentialPack = try addCredential(credentialPack: CredentialPack(), rawCredential: rawCredential)
            try credentialPack.save(storageManager: StorageManager())
            back()
        } catch {
            print(error)
            errorDetails = "Error: \(error)"
            presentError = true
        }
    }

    var body: some View {
        ZStack {
            if !presentError && credentialItem != nil {
                VStack {
                    Text("Review Info")
                        .font(.customFont(font: .inter, style: .bold, size: .h0))
                        .padding(.horizontal, 20)
                        .foregroundStyle(Color("TextHeader"))
                    AnyView(credentialItem!.credentialListItem(withOptions: false))
                        .frame(height: 100)
                    ScrollView(.vertical, showsIndicators: false) {
                        AnyView(credentialItem!.credentialDetails())
                    }
                }
                .padding(.bottom, 120)
                VStack {
                    Spacer()
                    Button {
                        addToWallet()
                    }  label: {
                        Text("Add to Wallet")
                            .frame(width: UIScreen.screenWidth)
                            .padding(.horizontal, -20)
                            .font(.customFont(font: .inter, style: .medium, size: .h4))
                    }
                    .foregroundColor(.white)
                    .padding(.vertical, 13)
                    .background(Color("CTAButtonGreen"))
                    .cornerRadius(8)
                    Button {
                        back()
                    }  label: {
                        Text("Decline")
                            .frame(width: UIScreen.screenWidth)
                            .padding(.horizontal, -20)
                            .font(.customFont(font: .inter, style: .medium, size: .h4))
                    }
                    .foregroundColor(Color("SecondaryButtonRed"))
                    .padding(.vertical, 13)
                    .cornerRadius(8)
                }
            } else {
                ErrorView(
                    errorTitle: "Unable to Parse Credential",
                    errorDetails: errorDetails) {
                        back()
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
