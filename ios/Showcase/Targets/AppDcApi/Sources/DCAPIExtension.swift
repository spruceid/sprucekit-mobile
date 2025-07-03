#if canImport(IdentityDocumentServices)
import IdentityDocumentServices
#endif
#if canImport(IdentityDocumentServicesUI)
import IdentityDocumentServicesUI
#endif
import SwiftUI
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import AppUIKit

@available(iOS 26.0, *)
@main
struct DocumentProviderExtension: IdentityDocumentProvider {
    
    func performRegistrationUpdates() async {
        print("performRegistrationUpdates")
    }
    

    var body: some IdentityDocumentRequestScene {
        ISO18013MobileDocumentRequestScene { context in
            DocumentProviderExtensionView(context: context)
        }
    }
}
