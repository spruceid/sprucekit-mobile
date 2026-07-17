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
        keyAlias: String?,
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

    func parseRawCredential(
        rawCredential: String,
        format: CredentialFormat,
        completion: @escaping (Result<ParsedCredentialPreview, any Error>) -> Void
    ) {
        Task {
            do {
                // Dummy alias: parsing does not bind the credential to any key.
                // On accept, the wallet re-parses with a real alias before persisting.
                let previewAlias = "preview-only-\(UUID().uuidString)"

                let parsed: SpruceIDMobileSdkRs.ParsedCredential
                if format == .msoMdoc {
                    let mdoc: SpruceIDMobileSdkRs.Mdoc
                    do {
                        mdoc = try SpruceIDMobileSdkRs.Mdoc.fromStringifiedDocument(
                            stringifiedDocument: rawCredential,
                            keyAlias: previewAlias
                        )
                    } catch {
                        mdoc = try SpruceIDMobileSdkRs.Mdoc.newFromBase64urlEncodedIssuerSigned(
                            base64urlEncodedIssuerSigned: rawCredential,
                            keyAlias: previewAlias
                        )
                    }
                    parsed = SpruceIDMobileSdkRs.ParsedCredential.newMsoMdoc(mdoc: mdoc)
                } else {
                    parsed = try SpruceIDMobileSdkRs.ParsedCredential.newFromStringWithFormat(
                        format: format.toRustFormatString(),
                        credential: rawCredential,
                        keyAlias: previewAlias
                    )
                }

                var doctype: String? = nil
                var vct: String? = nil
                let claimsJson: String

                switch format {
                case .jwtVc:
                    claimsJson = parsed.asJwtVc()?.credentialAsJsonEncodedUtf8String() ?? ""
                case .jsonVc:
                    claimsJson = parsed.asJsonVc()?.credentialAsJsonEncodedUtf8String() ?? ""
                case .sdJwt:
                    claimsJson = (try parsed.asSdJwt()?.revealedClaimsAsJsonString()) ?? ""
                case .msoMdoc:
                    if let mdoc = parsed.asMsoMdoc() {
                        let data = try JSONEncoder().encode(mdoc.jsonEncodedDetails())
                        claimsJson = String(data: data, encoding: .utf8) ?? ""
                        doctype = mdoc.doctype()
                    } else {
                        claimsJson = ""
                    }
                case .cwt:
                    if let cwt = parsed.asCwt() {
                        let data = try JSONEncoder().encode(cwt.credentialClaims())
                        claimsJson = String(data: data, encoding: .utf8) ?? ""
                    } else {
                        claimsJson = ""
                    }
                case .dcSdJwt:
                    if let dcSdJwt = parsed.asDcSdJwt() {
                        claimsJson = (try dcSdJwt.revealedClaimsAsJsonString())
                        vct = dcSdJwt.vct()
                    } else {
                        claimsJson = ""
                    }
                case .opticalBarcode:
                    claimsJson = parsed.asOpticalBarcodeCredential()?.rawJsonld() ?? ""
                }

                if claimsJson.isEmpty {
                    throw NSError(
                        domain: "CredentialPackAdapter",
                        code: -1,
                        userInfo: [NSLocalizedDescriptionKey: "Parsed credential did not match the declared format"]
                    )
                }

                completion(.success(ParsedCredentialPreview(
                    format: format,
                    doctype: doctype,
                    vct: vct,
                    claimsJson: claimsJson
                )))
            } catch {
                completion(.failure(error))
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

    func getStatusLists(
        packId: String,
        hasConnection: Bool,
        completion: @escaping (Result<[String: CredentialStatus], any Error>) -> Void
    ) {
        Task {
            lock.lock()
            let pack = packs[packId]
            lock.unlock()

            guard let pack = pack else {
                completion(.success([:]))
                return
            }

            let statuses = await pack.getStatusListsAsync(hasConnection: hasConnection)
            var res = [String: CredentialStatus]()
            for (credentialId, status) in statuses {
                res[credentialId] = status.toPigeon()
            }
            completion(.success(res))
        }
    }

    func deletePack(
        packId: String,
        appGroupId: String?,
        userHash: String?,
        completion: @escaping (Result<CredentialOperationResult, any Error>) -> Void
    ) {
        Task {
            do {
                let storageManager = StorageManager(appGroupId: appGroupId)
                lock.lock()
                let pack = packs[packId]
                lock.unlock()

                if let pack = pack {
                    try await pack.remove(storageManager: storageManager, scope: userHash)
                } else if let uuid = UUID(uuidString: packId),
                          let loaded = try await SpruceIDMobileSdk.CredentialPack.load(
                              storageManager: storageManager,
                              id: uuid,
                              scope: userHash
                          ) {
                    // Pack not in memory — load from storage so we can remove its credentials too
                    try await loaded.remove(storageManager: storageManager, scope: userHash)
                }

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
        userHash: String?,
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
                try await pack.save(storageManager: storageManager, scope: userHash)

                completion(.success(CredentialOperationSuccess(unused: nil)))
            } catch {
                completion(.success(CredentialOperationError(
                    message: "Failed to save pack: \(error.localizedDescription)"
                )))
            }
        }
    }

    func loadPack(
        packId: String,
        appGroupId: String?,
        userHash: String?,
        completion: @escaping (Result<CredentialOperationResult, any Error>) -> Void
    ) {
        Task {
            do {
                guard let uuid = UUID(uuidString: packId) else {
                    completion(.success(CredentialOperationError(message: "Invalid pack id: \(packId)")))
                    return
                }
                let storageManager = StorageManager(appGroupId: appGroupId)
                guard let pack = try await SpruceIDMobileSdk.CredentialPack.load(
                    storageManager: storageManager,
                    id: uuid,
                    scope: userHash
                ) else {
                    completion(.success(CredentialOperationError(message: "Pack not found: \(packId)")))
                    return
                }
                lock.lock()
                packs[packId] = pack
                lock.unlock()
                completion(.success(CredentialOperationSuccess(unused: nil)))
            } catch {
                completion(.success(CredentialOperationError(message: error.localizedDescription)))
            }
        }
    }

    func loadAllPacks(
        appGroupId: String?,
        userHash: String?,
        completion: @escaping (Result<[String], any Error>) -> Void
    ) {
        Task {
            do {
                let storageManager = StorageManager(appGroupId: appGroupId)
                let loadedPacks = try await SpruceIDMobileSdk.CredentialPack.loadAll(
                    storageManager: storageManager,
                    scope: userHash
                )
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
        var doctype: String? = nil
        var vct: String? = nil

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
                doctype = mdoc.doctype()
            }
        case .cwt:
            pigeonFormat = .cwt
            if let cwt = self.asCwt() {
                let claims = cwt.claims()
                rawCredential = claims.description
            }
        case .dcSdJwt:
            pigeonFormat = .dcSdJwt
            if let dcSdJwt = self.asDcSdJwt() {
                rawCredential = (try? dcSdJwt.revealedClaimsAsJsonString()) ?? ""
                vct = dcSdJwt.vct()
            }
        case .opticalBarcodeCredential:
            pigeonFormat = .opticalBarcode
            if let optical = self.asOpticalBarcodeCredential() {
                rawCredential = optical.rawJsonld()
            }
        case .other(_):
            pigeonFormat = .jwtVc // default fallback
        }

        return ParsedCredentialData(
            id: self.id(),
            format: pigeonFormat,
            rawCredential: rawCredential,
            doctype: doctype,
            vct: vct
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

extension CredentialFormat {
    /// Map the Pigeon `CredentialFormat` enum to the format string accepted by
    /// `ParsedCredential.newFromStringWithFormat`. Mirrors the canonical strings
    /// defined in `rust/src/credential/mod.rs::CredentialFormat::Display`.
    func toRustFormatString() -> String {
        switch self {
        case .msoMdoc: return "mso_mdoc"
        case .jwtVc: return "jwt_vc_json"
        case .jsonVc: return "ldp_vc"
        case .sdJwt: return "vcdm2_sd_jwt"
        case .dcSdJwt: return "dc+sd-jwt"
        case .cwt: return "cwt"
        case .opticalBarcode: return "optical_barcode_credential"
        }
    }
}

extension SpruceIDMobileSdk.CredentialStatusList {
    /// Map the native SDK `CredentialStatusList` to the Pigeon `CredentialStatus` enum.
    func toPigeon() -> CredentialStatus {
        switch self {
        case .valid: return .valid
        case .revoked: return .revoked
        case .suspended: return .suspended
        case .unknown: return .unknown
        case .invalid: return .invalid
        case .undefined: return .undefined
        case .pending: return .pending
        case .ready: return .ready
        }
    }
}
