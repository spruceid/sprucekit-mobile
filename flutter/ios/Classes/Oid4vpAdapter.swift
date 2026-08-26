import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Error types for OID4VP Signer
enum Oid4vpSignerError: Error {
    case illegalArgumentException(reason: String)
}

/// Signer implementation for OID4VP presentation
class Oid4vpSigner: Oid4vpPresentationSigner {
    private let keyId: String
    private let _jwk: String
    private let didJwk = DidMethodUtils(method: SpruceIDMobileSdkRs.DidMethod.jwk)

    init(keyId: String) throws {
        self.keyId = keyId
        if !KeyManager.keyExists(id: keyId) {
            _ = KeyManager.generateSigningKey(id: keyId)
        }
        guard let jwk = KeyManager.getJwk(id: keyId) else {
            throw Oid4vpSignerError.illegalArgumentException(reason: "Invalid kid")
        }
        self._jwk = jwk.description
    }

    func sign(payload: Data) async throws -> Data {
        guard let signature = KeyManager.signPayload(id: keyId, payload: [UInt8](payload)) else {
            throw Oid4vpSignerError.illegalArgumentException(reason: "Failed to sign payload")
        }
        return Data(signature)
    }

    func algorithm() -> String {
        // Parse the jwk as a JSON object and return the "alg" field
        if let data = _jwk.data(using: .utf8),
           let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let alg = json["alg"] as? String {
            return alg
        }
        return "ES256"
    }

    func verificationMethod() async -> String {
        do {
            return try await didJwk.vmFromJwk(jwk: _jwk)
        } catch {
            fatalError("Oid4vpSigner: failed to derive verification method from JWK: \(error)")
        }
    }

    func did() -> String {
        do {
            return try didJwk.didFromJwk(jwk: _jwk)
        } catch {
            fatalError("Oid4vpSigner: failed to derive DID from JWK: \(error)")
        }
    }

    func jwk() -> String {
        return _jwk
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
    /// Dynamic credential offers for the current session, keyed by `offerId`
    /// (surfacing order kept in `dynamicOfferIds`). Dart echoes ids back;
    /// these records are the authoritative ones passed to the Rust session.
    private var dynamicOfferIds: [String] = []
    private var dynamicOffersById: [String: SpruceIDMobileSdkRs.DynamicCredentialOffer] = [:]

    /// Atomically removes and returns the active session while clearing its cached state.
    private func clearSession() -> Oid4vpSession? {
        lock.lock()
        defer { lock.unlock() }
        let activeSession = session
        holder = nil
        session = nil
        credentialsByKey = [:]
        dynamicOfferIds = []
        dynamicOffersById = [:]
        return activeSession
    }

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
        keyId: String,
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

                // Snapshot registered providers for the lifetime of this
                // holder. With providers, an empty credential pack is valid:
                // the whole response may be issued on the fly.
                let providers = SprucekitMobilePlugin.dynamicCredentialProviders

                if credentials.isEmpty && providers.isEmpty {
                    completion(.success(Oid4vpError(message: "No credentials found in provided packs")))
                    return
                }

                // Create signer
                let signer = try Oid4vpSigner(keyId: keyId)

                // Create holder (version-agnostic facade)
                let newHolder = try await Oid4vpHolder.newWithCredentialsAndProviders(
                    providedCredentials: credentials,
                    trustedDids: trustedDids,
                    signer: signer,
                    contextMap: contextMap,
                    keystore: KeyManager(),
                    providers: providers
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
                // A JSON authorization-request body must pass through
                // verbatim, so only munge URL-shaped inputs.
                let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
                let processedUrl = trimmed.hasPrefix("{")
                    ? trimmed
                    : url.replacingOccurrences(of: "authorize", with: "")

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

                // Dynamic offers from registered providers; always empty
                // for non-v1 sessions.
                let offers = session.dynamicOffers()

                lock.lock()
                self.session = session
                self.credentialsByKey = keyMap
                self.dynamicOfferIds = offers.map { $0.offerId }
                self.dynamicOffersById = Dictionary(
                    offers.map { ($0.offerId, $0) },
                    uniquingKeysWith: { first, _ in first }
                )
                lock.unlock()

                if credentialData.isEmpty && offers.isEmpty {
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

                // The verifier's direct_post response may carry a redirect_uri
                // (OID4VP §8.2) sending the user back to the browser.
                let redirectUrl = try await session.submitPermissionResponse(
                    response: permissionResponse)

                completion(
                    .success(
                        Oid4vpSuccess(
                            message: "Presentation submitted successfully",
                            redirectUrl: redirectUrl)))
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

    func getDynamicOffers() throws -> [DynamicOfferData] {
        lock.lock()
        let offerIds = self.dynamicOfferIds
        let offersById = self.dynamicOffersById
        lock.unlock()

        return offerIds.compactMap { offersById[$0] }.map { offer in
            DynamicOfferData(
                offerId: offer.offerId,
                credentialQueryId: offer.credentialQueryId,
                title: offer.title
            )
        }
    }

    func submitResponseWithOffers(
        selectedCredentials: [PresentableCredentialKey],
        selectedFieldPaths: [[String]],
        selectedOfferIds: [String],
        options: ResponseOptions,
        completion: @escaping (Result<Oid4vpResult, Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let session = self.session else {
                    lock.unlock()
                    completion(.success(Oid4vpError(message: "Session not initialized")))
                    return
                }

                let resolvedCredentials = selectedCredentials.compactMap { self.credentialsByKey[$0] }
                let resolvedOffers = selectedOfferIds.compactMap { self.dynamicOffersById[$0] }
                lock.unlock()

                // Each offer may be selected at most once — a duplicate id
                // would be issued twice and still pass the size check below.
                if Set(selectedOfferIds).count != selectedOfferIds.count {
                    completion(.success(Oid4vpError(message: "Duplicate dynamic offer id selected")))
                    return
                }

                if resolvedOffers.count != selectedOfferIds.count {
                    completion(.success(Oid4vpError(message: "Unknown dynamic offer id selected")))
                    return
                }

                if resolvedCredentials.isEmpty && resolvedOffers.isEmpty {
                    completion(.success(Oid4vpError(message: "No valid credentials or offers selected")))
                    return
                }

                // `shouldStripQuotes` and `removeVpPathPrefix` are Draft
                // 18-only knobs not surfaced by the pigeon API; keep off.
                let responseOptions = SpruceIDMobileSdkRs.Oid4vpResponseOptions(
                    forceArraySerialization: options.forceArraySerialization,
                    shouldStripQuotes: false,
                    removeVpPathPrefix: false
                )

                // Selected offers are issued while creating the response.
                let permissionResponse = try await session.createPermissionResponseWithOffers(
                    selectedCredentials: resolvedCredentials,
                    selectedFields: selectedFieldPaths,
                    selectedOffers: resolvedOffers,
                    responseOptions: responseOptions
                )

                _ = try await session.submitPermissionResponse(response: permissionResponse)

                completion(.success(Oid4vpSuccess(message: "Presentation submitted successfully")))
            } catch {
                completion(.success(Oid4vpError(message: error.localizedDescription)))
            }
        }
    }

    func denyPermission(completion: @escaping (Result<Oid4vpResult, Error>) -> Void) {
        guard let activeSession = clearSession() else {
            completion(
                .success(
                    Oid4vpError(
                        message: "No active OID4VP session"
                    )
                )
            )
            return
        }
        Task {
            do {
                let redirectUrl = try await activeSession.denyPermission()
                completion(
                    .success(
                        Oid4vpSuccess(
                            message: "Permission denied",
                            redirectUrl: redirectUrl
                        )
                    )
                )
            } catch {
                completion(
                    .success(
                        Oid4vpError(
                            message: error.localizedDescription
                        )
                    )
                )
            }
        }
    }

    func cancel() throws {
        clearSession()
    }
}
