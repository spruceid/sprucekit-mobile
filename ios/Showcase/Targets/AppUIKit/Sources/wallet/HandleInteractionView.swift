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
            protocols = try await discoverProtocols(interactionUrl: discoveryUrl)
            sheetOpen = true
        } catch {
            err =
                "Couldn't discover protocols from QR Code payload. Error: \(error)"
        }
    }
    func onBack() {
        path.removeLast()
    }
    var body: some View {
        ZStack {
            if err != nil {
                ErrorView(
                    errorTitle: "Error discovering protocols",
                    errorDetails: err!,
                    onClose: onBack
                )
            } else {
                Text("Fetching Protocols")
            }
        }.task {
            await loadProtocols()
        }.sheet(isPresented: $sheetOpen) {

        } content: {
            ProtocolSelectorBottomSheet(
                protocols: $protocols,
                sheetOpen: $sheetOpen,
                path: $path,
                credentialPackId: credentialPackId
            )
            .padding(.horizontal, 20)
            .padding(.top, 36)
            .presentationDetents([.fraction(0.85)])
            .presentationDragIndicator(.visible)
            .presentationBackgroundInteraction(.automatic)
        }
        .navigationBarBackButtonHidden(true)
    }
}
