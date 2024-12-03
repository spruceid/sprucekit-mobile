import SwiftUI
import SpruceIDMobileSdk

struct CredentialOptionsDialogActions: View {
    let onDelete: (() -> Void)?
    let txtFile: URL?
    
    init(onDelete: (() -> Void)?, exportFileName: String, credentialPack: CredentialPack) {
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
        if(onDelete != nil) {
            Button("Delete", role: .destructive) { onDelete?() }
        }
        Button("Cancel", role: .cancel) { }
    }
}

func getFileContent(credentialPack: CredentialPack) -> String {
    var rawCredentials: [String] = []
    let claims = credentialPack.findCredentialClaims(claimNames: [])
    
    claims.keys.forEach { key in
        if let claim = claims[key] {
            if let jsonString = convertDictToJSONString(dict: claim) {
                rawCredentials.append(jsonString)
            }
        }
    }
    return rawCredentials.first ?? ""
}
