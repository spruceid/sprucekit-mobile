import SpruceIDMobileSdkRs
import SwiftUI

struct HandleInteraction: Hashable {
    var url: String
    var credentialPackId: String?
}

enum DiscoveryError: Error {
    case invalidInteractionURL(reason: String)
}

struct HandleInteractionView: View {
    @Binding var path: NavigationPath
    let url: String
    var credentialPackId: String?

    @State var sheetOpen: Bool = false
    @State var err: String?
    @State var protocolErrorTitle: String?
    @State var protocolErrorDetails: String?
    @State var protocolSelected: Bool = false
    enum ExchangeError: Error {
        case badStatus(Int)
        case noProtocolsOrFallback
    }

    struct ProtocolsResponse: Decodable {
        let protocols: [String: String]
    }

    @State var protocols: [String: String] = [:]

    func loadProtocols() async {
        do {
            var discoveryUrl = url
            let options: String.CompareOptions = [.anchored, .caseInsensitive]

            // Remove "interaction:" prefix if present
            if let range = discoveryUrl.range(
                of: "interaction:",
                options: options
            ) {
                discoveryUrl = String(discoveryUrl[range.upperBound...])
            }
            protocols = try await discoverProtocols(
                interactionUrl: discoveryUrl
            )
            sheetOpen = true
        } catch {
            err =
                "Couldn't discover protocols from QR Code payload. Error: \(error)"
        }
    }
    func onBack() {
        while !path.isEmpty {
            path.removeLast()
        }
    }
    var body: some View {
        ZStack {
            if let protocolErrorTitle, let protocolErrorDetails {
                ErrorView(
                    errorTitle: protocolErrorTitle,
                    errorDetails: protocolErrorDetails,
                    onClose: onBack
                )
            } else if err != nil {
                ErrorView(
                    errorTitle: "Error discovering protocols",
                    errorDetails: err!,
                    onClose: onBack
                )
            } else {
                LoadingView(loadingText: "Discovering protocols...")
            }
        }.task {
            await loadProtocols()
        }.sheet(
            isPresented: $sheetOpen,
            onDismiss: {
                if !protocolSelected {
                    onBack()
                }
            }
        ) {
            ProtocolSelectorBottomSheet(
                protocols: $protocols,
                sheetOpen: $sheetOpen,
                protocolSelected: $protocolSelected,
                path: $path,
                credentialPackId: credentialPackId,
                onError: { title, details in
                    sheetOpen = false
                    protocolErrorTitle = title
                    protocolErrorDetails = details
                }
            )
            .presentationBackgroundInteraction(.automatic)
        }
        .navigationBarBackButtonHidden(true)
    }
}
