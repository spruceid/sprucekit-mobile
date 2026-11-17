import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

#if canImport(IdentityDocumentServices)
import IdentityDocumentServices
#endif

/// DC API Adapter for iOS
///
/// Handles registering mDoc credentials with iOS IdentityDocumentProvider (iOS 26+)
class DcApiAdapter: DcApi {

    private let credentialPackAdapter: CredentialPackAdapter
    private var registeredCredentials: [String: RegisteredCredentialInfo] = [:]
    private let lock = NSLock()

    init(credentialPackAdapter: CredentialPackAdapter) {
        self.credentialPackAdapter = credentialPackAdapter
    }

    func syncCredentialsToAppGroup(
        appGroupId: String,
        packIds: [String],
        completion: @escaping (Result<DcApiResult, Error>) -> Void
    ) {
        // Not needed on iOS - credentials are loaded directly via CredentialPack.loadAll()
        // from the App Group StorageManager by the Extension
        completion(.success(DcApiSuccess(
            message: "Sync not needed on iOS (Extension uses StorageManager directly)"
        )))
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
                    // Get the native CredentialPack and use SDK's registration method
                    guard let credentialPack = credentialPackAdapter.getNativePack(packId: packId) else {
                        errors.append("CredentialPack not found for packId: \(packId)")
                        continue
                    }

                    // Count mDL credentials before registration
                    let credentials = credentialPack.list()
                    let mdlCredentials = credentials.filter { credential in
                        credential.asMsoMdoc()?.doctype() == "org.iso.18013.5.1.mDL"
                    }
                    let mdlCount = mdlCredentials.count

                    if mdlCount == 0 {
                        continue
                    }

                    // Use SDK's method which handles notAuthorized gracefully
                    do {
                        try await credentialPack.registerUnregisteredIDProviderDocuments()
                        registeredCount += mdlCount

                        // Track registered credentials
                        for credential in credentials {
                            if let mdoc = credential.asMsoMdoc(), mdoc.doctype() == "org.iso.18013.5.1.mDL" {
                                lock.lock()
                                registeredCredentials[credential.id()] = RegisteredCredentialInfo(
                                    credentialId: credential.id(),
                                    docType: mdoc.doctype(),
                                    isRegistered: true
                                )
                                lock.unlock()
                            }
                        }
                    } catch {
                        let errorMsg = "Registration failed: \(error)"
                        errors.append(errorMsg)
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

