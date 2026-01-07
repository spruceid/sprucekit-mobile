import Foundation
import SpruceIDMobileSdk
import SpruceIDMobileSdkRs

/// CredentialPack Pigeon Adapter for iOS
///
/// Manages collections of credentials with parsing capabilities
class CredentialPackAdapter: CredentialPack {

    // In-memory store of credential packs
    private var packs: [String: SpruceIDMobileSdk.CredentialPack] = [:]
    private let lock = NSLock()

    func createPack() throws -> String {
        let pack = SpruceIDMobileSdk.CredentialPack()
        let packId = pack.id.uuidString
        lock.lock()
        packs[packId] = pack
        lock.unlock()
        return packId
    }

    func getPack(packId: String) throws -> CredentialPackData? {
        lock.lock()
        let pack = packs[packId]
        lock.unlock()

        guard let pack = pack else { return nil }

        return CredentialPackData(
            id: packId,
            credentials: pack.list().map { $0.toData() }
        )
    }

    func addRawCredential(
        packId: String,
        rawCredential: String,
        completion: @escaping (Result<AddCredentialResult, any Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let pack = packs[packId] else {
                    lock.unlock()
                    completion(.success(AddCredentialError(message: "Pack not found: \(packId)")))
                    return
                }
                lock.unlock()

                let credentials = try pack.tryAddRawCredential(rawCredential: rawCredential)
                completion(.success(AddCredentialSuccess(
                    credentials: credentials.map { $0.toData() }
                )))
            } catch {
                completion(.success(AddCredentialError(
                    message: error.localizedDescription
                )))
            }
        }
    }

    func addRawMdoc(
        packId: String,
        rawCredential: String,
        keyAlias: String,
        completion: @escaping (Result<AddCredentialResult, any Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let pack = packs[packId] else {
                    lock.unlock()
                    completion(.success(AddCredentialError(message: "Pack not found: \(packId)")))
                    return
                }
                lock.unlock()

                let credentials = try await pack.tryAddRawMdoc(
                    rawCredential: rawCredential,
                    keyAlias: keyAlias
                )
                completion(.success(AddCredentialSuccess(
                    credentials: credentials.map { $0.toData() }
                )))
            } catch {
                completion(.success(AddCredentialError(
                    message: error.localizedDescription
                )))
            }
        }
    }

    func addAnyFormat(
        packId: String,
        rawCredential: String,
        mdocKeyAlias: String,
        completion: @escaping (Result<AddCredentialResult, any Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let pack = packs[packId] else {
                    lock.unlock()
                    completion(.success(AddCredentialError(message: "Pack not found: \(packId)")))
                    return
                }
                lock.unlock()

                let credentials = try await pack.tryAddAnyFormat(
                    rawCredential: rawCredential,
                    mdocKeyAlias: mdocKeyAlias
                )
                completion(.success(AddCredentialSuccess(
                    credentials: credentials.map { $0.toData() }
                )))
            } catch {
                completion(.success(AddCredentialError(
                    message: error.localizedDescription
                )))
            }
        }
    }

    func listCredentials(packId: String) throws -> [ParsedCredentialData] {
        lock.lock()
        let pack = packs[packId]
        lock.unlock()

        guard let pack = pack else { return [] }
        return pack.list().map { $0.toData() }
    }

    func getCredentialClaims(
        packId: String,
        credentialId: String,
        claimNames: [String]
    ) throws -> String? {
        lock.lock()
        let pack = packs[packId]
        lock.unlock()

        guard let pack = pack else { return nil }
        guard let credential = pack.get(credentialId: credentialId) else { return nil }

        let claims = pack.getCredentialClaims(credential: credential, claimNames: claimNames)

        // Convert claims dictionary to JSON string
        do {
            let jsonData = try JSONSerialization.data(withJSONObject: claims.mapValues { $0.toAny() })
            return String(data: jsonData, encoding: .utf8)
        } catch {
            return nil
        }
    }

    func deletePack(
        packId: String,
        appGroupId: String?,
        completion: @escaping (Result<CredentialOperationResult, any Error>) -> Void
    ) {
        Task {
            do {
                // Remove from persistent storage if appGroupId provided
                if let appGroupId = appGroupId {
                    let storageManager = StorageManager(appGroupId: appGroupId)
                    lock.lock()
                    if let pack = packs[packId] {
                        lock.unlock()
                        try await pack.remove(storageManager: storageManager)
                    } else {
                        lock.unlock()
                    }
                }

                // Remove from in-memory store
                lock.lock()
                packs.removeValue(forKey: packId)
                lock.unlock()

                completion(.success(CredentialOperationSuccess(unused: nil)))
            } catch {
                completion(.success(CredentialOperationError(
                    message: "Failed to delete pack: \(error.localizedDescription)"
                )))
            }
        }
    }

    func listPacks() throws -> [String] {
        lock.lock()
        let keys = Array(packs.keys)
        lock.unlock()
        return keys
    }

    func savePack(
        packId: String,
        appGroupId: String?,
        completion: @escaping (Result<CredentialOperationResult, any Error>) -> Void
    ) {
        Task {
            do {
                lock.lock()
                guard let pack = packs[packId] else {
                    lock.unlock()
                    completion(.success(CredentialOperationError(message: "Pack not found: \(packId)")))
                    return
                }
                lock.unlock()

                let storageManager = StorageManager(appGroupId: appGroupId)
                try await pack.save(storageManager: storageManager)

                completion(.success(CredentialOperationSuccess(unused: nil)))
            } catch {
                completion(.success(CredentialOperationError(
                    message: "Failed to save pack: \(error.localizedDescription)"
                )))
            }
        }
    }

    func loadAllPacks(
        appGroupId: String?,
        completion: @escaping (Result<[String], any Error>) -> Void
    ) {
        Task {
            do {
                let storageManager = StorageManager(appGroupId: appGroupId)
                let loadedPacks = try await SpruceIDMobileSdk.CredentialPack.loadAll(storageManager: storageManager)

                var packIds: [String] = []
                lock.lock()
                for pack in loadedPacks {
                    let packId = pack.id.uuidString
                    packs[packId] = pack
                    packIds.append(packId)
                }
                lock.unlock()

                completion(.success(packIds))
            } catch {
                completion(.success([]))
            }
        }
    }

    // MARK: - Internal methods for other adapters

    /// Get native ParsedCredential objects for use by other adapters
    func getNativeCredentials(packId: String) -> [SpruceIDMobileSdkRs.ParsedCredential] {
        lock.lock()
        let pack = packs[packId]
        lock.unlock()

        guard let pack = pack else { return [] }
        return pack.list()
    }

    /// Get native CredentialPack for use by other adapters
    func getNativePack(packId: String) -> SpruceIDMobileSdk.CredentialPack? {
        lock.lock()
        let pack = packs[packId]
        lock.unlock()
        return pack
    }

    /// Store a native CredentialPack
    func storePack(packId: String, pack: SpruceIDMobileSdk.CredentialPack) {
        lock.lock()
        packs[packId] = pack
        lock.unlock()
    }
}

// MARK: - Extensions

extension SpruceIDMobileSdkRs.ParsedCredential {
    func toData() -> ParsedCredentialData {
        // Map SpruceIDMobileSdkRs.CredentialFormat to Pigeon CredentialFormat
        let pigeonFormat: CredentialFormat
        var rawCredential = ""

        // Get native format from the credential
        let nativeFormat = self.format()

        switch nativeFormat {
        case .jwtVcJson, .jwtVcJsonLd:
            pigeonFormat = .jwtVc
            if let jwtVc = self.asJwtVc() {
                rawCredential = jwtVc.credentialAsJsonEncodedUtf8String()
            }
        case .ldpVc:
            pigeonFormat = .jsonVc
            if let jsonVc = self.asJsonVc() {
                rawCredential = jsonVc.credentialAsJsonEncodedUtf8String()
            }
        case .vcdm2SdJwt:
            pigeonFormat = .sdJwt
            if let sdJwt = self.asSdJwt() {
                // Use revealedClaimsAsJsonString for SD-JWT
                rawCredential = (try? sdJwt.revealedClaimsAsJsonString()) ?? ""
            }
        case .msoMdoc:
            pigeonFormat = .msoMdoc
            if let mdoc = self.asMsoMdoc() {
                let details = mdoc.jsonEncodedDetails()
                rawCredential = details.description
            }
        case .cwt:
            pigeonFormat = .cwt
            if let cwt = self.asCwt() {
                let claims = cwt.claims()
                rawCredential = claims.description
            }
        case .other(_):
            pigeonFormat = .jwtVc // default fallback
        }

        return ParsedCredentialData(
            id: self.id(),
            format: pigeonFormat,
            rawCredential: rawCredential
        )
    }
}

extension GenericJSON {
    func toAny() -> Any {
        switch self {
        case .string(let value):
            return value
        case .number(let value):
            return value
        case .bool(let value):
            return value
        case .array(let value):
            return value.map { $0.toAny() }
        case .object(let value):
            return value.mapValues { $0.toAny() }
        case .null:
            return NSNull()
        }
    }
}
