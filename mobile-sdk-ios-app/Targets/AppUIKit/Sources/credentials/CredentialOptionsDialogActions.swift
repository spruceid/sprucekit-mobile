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
        if(true) {
            ShareLink(item: txtFile!) {
                Text("Export")
                    .font(.customFont(font: .inter, style: .medium, size: .h4))
            }
        }
        if(onDelete != nil) {
            Button("Delete", role: .destructive) { onDelete?() }
        }
        Button("Cancel", role: .cancel) { }
    }
}

func getFileContent(credentialPack: CredentialPack) -> String {
    var rawCredentials: [String] = []
    
    credentialPack.list().forEach { parsedCredential in
        do {
            if let str = try String(data: parsedCredential.intoGenericForm().payload, encoding: .utf8) {
                rawCredentials.append(str)
            }
        } catch {
            print(error.localizedDescription)
        }
    }
    return rawCredentials.first ?? ""
}
