#if canImport(IdentityDocumentServices) && canImport(IdentityDocumentServicesUI)
import IdentityDocumentServices
import IdentityDocumentServicesUI
import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import AppUIKit

@available(iOS 26.0, *)
@main
struct DocumentProviderExtension: IdentityDocumentProvider {
    init() {
        // The extension runs in its own process, so it registers the client itself.
        RustHttpClient.configure()
    }
    
    func performRegistrationUpdates() async {
        print("performRegistrationUpdates")
    }
    

    var body: some IdentityDocumentRequestScene {
        ISO18013MobileDocumentRequestScene { context in
            DocumentProviderExtensionView(context: context)
                .background(Color.white)
        }
    }
}
#endif
