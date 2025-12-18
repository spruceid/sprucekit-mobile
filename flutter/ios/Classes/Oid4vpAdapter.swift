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
        self._jwk = jwk
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
        return try! await didJwk.vmFromJwk(jwk: _jwk)
    }

    func did() -> String {
        return try! didJwk.didFromJwk(jwk: _jwk)
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
    private var presentableCredentials: [PresentableCredential] = []

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
                    contextMap: contextMap
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
                let credentials = request.credentials()

                lock.lock()
                self.permissionRequest = request
                self.presentableCredentials = credentials
                lock.unlock()

                if credentials.isEmpty {
                    completion(.success(HandleAuthRequestError(
                        message: "No matching credentials found for this verification request"
                    )))
                    return
                }

                // Convert to Pigeon types
                let credentialData = credentials.enumerated().map { (index, cred) in
                    PresentableCredentialData(
                        index: Int64(index),
                        credentialId: cred.asParsedCredential().id(),
                        selectiveDisclosable: cred.selectiveDisclosable()
                    )
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

    func getRequestedFields(credentialIndex: Int64) throws -> [RequestedFieldData] {
        lock.lock()
        guard let permissionRequest = self.permissionRequest else {
            lock.unlock()
            return []
        }

        guard credentialIndex >= 0 && credentialIndex < presentableCredentials.count else {
            lock.unlock()
            return []
        }

        let credential = presentableCredentials[Int(credentialIndex)]
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
                inputDescriptorId: field.inputDescriptorId(),
                rawFields: field.rawFields()
            )
        }
    }

    func submitResponse(
        selectedCredentialIndices: [Int64],
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

                // Map indices to credentials
                let selectedCredentials = selectedCredentialIndices.compactMap { index -> PresentableCredential? in
                    guard index >= 0 && index < presentableCredentials.count else { return nil }
                    return presentableCredentials[Int(index)]
                }
                lock.unlock()

                if selectedCredentials.isEmpty {
                    completion(.success(Oid4vpError(message: "No valid credentials selected")))
                    return
                }

                // Create response options
                let responseOptions = SpruceIDMobileSdkRs.ResponseOptions(
                    shouldStripQuotes: options.shouldStripQuotes,
                    forceArraySerialization: options.forceArraySerialization,
                    removeVpPathPrefix: options.removeVpPathPrefix
                )

                // Create permission response
                let permissionResponse = try await permissionRequest.createPermissionResponse(
                    selectedCredentials: selectedCredentials,
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

    func cancel() throws {
        lock.lock()
        holder = nil
        permissionRequest = nil
        presentableCredentials = []
        lock.unlock()
    }
}
