import Foundation
import SpruceIDMobileSdkRs

/// Entrypoint to mdoc close proximity reading.
///
/// Dropping this class or calling `.disconnect()` will ensure that the underlying transmission services are cleaned up.
public class MdocProximityReader {
    private let qrCodePayload: String,
                delegate: Delegate,
                requestedItems: [String: [String: Bool]],
                trustAnchorRegistry: [String]?,
                l2capUsage: L2CAPUsage

    private var handle: DelegateWrapper?

    public init(
        fromHolderQrCode payload: String,
        delegate: Delegate,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]? = nil,
        l2capUsage: L2CAPUsage = .disableL2CAP,
    ) {
        qrCodePayload = payload
        self.delegate = delegate
        self.requestedItems = requestedItems
        self.trustAnchorRegistry = trustAnchorRegistry
        self.l2capUsage = l2capUsage
        reset()
    }

    /// Restart the transaction from scratch using the original parameters.
    public func reset() {
        self.handle = nil
        let session: MdlReaderSessionData
        do {
            session = try establishSession(
                uri: qrCodePayload,
                requestedItems: requestedItems,
                trustAnchorRegistry: trustAnchorRegistry
            )
        } catch let error {
            print("failed to construct session establishment: \(error)")
            self.delegate.connectionState(changedTo: .error)
            return
        }

        let handle = DelegateWrapper(delegate: delegate, session: session)
        let transport: Transport

        if let mdocCentral = session.state.bleCentralClientDetails().first {
            transport = PeripheralManager(
                reader: handle,
                mdocCentralClientMode: mdocCentral,
                l2capUsage,
            )
        } else if let mdocPeripheral = session.state.blePeripheralServerDetails().first {
            transport = CentralManager(
                reader: handle,
                mdocPeripheralServerMode: mdocPeripheral,
                l2capUsage,
            )
        } else {
            print("mdoc did not advertise any supported transmission methods")
            delegate.connectionState(changedTo: .error)
            return
        }

        handle.ready(transport: transport)
        self.handle = handle
    }

    /// Begin a graceful disconnection from the mdoc, delegate will be notified when the connection has been shutdown.
    public func disconnect() {
        handle = nil
    }

    /// MdocProximityReader notifies this delegate about updates to the state of the transaction.
    public protocol Delegate {
        /// The reader connection state has updated.
        func connectionState(changedTo: State)
    }

    /// A wrapper of the UI delegate that receives callbacks from the transport and handles presentation-session-level
    /// state management.
    class DelegateWrapper: NSObject & TransportCallback {
        private let inner: Delegate
        private let session: MdlReaderSessionData

        private let backgroundQueue = DispatchQueue(
            label: "com.spruceid.mobilesdk.mdoc.proximity.reader",
            qos: .userInitiated,
        )

        private var state: InternalState {
            didSet {
                let state: State =
                    switch self.state {
                    case .initializing:
                        .initializing
                    case .connecting:
                        .connecting
                    case .connected:
                        .connected
                    case let .receivedResponse(response):
                        .receivedResponse(response)
                    case .mdocDisconnected:
                        .mdocDisconnected
                    case .error:
                        .error
                    }
                inner.connectionState(changedTo: state)
            }
        }

        init(delegate: Delegate, session: MdlReaderSessionData) {
            backgroundQueue.suspend()
            inner = delegate
            self.session = session
            state = .initializing
        }

        func ready(transport: Transport) {
            print("MdocProximityReader using \(type(of: transport))")
            state = .connecting(transport)
            backgroundQueue.resume()
        }

        func bleIdent() -> Data { session.bleIdent }

        func required(action: RequiredAction) {
            inner.connectionState(changedTo: .action(required: action))
        }

        func peripheralDidUpdate(state: PeripheralManager.State) {
            backgroundQueue.async {
                switch state {
                case .initializing, .ready:
                    break
                case .connected:
                    guard case let .connecting(transport) = self.state else { return }
                    self.state = .connected(transport)
                    if !transport.send(message: self.session.request) {
                        print("failed to send request to mdoc")
                        self.state = .error
                    }
                case .disconnected:
                    switch self.state {
                    case .receivedResponse, .mdocDisconnected, .error:
                        break
                    default:
                        self.state = .mdocDisconnected
                    }
                case .error:
                    switch self.state {
                    case .receivedResponse, .mdocDisconnected, .error:
                        break
                    default:
                        self.state = .error
                    }
                }
            }
        }

        func centralDidUpdate(state _: CentralManager.State) {
            backgroundQueue.async {}
        }

        func sent(bytesSoFar: Int, outOfTotalBytes: Int) {
            guard case .connected = state else { return }
            inner.connectionState(changedTo: .sendingRequest(bytesSoFar: bytesSoFar, outOfTotalBytes: outOfTotalBytes))
        }

        func sent() {
            guard case .connected = state else { return }
            inner.connectionState(changedTo: .sentRequest)
        }

        func received(bytesSoFar: Int, outOfTotalBytes: Int?) {
            guard case .connected = state else { return }
            inner.connectionState(changedTo:
                    .receivingResponse(bytesSoFar: bytesSoFar, outOfTotalBytes: outOfTotalBytes)
            )
        }

        func received(message: Data) {
            guard case .connected = state else { return }
            let response: Response
            do {
                response = try Response(data: handleResponse(state: session.state, response: message))
            } catch let err {
                print("failed to parse the response")
                self.state = .error
                return
            }
            state = .receivedResponse(response)
        }
    }

    /// A response from the mdoc.
    public class Response {
        /// The response.
        public let data: MdlReaderResponseData

        fileprivate init(data: MdlReaderResponseData) {
            self.data = data
        }
    }

    /// The session state. This is intended to be used to directly drive the UI.
    public enum State {
        /// Preparing the system transport(s) to connect to the mdoc.
        case initializing
        /// An action must be performed by the app or user to continue.
        case action(required: RequiredAction)
        /// Attempting to establish a connection with the mdoc.
        case connecting
        /// The mdoc has connected.
        case connected
        /// Sending a request to the mdoc.
        case sendingRequest(bytesSoFar: Int, outOfTotalBytes: Int)
        /// Finished sending a request to the mdoc.
        case sentRequest
        /// Sending the response to the reader.
        case receivingResponse(bytesSoFar: Int, outOfTotalBytes: Int?)
        /// Sending the response to the reader.
        case receivedResponse(Response)
        /// The mdoc disconnected unexpectedly.
        case mdocDisconnected
        /// An unrecoverable error occurred.
        case error
    }

    private enum InternalState {
        /// Preparing the system transport(s) to connect to the mdoc.
        case initializing
        /// Attempting to establish a connection with the mdoc.
        case connecting(Transport)
        /// The mdoc has connected.
        case connected(Transport)
        /// Sending the response to the reader.
        case receivedResponse(Response)
        /// The mdoc disconnected unexpectedly.
        case mdocDisconnected
        /// An unrecoverable error occurred.
        case error
    }
}
