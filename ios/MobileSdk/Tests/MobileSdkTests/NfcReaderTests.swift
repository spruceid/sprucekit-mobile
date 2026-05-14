import XCTest

@testable import MobileSdk

final class NfcReaderTests: XCTestCase {

    // MARK: - NfcReaderEngagementError

    func testEngagementErrorDescriptions() {
        XCTAssertEqual(
            NfcReaderEngagementError.malformedCommandApdu.errorDescription,
            "The handover driver produced a malformed command APDU."
        )
        XCTAssertEqual(
            NfcReaderEngagementError.sessionUnavailable.errorDescription,
            "Could not start an NFC reader session on this device."
        )
        XCTAssertEqual(
            NfcReaderEngagementError.unsupportedTagType.errorDescription,
            "The detected NFC tag is not ISO 7816 / ISO-DEP."
        )
    }

    // MARK: - NfcReaderObservable

    @MainActor
    func testInitialPhaseIsIdle() {
        let observable = NfcReaderObservable()
        if case .idle = observable.phase {
            // expected
        } else {
            XCTFail("Initial phase should be .idle, got \(observable.phase)")
        }
        XCTAssertNil(observable.pendingHandover)
    }

    @MainActor
    func testConsumeHandoverIsIdempotent() {
        let observable = NfcReaderObservable()
        XCTAssertNil(observable.pendingHandover)
        // Calling repeatedly on a nil handover should remain nil and not crash.
        observable.consumeHandover()
        observable.consumeHandover()
        XCTAssertNil(observable.pendingHandover)
    }

    @MainActor
    func testSetActiveFalseEmitsIdleWithoutHardware() {
        // No NFC hardware in the simulator: setActive(true) would emit
        // .unsupported via start(). setActive(false) is a pure no-op disarm
        // that must always settle on .idle.
        let observable = NfcReaderObservable()
        observable.setActive(false)
        if case .idle = observable.phase {
            // expected
        } else {
            XCTFail("Phase should be .idle after setActive(false), got \(observable.phase)")
        }
    }

    @MainActor
    func testSetActiveTrueOnSimulatorSurfacesUnsupported() {
        // The simulator never reports NFC reading as available, so arming
        // should immediately surface the .unsupported phase.
        let observable = NfcReaderObservable()
        observable.setActive(true)
        if case .unsupported = observable.phase {
            // expected
        } else {
            XCTFail("Phase should be .unsupported on simulator, got \(observable.phase)")
        }
    }
}
