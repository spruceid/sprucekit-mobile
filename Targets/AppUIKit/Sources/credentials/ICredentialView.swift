import SwiftUI
import SpruceIDMobileSdk

enum CredentialError: Error {
    case parsingError(String)
}

protocol ICredentialView: View {
    // component used to display the credential in a list with multiple components
    func credentialListItem(withOptions: Bool) -> any View
    // component used to display only details of the credential
    func credentialDetails() -> any View
    // component used to display the preview and details of the credential
    func credentialPreviewAndDetails() -> any View
}

struct CredentialViewSelector: View {
    let credentialItem: any ICredentialView

    init(credentialPack: CredentialPack, onDelete: (() -> Void)? = nil) {
        self.credentialItem = GenericCredentialItem(credentialPack: credentialPack, onDelete: onDelete)
    }

    var body: some View {
        AnyView(credentialItem)
    }
}
