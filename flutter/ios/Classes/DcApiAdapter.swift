import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

#if canImport(IdentityDocumentServices)
import IdentityDocumentServices
#endif

/// DC API Adapter for iOS
///
/// Handles syncing credentials to App Group storage and registering
/// mDoc credentials with iOS IdentityDocumentProvider (iOS 26+)
class DcApiAdapter: DcApi {

    private let credentialPackAdapter: CredentialPackAdapter
    private var registeredCredentials: [String: RegisteredCredentialInfo] = [:]
    private let lock = NSLock()

    private static let SIGN_KEY = "keys/sign/default"

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
    }

    func syncCredentialsToAppGroup(
        appGroupId: String,
        packIds: [String],
        completion: @escaping (Result<DcApiResult, Error>) -> Void
    ) {
        Task {
            do {
                guard let groupURL = FileManager.default.containerURL(
                    forSecurityApplicationGroupIdentifier: appGroupId
                ) else {
                    completion(.success(DcApiError(
                        message: "Failed to get App Group container URL for: \(appGroupId)"
                    )))
                    return
                }

                // Ensure encryption key exists
                if !KeyManager.keyExists(id: "keys/encrypt/default") {
                    KeyManager.generateEncryptionKey(id: "keys/encrypt/default")
                }

                // Collect all credentials from all packs
                var credentialsMap: [String: [String: Any]] = [:]

                for packId in packIds {
                    let credentials = credentialPackAdapter.getNativeCredentials(packId: packId)
                    for credential in credentials {
                        let credId = credential.id()

                        // Store credential in appropriate format
                        if let jwtVc = credential.asJwtVc() {
                            credentialsMap[credId] = [
                                "data": [
                                    "jwt": jwtVc.credentialAsJsonEncodedUtf8String()
                                ]
                            ]
                        } else if let jsonVc = credential.asJsonVc() {
                            credentialsMap[credId] = [
                                "data": [
                                    "verifiableCredential": jsonVc.credentialAsJsonEncodedUtf8String()
                                ]
                            ]
                        } else if credential.asMsoMdoc() != nil {
                            // For mDoc, use the stored raw credential
                            if let rawCredential = credentialPackAdapter.getRawCredential(credentialId: credId) {
                                credentialsMap[credId] = [
                                    "data": [
                                        "mso_mdoc": rawCredential
                                    ]
                                ]
                            }
                        }
                    }
                }

                // Serialize to JSON
                let jsonData = try JSONSerialization.data(
                    withJSONObject: credentialsMap,
                    options: []
                )

                // Encrypt the data
                guard let (_, encrypted) = KeyManager.encryptPayload(
                    id: "keys/encrypt/default",
                    payload: [UInt8](jsonData)
                ) else {
                    completion(.success(DcApiError(
                        message: "Failed to encrypt credential data"
                    )))
                    return
                }

                // Use encrypted data directly (ECIES encryption is self-contained)
                let encryptedData = Data(encrypted)

                // Create signed payload format: header.payload
                let header = "dcapi"
                let payload = encryptedData.base64EncodedUrlSafe
                let content = "\(header).\(payload)"

                // Write to App Group
                let filePath = groupURL.appendingPathComponent("credentials.encrypted")
                try content.write(to: filePath, atomically: true, encoding: .utf8)

                completion(.success(DcApiSuccess(
                    message: "Synced \(credentialsMap.count) credentials to App Group"
                )))

            } catch {
                completion(.success(DcApiError(
                    message: "Failed to sync credentials: \(error.localizedDescription)"
                )))
            }
        }
    }

    func registerCredentials(
        packIds: [String],
        walletName: String?,
        completion: @escaping (Result<DcApiResult, Error>) -> Void
    ) {
        // Note: walletName is currently only used on Android for DC API display
        // iOS uses the app name from the extension's Info.plist
        Task {
            do {
                var registeredCount = 0
                var errors: [String] = []

                for packId in packIds {
                    let credentials = credentialPackAdapter.getNativeCredentials(packId: packId)
                    for credential in credentials {
                        guard let mdoc = credential.asMsoMdoc() else {
                            continue
                        }

                        // Only register mDL documents
                        guard mdoc.doctype() == "org.iso.18013.5.1.mDL" else {
                            continue
                        }

                        let credentialId = credential.id()
                        let docType = mdoc.doctype()

                        // Try to register directly with IdentityDocumentProvider
                        if #available(iOS 26.0, *) {
                            #if canImport(IdentityDocumentServices)
                            do {
                                let store = IdentityDocumentProviderRegistrationStore()

                                // Parse invalidation date
                                let dateFormatter = ISO8601DateFormatter()
                                let isoDateString = try mdoc.invalidationDate()
                                let trimmedIsoString = isoDateString.replacingOccurrences(
                                    of: "\\.\\d+",
                                    with: "",
                                    options: .regularExpression
                                )
                                guard let dateUntil = dateFormatter.date(from: trimmedIsoString) else {
                                    errors.append("Failed to parse invalidation date")
                                    continue
                                }

                                let registration = MobileDocumentRegistration(
                                    mobileDocumentType: "org.iso.18013.5.1.mDL",
                                    supportedAuthorityKeyIdentifiers: [],
                                    documentIdentifier: credentialId,
                                    invalidationDate: dateUntil
                                )

                                try await store.addRegistration(registration)
                                print("[DC API] Successfully registered credential: \(credentialId)")
                                registeredCount += 1

                                lock.lock()
                                registeredCredentials[credentialId] = RegisteredCredentialInfo(
                                    credentialId: credentialId,
                                    docType: docType,
                                    isRegistered: true
                                )
                                lock.unlock()

                            } catch {
                                let errorMsg = "Registration failed: \(error.localizedDescription)"
                                print("[DC API] \(errorMsg)")
                                errors.append(errorMsg)
                            }
                            #else
                            errors.append("IdentityDocumentServices not available")
                            #endif
                        } else {
                            errors.append("iOS 26+ required for DC API")
                        }
                    }
                }

                if registeredCount > 0 {
                    completion(.success(DcApiSuccess(
                        message: "Registered \(registeredCount) credentials"
                    )))
                } else if !errors.isEmpty {
                    completion(.success(DcApiError(
                        message: "No credentials registered. Errors: \(errors.joined(separator: "; "))"
                    )))
                } else {
                    completion(.success(DcApiError(
                        message: "No mDL credentials found to register"
                    )))
                }

            } catch {
                completion(.success(DcApiError(
                    message: "Failed to register credentials: \(error.localizedDescription)"
                )))
            }
        }
    }

    func unregisterCredentials(
        credentialIds: [String],
        completion: @escaping (Result<DcApiResult, Error>) -> Void
    ) {
        Task {
            do {
                var unregisteredCount = 0

                for credentialId in credentialIds {
                    // Unregister from iOS IdentityDocumentProvider (iOS 26+)
                    if #available(iOS 26.0, *) {
                        #if canImport(IdentityDocumentServices)
                        let store = IdentityDocumentProviderRegistrationStore()
                        try await store.removeRegistration(forDocumentIdentifier: credentialId)
                        #endif
                    }

                    lock.lock()
                    registeredCredentials.removeValue(forKey: credentialId)
                    lock.unlock()

                    unregisteredCount += 1
                }

                completion(.success(DcApiSuccess(
                    message: "Unregistered \(unregisteredCount) credentials"
                )))

            } catch {
                completion(.success(DcApiError(
                    message: "Failed to unregister credentials: \(error.localizedDescription)"
                )))
            }
        }
    }

    func getRegisteredCredentials() throws -> [RegisteredCredentialInfo] {
        lock.lock()
        let credentials = Array(registeredCredentials.values)
        lock.unlock()
        return credentials
    }

    func isSupported() throws -> Bool {
        if #available(iOS 26.0, *) {
            return true
        }
        return false
    }
}

// MARK: - Data Extensions

extension Data {
    var base64EncodedUrlSafe: String {
        let string = self.base64EncodedString()
        return string
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}
