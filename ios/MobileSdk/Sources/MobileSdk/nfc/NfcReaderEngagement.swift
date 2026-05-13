import CoreNFC
import Foundation
import SpruceIDMobileSdkRs

/// UI-facing phase for an NFC reader engagement.
///
/// Mirrors the Android `NfcReaderPhase` sealed class. iOS adds an explicit
/// `.idle` state used after the user dismisses the system NFC sheet — re-arming
/// in that case would just loop the modal, so the engagement stops and waits
/// for an explicit retry instead.
public enum NfcReaderPhase {
    /// NFC reading is not available on this device.
    case unsupported
    /// No session is currently running. Initial state, and the state entered
    /// when the user dismisses the iOS scan sheet.
    case idle
    /// The iOS scan sheet is up and the session is waiting for a tap.
    case waitingForTag
    /// A tap has been detected; the APDU handover exchange is in progress.
    case exchanging
    /// Protocol-level failure. The session has ended; the caller should
    /// re-arm with another `start()` (e.g. via a retry button).
    case protocolError(Error)
}

/// Drives the reader (verifier) side of ISO 18013-5 NFC engagement on iOS.
///
/// iOS does not have an "always-armed" reader mode like Android: each tap is
/// served by a one-shot `NFCTagReaderSession` that the system tears down on
/// success, timeout, user-cancel, or error. The engagement auto-restarts a
/// fresh session after transient session ends (timeout, tag lost) as long as
/// it is active. On success it surfaces the handover and stops. On user
/// cancel it stops and surfaces `.idle`. On a protocol-level error it stops
/// and surfaces `.protocolError(_)`.
///
/// Integrators driving the SDK from SwiftUI should prefer `NfcReaderObservable`,
/// which wraps this class with a published phase.
public final class NfcReaderEngagement: NSObject {
    /// Receives phase changes and the completed handover.
    public protocol Delegate: AnyObject {
        func nfcReaderEngagement(_ engagement: NfcReaderEngagement, didChangePhase phase: NfcReaderPhase)
        func nfcReaderEngagement(_ engagement: NfcReaderEngagement, didCompleteHandover handover: ReaderHandover)
    }

    public weak var delegate: Delegate?

    /// User-facing prompt shown on the iOS scan sheet. Set before `start()`.
    public var alertMessage: String = "Hold near the holder phone"

    private var session: NFCTagReaderSession?
    private var active = false

    public init(delegate: Delegate? = nil) {
        self.delegate = delegate
        super.init()
    }

    /// Open a reader session. If NFC reading is unavailable on this device,
    /// emits `.unsupported` and returns false.
    @discardableResult
    public func start() -> Bool {
        guard NFCTagReaderSession.readingAvailable else {
            emit(.unsupported)
            return false
        }
        active = true
        beginSession()
        return true
    }

    /// Tear down any in-flight session. The instance remains usable; call
    /// `start()` again to re-arm.
    public func stop() {
        active = false
        session?.invalidate()
        session = nil
    }

    private func beginSession() {
        guard active, session == nil else { return }
        guard let session = NFCTagReaderSession(
            pollingOption: [.iso14443],
            delegate: self,
            queue: nil
        ) else {
            active = false
            emit(.protocolError(NfcReaderEngagementError.sessionUnavailable))
            return
        }
        session.alertMessage = alertMessage
        session.begin()
        self.session = session
    }

    private func emit(_ phase: NfcReaderPhase) {
        let delegate = self.delegate
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            delegate?.nfcReaderEngagement(self, didChangePhase: phase)
        }
    }

    private func emitHandover(_ handover: ReaderHandover) {
        let delegate = self.delegate
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            delegate?.nfcReaderEngagement(self, didCompleteHandover: handover)
        }
    }
}

extension NfcReaderEngagement: NFCTagReaderSessionDelegate {
    public func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {
        emit(.waitingForTag)
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        self.session = nil
        // If we already shut the session down deliberately (success / protocol
        // error path set active=false before invalidating), the outcome has
        // been surfaced — nothing more to do here.
        guard active else { return }

        let nfcError = error as? NFCReaderError
        if nfcError?.code == .readerSessionInvalidationErrorUserCanceled {
            active = false
            emit(.idle)
            return
        }
        // Transient session end (timeout, tag lost mid-poll, system reset).
        // Re-arm after a brief delay so iOS has time to dismiss its sheet
        // before we open another one.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in
            self?.beginSession()
        }
    }

    public func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard let firstTag = tags.first, case let .iso7816(iso7816Tag) = firstTag else {
            failHandover(session: session, error: NfcReaderEngagementError.unsupportedTagType)
            return
        }
        emit(.exchanging)
        Task { [weak self] in
            await self?.runHandover(session: session, tag: iso7816Tag)
        }
    }

    private func runHandover(session: NFCTagReaderSession, tag: NFCISO7816Tag) async {
        do {
            try await connect(session: session, to: .iso7816(tag))
            // Fresh driver per tap: holders that disconnect mid-handover
            // (e.g. wallet picker) reconnect with a new SELECT, so resumption
            // is not possible. Matches the Android driver.
            let driverInit = newReaderApduHandoverDriver()
            var rapdu = try await sendCommand(driverInit.initialApdu, on: tag)
            while true {
                let progress = try driverInit.driver.processRapdu(command: rapdu)
                switch progress {
                case .inProgress(let nextApdu):
                    rapdu = try await sendCommand(nextApdu, on: tag)
                case .done(let handover):
                    active = false
                    session.invalidate()
                    emitHandover(handover)
                    return
                }
            }
        } catch {
            failHandover(session: session, error: error)
        }
    }

    private func failHandover(session: NFCTagReaderSession, error: Error) {
        // If `stop()` already disarmed us, the error is just collateral from
        // the session invalidation it triggered — don't surface it.
        let wasActive = active
        active = false
        session.invalidate(errorMessage: error.localizedDescription)
        if wasActive {
            emit(.protocolError(error))
        }
    }

    private func connect(session: NFCTagReaderSession, to tag: NFCTag) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            session.connect(to: tag) { error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: ())
                }
            }
        }
    }

    private func sendCommand(_ rawApdu: Data, on tag: NFCISO7816Tag) async throws -> Data {
        guard let apdu = NFCISO7816APDU(data: rawApdu) else {
            throw NfcReaderEngagementError.malformedCommandApdu
        }
        return try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Data, Error>) in
            tag.sendCommand(apdu: apdu) { responseData, sw1, sw2, error in
                if let error {
                    continuation.resume(throwing: error)
                    return
                }
                var response = responseData
                response.append(sw1)
                response.append(sw2)
                continuation.resume(returning: response)
            }
        }
    }
}

/// Errors specific to the NFC engagement orchestrator. CoreNFC and
/// `ReaderApduHandoverError` propagate through unchanged.
public enum NfcReaderEngagementError: Error, LocalizedError {
    case malformedCommandApdu
    case sessionUnavailable
    case unsupportedTagType

    public var errorDescription: String? {
        switch self {
        case .malformedCommandApdu:
            return "The handover driver produced a malformed command APDU."
        case .sessionUnavailable:
            return "Could not start an NFC reader session on this device."
        case .unsupportedTagType:
            return "The detected NFC tag is not ISO 7816 / ISO-DEP."
        }
    }
}
