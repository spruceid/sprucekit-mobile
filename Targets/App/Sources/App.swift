import SwiftUI
import AppUIKit

@main
struct AppApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { _ in
                    // @TODO: integrate with the OID4VP flow
                }
        }
    }
}
