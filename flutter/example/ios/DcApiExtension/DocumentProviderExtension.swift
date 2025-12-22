import ExtensionKit
import IdentityDocumentServicesUI
import SwiftUI

@main
struct DocumentProviderExtension: IdentityDocumentProvider {

    var body: some IdentityDocumentRequestScene {
        ISO18013MobileDocumentRequestScene { context in
            DocumentProviderExtensionView(context: context)
                .preferredColorScheme(.light)
                .environment(\.colorScheme, .light)
        }
    }

    func performRegistrationUpdates() async {
        print("performRegistrationUpdates")
    }

}
