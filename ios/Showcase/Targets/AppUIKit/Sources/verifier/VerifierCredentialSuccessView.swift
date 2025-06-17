import SpruceIDMobileSdk
import SwiftUI

struct VerifierCredentialSuccessView: View {
    @EnvironmentObject private var statusListObservable: StatusListObservable
    var rawCredential: String
    var onClose: () -> Void
    var logVerification: (String, String, String) -> Void

    @State var credentialPack: CredentialPack?
    @State var credentialItem: (any ICredentialView)?
    @State var title: String?
    @State var issuer: String?

    var body: some View {
        VStack {
            Text(title ?? "")
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.customFont(font: .inter, style: .semiBold, size: .h0))
                .foregroundStyle(Color("ColorStone950"))
            Text(issuer ?? "")
                .multilineTextAlignment(.leading)
                .frame(maxWidth: .infinity, alignment: .leading)
                .font(.customFont(font: .inter, style: .semiBold, size: .h3))
                .foregroundStyle(Color("ColorStone600"))
            Divider()
            if credentialItem != nil {
                AnyView(credentialItem!.credentialDetails())
            } else {
                Spacer()
            }
            Button {
                onClose()
            } label: {
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
                    .stroke(Color("ColorStone300"), lineWidth: 1)
            )

        }
        .padding(20)
        .padding(.top, 20)
        .navigationBarBackButtonHidden(true)
        .onAppear(perform: {
            Task {
                do {
                    self.credentialPack = try addCredential(
                        credentialPack: CredentialPack(),
                        rawCredential: rawCredential)

                    self.credentialItem = try credentialDisplayerSelector(
                        credentialPack: credentialPack.unwrap())

                    let status =
                        await statusListObservable.fetchAndUpdateStatus(
                            credentialPack: credentialPack!)

                    let credential = try credentialPack.unwrap().list().first

                    let claims = try credentialPack.unwrap().getCredentialClaims(
                        credential: credential.unwrap(),
                        claimNames: [
                            "name", "type", "description", "issuer", "Given Names", "Family Name"
                        ]
                    )

                    var tmpTitle = claims["name"]?.toString()
                    if tmpTitle == nil {
                        claims["type"]?.arrayValue?.forEach {
                            if $0.toString() != "VerifiableCredential" {
                                tmpTitle = $0.toString().camelCaseToWords()
                                return
                            }
                        }
                    }

                    // Birth Certificate Title
                    if tmpTitle == nil {
                        do {
                            let names = try claims["Given Names"].unwrap().toString()
                            let family = try claims["Family Name"].unwrap().toString()
                            tmpTitle = names + " " + family
                        } catch {
                            print("It does not have a Given Names or Family Name attribute.")
                        }
                    }
                    self.title = tmpTitle ?? ""

                    if let issuerName = claims["issuer"]?.dictValue?["name"]?
                        .toString() {
                        self.issuer = issuerName
                    } else {
                        self.issuer = ""
                    }
                    logVerification(
                        title ?? "", issuer ?? "", status.rawValue)
                } catch {
                    self.title = ""
                    self.issuer = ""
                }
            }
        })
    }
}
