import SpruceIDMobileSdk
import SwiftUI

struct GenericCredentialItemRevokedInfo: View {
    let credentialPack: CredentialPack
    let credentialTitleAndIssuer: (String, String, String)
    let onClose: () -> Void

    init(
        credentialPack: CredentialPack,
        onClose: @escaping () -> Void
    ) {
        self.credentialPack = credentialPack
        self.credentialTitleAndIssuer = getCredentialIdTitleAndIssuer(
            credentialPack: credentialPack)
        self.onClose = onClose
    }

    var body: some View {
        VStack(alignment: .leading) {
            Text("Revoked Credential")
                .font(.customFont(font: .inter, style: .bold, size: .h0))
                .foregroundStyle(Color("ColorStone950"))
            Text("The following credential(s) have been revoked:")
                .font(.customFont(font: .inter, style: .regular, size: .h4))
                .foregroundStyle(Color("ColorStone600"))
                .padding(.vertical, 12)
            Text(credentialTitleAndIssuer.1)
                .font(.customFont(font: .inter, style: .bold, size: .h4))
                .foregroundStyle(Color("ColorRose700"))
            Spacer()
            Button {
                onClose()
            } label: {
                Text("Close")
                    .frame(width: UIScreen.screenWidth)
                    .padding(.horizontal, -20)
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
            .foregroundColor(Color("ColorStone950"))
            .padding(.vertical, 13)
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(Color("ColorStone300"), lineWidth: 1)
            )
            .padding(.top, 10)
        }
    }
}
