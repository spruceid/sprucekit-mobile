import SwiftUI

struct VerifierCredentialSuccessView: View {
    var rawCredential: String
    var onClose: () -> Void
    
    var credentialItem: (any ICredentialView)?
    var title: String
    var issuer: String
    
    init(rawCredential: String, onClose: @escaping () -> Void) {
        self.rawCredential = rawCredential
        self.onClose = onClose
        do {
            self.credentialItem = try credentialDisplayerSelector(rawCredential: rawCredential)
            let credential = try credentialItem.unwrap().credentialPack.list().first
            let claims = try credentialItem.unwrap().credentialPack.findCredentialClaims(
                claimNames: ["name", "type", "description", "issuer"]
            )[credential.unwrap().id()]
            
            
            var tmpTitle = claims?["name"]?.toString()
            if tmpTitle == nil {
                claims?["type"]?.arrayValue?.forEach {
                    if $0.toString() != "VerifiableCredential" {
                        tmpTitle = $0.toString().camelCaseToWords()
                        return
                    }
                }
            }
            self.title = tmpTitle ?? ""
            
            if let issuerName = claims?["issuer"]?.dictValue?["name"]?.toString() {
                self.issuer = issuerName
            } else {
                self.issuer = ""
            }
        } catch {
            self.credentialItem = nil
            self.title = ""
            self.issuer = ""
        }
    }
    
    var body: some View {
        VStack {
            Text(title)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.customFont(font: .inter, style: .semiBold, size: .h0))
                .foregroundStyle(Color("ColorStone950"))
            Text(issuer)
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                .foregroundStyle(Color("ColorStone600"))
            Divider()
            if let credItem = credentialItem {
                AnyView(credItem.credentialDetails())
            }
            Button {
                onClose()
            }  label: {
                Text("Close")
                    .frame(width: UIScreen.screenWidth)
                    .padding(.horizontal, -20)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(.black)
            .padding(.vertical, 13)
            .cornerRadius(8)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("CodeBorder"), lineWidth: 1)
            )
            
        }
        .padding(20)
        .padding(.top, 20)
        .navigationBarBackButtonHidden(true)
    }
}
