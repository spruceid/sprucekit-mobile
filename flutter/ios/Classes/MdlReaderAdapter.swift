import Combine
import CoreNFC
import Flutter
import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Adapter implementing the MdlReader Pigeon protocol for iOS.
///
/// Two engagement paths converge on `MdocProximityReader`:
///   - NFC: drives `NfcReaderObservable` which wraps `NFCTagReaderSession`
///     and the APDU handover. On `pendingHandover` we feed the resulting
///     `ReaderHandover` into `MdocProximityReader(fromHandover:)`.
///   - QR: skip NFC entirely; call `MdocProximityReader(fromHolderQrCode:)`
///     directly.
///
/// All callbacks are dispatched on the main queue before invoking the
/// Pigeon `MdlReaderCallback` (which itself expects main).
class MdlReaderAdapter: NSObject, MdlReader {

    private var nfcObservable: NfcReaderObservable?
    private var reader: MdocProximityReader?
    private var cancellables = Set<AnyCancellable>()
    private var flutterCallback: MdlReaderCallback?
    private var currentState: MdlReaderStateUpdate =
        MdlReaderStateUpdate(state: .uninitialized)

    func setCallback(_ callback: MdlReaderCallback) {
        self.flutterCallback = callback
    }

    func isNfcSupported() throws -> Bool {
        return NFCTagReaderSession.readingAvailable
    }

    func startNfcReader(
        query: [String: [String: Bool]],
        trustedRoots: [String]
    ) throws {
        cleanupInternal()

        guard NFCTagReaderSession.readingAvailable else {
            updateState(MdlReaderStateUpdate(state: .nfcUnsupported))
            return
        }

        let observable = NfcReaderObservable(
            alertMessage: "Hold near the holder phone to share their credential"
        )
        self.nfcObservable = observable

        // Phase → MdlReaderState
        observable.$phase
            .receive(on: DispatchQueue.main)
            .sink { [weak self] phase in
                guard let self else { return }
                switch phase {
                case .unsupported:
                    self.updateState(MdlReaderStateUpdate(state: .nfcUnsupported))
                case .idle:
                    // Idle means session torn down (user cancel or terminal).
                    // Don't surface as a state change unless we're not already
                    // past it, otherwise we'd overwrite a success/error state.
                    if self.currentState.state == .nfcWaitingForTag
                        || self.currentState.state == .nfcExchanging {
                        self.updateState(MdlReaderStateUpdate(state: .uninitialized))
                    }
                case .waitingForTag:
                    self.updateState(MdlReaderStateUpdate(state: .nfcWaitingForTag))
                case .exchanging:
                    self.updateState(MdlReaderStateUpdate(state: .nfcExchanging))
                case .protocolError(let err):
                    self.updateState(MdlReaderStateUpdate(
                        state: .error,
                        error: err.localizedDescription
                    ))
                }
            }
            .store(in: &cancellables)

        // Handover → start BLE session
        observable.$pendingHandover
            .compactMap { $0 }
            .first()
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak observable] handover in
                self?.onHandover(handover, query: query, trustedRoots: trustedRoots)
                observable?.consumeHandover()
            }
            .store(in: &cancellables)

        observable.setActive(true)
    }

    func startQrReader(
        qrUri: String,
        query: [String: [String: Bool]],
        trustedRoots: [String]
    ) throws {
        cleanupInternal()
        let handover = ReaderHandover.newQr(qr: qrUri)
        onHandover(handover, query: query, trustedRoots: trustedRoots)
    }

    func cancel() throws {
        cleanupInternal()
        updateState(MdlReaderStateUpdate(state: .uninitialized))
    }

    // MARK: - Internal

    private func onHandover(
        _ handover: ReaderHandover,
        query: [String: [String: Bool]],
        trustedRoots: [String]
    ) {
        updateState(MdlReaderStateUpdate(state: .bleConnecting))
        let delegate = ReaderDelegate(adapter: self)
        self.reader = MdocProximityReader(
            fromHandover: handover,
            delegate: delegate,
            requestedItems: query,
            trustAnchorRegistry: trustedRoots.isEmpty ? nil : trustedRoots
        )
    }

    fileprivate func onReaderState(_ state: MdocProximityReader.State) {
        switch state {
        case .initializing, .connecting:
            updateState(MdlReaderStateUpdate(state: .bleConnecting))

        case .connected, .sendingRequest, .sentRequest, .receivingResponse:
            updateState(MdlReaderStateUpdate(state: .bleReceivingResponse))

        case .receivedResponse(let response):
            updateState(MdlReaderStateUpdate(
                state: .success,
                response: toPigeon(response.data)
            ))

        case .action(required: let action):
            let message: String
            switch action {
            case .turnOnBluetooth:
                message = "Bluetooth is off; please enable it."
            case .authorizeBluetoothForApp:
                message = "Bluetooth permission not granted for this app."
            }
            updateState(MdlReaderStateUpdate(state: .error, error: message))

        case .mdocDisconnected:
            updateState(MdlReaderStateUpdate(
                state: .error,
                error: "Holder disconnected unexpectedly."
            ))

        case .error:
            updateState(MdlReaderStateUpdate(
                state: .error,
                error: "Unrecoverable reader error."
            ))
        }
    }

    private func cleanupInternal() {
        nfcObservable?.setActive(false)
        nfcObservable = nil
        reader?.disconnect()
        reader = nil
        cancellables.removeAll()
    }

    private func updateState(_ state: MdlReaderStateUpdate) {
        currentState = state
        // Pigeon callbacks expect main thread; ensure even if a publisher
        // forgot `.receive(on:)`.
        if Thread.isMainThread {
            flutterCallback?.onStateChange(update: state) { _ in }
        } else {
            DispatchQueue.main.async { [weak self] in
                self?.flutterCallback?.onStateChange(update: state) { _ in }
            }
        }
    }

    /// Serialize ``MdlReaderResponseData`` into the Pigeon wire shape.
    ///
    /// `verifiedResponse` is JSON-encoded via the Rust-side
    /// ``verifiedResponseAsJsonString`` rather than transported as a typed
    /// nested map. See ``MdlReadResponse`` doc for the rationale (Pigeon
    /// recursive-type OOM + nested-Map cast bug).
    private func toPigeon(_ data: MdlReaderResponseData) -> MdlReadResponse {
        let verifiedJson: String
        do {
            verifiedJson = try verifiedResponseAsJsonString(response: data)
        } catch {
            verifiedJson = "{}"
        }
        return MdlReadResponse(
            verifiedResponseJson: verifiedJson,
            docTypes: data.docTypes,
            issuerAuthentication: toPigeon(data.issuerAuthentication),
            deviceAuthentication: toPigeon(data.deviceAuthentication),
            errors: data.errors
        )
    }

    private func toPigeon(_ s: AuthenticationStatus) -> MdlAuthenticationStatus {
        switch s {
        case .valid: return .valid
        case .invalid: return .invalid
        case .unchecked: return .unchecked
        }
    }
}

/// Delegate for `MdocProximityReader`. iOS callback queue is internal to
/// the SDK's `DelegateWrapper` (background `userInitiated`); we hop to main
/// in [MdlReaderAdapter.updateState] before invoking the Pigeon callback.
private final class ReaderDelegate: MdocProximityReader.Delegate {
    private weak var adapter: MdlReaderAdapter?

    init(adapter: MdlReaderAdapter) {
        self.adapter = adapter
    }

    func connectionState(changedTo state: MdocProximityReader.State) {
        DispatchQueue.main.async { [weak self] in
            self?.adapter?.onReaderState(state)
        }
    }
}
