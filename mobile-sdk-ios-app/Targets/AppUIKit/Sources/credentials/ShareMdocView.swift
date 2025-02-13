import CoreBluetooth
import CoreImage.CIFilterBuiltins
import CryptoKit
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs
import SwiftUI

struct ShareableCredentialListItemQRCode: View {
    let credentialPack: CredentialPack
    @State private var qrSheetView: QRSheetView? = nil

    func getQRSheetView() async -> QRSheetView {
        return await QRSheetView(credentialPack: credentialPack)
    }

    var body: some View {
        ZStack {
            VStack(spacing: 0) {
                if let qrCode = qrSheetView {
                    qrCode.padding()
                        .background(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(Color("ColorStone300"), lineWidth: 1)
                                .background(Color.white)
                                .clipShape(RoundedRectangle(cornerRadius: 12))
                        )
                }
                Text(
                    "Present this QR code to a verifier in order to share data. You will see a consent dialogue."
                )
                .multilineTextAlignment(.center)
                .font(.customFont(font: .inter, style: .regular, size: .small))
                .foregroundStyle(Color("ColorStone400"))
                .padding(.vertical, 12)
                .task {
                    qrSheetView = await getQRSheetView()
                }
            }
            .padding(.horizontal, 24)
        }
    }
}

public struct QRSheetView: View {
    var credentials: CredentialStore
    @State var proceed = true
    @StateObject var delegate: ShareViewDelegate

    init(credentialPack: CredentialPack) async {
        let credentialStore = CredentialStore(
            credentials: credentialPack.list())
        self.credentials = credentialStore
        let viewDelegate = await ShareViewDelegate(credentials: credentialStore)
        self._delegate = StateObject(wrappedValue: viewDelegate)
    }

    @ViewBuilder
    var cancelButton: some View {
        Button("Cancel") {
            self.delegate.cancel()
            proceed = false
        }
        .padding(10)
        .buttonStyle(.bordered)
        .tint(.red)
        .foregroundColor(.red)
    }

    public var body: some View {
        VStack {
            if proceed {
                switch self.delegate.state {
                case .engagingQRCode(let data):
                    Image(uiImage: generateQRCode(from: data))
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .aspectRatio(contentMode: .fit)
                case .error(let error):
                    let message =
                        switch error {
                        case .bluetooth(let central):
                            switch central.state {
                            case .poweredOff:
                                "Is Powered Off."
                            case .unsupported:
                                "Is Unsupported."
                            case .unauthorized:
                                switch CBManager.authorization {
                                case .denied:
                                    "Authorization denied"
                                case .restricted:
                                    "Authorization restricted"
                                case .allowedAlways:
                                    "Authorized"
                                case .notDetermined:
                                    "Authorization not determined"
                                @unknown default:
                                    "Unknown authorization error"
                                }
                            case .unknown:
                                "Unknown"
                            case .resetting:
                                "Resetting"
                            case .poweredOn:
                                "Impossible"
                            @unknown default:
                                "Error"
                            }
                        case .peripheral(let error):
                            error
                        case .generic(let error):
                            error
                        }
                    Text(message)
                case .uploadProgress(let value, let total):
                    ProgressView(
                        value: Double(value), total: Double(total),
                        label: {
                            Text("Uploading...").padding(.bottom, 4)
                        },
                        currentValueLabel: {
                            Text("\(100 * value/total)%")
                                .padding(.top, 4)
                        }
                    ).progressViewStyle(.linear)
                    cancelButton
                case .success:
                    Text("Successfully presented credential.")
                case .selectNamespaces(let items):
                    SelectiveDisclosureView(
                        itemsRequests: items, delegate: delegate,
                        proceed: $proceed
                    )
                    .onChange(of: proceed) { _ in
                        self.delegate.cancel()
                    }
                case .connected:
                    Text("Connected")
                }
            } else {
                Text("Operation Canceled")
            }
        }
    }
}

class ShareViewDelegate: ObservableObject {
    @Published var state: BLESessionState = .connected
    private var sessionManager: IsoMdlPresentation?

    init(credentials: CredentialStore) async {
        self.sessionManager = await credentials.presentMdocBLE(
            deviceEngagement: .QRCode, callback: self)!
    }

    func cancel() {
        self.sessionManager?.cancel()
    }

    func submitItems(items: [String: [String: [String: Bool]]]) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationLabel as String: "mdoc_key",
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecReturnRef as String: true,
        ]

        var item: CFTypeRef?
        _ = SecItemCopyMatching(query as CFDictionary, &item)
        let key = item as! SecKey
        self.sessionManager?.submitNamespaces(
            items: items.mapValues { namespaces in
                return namespaces.mapValues { items in
                    Array(items.filter { $0.value }.keys)
                }
            }, signingKey: key)
    }
}

extension ShareViewDelegate: BLESessionStateDelegate {
    public func update(state: BLESessionState) {
        self.state = state
    }
}

public struct SelectiveDisclosureView: View {
    @State private var showingSDSheet = true
    @State private var itemsSelected: [String: [String: [String: Bool]]]
    @State private var itemsRequests: [ItemsRequest]
    @Binding var proceed: Bool
    @StateObject var delegate: ShareViewDelegate

    init(
        itemsRequests: [ItemsRequest], delegate: ShareViewDelegate,
        proceed: Binding<Bool>
    ) {
        self.itemsRequests = itemsRequests
        self._delegate = StateObject(wrappedValue: delegate)
        var defaultSelected: [String: [String: [String: Bool]]] = [:]
        for itemRequest in itemsRequests {
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
        self._proceed = proceed
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
            SDSheetView(
                itemsSelected: $itemsSelected,
                itemsRequests: $itemsRequests,
                proceed: $proceed,
                onProceed: {
                    //                    print(itemsSelected)
                    delegate.submitItems(items: itemsSelected)
                },
                onCancel: {
                    delegate.cancel()
                }
            )
        }
    }
}

struct SDSheetView: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var itemsSelected: [String: [String: [String: Bool]]]
    @Binding var itemsRequests: [ItemsRequest]
    @Binding var proceed: Bool
    let onProceed: () -> Void
    let onCancel: () -> Void

    public var body: some View {
        NavigationStack {
            Form {
                ForEach(itemsRequests, id: \.self) { request in
                    let namespaces: [String: [String: Bool]] = request
                        .namespaces
                    Section(header: Text(request.docType)) {
                        ForEach(Array(namespaces.keys), id: \.self) {
                            namespace in
                            let namespaceItems: [String: Bool] = namespaces[
                                namespace]!
                            ForEach(Array(namespaceItems.keys), id: \.self) {
                                item in
                                let retain: Bool = namespaceItems[item]!
                                VStack {
                                    ItemToggle(
                                        selected: self.binding(
                                            docType: request.docType,
                                            namespace: namespace, item: item),
                                        name: item)
                                    if retain {
                                        Text(
                                            "This piece of information will be retained by the reader."
                                        ).font(.system(size: 10))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Select items")
            .toolbar(content: {
                ToolbarItemGroup(placement: .bottomBar) {
                    Button("Cancel", role: .cancel) {
                        onCancel()
                        proceed = false
                    }.tint(.red)
                    Button("Share") {
                        onProceed()
                    }
                }
            })
        }
    }

    private func binding(docType: String, namespace: String, item: String)
        -> Binding<Bool>
    {
        return .init(
            get: { self.itemsSelected[docType]![namespace]![item]! },
            set: { self.itemsSelected[docType]![namespace]![item] = $0 })
    }

}

struct ItemToggle: View {
    @Binding var selected: Bool
    let name: String

    public var body: some View {
        Toggle(name, isOn: $selected)
    }
}
