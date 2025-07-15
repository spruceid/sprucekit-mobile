import CryptoKit
import Foundation
import SpruceIDMobileSdkRs

/// A collection of ParsedCredentials with methods to interact with all instances.
///
/// A CredentialPack is a semantic grouping of Credentials for display in the wallet. For example,
/// the CredentialPack could represent:
/// - multiple copies of the same credential (for one-time use),
/// - different encodings of the same credential (JwtVC & JsonVC),
/// - multiple instances of the same credential type (vehicle title credentials for more than 1 vehicle).
public class CredentialPack {
    public let id: UUID
    private var credentials: [ParsedCredential]

    /// Initialize an empty CredentialPack.
    public init() {
        id = UUID()
        credentials = []
    }

    /// Initialize a CredentialPack from existing credentials.
    public init(id: UUID, credentials: [ParsedCredential]) {
        self.id = id
        self.credentials = credentials
    }

    /// Add a JwtVc to the CredentialPack.
    public func addJwtVc(jwtVc: JwtVc) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newJwtVcJson(jwtVc: jwtVc))
        return credentials
    }

    /**
     * Try to add a credential and throws a ParsingException if not possible
     */
    public func tryAddRawCredential(rawCredential: String) throws
        -> [ParsedCredential] {
        if let credentials = try? addJwtVc(
            jwtVc: JwtVc.newFromCompactJws(jws: rawCredential)
        ) {
            return credentials
        } else if let credentials = try? addJsonVc(
            jsonVc: JsonVc.newFromJson(utf8JsonString: rawCredential)
        ) {
            return credentials
        } else if let credentials = try? addSdJwt(
            sdJwt: Vcdm2SdJwt.newFromCompactSdJwt(input: rawCredential)
        ) {
            return credentials
        } else if let credentials = try? addCwt(
            cwt: Cwt.newFromBase10(payload: rawCredential)
        ) {
            return credentials
        } else {
            throw CredentialPackError.credentialParsing(
                reason: "Couldn't parse credential: \(rawCredential)"
            )
        }
    }

    /**
     * Try to add a raw mDoc with specified keyAlias
     */
    public func tryAddRawMdoc(rawCredential: String, keyAlias: String) throws
        -> [ParsedCredential] {
        if let credentials = try? addMDoc(
            mdoc: Mdoc.fromStringifiedDocument(
                stringifiedDocument: rawCredential,
                keyAlias: keyAlias
            )
        ) {
            return credentials
        } else if let credentials = try? addMDoc(
            mdoc: Mdoc.newFromBase64urlEncodedIssuerSigned(
                base64urlEncodedIssuerSigned: rawCredential,
                keyAlias: keyAlias
            )
        ) {
            return credentials
        } else {
            throw CredentialPackError.credentialParsing(
                reason:
                    "The mdoc format is not supported. Credential = \(rawCredential)"
            )
        }
    }

    /**
     * Try to add a credential in any supported format (standard credential or mdoc).
     * Attempts to parse as standard credential first, then as mdoc with specified keyAlias if that fails.
     *
     * @param rawCredential The raw credential data as a string
     * @param mdocKeyAlias The key alias to use if parsing as mdoc is needed
     * @return List of parsed credentials
     * @throws CredentialPackError if the credential cannot be parsed in any supported format
     */
    public func tryAddAnyFormat(rawCredential: String, mdocKeyAlias: String)
        throws -> [ParsedCredential] {
        // First try our standard formats (which already include mdoc but with random keyAlias)
        do {
            return try tryAddRawCredential(rawCredential: rawCredential)
        } catch {
            // If that fails, try specifically with the provided keyAlias
            do {
                return try tryAddRawMdoc(
                    rawCredential: rawCredential,
                    keyAlias: mdocKeyAlias
                )
            } catch {
                throw CredentialPackError.credentialParsing(
                    reason:
                        "The credential format is not supported in any format. Credential = \(rawCredential)"
                )
            }
        }
    }

    /// Add a Cwt to the CredentialPack
    public func addCwt(cwt: Cwt) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newCwt(cwt: cwt))
        return credentials
    }

    /// Add a JsonVc to the CredentialPack.
    public func addJsonVc(jsonVc: JsonVc) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newLdpVc(jsonVc: jsonVc))
        return credentials
    }

    /// Add an SD-JWT to the CredentialPack.
    public func addSdJwt(sdJwt: Vcdm2SdJwt) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newSdJwt(sdJwtVc: sdJwt))
        return credentials
    }

    /// Add an Mdoc to the CredentialPack.
    public func addMDoc(mdoc: Mdoc) -> [ParsedCredential] {
        credentials.append(ParsedCredential.newMsoMdoc(mdoc: mdoc))
        return credentials
    }

    /// Get all status from all credentials async
    public func getStatusListsAsync(hasConnection: Bool) async -> [Uuid:
        CredentialStatusList] {
        var res = [Uuid: CredentialStatusList]()
        for credential in credentials {
            let credentialId = credential.id()
            if let cred = credential.asJsonVc() {
                if hasConnection {
                    do {
                        let status = try await cred.status()
                        if status.isRevoked() {
                            res[credentialId] = CredentialStatusList.revoked
                        } else if status.isSuspended() {
                            res[credentialId] = CredentialStatusList.suspended
                        } else {
                            res[credentialId] = CredentialStatusList.valid
                        }
                    } catch {
                        res[credentialId] = CredentialStatusList.undefined
                    }
                } else {
                    res[credentialId] = CredentialStatusList.unknown
                }
            } else if let cred = credential.asSdJwt() {
                if hasConnection {
                    do {
                        let status = try await cred.status()
                        res[credentialId] = CredentialStatusList.valid
                        for credentialStatus in status {
                            if credentialStatus.isRevoked() {
                                res[credentialId] = CredentialStatusList.revoked
                                break
                            } else if credentialStatus.isSuspended() {
                                res[credentialId] =
                                    CredentialStatusList.suspended
                            }
                        }
                    } catch {
                        res[credentialId] = CredentialStatusList.undefined
                    }
                } else {
                    res[credentialId] = CredentialStatusList.unknown
                }
            }
        }

        return res
    }

    /// Find credential claims from all credentials in this CredentialPack.
    public func findCredentialClaims(claimNames: [String]) -> [Uuid: [String:
        GenericJSON]] {
        Dictionary(
            uniqueKeysWithValues: list()
                .map { credential in
                    let claims: [String: GenericJSON] =
                        getCredentialClaims(credential: credential, claimNames: claimNames)
                    return (credential.id(), claims)
                }
        )
    }

    /// Find credential claims from a specific credential.
    public func getCredentialClaims(credential: ParsedCredential, claimNames: [String]) -> [String: GenericJSON] {

        if let mdoc = credential.asMsoMdoc() {
            if claimNames.isEmpty {
                return mdoc.jsonEncodedDetails()
            } else {
                return mdoc.jsonEncodedDetails(
                    containing: claimNames
                )
            }
        } else if let jwtVc = credential.asJwtVc() {
            if claimNames.isEmpty {
                return jwtVc.credentialClaims()
            } else {
                return jwtVc.credentialClaims(
                    containing: claimNames
                )
            }
        } else if let cwt = credential.asCwt() {
            if claimNames.isEmpty {
                return cwt.credentialClaims()
            } else {
                return cwt.credentialClaims(
                    containing: claimNames
                )
            }
        } else if let jsonVc = credential.asJsonVc() {
            if claimNames.isEmpty {
                return jsonVc.credentialClaims()
            } else {
                return jsonVc.credentialClaims(
                    containing: claimNames
                )
            }
        } else if let sdJwt = credential.asSdJwt() {
            if claimNames.isEmpty {
                return sdJwt.credentialClaims()
            } else {
                return sdJwt.credentialClaims(
                    containing: claimNames
                )
            }
        } else {
            var type: String
            do {
                type = try credential.intoGenericForm().type
            } catch {
                type = "unknown"
            }
            print("unsupported credential type: \(type)")
            return [:]
        }

    }

    /// Get credentials by id.
    public func get(credentialsIds: [Uuid]) -> [ParsedCredential] {
        return credentials.filter {
            credentialsIds.contains($0.id())
        }
    }

    /// Get a credential by id.
    public func get(credentialId: Uuid) -> ParsedCredential? {
        return credentials.first(where: { $0.id() == credentialId })
    }

    /// List all of the credentials in the CredentialPack.
    public func list() -> [ParsedCredential] {
        return credentials
    }

    /// Persists the CredentialPack in the StorageManager, and persists all credentials in the VdcCollection.
    ///
    /// If a credential already exists in the VdcCollection (matching on id), then it will be skipped without updating.
    public func save(storageManager: StorageManagerInterface) async throws {
        let vdcCollection = VdcCollection(engine: storageManager)
        for credential in list() {
            do {
                if (try await vdcCollection.get(id: credential.id())) == nil {
                    try await vdcCollection.add(
                        credential: try credential.intoGenericForm()
                    )
                }
            } catch {
                throw CredentialPackError.credentialStorage(
                    id: credential.id(),
                    reason: error
                )
            }
        }

        try await self.intoContents().save(storageManager: storageManager)
    }

    /// Remove this CredentialPack from the StorageManager.
    ///
    /// Credentials that are in this pack __are__ removed from the VdcCollection.
    public func remove(storageManager: StorageManagerInterface) async throws {
        try await self.intoContents().remove(storageManager: storageManager)
    }

    /// Loads all CredentialPacks from the StorageManager.
    public static func loadAll(storageManager: StorageManagerInterface)
        async throws -> [CredentialPack] {
        try await CredentialPackContents.list(storageManager: storageManager)
            .asyncMap { contents in
                try await contents.load(
                    vdcCollection: VdcCollection(engine: storageManager)
                )
            }
    }

    private func intoContents() -> CredentialPackContents {
        CredentialPackContents(
            id: self.id,
            credentials: self.credentials.map { credential in
                credential.id()
            }
        )
    }
}

/// Metadata for a CredentialPack, as loaded from the StorageManager.
public struct CredentialPackContents {
    private static let storagePrefix = "CredentialPack:"
    private let idKey = "id"
    private let credentialsKey = "credentials"
    public let id: UUID
    let credentials: [Uuid]

    public init(id: UUID, credentials: [Uuid]) {
        self.id = id
        self.credentials = credentials
    }

    public init(fromBytes data: Data) throws {
        let json: [String: GenericJSON]
        do {
            json = try JSONDecoder().decode(
                [String: GenericJSON].self,
                from: data
            )
        } catch {
            throw CredentialPackError.contentsNotJSON(reason: error)
        }

        switch json[idKey] {
        case .string(let id):
            guard let id = UUID(uuidString: id) else {
                throw CredentialPackError.idNotUUID(id: id)
            }
            self.id = id
        case nil:
            throw CredentialPackError.idMissingFromContents
        default:
            throw CredentialPackError.idNotString(value: json[idKey]!)

        }

        switch json[credentialsKey] {
        case .array(let credentialIds):
            self.credentials = try credentialIds.map { id in
                switch id {
                case .string(let id):
                    id
                default:
                    throw CredentialPackError.credentialIdNotString(value: id)
                }
            }
        case nil:
            throw CredentialPackError.credentialIdsMissingFromContents
        default:
            throw CredentialPackError.credentialIdsNotArray(
                value: json[credentialsKey]!
            )
        }
    }

    /// Loads all of the credentials from the VdcCollection for this CredentialPack.
    public func load(vdcCollection: VdcCollection) async throws
        -> CredentialPack {
        let credentials = try await credentials.asyncMap { credentialId in
            do {
                guard
                    let credential = try await vdcCollection.get(
                        id: credentialId
                    )
                else {
                    throw CredentialPackError.credentialNotFound(
                        id: credentialId
                    )
                }
                return try ParsedCredential.parseFromCredential(
                    credential: credential
                )
            } catch {
                throw CredentialPackError.credentialLoading(reason: error)
            }
        }

        return CredentialPack(id: self.id, credentials: credentials)
    }

    /// Clears all CredentialPacks.
    public static func clear(storageManager: StorageManagerInterface)
        async throws {
        do {
            try await storageManager.list()
                .filter { file in
                    file.hasPrefix(Self.storagePrefix)
                }
                .asyncForEach { file in
                    try await storageManager.remove(key: file)
                }
        } catch {
            throw CredentialPackError.clearing(reason: error)
        }
    }

    /// Lists all CredentialPacks.
    ///
    /// These can then be individually loaded. For eager loading of all packs, see `CredentialPack.loadAll`.
    public static func list(storageManager: StorageManagerInterface)
        async throws -> [CredentialPackContents] {
        do {
            return try await storageManager.list()
                .filter { file in
                    file.hasPrefix(Self.storagePrefix)
                }
                .asyncMap { file in
                    guard let contents = try await storageManager.get(key: file)
                    else {
                        throw CredentialPackError.missing(file: file)
                    }
                    return try CredentialPackContents(fromBytes: contents)
                }
        } catch {
            throw CredentialPackError.listing(reason: error)
        }
    }

    public func save(storageManager: StorageManagerInterface) async throws {
        let bytes = try self.toBytes()
        do {
            try await storageManager.add(key: self.storageKey(), value: bytes)
        } catch {
            throw CredentialPackError.storage(reason: error)
        }
    }

    private func toBytes() throws -> Data {
        do {
            let json = [
                idKey: GenericJSON.string(self.id.uuidString),
                credentialsKey: GenericJSON.array(
                    self.credentials.map { id in
                        GenericJSON.string(id)
                    }
                )
            ]

            return try JSONEncoder().encode(json)
        } catch {
            throw CredentialPackError.contentsEncoding(reason: error)
        }
    }

    /// Remove this CredentialPack from the StorageManager.
    ///
    /// Credentials that are in this pack __are__ removed from the VdcCollection.
    public func remove(storageManager: StorageManagerInterface) async throws {
        let vdcCollection = VdcCollection(engine: storageManager)
        await self.credentials.asyncForEach { credential in
            do {
                try await vdcCollection.delete(id: credential)
            } catch {
                print(
                    "failed to remove Credential '\(credential)' from the VdcCollection"
                )
            }
        }

        do {
            try await storageManager.remove(key: self.storageKey())
        } catch {
            throw CredentialPackError.removing(reason: error)
        }
    }

    private func storageKey() -> String {
        "\(Self.storagePrefix)\(self.id)"
    }
}

enum CredentialPackError: Error, Sendable {
    /// CredentialPackContents file missing from storage.
    case missing(file: String)
    /// Failed to list CredentialPackContents from storage.
    case listing(reason: Error)
    /// Failed to clear CredentialPacks from storage.
    case clearing(reason: Error)
    /// Failed to remove CredentialPackContents from storage.
    case removing(reason: Error)
    /// Failed to save CredentialPackContents to storage.
    case storage(reason: Error)
    /// Failed to store a new credential when saving a CredentialPack.
    case credentialStorage(id: Uuid, reason: Error)
    /// Could not interpret the file payload as JSON when loading a CredentialPackContents from storage.
    case contentsNotJSON(reason: Error)
    /// Failed to encode CredentialPackContents as JSON.
    case contentsEncoding(reason: Error)
    /// The ID is missing from the CredentialPackContents when loading from storage.
    case idMissingFromContents
    /// The CredentialPackContents ID could not be parsed as a JSON String when loading from storage.
    case idNotString(value: GenericJSON)
    /// The CredentialPackContents ID could not be parsed as a UUID when loading from storage.
    case idNotUUID(id: String)
    /// The credential IDs are missing from the CredentialPackContents when loading from storage.
    case credentialIdsMissingFromContents
    /// The CredentialPackContents credential IDs could not be parsed as a JSON Array when loading from storage.
    case credentialIdsNotArray(value: GenericJSON)
    /// A CredentialPackContents credential ID could not be parsed as a JSON String when loading from storage.
    case credentialIdNotString(value: GenericJSON)
    /// The credential could not be found in storage.
    case credentialNotFound(id: Uuid)
    /// The credential could not be loaded from storage.
    case credentialLoading(reason: Error)
    /// The raw credential could not be parsed.
    case credentialParsing(reason: String)
}

public enum CredentialStatusList: String {
    init?(from string: String) {
        self.init(rawValue: string.uppercased())
    }

    /// Valid credential
    case valid = "VALID"
    /// Credential revoked
    case revoked = "REVOKED"
    /// Credential suspended
    case suspended = "SUSPENDED"
    /// No connection
    case unknown = "UNKNOWN"
    /// Invalid credential
    case invalid = "INVALID"
    /// Credential doesn't have status list
    case undefined = "UNDEFINED"
    /// Credential is pending approval
    case pending = "PENDING"
    /// Credential is ready to be claimed
    case ready = "READY"
}
