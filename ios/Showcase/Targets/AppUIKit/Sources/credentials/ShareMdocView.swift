import CoreBluetooth
import CoreImage.CIFilterBuiltins
import CryptoKit
import LocalAuthentication
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct ShareMdocView: View {
    let mdoc: Mdoc

    init(mdoc: Mdoc) {
        self.mdoc = mdoc
    }

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                ShareMdocQR(mdoc: mdoc).padding()
                    .background(
                        RoundedRectangle(cornerRadius: 12)
                            .stroke(Color("ColorStone300"), lineWidth: 1)
                            .background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: 12))
                    )
                Text(
                    "Present this QR code to a verifier in order to share data. You will see a consent dialogue."
                )
                .multilineTextAlignment(.center)
                .font(.customFont(font: .inter, style: .regular, size: .small))
                .foregroundStyle(Color("ColorStone400"))
                .padding(.vertical, 12)
            }
            .padding(.horizontal, 24)
        }
    }
}

public struct ShareMdocQR: View {
    @StateObject var delegate: ShareViewDelegate

    init(mdoc: Mdoc) {
        let viewDelegate = ShareViewDelegate(mdoc: mdoc)
        self._delegate = StateObject(wrappedValue: viewDelegate)
    }
    
    @ViewBuilder
    var resetButton: some View {
        Button("Reset") {
            self.delegate.reset()
        }
        .padding(10)
        .buttonStyle(.bordered)
        .tint(.blue)
        .foregroundColor(.blue)
    }

    @ViewBuilder
    var cancelButton: some View {
        Button("Cancel") {
            self.delegate.cancel()
        }
        .padding(10)
        .buttonStyle(.bordered)
        .tint(.red)
        .foregroundColor(.red)
    }

    public var body: some View {
        VStack {
            switch self.delegate.state {
            case .initializing:
                settingUpView
            case .action(required: .turnOnBluetooth):
                turnOnBluetoothView
            case .action(required: .authorizeBluetoothForApp):
                authorizeBluetooth
            case .connecting(let qrPayload):
                connectingView(qrPayload: qrPayload)
            // ShareMdocView only uses QR engagement; this state never occurs here.
            case .connectingViaNfc:
                EmptyView()
            case .connected:
                connectedView
            case .receivingRequest(let bytesSoFar, let total):
                receivingRequest(bytesSoFar: bytesSoFar, total: total)
            case .receivedRequest(let request):
                receivedRequestView(request: request)
            case .requestDismissed:
                requestDismissedView
            case .sendingResponse(let value, let total):
                sendingResponse(value: value, total: total)
            case .sentResponse:
                sentResponseView
            case .readerDisconnected:
                readerDisconnectedView
            case .error:
                errorView
            }
        }
    }
    
    @ViewBuilder
    var authorizeBluetooth: some View {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            Button("Authorize bluetooth to continue") {
                UIApplication.shared.open(url)
            }
            .padding(10)
            .buttonStyle(.bordered)
            .tint(.blue)
            .foregroundColor(.blue)
        } else {
            Text("Open iPhone settings and allow bluetooth permissions for this app to continue.")
        }
    }
    
    @ViewBuilder
    func sendingResponse(value: Int, total: Int) -> some View {
        ProgressView(
            value: Double(value),
            total: Double(total),
            label: {
                Text("Sending response...").padding(.bottom, 4)
            },
            currentValueLabel: {
                Text("\(100 * value/total)%")
                    .padding(.top, 4)
            }
        ).progressViewStyle(.linear)
        cancelButton
    }
    
    @ViewBuilder
    func receivingRequest(bytesSoFar: Int, total: Int?) -> some View {
        if let total = total {
            ProgressView(
                value: Double(bytesSoFar),
                total: Double(total),
                label: {
                    Text("Receiving request...").padding(.bottom, 4)
                },
                currentValueLabel: {
                    Text("\(100 * bytesSoFar/total)%")
                        .padding(.top, 4)
                }
            )
            .progressViewStyle(.linear)
        } else {
            ProgressView {
                Text("Receiving request...").padding(.bottom, 4)
            }
            .progressViewStyle(.linear)
        }
        cancelButton
    }

    @ViewBuilder
    var settingUpView: some View {
        ProgressView("Initializing...")
    }

    @ViewBuilder
    var turnOnBluetoothView: some View {
        Text("Turn on bluetooth to continue.")
    }

    @ViewBuilder
    func connectingView(qrPayload: Data) -> some View {
        Image(uiImage: generateQRCode(from: qrPayload))
            .interpolation(.none)
            .resizable()
            .scaledToFit()
            .aspectRatio(contentMode: .fit)
    }

    @ViewBuilder
    var requestDismissedView: some View {
        Text(delegate.sharingCanceled ? "Sharing canceled." : "Request rejected.")
        resetButton
    }
    
    @ViewBuilder
    var readerDisconnectedView: some View {
        Text("The reader unexpectedly disconnected.")
        resetButton
    }
    
    @ViewBuilder
    var errorView: some View {
        Text("An error occurred, please try again later.")
        resetButton
    }

    @ViewBuilder
    var sentResponseView: some View {
        Text("Successfully presented credential.")
        resetButton
    }

    @ViewBuilder
    func receivedRequestView(request: MdocProximityPresentationManager.Request) -> some View {
        ShareMdocSelectiveDisclosureView(request: request, delegate: delegate)
    }

    @ViewBuilder
    var connectedView: some View {
        Text("Connected.")
    }
}

class ShareViewDelegate: ObservableObject, MdocProximityPresentationManager.Delegate {
    @Published var state: MdocProximityPresentationManager.State = .initializing
    /// Set when the user canceled authentication, so the dismissed request reads as canceled.
    @Published var sharingCanceled = false
    /// True while an approval runs, so a second tap can't start another prompt and response.
    @Published private(set) var isApproving = false

    private let mdoc: Mdoc
    private var sessionManager: MdocProximityPresentationManager? = nil

    init(mdoc: Mdoc) {
        self.mdoc = mdoc
        self.sessionManager = MdocProximityPresentationManager(
            mdoc: mdoc,
            delegate: self,
        )
    }

    func cancel() {
        self.sessionManager?.disconnect()
    }
    
    func connectionState(changedTo: SpruceIDMobileSdk.MdocProximityPresentationManager.State) {
        DispatchQueue.main.async {
            self.state = changedTo
        }
    }
    
    func reset() {
        self.sharingCanceled = false
        self.isApproving = false
        self.sessionManager?.authenticationContext = nil
        self.sessionManager?.reset()
    }

    /// Approves the request, after authenticating the user if the mdoc's device key requires it.
    ///
    /// The evaluated context goes to the presentation manager so the signature doesn't prompt a
    /// second time. If the user cancels or fails authentication, the request is dismissed, so the
    /// reader learns it was declined.
    ///
    /// A request is approved at most once: `isApproving` stays set after `request.approve`, which
    /// only queues the signing and sending, until `reset()`.
    @MainActor
    func approve(request: MdocProximityPresentationManager.Request, items: [String: [String: [String]]]) async {
        guard !isApproving else { return }
        isApproving = true
        if KeyManager.isUserAuthenticationRequired(id: mdoc.keyAlias()) {
            // The test mdoc key's policy: its evaluation policy matches the key's access control.
            let policy = TEST_MDOC_KEY_POLICY
            let context = policy.makeContext()
            do {
                try await context.evaluatePolicy(
                    policy.evaluationPolicy,
                    localizedReason: "Approve sharing your data with the reader"
                )
            } catch {
                print("authentication canceled or failed: \(error)")
                sharingCanceled = true
                isApproving = false
                request.dismiss()
                return
            }
            sessionManager?.authenticationContext = context
        }
        request.approve(items: items)
        // The response captured the context; don't let a later session reuse it.
        sessionManager?.authenticationContext = nil
    }
}

public struct ShareMdocSelectiveDisclosureView: View {
    @State private var showingSDSheet = true
    @State private var itemsSelected: [String: [String: [String: Bool]]]
    @State private var request: MdocProximityPresentationManager.Request
    @ObservedObject private var delegate: ShareViewDelegate

    init(request: MdocProximityPresentationManager.Request, delegate: ShareViewDelegate) {
        self.request = request
        self.delegate = delegate
        var defaultSelected: [String: [String: [String: Bool]]] = [:]
        for itemRequest in request.items {
            var defaultSelectedNamespaces: [String: [String: Bool]] = [:]
            for (namespace, namespaceItems) in itemRequest.namespaces {
                var defaultSelectedItems: [String: Bool] = [:]
                for (item, _) in namespaceItems {
                    defaultSelectedItems[item] = true
                }
                defaultSelectedNamespaces[namespace] = defaultSelectedItems
            }
            defaultSelected[itemRequest.docType] = defaultSelectedNamespaces
        }
        self.itemsSelected = defaultSelected
    }

    public var body: some View {
        Button("Select items") {
            showingSDSheet.toggle()
        }
        .padding(10)
        .buttonStyle(.borderedProminent)
        .sheet(
            isPresented: $showingSDSheet
        ) {
            ShareMdocSDSheetView(
                itemsSelected: $itemsSelected,
                itemsRequests: request.items,
                isApproving: delegate.isApproving,
                onProceed: {
                    let items = itemsSelected.mapValues {
                        $0.mapValues {
                            $0.filter { $1 }.map{ $0.key }
                        }
                    }
                    let request = request
                    Task {
                        await delegate.approve(request: request, items: items)
                    }
                },
                onCancel: {
                    request.dismiss()
                }
            )
        }
    }
}

struct ShareMdocSDSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var itemsSelected: [String: [String: [String: Bool]]]
    let itemsRequests: [ItemsRequest]
    /// Disables Approve while an approval runs.
    var isApproving: Bool = false
    let onProceed: () -> Void
    let onCancel: () -> Void

    public var body: some View {
        VStack {
            Group {
                Text("Verifier ")
                    .font(
                        .customFont(font: .inter, style: .bold, size: .h2)
                    )
                    .foregroundColor(Color("ColorBlue600"))
                    + Text(
                        "is requesting access to the following information"
                    )
                    .font(
                        .customFont(font: .inter, style: .bold, size: .h2)
                    )
                    .foregroundColor(Color("ColorStone950"))
            }
            .multilineTextAlignment(.center)
            ScrollView {
                ForEach(itemsRequests, id: \.self) { request in
                    let namespaces: [String: [String: Bool]] = request
                        .namespaces
                    ForEach(Array(namespaces.keys), id: \.self) {
                        namespace in
                        let namespaceItems: [String: Bool] = namespaces[
                            namespace
                        ]!
                        Text(namespace)
                            .font(
                                .customFont(
                                    font: .inter,
                                    style: .regular,
                                    size: .h4
                                )
                            )
                            .foregroundStyle(Color("ColorStone950"))
                            .frame(maxWidth: .infinity, alignment: .leading)
                        ForEach(Array(namespaceItems.keys), id: \.self) {
                            item in
                            VStack {
                                ShareMdocSelectiveDisclosureNamespaceItem(
                                    selected: self.binding(
                                        docType: request.docType,
                                        namespace: namespace,
                                        item: item
                                    ),
                                    name: item
                                )
                            }
                        }
                        .padding(.leading, 8)
                    }
                }
            }
            HStack {
                Button {
                    onCancel()
                } label: {
                    Text("Cancel")
                        .frame(maxWidth: .infinity)
                        .font(
                            .customFont(
                                font: .inter,
                                style: .medium,
                                size: .h4
                            )
                        )
                }
                .foregroundColor(Color("ColorStone950"))
                .padding(.vertical, 13)
                .overlay(
                    RoundedRectangle(cornerRadius: 8)
                        .stroke(Color("ColorStone300"), lineWidth: 1)
                )
                Button {
                    onProceed()
                } label: {
                    Text("Approve")
                        .frame(maxWidth: .infinity)
                        .font(
                            .customFont(
                                font: .inter,
                                style: .medium,
                                size: .h4
                            )
                        )
                }
                .foregroundColor(.white)
                .padding(.vertical, 13)
                .background(Color("ColorEmerald900"))
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .disabled(isApproving)
                .opacity(isApproving ? 0.5 : 1)
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.vertical, 36)
        .padding(.horizontal, 24)
        .navigationBarBackButtonHidden(true)
    }

    private func binding(docType: String, namespace: String, item: String)
        -> Binding<Bool>
    {
        return .init(
            get: { self.itemsSelected[docType]![namespace]![item]! },
            set: { self.itemsSelected[docType]![namespace]![item] = $0 }
        )
    }

}

struct ShareMdocSelectiveDisclosureNamespaceItem: View {
    @Binding var selected: Bool
    let name: String

    public var body: some View {
        HStack {
            Toggle(isOn: $selected) {
                Text(name)
                    .font(.customFont(font: .inter, style: .regular, size: .h4))
                    .foregroundStyle(Color("ColorStone950"))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .toggleStyle(iOSCheckboxToggleStyle(enabled: true))
        }
    }
}
