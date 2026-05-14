import Combine
import Foundation
import SpruceIDMobileSdkRs

/// SwiftUI-friendly wrapper around `NfcReaderEngagement`.
///
/// Mirrors the Android `rememberNfcReaderEngagement` Compose helper: hold an
/// instance from your view (typically via `@StateObject`), drive
/// `setActive(_:)` from whatever conditions should arm the engagement (e.g.
/// "NFC tab is selected and we are still in the scanning phase"), and react
/// to phase changes by observing `phase`. The completed handover is
/// surfaced via the `pendingHandover` published property — observe it with
/// `.onChange(of:)` and call `consumeHandover()` once you have handled it.
public final class NfcReaderObservable: ObservableObject {
    /// The current UI-facing phase. Updated on the main queue.
    @Published public private(set) var phase: NfcReaderPhase = .idle

    /// A handover produced by the most recent successful NFC tap, ready for
    /// the caller to consume. Reset to `nil` by `consumeHandover()`.
    @Published public private(set) var pendingHandover: ReaderHandover?

    private let engagement: NfcReaderEngagement
    private var isActive = false

    public init(alertMessage: String = "Hold near the holder phone") {
        self.engagement = NfcReaderEngagement()
        engagement.alertMessage = alertMessage
        engagement.delegate = self
    }

    // No explicit deinit: when this observable is released, its `engagement`
    // is released too, and `NfcReaderEngagement.deinit` invalidates any
    // in-flight session. Calling `engagement.stop()` here would require
    // running on the main thread, which we can't guarantee for arbitrary
    // release paths.

    /// Arm (or disarm) the engagement. While active, the iOS NFC scan sheet
    /// will be shown; while inactive, any in-flight session is invalidated.
    /// Calling `setActive(true)` after a user-cancel or protocol error
    /// re-arms the engagement.
    public func setActive(_ active: Bool) {
        if active {
            guard !isActive else { return }
            isActive = true
            engagement.start()
        } else {
            isActive = false
            engagement.stop()
            phase = .idle
        }
    }

    /// Clear the pending handover once the caller has acted on it. Idempotent.
    public func consumeHandover() {
        pendingHandover = nil
    }
}

extension NfcReaderObservable: NfcReaderEngagement.Delegate {
    public func nfcReaderEngagement(_ engagement: NfcReaderEngagement, didChangePhase phase: NfcReaderPhase) {
        // The engagement dispatches delegate callbacks on main.
        self.phase = phase
        // Sync with engagement-side terminal states so the caller can re-arm
        // via setActive(true) without first having to toggle through false.
        switch phase {
        case .idle, .protocolError, .unsupported:
            isActive = false
        case .waitingForTag, .exchanging:
            break
        }
    }

    public func nfcReaderEngagement(_ engagement: NfcReaderEngagement, didCompleteHandover handover: ReaderHandover) {
        isActive = false
        phase = .idle
        pendingHandover = handover
    }
}
