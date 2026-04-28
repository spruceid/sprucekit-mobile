import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// Error types for OID4VP Signer
enum Oid4vpSignerError: Error {
    case illegalArgumentException(reason: String)
}

/// Signer implementation for OID4VP presentation
class Oid4vpSigner: PresentationSigner {
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
/// Handles OpenID for Verifiable Presentation flow
class Oid4vpAdapter: Oid4vp {

    private let credentialPackAdapter: CredentialPackAdapter
    private let lock = NSLock()

    // Session state
    private var holder: Holder?
    private var permissionRequest: PermissionRequest?
    /// Resolves a Dart-side `PresentableCredentialKey` back to the live
    /// `PresentableCredential` handle. Built from `credentialsGroupedByQuery()`
    /// in `handleAuthorizationRequest`: each group entry contributes one
    /// `(credentialId, credentialQueryId) -> credential` mapping. The same
    /// underlying credential may appear under multiple keys if it satisfies
    /// multiple DCQL queries — those are distinct `PresentableCredential`
    /// instances on the Rust side, each carrying its own internal query id.
    private var credentialsByKey: [PresentableCredentialKey: PresentableCredential] = [:]

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
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

                if credentials.isEmpty {
                    completion(.success(Oid4vpError(message: "No credentials found in provided packs")))
                    return
                }

                // Create signer
                let signer = try Oid4vpSigner(keyId: keyId)

                // Create holder
                let newHolder = try await Holder.newWithCredentials(
                    providedCredentials: credentials,
                    trustedDids: trustedDids,
                    signer: signer,
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

                // Parse authorization request
                let request = try await holder.authorizationRequest(req: Url(processedUrl))

                // Build (credentialId, credentialQueryId) -> credential map and
                // the flat credential list for Dart from a single source: the
                // grouped-by-query view. Rust groups by each credential's
                // internal `credential_query_id` (1-to-1 with the flat list),
                // so the union of group entries equals `request.credentials()`.
                let groups = request.credentialsGroupedByQuery()
                var keyMap: [PresentableCredentialKey: PresentableCredential] = [:]
                var credentialData: [PresentableCredentialData] = []
                for group in groups {
                    let qid = group.credentialQueryId
                    for cred in group.credentials {
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
                self.permissionRequest = request
                self.credentialsByKey = keyMap
                lock.unlock()

                if credentialData.isEmpty {
                    completion(.success(HandleAuthRequestError(
                        message: "No matching credentials found for this verification request"
                    )))
                    return
                }

                let info = PermissionRequestInfo(
                    clientId: request.clientId(),
                    domain: request.domain(),
                    purpose: request.purpose(),
                    isMultiCredentialSelection: request.isMultiCredentialSelection(),
                    isMultiCredentialMatching: request.isMultiCredentialMatching()
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
        guard let permissionRequest = self.permissionRequest,
              let credential = self.credentialsByKey[key] else {
            lock.unlock()
            return []
        }
        lock.unlock()

        let fields = permissionRequest.requestedFields(credential: credential)

        return fields.map { field in
            RequestedFieldData(
                id: field.id(),
                name: field.name(),
                path: field.path(),
                required: field.required(),
                retained: field.retained(),
                purpose: field.purpose(),
                credentialQueryId: field.credentialQueryId(),
                rawFields: field.rawFields()
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
                guard let holder = self.holder,
                      let permissionRequest = self.permissionRequest else {
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

                // Create response options
                let responseOptions = SpruceIDMobileSdkRs.ResponseOptions(
                    forceArraySerialization: options.forceArraySerialization
                )

                // Create permission response
                let permissionResponse = try await permissionRequest.createPermissionResponse(
                    selectedCredentials: resolvedCredentials,
                    selectedFields: selectedFieldPaths,
                    responseOptions: responseOptions
                )

                // Submit response
                _ = try await holder.submitPermissionResponse(response: permissionResponse)

                completion(.success(Oid4vpSuccess(message: "Presentation submitted successfully")))
            } catch {
                completion(.success(Oid4vpError(message: error.localizedDescription)))
            }
        }
    }

    func getCredentialRequirements() throws -> [CredentialRequirementData] {
        lock.lock()
        guard let permissionRequest = self.permissionRequest else {
            lock.unlock()
            return []
        }
        let keyMap = self.credentialsByKey
        lock.unlock()

        let requirements = permissionRequest.credentialRequirements()
        return requirements.map { req in
            // For each credential in the requirement, pick the first
            // credentialQueryId from `req.credentialQueryIds` (in order)
            // for which `(credentialId, qid)` exists in `keyMap`. Rust's
            // invariant guarantees at least one such qid exists per cred.
            let creds = req.credentials.map { cred -> PresentableCredentialData in
                let credId = cred.asParsedCredential().id()
                let qid = req.credentialQueryIds.first { qid in
                    keyMap[PresentableCredentialKey(
                        credentialId: credId,
                        credentialQueryId: qid
                    )] != nil
                } ?? req.credentialQueryIds.first ?? ""
                return PresentableCredentialData(
                    credentialId: credId,
                    credentialQueryId: qid,
                    selectiveDisclosable: cred.selectiveDisclosable()
                )
            }
            return CredentialRequirementData(
                displayName: req.displayName,
                required: req.required,
                credentialQueryIds: req.credentialQueryIds,
                credentials: creds
            )
        }
    }

    func getCredentialsGroupedByQuery() throws -> [CredentialQueryGroupData] {
        lock.lock()
        guard let permissionRequest = self.permissionRequest else {
            lock.unlock()
            return []
        }
        lock.unlock()

        let groups = permissionRequest.credentialsGroupedByQuery()
        return groups.map { group in
            let qid = group.credentialQueryId
            let creds = group.credentials.map { cred in
                PresentableCredentialData(
                    credentialId: cred.asParsedCredential().id(),
                    credentialQueryId: qid,
                    selectiveDisclosable: cred.selectiveDisclosable()
                )
            }
            return CredentialQueryGroupData(
                credentialQueryId: qid,
                credentials: creds
            )
        }
    }

    func getCredentialQueryIds() throws -> [String] {
        lock.lock()
        guard let permissionRequest = self.permissionRequest else {
            lock.unlock()
            return []
        }
        lock.unlock()

        return permissionRequest.credentialQueryIds()
    }

    func cancel() throws {
        lock.lock()
        holder = nil
        permissionRequest = nil
        credentialsByKey = [:]
        lock.unlock()
    }
}
