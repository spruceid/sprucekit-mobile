import SpruceIDMobileSdk
import SwiftUI

enum CredentialError: Error {
    case parsingError(String)
}

protocol ICredentialView: View {
    var credentialPack: CredentialPack { get }
    // component used to display the credential in a list with multiple components
    func credentialListItem(withOptions: Bool) -> any View
    // component used to display only details of the credential
    func credentialDetails() -> any View
    // component used to display the review information view
    func credentialReviewInfo() -> any View
    // component used to display the revoked credential information view
    func credentialRevokedInfo(onClose: @escaping () -> Void) -> any View
    // component used to display the preview and details of the credential
    func credentialPreviewAndDetails() -> any View
}

struct CredentialViewSelector: View {
    let credentialItem: any ICredentialView

    init(credentialPack: CredentialPack, onDelete: (() -> Void)? = nil) {
        self.credentialItem = GenericCredentialItem(
            credentialPack: credentialPack, onDelete: onDelete)
    }

    var body: some View {
        AnyView(credentialItem)
    }
}
