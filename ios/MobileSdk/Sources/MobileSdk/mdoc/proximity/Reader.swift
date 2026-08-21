import Foundation
import SpruceIDMobileSdkRs

/// Entrypoint to mdoc close proximity reading.
///
/// Dropping this class or calling `.disconnect()` will ensure that the underlying transmission services are cleaned up.
public class MdocProximityReader {
    private let handover: ReaderHandover,
                delegate: Delegate,
                requestedItems: [String: [String: Bool]],
                trustAnchorRegistry: [String]?,
                docType: String,
                certificateProfiles: [String: MdocCertificateProfiles]?,
                l2capUsage: L2CAPUsage

    private var handle: DelegateWrapper?

    /// Start a reading session from a holder-presented QR code.
    ///
    /// - Parameters:
    ///   - requestedItems: data elements to request, keyed by namespace then element identifier.
    ///   - docType: document type to request. Required: a request naming the wrong doctype is
    ///     answered with nothing rather than an error, so the caller must say what it wants.
    ///   - certificateProfiles: certificate validation rules per doctype. `nil` validates every
    ///     doctype under the ISO/IEC 18013-5 mDL profile, which is the behaviour every caller had
    ///     before this was configurable. Supplying a map also means a doctype absent from it is
    ///     refused rather than validated under a guess, so it must cover `docType`.
    public convenience init(
        fromHolderQrCode payload: String,
        delegate: Delegate,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]? = nil,
        docType: String,
        certificateProfiles: [String: MdocCertificateProfiles]? = nil,
        l2capUsage: L2CAPUsage = .disableL2CAP,
    ) {
        self.init(
            fromHandover: ReaderHandover.newQr(qr: payload),
            delegate: delegate,
            requestedItems: requestedItems,
            trustAnchorRegistry: trustAnchorRegistry,
            docType: docType,
            certificateProfiles: certificateProfiles,
            l2capUsage: l2capUsage,
        )
    }

    /// Start a reading session from a pre-built handover (e.g. produced by an NFC engagement).
    ///
    /// - Parameters:
    ///   - requestedItems: data elements to request, keyed by namespace then element identifier.
    ///   - docType: document type to request. Required: a request naming the wrong doctype is
    ///     answered with nothing rather than an error, so the caller must say what it wants.
    ///   - certificateProfiles: certificate validation rules per doctype. `nil` validates every
    ///     doctype under the ISO/IEC 18013-5 mDL profile, which is the behaviour every caller had
    ///     before this was configurable. Supplying a map also means a doctype absent from it is
    ///     refused rather than validated under a guess, so it must cover `docType`.
    public init(
        fromHandover handover: ReaderHandover,
        delegate: Delegate,
        requestedItems: [String: [String: Bool]],
        trustAnchorRegistry: [String]? = nil,
        docType: String,
        certificateProfiles: [String: MdocCertificateProfiles]? = nil,
        l2capUsage: L2CAPUsage = .disableL2CAP,
    ) {
        self.handover = handover
        self.delegate = delegate
        self.requestedItems = requestedItems
        self.trustAnchorRegistry = trustAnchorRegistry
        self.docType = docType
        self.certificateProfiles = certificateProfiles
        self.l2capUsage = l2capUsage
        reset()
    }

    /// Restart the transaction from scratch using the original parameters.
    public func reset() {
        self.handle = nil
        let session: MdlReaderSessionData
        do {
            session = try establishSession(
                handover: handover,
                requestedItems: requestedItems,
                trustAnchorRegistry: trustAnchorRegistry,
                docType: docType
            )
        } catch {
            print("failed to construct session establishment: \(error)")
            delegate.connectionState(changedTo: .error)
            return
        }

        let handle = DelegateWrapper(
            delegate: delegate,
            session: session,
            certificateProfiles: certificateProfiles
        )
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
        private let certificateProfiles: [String: MdocCertificateProfiles]?

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

        init(
            delegate: Delegate,
            session: MdlReaderSessionData,
            certificateProfiles: [String: MdocCertificateProfiles]?
        ) {
            backgroundQueue.suspend()
            inner = delegate
            self.session = session
            self.certificateProfiles = certificateProfiles
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

        func centralDidUpdate(state: CentralManager.State) {
            backgroundQueue.async {
                switch state {
                case .initializing, .scanning, .connecting:
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
                response = try Response(data: handleResponse(
                    state: session.state,
                    response: message,
                    certificateProfiles: certificateProfiles
                ))
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
