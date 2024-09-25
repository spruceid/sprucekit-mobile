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

    
    init(path: Binding<NavigationPath>, rawCredential: String) {
        self._path = path
        self.rawCredential = rawCredential
        
        do {
            let res = try decodeRevealSdJwt(input: rawCredential)
            self.credential = getGenericJSON(jsonString: res)
            errorDetails = ""
            presentError = false
        } catch {
            print(error)
            errorDetails = "Error: \(error)"
            presentError = true
        }
    }
    
    func back() {
        while !path.isEmpty {
            path.removeLast()
        }
    }
    
    func addToWallet() {
        _ = CredentialDataStore.shared.insert(
            rawCredential: rawCredential
        )
        back()
    }
    
    let monospacedFont = Font
                .system(size: 16)
                .monospaced()
    
    var body: some View {
        let credentialItem = AchievementCredentialItem(credential: credential)
        ZStack {
            if(!presentError){
                VStack{
                    Text("Review Info")
                        .font(.customFont(font: .inter, style: .bold, size: .h0))
                        .padding(.horizontal, 20)
                        .foregroundStyle(Color("TextHeader"))
                    credentialItem.listComponent
                    ScrollView(.vertical, showsIndicators: false) {
                        credentialItem.detailsComponent
                    }
                }
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
                VStack {
                    Image("Error")
                        .padding(.top, 30)
                    Text("Unable to add credential")
                        .font(.customFont(font: .inter, style: .bold, size: .h1))
                        .foregroundColor(Color("RedInvalid"))
                        .padding(.vertical, 10)
                    Text("Error parsing data")
                        .font(.customFont(font: .inter, style: .regular, size: .h4))
                        .multilineTextAlignment(.center)
                        .foregroundColor(Color("TextBody"))

                    ScrollView {
                        Text(errorDetails)
                            .font(monospacedFont)
                            .foregroundColor(Color("TextBody"))
                            .lineLimit(nil)
                            .padding(.horizontal, 10)
                    }
                    .frame(maxWidth: .infinity, maxHeight: 150)
                    .padding(.vertical, 20)
                    .background(Color("CodeBg"))
                    .overlay(
                        RoundedRectangle(cornerRadius: 8)
                            .stroke(Color("CodeBorder"), lineWidth: 1)
                    )

                    Button {
                        back()
                    }  label: {
                        Text("Close")
                            .frame(width: UIScreen.screenWidth)
                            .padding(.horizontal, -20)
                            .font(.customFont(font: .inter, style: .medium, size: .h4))
                    }
                    .foregroundColor(.white)
                    .padding(.vertical, 13)
                    .background(Color("GrayButton"))
                    .cornerRadius(8)
                    .padding(.top, 10)
                }
                .padding(.horizontal, 20)
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
