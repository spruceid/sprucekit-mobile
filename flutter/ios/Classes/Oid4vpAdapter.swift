import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Error types for OID4VP Signer
enum Oid4vpSignerError: Error {
    case illegalArgumentException(reason: String)
}

class Oid4vpSigner: Oid4vpPresentationSigner {
    private static let defaultKeyId = "sprucekit/keymanager/oid4vp/default"

    private let didJwk = DidMethodUtils(method: SpruceIDMobileSdkRs.DidMethod.jwk)
    private let fallbackKeyId: String

    init(fallbackKeyId: String) {
        self.fallbackKeyId = fallbackKeyId
    }

    // A per-credential key must already exist from issuance (else it can't match
    // the credential's cnf binding); only the fallback/legacy key is created on demand.
    private func resolveAlias(_ keyId: String) -> (id: String, mayGenerate: Bool) {
        if keyId.isEmpty || keyId == fallbackKeyId {
            let id = fallbackKeyId.isEmpty ? Self.defaultKeyId : fallbackKeyId
            return (id, true)
        }
        return (keyId, false)
    }

    private func ensureKey(_ keyId: String) throws -> String {
        let (id, mayGenerate) = resolveAlias(keyId)
        if !KeyManager.keyExists(id: id) {
            guard mayGenerate else {
                throw Oid4vpSignerError.illegalArgumentException(
                    reason: "No signing key for per-credential kid '\(id)'; it must exist from issuance")
            }
            _ = KeyManager.generateSigningKey(id: id)
        }
        return id
    }

    private func resolveJwk(_ keyId: String) throws -> String {
        let id = try ensureKey(keyId)
        guard let jwk = KeyManager.getJwk(id: id) else {
            throw Oid4vpSignerError.illegalArgumentException(reason: "Invalid kid: \(id)")
        }
        return jwk.description
    }

    func sign(keyId: String, payload: Data) async throws -> Data {
        let id = try ensureKey(keyId)
        guard let signature = KeyManager.signPayload(id: id, payload: [UInt8](payload)) else {
            throw Oid4vpSignerError.illegalArgumentException(reason: "Failed to sign payload")
        }
        return Data(signature)
    }

    func algorithm() -> String {
        return "ES256"
    }

    func verificationMethod(keyId: String) async -> String {
        do {
            let jwk = try resolveJwk(keyId)
            return try await didJwk.vmFromJwk(jwk: jwk)
        } catch {
            fatalError("Oid4vpSigner: failed to derive verification method from JWK: \(error)")
        }
    }

    func did(keyId: String) -> String {
        do {
            let jwk = try resolveJwk(keyId)
            return try didJwk.didFromJwk(jwk: jwk)
        } catch {
            fatalError("Oid4vpSigner: failed to derive DID from JWK: \(error)")
        }
    }

    func jwk(keyId: String) -> String {
        do {
            return try resolveJwk(keyId)
        } catch {
            fatalError("Oid4vpSigner: failed to resolve JWK: \(error)")
        }
    }

    func cryptosuite() -> String {
        return "ecdsa-rdfc-2019"
    }
}

/// OID4VP Pigeon Adapter for iOS
///
/// Handles OpenID for Verifiable Presentation flow.
///
/// Backed by the version-agnostic OID4VP facade (`Oid4vpHolder` /
/// `Oid4vpSession`), which negotiates OID4VP 1.0 or Draft 18 per request.
/// The negotiated version is chosen by the `mode` passed to
/// `handleAuthorizationRequest`.
class Oid4vpAdapter: Oid4vp {

    private let credentialPackAdapter: CredentialPackAdapter
    private let lock = NSLock()

    // Session state
    private var holder: Oid4vpHolder?
    private var session: Oid4vpSession?
    /// Resolves a Dart-side `PresentableCredentialKey` back to the live
    /// `Oid4vpPresentableCredential` handle. Built from `session.credentials()`
    /// in `handleAuthorizationRequest`, grouped by each credential's `matchId`
    /// (the DCQL `credential_query_id` for v1, the input-descriptor id for
    /// Draft 18). The same underlying credential may appear under multiple
    /// keys when it satisfies multiple queries — those are distinct
    /// `Oid4vpPresentableCredential` instances on the Rust side.
    private var credentialsByKey: [PresentableCredentialKey: Oid4vpPresentableCredential] = [:]

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
    }

    /// Maps the pigeon-facing supported versions to the Rust facade enum.
    private func rustVersions(
        _ versions: [Oid4vpVersion]
    ) -> [SpruceIDMobileSdkRs.Oid4vpVersion] {
        versions.map { version in
            switch version {
            case .v1: return .v1
            case .draft18: return .draft18
            case .draft13: return .draft13
            }
        }
    }

    /// Groups the session's presentable credentials by `matchId`, preserving
    /// first-appearance order. Single source of truth for the key map, the
    /// flat credential list and the grouped-by-query view.
    private func groupedByQuery(
        _ session: Oid4vpSession
    ) -> [(qid: String, creds: [Oid4vpPresentableCredential])] {
        var order: [String] = []
        var map: [String: [Oid4vpPresentableCredential]] = [:]
        for cred in session.credentials() {
            let qid = cred.matchId()
            if map[qid] == nil {
                order.append(qid)
                map[qid] = []
            }
            map[qid]?.append(cred)
        }
        return order.map { (qid: $0, creds: map[$0] ?? []) }
    }

    func createHolder(
        credentialPackIds: [String],
        trustedDids: [String],
        keyMap: [String: String],
        fallbackKeyId: String,
        contextMap: [String: String]?,
        completion: @escaping (Result<Oid4vpResult, any Error>) -> Void
    ) {
        Task {
            do {
                // Get native credentials from packs
                var credentials: [SpruceIDMobileSdkRs.ParsedCredential] = []
                for packId in credentialPackIds {
                    let packCredentials = credentialPackAdapter.getNativeCredentials(packId: packId)
                    credentials.append(contentsOf: packCredentials)
                }

                if credentials.isEmpty {
                    completion(.success(Oid4vpError(message: "No credentials found in provided packs")))
                    return
                }

                let signer = Oid4vpSigner(fallbackKeyId: fallbackKeyId)

                // Create holder (version-agnostic facade)
                let newHolder = try await Oid4vpHolder.newWithCredentials(
                    providedCredentials: credentials,
                    trustedDids: trustedDids,
                    signer: signer,
                    keyMap: keyMap,
                    fallbackKeyId: fallbackKeyId,
                    contextMap: contextMap,
                    keystore: KeyManager()
                )

                lock.lock()
                self.holder = newHolder
                lock.unlock()

                completion(.success(Oid4vpSuccess(message: "Holder created successfully")))
            } catch {
                completion(.success(Oid4vpError(message: error.localizedDescription)))
            }
        }
    }

    func handleAuthorizationRequest(
        url: String,
        supportedVersions: [Oid4vpVersion],
        completion: @escaping (Result<HandleAuthRequestResult, any Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let holder = self.holder else {
                    lock.unlock()
                    completion(.success(HandleAuthRequestError(message: "Holder not initialized. Call createHolder first.")))
                    return
                }
                lock.unlock()

                // Handle URL format (remove "authorize" if present, similar to Showcase)
                let processedUrl = url.replacingOccurrences(of: "authorize", with: "")

                // Start a session, restricting negotiation to `supportedVersions`.
                let session = try await holder.startWithSupportedVersions(
                    request: processedUrl,
                    supportedVersions: rustVersions(supportedVersions)
                )

                // Build (credentialId, matchId) -> credential map and the flat
                // credential list for Dart from a single source: credentials
                // grouped by their `matchId`.
                let groups = groupedByQuery(session)
                var keyMap: [PresentableCredentialKey: Oid4vpPresentableCredential] = [:]
                var credentialData: [PresentableCredentialData] = []
                for group in groups {
                    let qid = group.qid
                    for cred in group.creds {
                        let cid = cred.asParsedCredential().id()
                        let key = PresentableCredentialKey(
                            credentialId: cid,
                            credentialQueryId: qid
                        )
                        keyMap[key] = cred
                        credentialData.append(PresentableCredentialData(
                            credentialId: cid,
                            credentialQueryId: qid,
                            selectiveDisclosable: cred.selectiveDisclosable()
                        ))
                    }
                }

                lock.lock()
                self.session = session
                self.credentialsByKey = keyMap
                lock.unlock()

                if credentialData.isEmpty {
                    completion(.success(HandleAuthRequestError(
                        message: "No matching credentials found for this verification request"
                    )))
                    return
                }

                let info = PermissionRequestInfo(
                    clientId: session.clientId(),
                    domain: session.domain(),
                    purpose: session.purpose(),
                    isMultiCredentialSelection: session.isMultiCredentialSelection(),
                    isMultiCredentialMatching: session.isMultiCredentialMatching()
                )

                completion(.success(HandleAuthRequestSuccess(
                    credentials: credentialData,
                    info: info
                )))
            } catch {
                completion(.success(HandleAuthRequestError(message: error.localizedDescription)))
            }
        }
    }

    func getRequestedFields(key: PresentableCredentialKey) throws -> [RequestedFieldData] {
        lock.lock()
        guard let session = self.session,
              let credential = self.credentialsByKey[key] else {
            lock.unlock()
            return []
        }
        lock.unlock()

        let fields = try session.requestedFields(credential: credential)

        return fields.map { field in
            RequestedFieldData(
                id: field.id,
                name: field.name,
                path: field.path,
                required: field.required,
                retained: field.retained,
                purpose: field.purpose,
                credentialQueryId: field.matchId,
                rawFields: field.rawFields
            )
        }
    }

    func submitResponse(
        selectedCredentials: [PresentableCredentialKey],
        selectedFieldPaths: [[String]],
        options: ResponseOptions,
        completion: @escaping (Result<Oid4vpResult, any Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let session = self.session else {
                    lock.unlock()
                    completion(.success(Oid4vpError(message: "Session not initialized")))
                    return
                }

                // Resolve keys to live credential handles
                let resolvedCredentials = selectedCredentials.compactMap { self.credentialsByKey[$0] }
                lock.unlock()

                if resolvedCredentials.isEmpty {
                    completion(.success(Oid4vpError(message: "No valid credentials selected")))
                    return
                }

                // Create response options. `shouldStripQuotes` and
                // `removeVpPathPrefix` are Draft 18-only knobs not surfaced by
                // the pigeon API; default them off.
                let responseOptions = SpruceIDMobileSdkRs.Oid4vpResponseOptions(
                    forceArraySerialization: options.forceArraySerialization,
                    shouldStripQuotes: false,
                    removeVpPathPrefix: false
                )

                // Create and submit the permission response on the session.
                let permissionResponse = try await session.createPermissionResponse(
                    selectedCredentials: resolvedCredentials,
                    selectedFields: selectedFieldPaths,
                    responseOptions: responseOptions
                )

                _ = try await session.submitPermissionResponse(response: permissionResponse)

                completion(.success(Oid4vpSuccess(message: "Presentation submitted successfully")))
            } catch {
                completion(.success(Oid4vpError(message: error.localizedDescription)))
            }
        }
    }

    func getCredentialRequirements() throws -> [CredentialRequirementData] {
        lock.lock()
        guard let session = self.session else {
            lock.unlock()
            return []
        }
        lock.unlock()

        let requirements = session.requirements()
        return requirements.map { req in
            // The facade encodes the requirement's credential query ids as a
            // "|"-joined string in `id`; split it back into the list. Each
            // credential carries its own `matchId`, consistent with the key
            // map built in `handleAuthorizationRequest`.
            let queryIds = req.id.split(separator: "|").map(String.init)
            let creds = req.credentials.map { cred -> PresentableCredentialData in
                PresentableCredentialData(
                    credentialId: cred.asParsedCredential().id(),
                    credentialQueryId: cred.matchId(),
                    selectiveDisclosable: cred.selectiveDisclosable()
                )
            }
            return CredentialRequirementData(
                displayName: req.displayName,
                required: req.required,
                credentialQueryIds: queryIds,
                credentials: creds
            )
        }
    }

    func getCredentialsGroupedByQuery() throws -> [CredentialQueryGroupData] {
        lock.lock()
        guard let session = self.session else {
            lock.unlock()
            return []
        }
        lock.unlock()

        return groupedByQuery(session).map { group in
            let creds = group.creds.map { cred in
                PresentableCredentialData(
                    credentialId: cred.asParsedCredential().id(),
                    credentialQueryId: group.qid,
                    selectiveDisclosable: cred.selectiveDisclosable()
                )
            }
            return CredentialQueryGroupData(
                credentialQueryId: group.qid,
                credentials: creds
            )
        }
    }

    func getCredentialQueryIds() throws -> [String] {
        lock.lock()
        guard let session = self.session else {
            lock.unlock()
            return []
        }
        lock.unlock()

        return groupedByQuery(session).map { $0.qid }
    }

    func cancel() throws {
        lock.lock()
        holder = nil
        session = nil
        credentialsByKey = [:]
        lock.unlock()
    }
}
