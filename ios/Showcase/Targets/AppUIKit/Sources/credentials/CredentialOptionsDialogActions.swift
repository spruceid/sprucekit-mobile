import SpruceIDMobileSdk
import SwiftUI

struct CredentialOptionsDialogActions: View {
    let onDelete: (() -> Void)?
    let txtFile: URL?

    init(
        onDelete: (() -> Void)?,
        exportFileName: String,
        credentialPack: CredentialPack
    ) {
        self.onDelete = onDelete
        self.txtFile = generateTxtFile(
            content: getFileContent(credentialPack: credentialPack),
            filename: exportFileName
        )
    }

    var body: some View {
        ShareLink(item: txtFile!) {
            Text("Export")
                .font(.customFont(font: .inter, style: .medium, size: .h4))
        }
        if onDelete != nil {
            Button("Delete", role: .destructive) { onDelete?() }
        }
        Button("Cancel", role: .cancel) {}
    }
}

func getFileContent(credentialPack: CredentialPack) -> String {
    var rawCredentials: [String] = []
    let claims = credentialPack.findCredentialClaims(claimNames: [])

    credentialPack.list().forEach { parsedCredential in
        if let parsedSdJwt = parsedCredential.asSdJwt() {
            if let sdJwt = try! String(
                data: parsedCredential.intoGenericForm().payload,
                encoding: .utf8) {
                rawCredentials.append(
                    envelopVerifiableSdJwtCredential(
                        sdJwt: sdJwt
                    )!.replaceEscaping()
                )
            }
        } else {
            if let claim = claims[parsedCredential.id()] {
                if let jsonString = convertDictToJSONString(dict: claim) {
                    rawCredentials.append(jsonString.replaceEscaping())
                }
            }
        }
    }
    return rawCredentials.first ?? ""
}

func envelopVerifiableSdJwtCredential(sdJwt: String) -> String? {
    return prettyPrintedJSONString(
        from: """
            {
              "@context": ["https://www.w3.org/ns/credentials/v2"],
              "type": ["EnvelopedVerifiableCredential"],
              "id": "data:application/vc+sd-jwt,\(sdJwt)"
            }
            """
    )
}
