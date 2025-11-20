import Foundation
import SpruceIDMobileSdkRs

/// Entrypoint to mdoc close proximity presentation.
///
/// Dropping this class or calling `.disconnect()` will ensure that the underlying transmission services are cleaned up.
public class MdocProximityPresentationManager {
    private let mdoc: Mdoc, delegate: Delegate, engagement: DeviceEngagement, transmissionModes: [TransmissionOption]

    private var handle: DelegateWrapper?

    // TODO: Support multiple mdocs (requires changes in isomdl).
    public init(
        mdoc: Mdoc,
        delegate: Delegate,
        engagement: DeviceEngagement = .QRCode,
        /// List of enabled Transmission modes.
        transmissionModes: [TransmissionOption] = [
            .bleMdocCentralMode(L2CAPUsage.disableL2CAP),
            .bleMdocPeripheralMode(L2CAPUsage.disableL2CAP)
        ]
    ) {
        self.mdoc = mdoc
        self.delegate = delegate
        self.engagement = engagement
        self.transmissionModes = transmissionModes
        reset()
    }

    /// Restart the transaction from scratch using the original parameters.
    public func reset() {
        self.handle = nil
        if transmissionModes.isEmpty {
            print("at least one transmission mode must be provided")
            delegate.connectionState(changedTo: .error)
            return
        }

        var central: CentralManager?
        var peripheral: PeripheralManager?
        let handle = DelegateWrapper(delegate: delegate, mdoc: mdoc)

        for mode in transmissionModes {
            switch mode {
            case let .bleMdocCentralMode(l2capUsage):
                if central != nil {
                    print("multiple .bleMdocCentralMode instances provided, ignoring all but the first")
                    break
                }
                central = CentralManager(mdoc: handle, l2capUsage)
            case let .bleMdocPeripheralMode(l2capUsage):
                if peripheral != nil {
                    print("multiple .bleMdocPeripheralMode instances provided, ignoring all but the first")
                    break
                }
                peripheral = PeripheralManager(mdoc: handle, l2capUsage)
            }
        }

        handle.set(bleCentral: central)
        handle.set(blePeripheral: peripheral)
        handle.ready()

        self.handle = handle
    }

    /// Begin a graceful disconnection from the reader, delegate will be notified when the connection has been shutdown.
    public func disconnect() {
        handle = nil
    }

    /// This delegate is updated to the state of the transaction.
    public protocol Delegate {
        /// The connection state has updated.
        func connectionState(changedTo: State)
    }

    /// A wrapper of the UI delegate that receives callbacks from the transport(s), and handles transport multiplexing
    /// and presentation-session-level state management.
    class DelegateWrapper: NSObject & TransportCallback {
        private let inner: Delegate
        private let mdoc: Mdoc

        private let backgroundQueue = DispatchQueue(
            label: "com.spruceid.mobilesdk.mdoc.proximity.presentationmanager",
            qos: .userInitiated,
        )

        private var lastExternalState: State = .initializing
        private var state: InternalState = .initializing(Initializing()) {
            didSet {
                let state: State
                switch self.state {
                case .initializing:
                    state = .initializing
                case let .connecting(connecting):
                    do {
                        guard let data = try connecting.session.getQrHandover().data(using: .ascii) else {
                            print("failed to parse QR code")
                            self.state = .error
                            return
                        }
                        state = .connecting(qrPayload: data)
                    } catch {
                        // Should be unreachable - get_qr_handover() only returns Err if the session mutex
                        // is poisoned, which should never be able to happen.
                        print("failed to obtain QR code")
                        self.state = .error
                        return
                    }
                case .connected:
                    state = .connected
                case .sentResponse:
                    state = .sentResponse
                case .requestDismissed:
                    state = .requestDismissed
                case .readerDisconnected:
                    state = .readerDisconnected
                case .error:
                    state = .error
                }
                inner.connectionState(changedTo: state)
            }
        }

        init(delegate: Delegate, mdoc: Mdoc) {
            inner = delegate
            self.mdoc = mdoc
            backgroundQueue.suspend()
        }

        func set(bleCentral: CentralManager?) {
            guard case let .initializing(initializing) = state else {
                return
            }
            initializing.bleCentral = bleCentral
        }

        func set(blePeripheral: PeripheralManager?) {
            guard case let .initializing(initializing) = state else {
                return
            }
            initializing.blePeripheral = blePeripheral
        }

        func ready() {
            backgroundQueue.resume()
            guard case let .initializing(initializing) = state else { return }
            if initializing.noTransportsLeft() {
                print("at least one transmission method must be enabled")
                state = .error
            }
        }

        func expectedIdent() -> Data? {
            switch state {
            case let .connecting(connecting):
                connecting.session.getBleIdent()
            case let .connected(connected):
                connected.session.getBleIdent()
            default:
                nil
            }
        }

        func centralDidUpdate(state: CentralManager.State) {
            backgroundQueue.async {
                switch state {
                case .initializing:
                    break
                case let .scanning(details):
                    guard case let .initializing(initializing) = self.state else { return }
                    initializing.centralDetails = details
                    if initializing.allTransportsReady() { self.setupSession(initializing) }
                case .connecting:
                    break
                case .connected:
                    guard case let .connecting(connecting) = self.state,
                          let bleCentral = connecting.bleCentral
                    else {
                        return
                    }
                    self.state = .connected(Connected(transport: bleCentral, session: connecting.session))
                case .disconnected:
                    switch self.state {
                    case .requestDismissed, .sentResponse, .readerDisconnected, .error:
                        break
                    default:
                        self.state = .readerDisconnected
                    }
                case .error:
                    switch self.state {
                    case let .connecting(connecting):
                        connecting.bleCentral = nil
                        if connecting.noTransportsLeft() { self.state = .error }
                    case .requestDismissed, .sentResponse, .readerDisconnected, .error:
                        break
                    default:
                        self.state = .error
                    }
                }
            }
        }

        func peripheralDidUpdate(state: PeripheralManager.State) {
            backgroundQueue.async {
                switch state {
                case .initializing:
                    break
                case let .ready(details):
                    guard case let .initializing(initializing) = self.state else { return }
                    initializing.peripheralDetails = details
                    if initializing.allTransportsReady() { self.setupSession(initializing) }
                case .connected:
                    guard case let .connecting(connecting) = self.state,
                          let blePeripheral = connecting.blePeripheral
                    else {
                        return
                    }
                    self.state = .connected(Connected(transport: blePeripheral, session: connecting.session))
                case .disconnected:
                    switch self.state {
                    case .requestDismissed, .sentResponse, .readerDisconnected, .error:
                        break
                    default:
                        self.state = .readerDisconnected
                    }
                case .error:
                    switch self.state {
                    case let .connecting(connecting):
                        connecting.blePeripheral = nil
                        if connecting.noTransportsLeft() { self.state = .error }
                    case .requestDismissed, .sentResponse, .readerDisconnected, .error:
                        break
                    default:
                        self.state = .error
                    }
                }
            }
        }

        func required(action: RequiredAction) {
            inner.connectionState(changedTo: .action(required: action))
        }

        private func setupSession(_ initializing: Initializing) {
            let session: MdlPresentationSession
            do {
                session = try initializeMdlPresentationFromBytes(
                    mdoc: mdoc,
                    centralClientMode: initializing.centralDetails,
                    peripheralServerMode: initializing.peripheralDetails,
                    engagement: .qr,
                )
            } catch {
                print("unexpected error preparing the session: \(error.localizedDescription)")
                state = .error
                return
            }

            state = .connecting(Connecting(
                session: session,
                bleCentral: initializing.bleCentral,
                blePeripheral: initializing.blePeripheral,
            ))
        }

        func received(bytesSoFar: Int, outOfTotalBytes: Int?) {
            guard case .connected = state else { return }
            inner.connectionState(changedTo: .receivingRequest(
                bytesSoFar: bytesSoFar,
                outOfTotalBytes: outOfTotalBytes
            ))
        }

        func received(message: Data) {
            guard case let .connected(connected) = state else { return }
            let request: [ItemsRequest]
            do {
                request = try connected.session.handleRequest(request: message)
            } catch {
                print("message parsing failed: \(error)")
                state = .error
                return
            }
            inner.connectionState(changedTo: .receivedRequest(request: Request(items: request, responder: self)))
        }

        fileprivate func approve(items: [String: [String: [String]]]) {
            backgroundQueue.async {
                guard case let .connected(connected) = self.state else {
                    print("ignoring an approved response while in an invalid state: \(self.state)")
                    return
                }

                let payload: Data
                do {
                    payload = try connected.session.generateResponse(permittedItems: items)
                } catch {
                    print("response generation failed: \(error)")
                    self.state = .error
                    return
                }

                let signature: Data
                do {
                    signature = try KeyManager().getSigningKey(alias: self.mdoc.keyAlias()).sign(payload: payload)
                } catch {
                    print("response signing failed: \(error)")
                    self.state = .error
                    return
                }

                let response: Data
                do {
                    response = try connected.session.submitResponse(signature: signature)
                } catch {
                    print("response generation failed: \(error)")
                    self.state = .error
                    return
                }

                if !connected.transport.send(message: response) {
                    print("response sending failed")
                    self.state = .error
                }
            }
        }

        fileprivate func dismissRequest() {
            backgroundQueue.async {
                guard case .connected = self.state else { return }
                self.state = .requestDismissed
            }
        }

        func sent(bytesSoFar: Int, outOfTotalBytes: Int) {
            guard case .connected = state else { return }
            inner.connectionState(changedTo: .sendingResponse(
                bytesSoFar: bytesSoFar,
                outOfTotalBytes: outOfTotalBytes
            ))
        }

        func sent() {
            guard case let .connected(connected) = state else { return }
            state = .sentResponse
            // Short pause to keep the transport from being deinitialized while the reader receives the response.
            DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(1)) {
                print("Closing connection (\(type(of: connected.transport))) after successful submission")
            }
        }
    }

    /// The session state. This is intended to be used to directly drive the UI.
    public enum State {
        /// Preparing the system transport(s) to connect to the reader.
        case initializing
        /// An action must be performed by the app or user to continue.
        case action(required: RequiredAction)
        /// Attempting to establish a connection with the reader.
        case connecting(qrPayload: Data)
        /// The reader has connected.
        case connected
        /// Receiving a request from the reader.
        case receivingRequest(bytesSoFar: Int, outOfTotalBytes: Int?)
        /// Finished receiving a request from the reader.
        case receivedRequest(request: Request)
        /// Sending the response to the reader.
        case sendingResponse(bytesSoFar: Int, outOfTotalBytes: Int)
        /// Finished sending the response to the reader.
        case sentResponse
        /// The request to share was dismissed by the user.
        case requestDismissed
        /// The reader disconnected unexpectedly.
        case readerDisconnected
        /// An unrecoverable error occurred.
        case error
    }

    /// A request for mdoc presentation from a reader.
    ///
    /// Use `approve()` to transmit the response.
    public class Request {
        /// The requested items.
        public let items: [ItemsRequest]
        private let responder: DelegateWrapper

        fileprivate init(items: [ItemsRequest], responder: DelegateWrapper) {
            self.items = items
            self.responder = responder
        }

        /// Approve the supplied items for sharing with the reader.
        ///
        /// The nested dictionaries are structured as follows: `doctype -> namespace -> element identifier`.
        public consuming func approve(items: [String: [String: [String]]]) {
            responder.approve(items: items)
        }

        /// Dismiss the request.
        public consuming func dismiss() {
            responder.dismissRequest()
        }
    }

    private enum InternalState {
        /// Preparing the system transport(s) to connect to the reader.
        case initializing(Initializing)
        /// Attempting to establish a connection with the reader.
        case connecting(Connecting)
        /// The reader has connected.
        case connected(Connected)
        /// Finished sending the response to the reader.
        case sentResponse
        /// The request to share was dismissed by the user.
        case requestDismissed
        /// The reader disconnected unexpectedly.
        case readerDisconnected
        /// An unrecoverable error occurred.
        case error
    }

    private class Initializing {
        var bleCentral: CentralManager?
        var centralDetails: CentralClientDetails?
        var blePeripheral: PeripheralManager?
        var peripheralDetails: PeripheralServerDetails?

        func all() -> [Transport] {
            [bleCentral, blePeripheral].compactMap { $0 }
        }

        func allTransportsReady() -> Bool {
            if bleCentral != nil && centralDetails == nil {
                return false
            }
            if blePeripheral != nil && peripheralDetails == nil {
                return false
            }
            return true
        }

        func noTransportsLeft() -> Bool {
            bleCentral == nil && blePeripheral == nil
        }
    }

    private class Connecting {
        let session: MdlPresentationSession
        var bleCentral: CentralManager?
        var blePeripheral: PeripheralManager?

        init(session: MdlPresentationSession, bleCentral: CentralManager?, blePeripheral: PeripheralManager?) {
            self.session = session
            self.bleCentral = bleCentral
            self.blePeripheral = blePeripheral
        }

        func noTransportsLeft() -> Bool {
            bleCentral == nil && blePeripheral == nil
        }
    }

    private class Connected {
        let transport: Transport
        let session: MdlPresentationSession

        init(transport: Transport, session: MdlPresentationSession) {
            self.transport = transport
            self.session = session
        }
    }

    public enum DeviceEngagement {
        /// Engage using a QR code.
        case QRCode
    }
}
