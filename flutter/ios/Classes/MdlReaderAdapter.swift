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
        docType: String,
        trustedRoots: [String],
        certificateProfiles: [String: MdlCertificateProfiles]?
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
                    // `.idle` fires from two distinct paths:
                    //   1. User-cancel before tap — was `.nfcWaitingForTag`,
                    //      want to surface as `.uninitialized` so the host
                    //      can drive a "tap to retry" UI.
                    //   2. Successful handover delivery — `NfcReaderObservable`
                    //      sets `phase = .idle` *immediately before*
                    //      `pendingHandover = handover`. The two are separate
                    //      `@Published` properties; the `$phase` sink runs
                    //      first. If we emit `.uninitialized` here we'd
                    //      flicker `.nfcExchanging → .uninitialized →
                    //      .bleConnecting` on Dart side.
                    // Only emit for path 1; the `.exchanging → .idle`
                    // transition is swallowed and `pendingHandover` drives
                    // the real next state.
                    if self.currentState.state == .nfcWaitingForTag {
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
                guard let self else { return }
                do {
                    try self.onHandover(
                        handover,
                        query: query,
                        docType: docType,
                        trustedRoots: trustedRoots,
                        certificateProfiles: certificateProfiles
                    )
                } catch {
                    // A misconfigured certificate profile is only detectable here, after the tap
                    // succeeded. Report it rather than leaving the session in `bleConnecting`.
                    // `Error.localizedDescription` would bridge through NSError and lose the
                    // message, so read it off the concrete type.
                    self.updateState(MdlReaderStateUpdate(
                        state: .error,
                        error: (error as? MdlReaderPigeonError)?.message
                            ?? error.localizedDescription
                    ))
                }
                observable?.consumeHandover()
            }
            .store(in: &cancellables)

        observable.setActive(true)
    }

    func startQrReader(
        qrUri: String,
        query: [String: [String: Bool]],
        docType: String,
        trustedRoots: [String],
        certificateProfiles: [String: MdlCertificateProfiles]?
    ) throws {
        cleanupInternal()
        let handover = ReaderHandover.newQr(qr: qrUri)
        try onHandover(
            handover,
            query: query,
            docType: docType,
            trustedRoots: trustedRoots,
            certificateProfiles: certificateProfiles
        )
    }

    func cancel() throws {
        cleanupInternal()
        updateState(MdlReaderStateUpdate(state: .uninitialized))
    }

    // MARK: - Internal

    private func onHandover(
        _ handover: ReaderHandover,
        query: [String: [String: Bool]],
        docType: String,
        trustedRoots: [String],
        certificateProfiles: [String: MdlCertificateProfiles]?
    ) throws {
        updateState(MdlReaderStateUpdate(state: .bleConnecting))
        let delegate = ReaderDelegate(adapter: self)
        self.reader = MdocProximityReader(
            fromHandover: handover,
            delegate: delegate,
            requestedItems: query,
            trustAnchorRegistry: trustedRoots.isEmpty ? nil : trustedRoots,
            docType: docType,
            certificateProfiles: try certificateProfiles.map {
                try Self.toNative($0, docType: docType)
            }
        )
    }

    /// Translate the Pigeon profile map into the native SDK's representation.
    ///
    /// Throws rather than falling back to a default profile when the map does not cover
    /// `docType`: silently substituting mDL rules would validate a credential against the wrong
    /// PKI without saying so.
    private static func toNative(
        _ profiles: [String: MdlCertificateProfiles],
        docType: String
    ) throws -> [String: MdocCertificateProfiles] {
        guard profiles[docType] != nil else {
            throw MdlReaderPigeonError(
                code: "certificate-profiles",
                message: "certificateProfiles has no entry for the requested doctype \(docType); "
                    + "found \(Array(profiles.keys))",
                details: nil
            )
        }
        return try profiles.mapValues { profiles in
            MdocCertificateProfiles(
                issuer: try toNative(profiles.issuer, docType: docType),
                reader: try toNative(profiles.reader, docType: docType)
            )
        }
    }

    private static func toNative(
        _ profile: MdlIssuerCertificateProfile,
        docType: String
    ) throws -> IssuerCertificateProfile {
        // Pigeon renders a Dart sealed class as a Swift protocol rather than an enum, so this
        // switch cannot be exhaustive. The default arm throws instead of substituting a profile:
        // an unrecognised case means the Pigeon definitions and this adapter have diverged, and
        // guessing would validate a credential against rules the caller did not ask for.
        switch profile {
        case let profile as MdlIssuerBuiltinProfile:
            return .builtin(profile: toNative(profile.profile))
        case let profile as MdlIssuerConfiguredProfile:
            return .config(config: IssuerProfileConfig(
                documentSignerEku: profile.config.documentSignerEku,
                stateOrProvince: toNative(profile.config.stateOrProvince),
                crlDistributionPoints: toNative(profile.config.crlDistributionPoints),
                issuerAlternativeName: toNative(profile.config.issuerAlternativeName)
            ))
        default:
            throw profileError("issuer", docType, "is not a recognised profile kind")
        }
    }

    private static func toNative(
        _ profile: MdlReaderCertificateProfile,
        docType: String
    ) throws -> ReaderCertificateProfile {
        switch profile {
        case let profile as MdlReaderBuiltinProfile:
            return .builtin(profile: toNative(profile.profile))
        case let profile as MdlReaderConfiguredProfile:
            return .config(config: ReaderProfileConfig(
                readerAuthEku: profile.config.readerAuthEku,
                crlDistributionPoints: toNative(profile.config.crlDistributionPoints),
                issuerAlternativeName: toNative(profile.config.issuerAlternativeName)
            ))
        default:
            throw profileError("reader", docType, "is not a recognised profile kind")
        }
    }

    private static func profileError(
        _ half: String,
        _ docType: String,
        _ problem: String
    ) -> MdlReaderPigeonError {
        MdlReaderPigeonError(
            code: "certificate-profiles",
            message: "\(half) profile for \(docType) \(problem)",
            details: nil
        )
    }

    private static func toNative(
        _ profile: MdlBuiltinCertificateProfile
    ) -> BuiltinCertificateProfile {
        switch profile {
        case .mdl: return .mdl
        case .aamvaMdl: return .aamvaMdl
        case .eudiPid: return .eudiPid
        case .iso23220: return .iso23220
        }
    }

    private static func toNative(_ rule: MdlCertificateRdnRule) -> CertificateRdnRule {
        switch rule {
        case .matchIfPresent: return .matchIfPresent
        case .required: return .required
        }
    }

    private static func toNative(
        _ rule: MdlCertificateExtensionRule
    ) -> CertificateExtensionRule {
        switch rule {
        case .required: return .required
        case .optional: return .optional
        }
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
            errors: data.errors
        )
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
