import CryptoKit
import SpruceIDMobileSdkRs
import XCTest

@testable import MobileSdk

final class PresentationManagerTests: XCTestCase {

    func testTerminateWhileConnectedSignalsEndAndNotifies() throws {
        let session = try connectedSession()

        let dismissed = session.delegate.expect(.requestDismissed)
        session.wrapper.terminate(notify: true)
        wait(for: [dismissed], timeout: 1)

        XCTAssertEqual(session.transport.signalEndCount, 1)
        XCTAssertEqual(session.delegate.states.filter { $0 == .requestDismissed }.count, 1)
    }

    func testTerminateWhileConnectingNotifies() throws {
        let session = try connectingSession()

        let dismissed = session.delegate.expect(.requestDismissed)
        session.wrapper.terminate(notify: true)
        wait(for: [dismissed], timeout: 1)
    }

    /// The transport holds the wrapper, like the BLE managers do, so only terminating the session breaks the cycle.
    func testTerminateReleasesSession() throws {
        weak var weakWrapper: MdocProximityPresentationManager.DelegateWrapper?
        weak var weakTransport: FakeTransport?
        try autoreleasepool {
            let session = try connectedSession()
            weakWrapper = session.wrapper
            weakTransport = session.transport
            let dismissed = session.delegate.expect(.requestDismissed)
            session.wrapper.terminate(notify: true)
            wait(for: [dismissed], timeout: 1)
        }

        let released = expectation(description: "session released")
        DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(1500)) {
            if weakWrapper == nil && weakTransport == nil { released.fulfill() }
        }
        wait(for: [released], timeout: 2)
    }

    func testSilencedSessionDoesNotNotify() throws {
        let session = try connectedSession()

        let anyState = session.delegate.expectAny()
        anyState.isInverted = true
        session.wrapper.terminate(notify: false)
        session.wrapper.required(action: .turnOnBluetooth)
        wait(for: [anyState], timeout: 0.5)

        XCTAssertEqual(session.transport.signalEndCount, 1)
    }

    func testReaderDisconnectAfterTerminateIsIgnored() throws {
        let session = try connectedSession()
        let dismissed = session.delegate.expect(.requestDismissed)
        session.wrapper.terminate(notify: true)
        wait(for: [dismissed], timeout: 1)

        let anyState = session.delegate.expectAny()
        anyState.isInverted = true
        session.wrapper.centralDidUpdate(state: .disconnected)
        session.wrapper.centralDidUpdate(state: .error)
        wait(for: [anyState], timeout: 0.5)
    }

    // MARK: - Helpers

    private struct Session {
        let wrapper: MdocProximityPresentationManager.DelegateWrapper
        let delegate: RecordingDelegate
        let transport: FakeTransport
    }

    private func connectingSession() throws -> Session {
        let mdoc = try generateTestMdl(keyManager: InMemoryKeyStore(), keyAlias: "test")
        let delegate = RecordingDelegate()
        let wrapper = MdocProximityPresentationManager.DelegateWrapper(
            delegate: delegate,
            mdoc: mdoc,
            engagement: .QRCode
        )
        let transport = FakeTransport()
        transport.callback = wrapper
        wrapper.set(bleCentral: transport)
        wrapper.ready()

        let connecting = delegate.expect { if case .connecting = $0 { true } else { false } }
        wrapper.centralDidUpdate(state: .scanning(CentralClientDetails(serviceUuid: UUID().uuidString)))
        wait(for: [connecting], timeout: 5)
        return Session(wrapper: wrapper, delegate: delegate, transport: transport)
    }

    private func connectedSession() throws -> Session {
        let session = try connectingSession()
        let connected = session.delegate.expect(.connected)
        session.wrapper.centralDidUpdate(state: .connected)
        wait(for: [connected], timeout: 1)
        return session
    }
}

private final class FakeTransport: Transport {
    /// Held strongly, like the BLE managers hold their participant.
    var callback: TransportCallback?
    private(set) var signalEndCount = 0

    func send(message _: Data) -> Bool { true }

    func signalEnd() { signalEndCount += 1 }
}

private final class RecordingDelegate: MdocProximityPresentationManager.Delegate {
    private typealias Matcher = (MdocProximityPresentationManager.State) -> Bool
    private typealias Waiter = (expectation: XCTestExpectation, matches: Matcher)

    private let lock = NSLock()
    private var recorded: [MdocProximityPresentationManager.State] = []
    private var waiters: [Waiter] = []

    var states: [MdocProximityPresentationManager.State] {
        lock.lock()
        defer { lock.unlock() }
        return recorded
    }

    func expect(_ state: MdocProximityPresentationManager.State) -> XCTestExpectation {
        expect { $0 == state }
    }

    func expectAny() -> XCTestExpectation {
        expect { _ in true }
    }

    func expect(where matches: @escaping (MdocProximityPresentationManager.State) -> Bool) -> XCTestExpectation {
        let expectation = XCTestExpectation(description: "delegate state")
        lock.lock()
        waiters.append((expectation, matches))
        lock.unlock()
        return expectation
    }

    func connectionState(changedTo state: MdocProximityPresentationManager.State) {
        lock.lock()
        recorded.append(state)
        let matched = waiters.filter { $0.matches(state) }
        waiters.removeAll { $0.matches(state) }
        lock.unlock()
        matched.forEach { $0.expectation.fulfill() }
    }
}

private final class InMemoryKeyStore: KeyStore, @unchecked Sendable {
    private let key = InMemorySigningKey()

    func getSigningKey(alias _: KeyAlias) throws -> SigningKey { key }
}

private final class InMemorySigningKey: SigningKey, @unchecked Sendable {
    private let privateKey = P256.Signing.PrivateKey()

    func jwk() throws -> String {
        // x963 representation: 0x04 || x || y
        let point = privateKey.publicKey.x963Representation
        let xCoordinate = base64url(point[1..<33])
        let yCoordinate = base64url(point[33..<65])
        return #"{"kty":"EC","crv":"P-256","x":"\#(xCoordinate)","y":"\#(yCoordinate)"}"#
    }

    func sign(payload: Data) throws -> Data {
        try privateKey.signature(for: payload).rawRepresentation
    }

    private func base64url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
