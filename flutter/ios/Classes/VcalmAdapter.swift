import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Error types for the VCALM signer.
enum VcalmSignerError: Error {
    case illegalArgumentException(reason: String)
}

/// Signer for VCALM presentation.
///
/// Uses `did:key` (not `did:jwk`) because the target exchange server requires the
/// holder DID to be `did:key`. Conforms to the base `PresentationSigner`.
class VcalmSigner: PresentationSigner {
    private let keyId: String
    private let _jwk: String
    private let didKey = DidMethodUtils(method: SpruceIDMobileSdkRs.DidMethod.key)

    init(keyId: String) throws {
        self.keyId = keyId
        if !KeyManager.keyExists(id: keyId) {
            _ = KeyManager.generateSigningKey(id: keyId)
        }
        guard let jwk = KeyManager.getJwk(id: keyId) else {
            throw VcalmSignerError.illegalArgumentException(reason: "Invalid kid")
        }
        self._jwk = jwk.description
    }

    func sign(payload: Data) async throws -> Data {
        guard let signature = KeyManager.signPayload(id: keyId, payload: [UInt8](payload)) else {
            throw VcalmSignerError.illegalArgumentException(reason: "Failed to sign payload")
        }
        return Data(signature)
    }

    func algorithm() -> Algorithm {
        if let data = _jwk.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let alg = json["alg"] as? String {
            return alg
        }
        return "ES256"
    }

    func verificationMethod() async -> String {
        do {
            return try await didKey.vmFromJwk(jwk: _jwk)
        } catch {
            fatalError("VcalmSigner: failed to derive verification method from JWK: \(error)")
        }
    }

    func did() -> String {
        do {
            return try didKey.didFromJwk(jwk: _jwk)
        } catch {
            fatalError("VcalmSigner: failed to derive DID from JWK: \(error)")
        }
    }

    func jwk() -> String {
        return _jwk
    }

    // The VP-wrapper proof stays `ecdsa-rdfc-2019` for challenge/domain binding;
    // any `ecdsa-sd-2023` selective-disclosure proof lives on the credential
    // (derived in Rust), not on the VP wrapper.
    func cryptosuite() -> CryptosuiteString {
        return "ecdsa-rdfc-2019"
    }
}

/// VCALM (`vcapi`) Pigeon adapter for iOS.
///
/// Pure marshaling layer: holds one `VcalmHolder` session, retains matched
/// `ParsedCredential` opaque handles in an `NSLock`-guarded key-map, and
/// projects the UniFFI `StepResult` onto the Pigeon `VcalmStepResult`. NO
/// protocol logic lives here — all VCALM logic stays in Rust.
class VcalmAdapter: Vcalm {

    private let credentialPackAdapter: CredentialPackAdapter
    private let lock = NSLock()

    private var holder: VcalmHolder?
    /// Resolves a Dart-side `VcalmCredentialKey` back to the live opaque
    /// `ParsedCredential` handle. `ParsedCredential` is a UniFFI object that
    /// cannot cross Pigeon, so the live handles are retained here and Dart
    /// only ever holds the lightweight key.
    private var credentialsByKey: [VcalmCredentialKey: ParsedCredential] = [:]

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
    }

    /// Flow logger. Filter device logs with the `VcalmAdapter` tag (Xcode
    /// console / `log stream`). Dart-side logs use the `VcalmDemo` tag.
    private func log(_ message: String) {
        NSLog("[VcalmAdapter] %@", message)
    }

    func createHolder(
        credentialPackIds: [String],
        trustedDids: [String],
        keyId: String,
        contextMap: [String: String]?,
        completion: @escaping (Result<VcalmResult, Error>) -> Void
    ) {
        log("createHolder: keyId=\(keyId), trustedDids=\(trustedDids.count), packIds=\(credentialPackIds.count)")
        Task {
            do {
                // The holder's own VdcCollection receives issuance (`acceptOffer`)
                // credentials. To ALSO make the host app's existing wallet
                // credentials presentable via QBE matching, load the passed packs
                // into native ParsedCredential handles and seed the holder via
                // provideCredentials.
                let vdc = VdcCollection(engine: StorageManager(appGroupId: nil))
                let signer = try VcalmSigner(keyId: keyId)

                let newHolder = try await VcalmHolder.newSession(
                    vdcCollection: vdc,
                    trustedDids: trustedDids,
                    signer: signer,
                    contextMap: contextMap,
                    keystore: KeyManager()
                )

                if !credentialPackIds.isEmpty {
                    var credentials: [SpruceIDMobileSdkRs.ParsedCredential] = []
                    for packId in credentialPackIds {
                        credentials.append(
                            contentsOf: credentialPackAdapter.getNativeCredentials(packId: packId))
                    }
                    log("createHolder: seeding \(credentials.count) wallet credential(s) for QBE matching")
                    if !credentials.isEmpty {
                        try await newHolder.provideCredentials(credentials: credentials)
                    }
                }

                lock.lock()
                self.holder = newHolder
                lock.unlock()

                log("createHolder: success")
                completion(.success(VcalmSuccess(message: "Holder created successfully")))
            } catch {
                log("createHolder FAILED: \(error)")
                completion(.success(VcalmError(message: error.localizedDescription)))
            }
        }
    }

    func startExchange(
        url: String,
        authHeader: String?,
        completion: @escaping (Result<VcalmStepResult, Error>) -> Void
    ) {
        Task {
            do {
                guard let holder = currentHolder() else {
                    completion(.success(VcalmProblem(
                        problemType: "no-holder",
                        status: nil,
                        title: "Holder not initialized",
                        detail: "Call createHolder first."
                    )))
                    return
                }
                log("startExchange: url=\(url), authHeader=\(authHeader != nil)")
                let step = try await holder.startExchange(input: url, authHeader: authHeader)
                completion(.success(try await toPigeonStep(step)))
            } catch {
                log("startExchange FAILED: \(error)")
                completion(.success(VcalmProblem(
                    problemType: "exchange-error",
                    status: nil,
                    title: "Exchange failed",
                    detail: error.localizedDescription
                )))
            }
        }
    }

    func matchedCredentials(completion: @escaping (Result<[VcalmCredentialKey], Error>) -> Void) {
        Task {
            do {
                guard let holder = currentHolder() else {
                    completion(.success([]))
                    return
                }
                let groups = try await holder.matchedCredentials()
                var keyMap: [VcalmCredentialKey: ParsedCredential] = [:]
                var keys: [VcalmCredentialKey] = []
                for group in groups {
                    // Each match now carries its disclosure mode
                    // (match.selectiveDisclosure); the Pigeon surface keeps the
                    // lightweight key shape, so only the handle is retained here.
                    for match in group.credentials {
                        let cred = match.credential
                        let key = VcalmCredentialKey(
                            queryIndex: Int64(group.queryIndex),
                            credentialId: cred.id()
                        )
                        keyMap[key] = cred
                        keys.append(key)
                    }
                }
                lock.lock()
                self.credentialsByKey = keyMap
                lock.unlock()
                log("matchedCredentials: \(keys.count) key(s)")
                completion(.success(keys))
            } catch {
                log("matchedCredentials FAILED: \(error)")
                completion(.success([]))
            }
        }
    }

    func requestedFields(completion: @escaping (Result<[VcalmRequestedFieldData], Error>) -> Void) {
        Task {
            do {
                guard let holder = currentHolder() else {
                    completion(.success([]))
                    return
                }
                let fields = try await holder.requestedFields()
                log("requestedFields: \(fields.count) field(s)")
                completion(.success(fields.map { field in
                    VcalmRequestedFieldData(
                        queryIndex: Int64(field.queryIndex),
                        path: field.path,
                        value: field.value,
                        required: field.required,
                        purpose: field.purpose
                    )
                }))
            } catch {
                log("requestedFields FAILED: \(error)")
                completion(.success([]))
            }
        }
    }

    func submitPresentation(
        selected: [VcalmCredentialKey],
        allowDomainMismatch: Bool,
        completion: @escaping (Result<VcalmStepResult, Error>) -> Void
    ) {
        Task {
            do {
                guard let holder = currentHolder() else {
                    completion(.success(VcalmProblem(
                        problemType: "no-holder",
                        status: nil,
                        title: "Holder not initialized",
                        detail: "Call createHolder first."
                    )))
                    return
                }
                lock.lock()
                let resolved = selected.compactMap { self.credentialsByKey[$0] }
                lock.unlock()
                log("submitPresentation: resolved \(resolved.count)/\(selected.count) handle(s), allowDomainMismatch=\(allowDomainMismatch)")
                // Suite is server-driven — no suite parameter.
                let step = try await holder.submitPresentation(
                    selectedCredentials: resolved,
                    allowDomainMismatch: allowDomainMismatch
                )
                completion(.success(try await toPigeonStep(step)))
            } catch VcalmError.DomainChannelMismatch(let domain, let channel) {
                // §3.4.3.2 anti-replay refusal — surface a distinct problemType so the
                // host app can ask the user for consent and retry with
                // allowDomainMismatch = true.
                log("submitPresentation: domain/channel mismatch (domain=\(domain), channel=\(channel))")
                completion(.success(VcalmProblem(
                    problemType: "domain-mismatch",
                    status: nil,
                    title: "Verifier domain does not match the exchange channel",
                    detail: "domain=\(domain), channel=\(channel)"
                )))
            } catch {
                log("submitPresentation FAILED: \(error)")
                completion(.success(VcalmProblem(
                    problemType: "submit-error",
                    status: nil,
                    title: "Presentation failed",
                    detail: error.localizedDescription
                )))
            }
        }
    }

    func offeredCredentials(completion: @escaping (Result<[VcalmOfferedCredentialData], Error>) -> Void) {
        Task {
            do {
                guard let holder = currentHolder() else {
                    completion(.success([]))
                    return
                }
                let offered = try await holder.offeredCredentials()
                log("offeredCredentials: \(offered.count) offered")
                completion(.success(offered.map(Self.projectOffered)))
            } catch {
                log("offeredCredentials FAILED: \(error)")
                completion(.success([]))
            }
        }
    }

    func acceptOffer(completion: @escaping (Result<VcalmStepResult, Error>) -> Void) {
        Task {
            do {
                guard let holder = currentHolder() else {
                    completion(.success(VcalmProblem(
                        problemType: "no-holder", status: nil,
                        title: "Holder not initialized", detail: "Call createHolder first."
                    )))
                    return
                }
                let step = try await holder.acceptOffer()
                completion(.success(try await toPigeonStep(step)))
            } catch {
                log("acceptOffer FAILED: \(error)")
                completion(.success(VcalmProblem(
                    problemType: "accept-error", status: nil,
                    title: "Accept failed", detail: error.localizedDescription
                )))
            }
        }
    }

    func rejectOffer(completion: @escaping (Result<VcalmStepResult, Error>) -> Void) {
        Task {
            do {
                guard let holder = currentHolder() else {
                    completion(.success(VcalmProblem(
                        problemType: "no-holder", status: nil,
                        title: "Holder not initialized", detail: "Call createHolder first."
                    )))
                    return
                }
                let step = try await holder.rejectOffer()
                completion(.success(try await toPigeonStep(step)))
            } catch {
                log("rejectOffer FAILED: \(error)")
                completion(.success(VcalmProblem(
                    problemType: "reject-error", status: nil,
                    title: "Reject failed", detail: error.localizedDescription
                )))
            }
        }
    }

    func cancel() throws {
        lock.lock()
        holder = nil
        credentialsByKey = [:]
        lock.unlock()
    }

    // MARK: - Marshaling helpers

    private func currentHolder() -> VcalmHolder? {
        lock.lock()
        defer { lock.unlock() }
        return holder
    }

    /// Projects a UniFFI `StepResult` onto the Pigeon `VcalmStepResult`.
    ///
    /// `async` because the `Offer` arm fetches the offered-credential preview
    /// from the holder so the step carries it directly.
    private func toPigeonStep(_ step: StepResult) async throws -> VcalmStepResult {
        switch step {
        case let .request(vpr):
            log("step Request: vprListsSdSuite=\(vprListsSd(vpr))")
            return VcalmRequest(
                challenge: vpr.challenge,
                domain: vpr.domain,
                purpose: vpr.query.flatMap { $0.credentialQuery }.compactMap { $0.reason }.first,
                vprListsSdSuite: vprListsSd(vpr)
            )
        case let .offer(_, nextVpr, _):
            // `vcs` is an opaque JSON String — do NOT parse structurally; use the
            // holder's read-only preview for display. A combined redirectUrl (the
            // third associated value) is consumed in Rust by acceptOffer, which
            // surfaces it as the terminal Redirect step after storing.
            let offered = (try? await holder?.offeredCredentials()) ?? []
            return VcalmOffer(
                credentials: offered.map(Self.projectOffered),
                hasNextRequest: nextVpr != nil
            )
        case let .redirect(url):
            // Surfaced as data only — NEVER auto-followed.
            log("step Redirect (surfaced only)")
            return VcalmRedirect(url: url)
        case .complete:
            log("step Complete")
            return VcalmComplete(completed: true)
        case let .problem(details):
            // Server-supplied; logged for diagnosis, not at info level.
            log("step Problem: type=\(details.problemType) status=\(String(describing: details.status)) title=\(String(describing: details.title))")
            return VcalmProblem(
                problemType: details.problemType,
                status: details.status.map { Int64($0) },
                title: details.title,
                detail: details.detail
            )
        }
    }

    /// Recomputes the SD-requested hint natively from `Vpr.acceptedCryptosuites`,
    /// mirroring the Rust `vpr_lists_sd_suite` (which does not cross FFI). This is
    /// marshaling for the display indicator, not protocol logic.
    private func entriesListSd(_ entries: [CryptosuiteEntry]?) -> Bool {
        guard let entries else { return false }
        return entries.contains { entry in
            switch entry {
            case let .name(name): return name == "ecdsa-sd-2023"
            case let .object(cryptosuite): return cryptosuite == "ecdsa-sd-2023"
            }
        }
    }

    private func vprListsSd(_ vpr: Vpr) -> Bool {
        // Mirrors Rust vpr_lists_sd_suite: SD may be listed at the VPR top level,
        // at the query level (§3.4.3.1 — the spec's Examples 6/7 placement), OR
        // per-credentialQuery (some deployments use the latter).
        if entriesListSd(vpr.acceptedCryptosuites) { return true }
        return vpr.query.contains { q in
            entriesListSd(q.acceptedCryptosuites)
                || q.credentialQuery.contains { cq in entriesListSd(cq.acceptedCryptosuites) }
        }
    }

    private static func projectOffered(_ c: VcalmOfferedCredential) -> VcalmOfferedCredentialData {
        VcalmOfferedCredentialData(
            issuer: c.issuer,
            types: c.types,
            credentialSubject: c.credentialSubject,
            validity: validityLabel(c.validity),
            rawCredential: c.rawCredential
        )
    }

    private static func validityLabel(_ v: OfferedValidity) -> String {
        switch v {
        case .valid: return "valid"
        case .timeBounded: return "timeBounded"
        case .proofInvalid: return "proofInvalid"
        case .enveloped: return "enveloped"
        case .unsupportedProof: return "unsupportedProof"
        case .unverifiable: return "unverifiable"
        }
    }
}
