import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// OID4VP mDoc (ISO 18013-7) Pigeon Adapter for iOS
///
/// Handles OpenID for Verifiable Presentation with mDoc credentials
class Oid4vpMdocAdapter: Oid4vpMdoc {

    private let credentialPackAdapter: CredentialPackAdapter
    private let lock = NSLock()

    // Session state
    private var handler: Oid4vp180137?
    private var request: InProgressRequest180137?
    private var matches: [RequestMatch180137] = []
    private var keyManager: KeyManager?

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
    }

    func initialize(
        credentialPackIds: [String],
        completion: @escaping (Result<Oid4vpMdocResult, any Error>) -> Void
    ) {
        Task {
            do {
                // Get mDoc credentials from packs
                var mdocs: [Mdoc] = []
                for packId in credentialPackIds {
                    let packCredentials = credentialPackAdapter.getNativeCredentials(packId: packId)
                    for credential in packCredentials {
                        if let mdoc = credential.asMsoMdoc() {
                            mdocs.append(mdoc)
                        }
                    }
                }

                if mdocs.isEmpty {
                    completion(.success(Oid4vpMdocError(message: "No mDoc credentials found in provided packs")))
                    return
                }

                // Create KeyManager instance
                let keyMgr = KeyManager()

                // Create the handler
                let newHandler = try Oid4vp180137(
                    credentials: mdocs,
                    keystore: keyMgr
                )

                lock.lock()
                self.handler = newHandler
                self.keyManager = keyMgr
                lock.unlock()

                completion(.success(Oid4vpMdocSuccess(message: "Handler initialized with \(mdocs.count) mDoc(s)")))
            } catch {
                completion(.success(Oid4vpMdocError(message: error.localizedDescription)))
            }
        }
    }

    func processRequest(
        url: String,
        completion: @escaping (Result<ProcessRequestResult, any Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let handler = self.handler else {
                    lock.unlock()
                    completion(.success(ProcessRequestError(message: "Handler not initialized. Call initialize first.")))
                    return
                }
                lock.unlock()

                // Process the request
                let inProgressRequest = try await handler.processRequest(url: url)
                let requestMatches = inProgressRequest.matches()

                lock.lock()
                self.request = inProgressRequest
                self.matches = requestMatches
                lock.unlock()

                if requestMatches.isEmpty {
                    completion(.success(ProcessRequestError(
                        message: "No matching credentials found for this verification request"
                    )))
                    return
                }

                // Convert to Pigeon types
                let matchesData = requestMatches.enumerated().map { (index, match) -> RequestMatch180137Data in
                    let fieldsData = match.requestedFields().map { field -> RequestedField180137Data in
                        RequestedField180137Data(
                            id: field.id,
                            displayableName: field.displayableName,
                            displayableValue: field.displayableValue,
                            selectivelyDisclosable: field.selectivelyDisclosable,
                            intentToRetain: field.intentToRetain,
                            required: field.required,
                            purpose: field.purpose
                        )
                    }
                    return RequestMatch180137Data(
                        index: Int64(index),
                        credentialId: match.credentialId(),
                        requestedFields: fieldsData
                    )
                }

                let info = Oid4vpMdocRequestInfo(
                    requestedBy: inProgressRequest.requestedBy(),
                    matches: matchesData
                )

                completion(.success(ProcessRequestSuccess(info: info)))
            } catch {
                completion(.success(ProcessRequestError(message: error.localizedDescription)))
            }
        }
    }

    func submitResponse(
        matchIndex: Int64,
        approvedFieldIds: [String],
        completion: @escaping (Result<Oid4vpMdocResult, any Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let request = self.request else {
                    lock.unlock()
                    completion(.success(Oid4vpMdocError(message: "No active request. Call processRequest first.")))
                    return
                }

                guard matchIndex >= 0 && matchIndex < matches.count else {
                    lock.unlock()
                    completion(.success(Oid4vpMdocError(message: "Invalid match index")))
                    return
                }

                let selectedMatch = matches[Int(matchIndex)]
                lock.unlock()

                // Create approved response
                let approvedResponse = ApprovedResponse180137(
                    credentialId: selectedMatch.credentialId(),
                    approvedFields: approvedFieldIds
                )

                // Submit and get redirect URL
                let redirectUrl = try await request.respond(approvedResponse: approvedResponse)

                completion(.success(Oid4vpMdocSuccess(
                    message: "Presentation submitted successfully",
                    redirectUrl: redirectUrl
                )))
            } catch {
                completion(.success(Oid4vpMdocError(message: error.localizedDescription)))
            }
        }
    }

    func cancel() throws {
        lock.lock()
        handler = nil
        request = nil
        matches = []
        keyManager = nil
        lock.unlock()
    }
}
