import Flutter
import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Adapter implementing the MdlPresentation Pigeon protocol
class MdlPresentationAdapter: NSObject, MdlPresentation {
    private let credentialPackAdapter: CredentialPackAdapter
    private var presentationManager: MdocProximityPresentationManager?
    private var flutterCallback: MdlPresentationCallback?
    private var currentState: MdlPresentationStateUpdate = MdlPresentationStateUpdate(state: .uninitialized)
    private var currentRequest: MdocProximityPresentationManager.Request?

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
        super.init()
    }

    func setCallback(_ callback: MdlPresentationCallback) {
        self.flutterCallback = callback
    }

    func initializeQrPresentation(
        packId: String,
        credentialId: String,
        completion: @escaping (Result<MdlPresentationResult, Error>) -> Void
    ) {
        // Cancel any existing session
        presentationManager?.disconnect()
        presentationManager = nil
        currentRequest = nil

        // Get the credential pack
        guard let pack = credentialPackAdapter.getNativePack(packId: packId) else {
            completion(.success(MdlPresentationError(message: "Credential pack not found: \(packId)")))
            return
        }

        // Get the credential and extract the mDoc
        guard let credential = pack.get(credentialId: credentialId),
              let mdoc = credential.asMsoMdoc() else {
            completion(.success(MdlPresentationError(message: "mDL credential not found: \(credentialId)")))
            return
        }

        // Create the delegate
        let delegate = PresentationDelegate(adapter: self)

        // Create the presentation manager
        presentationManager = MdocProximityPresentationManager(
            mdoc: mdoc,
            delegate: delegate,
            engagement: .QRCode,
            transmissionModes: [
                .bleMdocCentralMode(.disableL2CAP),
                .bleMdocPeripheralMode(.disableL2CAP)
            ]
        )

        updateState(MdlPresentationStateUpdate(state: .initializing))
        completion(.success(MdlPresentationSuccess(message: "Presentation initialized")))
    }

    func getQrCodeUri() throws -> String? {
        return currentState.qrCodeUri
    }

    func getCurrentState() throws -> MdlPresentationStateUpdate {
        return currentState
    }

    func submitNamespaces(
        selectedNamespaces: [String: [String: [String]]],
        completion: @escaping (Result<MdlPresentationResult, Error>) -> Void
    ) {
        guard let request = currentRequest else {
            completion(.success(MdlPresentationError(message: "No pending request to respond to")))
            return
        }

        // Check if any fields are selected
        let hasSelectedFields = selectedNamespaces.values.contains { docTypeNamespaces in
            docTypeNamespaces.values.contains { fields in !fields.isEmpty }
        }

        if !hasSelectedFields {
            completion(.success(MdlPresentationError(message: "Select at least one attribute to share")))
            return
        }

        // Submit the response
        updateState(MdlPresentationStateUpdate(state: .sendingResponse))
        request.approve(items: selectedNamespaces)
        currentRequest = nil

        completion(.success(MdlPresentationSuccess(message: "Response submitted")))
    }

    func cancel() throws {
        presentationManager?.disconnect()
        presentationManager = nil
        currentRequest = nil
        updateState(MdlPresentationStateUpdate(state: .uninitialized))
    }

    // MARK: - Internal methods

    fileprivate func updateState(_ state: MdlPresentationStateUpdate) {
        currentState = state
        flutterCallback?.onStateChange(update: state) { _ in }
    }

    fileprivate func handleRequest(_ request: MdocProximityPresentationManager.Request) {
        currentRequest = request

        // Convert ItemsRequest to MdlItemsRequest
        let mdlItemsRequests = request.items.map { itemsRequest -> MdlItemsRequest in
            let namespaceRequests = itemsRequest.namespaces.map { (namespace, fields) -> MdlNamespaceRequest in
                let items = fields.map { (fieldName, intentToRetain) -> MdlNamespaceItem in
                    MdlNamespaceItem(name: fieldName, intentToRetain: intentToRetain)
                }
                return MdlNamespaceRequest(namespace: namespace, items: items)
            }
            return MdlItemsRequest(docType: itemsRequest.docType, namespaces: namespaceRequests)
        }

        updateState(MdlPresentationStateUpdate(
            state: .selectingNamespaces,
            itemsRequests: mdlItemsRequests
        ))
    }
}

/// Delegate for receiving presentation state updates
private class PresentationDelegate: MdocProximityPresentationManager.Delegate {
    private weak var adapter: MdlPresentationAdapter?

    init(adapter: MdlPresentationAdapter) {
        self.adapter = adapter
    }

    func connectionState(changedTo state: MdocProximityPresentationManager.State) {
        switch state {
        case .initializing:
            adapter?.updateState(MdlPresentationStateUpdate(state: .initializing))

        case let .action(required: action):
            // Handle required actions (e.g., enable Bluetooth)
            switch action {
            case .turnOnBluetooth:
                adapter?.updateState(MdlPresentationStateUpdate(state: .bluetoothRequired))
            case .authorizeBluetoothForApp:
                adapter?.updateState(MdlPresentationStateUpdate(state: .bluetoothAuthorizationRequired))
            }

        case let .connecting(qrPayload: data):
            if let qrString = String(data: data, encoding: .ascii) {
                adapter?.updateState(MdlPresentationStateUpdate(
                    state: .engagingQrCode,
                    qrCodeUri: qrString
                ))
            }

        case .connected:
            adapter?.updateState(MdlPresentationStateUpdate(state: .connected))

        case .receivingRequest:
            // Still waiting for full request
            break

        case let .receivedRequest(request: request):
            adapter?.handleRequest(request)

        case .sendingResponse:
            adapter?.updateState(MdlPresentationStateUpdate(state: .sendingResponse))

        case .sentResponse:
            adapter?.updateState(MdlPresentationStateUpdate(state: .success))

        case .requestDismissed:
            adapter?.updateState(MdlPresentationStateUpdate(state: .uninitialized))

        case .readerDisconnected:
            adapter?.updateState(MdlPresentationStateUpdate(state: .readerDisconnected))

        case .error:
            adapter?.updateState(MdlPresentationStateUpdate(
                state: .error,
                error: "An error occurred during presentation"
            ))
        }
    }
}
