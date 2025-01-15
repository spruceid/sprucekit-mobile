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
    public func tryAddRawCredential(rawCredential: String) throws -> [ParsedCredential] {
        if let credentials = try? addJwtVc(jwtVc: JwtVc.newFromCompactJws(jws: rawCredential)) {
            return credentials
        } else if let credentials = try? addJsonVc(jsonVc: JsonVc.newFromJson(utf8JsonString: rawCredential)) {
            return credentials
        } else if let credentials = try? addSdJwt(sdJwt: Vcdm2SdJwt.newFromCompactSdJwt(input: rawCredential)) {
            return credentials
        } else if let credentials = try? addMDoc(mdoc: Mdoc.fromStringifiedDocument(
            stringifiedDocument: rawCredential,
            keyAlias: UUID().uuidString)
        ) {
            return credentials
        } else {
            throw CredentialPackError.credentialParsing(reason: "Couldn't parse credential: \(rawCredential)")
        }
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
    public func getStatusListsAsync(hasConnection: Bool) async -> [Uuid: CredentialStatusList] {
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
                    } catch {}
                } else {
                    res[credentialId] = CredentialStatusList.unknown
                }
            }
        }

        return res
    }

    /// Find credential claims from all credentials in this CredentialPack.
    public func findCredentialClaims(claimNames: [String]) -> [Uuid: [String: GenericJSON]] {
        Dictionary(
            uniqueKeysWithValues: list()
                .map { credential in
                    var claims: [String: GenericJSON]
                    if let mdoc = credential.asMsoMdoc() {
                        if claimNames.isEmpty {
                            claims = mdoc.jsonEncodedDetails()
                        } else {
                            claims = mdoc.jsonEncodedDetails(containing: claimNames)
                        }
                    } else if let jwtVc = credential.asJwtVc() {
                        if claimNames.isEmpty {
                            claims = jwtVc.credentialClaims()
                        } else {
                            claims = jwtVc.credentialClaims(containing: claimNames)
                        }
                    } else if let jsonVc = credential.asJsonVc() {
                        if claimNames.isEmpty {
                            claims = jsonVc.credentialClaims()
                        } else {
                            claims = jsonVc.credentialClaims(containing: claimNames)
                        }
                    } else if let sdJwt = credential.asSdJwt() {
                        if claimNames.isEmpty {
                            claims = sdJwt.credentialClaims()
                        } else {
                            claims = sdJwt.credentialClaims(containing: claimNames)
                        }
                    } else {
                        var type: String
                        do {
                            type = try credential.intoGenericForm().type
                        } catch {
                            type = "unknown"
                        }
                        print("unsupported credential type: \(type)")
                        claims = [:]
                    }
                    return (credential.id(), claims)
                })
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
    public func save(storageManager: StorageManagerInterface) throws {
        let vdcCollection = VdcCollection(engine: storageManager)
        for credential in list() {
            do {
                if (try vdcCollection.get(id: credential.id())) == nil {
                    try vdcCollection.add(credential: try credential.intoGenericForm())
                }
            } catch {
                throw CredentialPackError.credentialStorage(id: credential.id(), reason: error)
            }
        }

        try self.intoContents().save(storageManager: storageManager)
    }

    /// Remove this CredentialPack from the StorageManager.
    ///
    /// Credentials that are in this pack __are__ removed from the VdcCollection.
    public func remove(storageManager: StorageManagerInterface) throws {
        try self.intoContents().remove(storageManager: storageManager)
    }

    /// Loads all CredentialPacks from the StorageManager.
    public static func loadAll(storageManager: StorageManagerInterface) throws -> [CredentialPack] {
        try CredentialPackContents.list(storageManager: storageManager).map { contents in
            try contents.load(vdcCollection: VdcCollection(engine: storageManager))
        }
    }

    private func intoContents() -> CredentialPackContents {
        CredentialPackContents(id: self.id, credentials: self.credentials.map { credential in
            credential.id()
        })
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
            json = try JSONDecoder().decode([String: GenericJSON].self, from: data)
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
            throw CredentialPackError.credentialIdsNotArray(value: json[credentialsKey]!)
        }
    }

    /// Loads all of the credentials from the VdcCollection for this CredentialPack.
    public func load(vdcCollection: VdcCollection) throws -> CredentialPack {
        let credentials = try credentials.map { credentialId in
            do {
                guard let credential = try vdcCollection.get(id: credentialId) else {
                    throw CredentialPackError.credentialNotFound(id: credentialId)
                }
                return try ParsedCredential.parseFromCredential(credential: credential, selectedFields: nil)
            } catch {
                throw CredentialPackError.credentialLoading(reason: error)
            }
        }

        return CredentialPack(id: self.id, credentials: credentials)
    }

    /// Clears all CredentialPacks.
    public static func clear(storageManager: StorageManagerInterface) throws {
        do {
            try storageManager.list()
                .filter { file in
                    file.hasPrefix(Self.storagePrefix)
                }
                .forEach { file in
                    try storageManager.remove(key: file)
                }
        } catch {
            throw CredentialPackError.clearing(reason: error)
        }
    }

    /// Lists all CredentialPacks.
    ///
    /// These can then be individually loaded. For eager loading of all packs, see `CredentialPack.loadAll`.
    public static func list(storageManager: StorageManagerInterface) throws -> [CredentialPackContents] {
        do {
            return try storageManager.list()
                .filter { file in
                    file.hasPrefix(Self.storagePrefix)
                }
                .map { file in
                    guard let contents = try storageManager.get(key: file) else {
                        throw CredentialPackError.missing(file: file)
                    }
                    return try CredentialPackContents(fromBytes: contents)
                }
        } catch {
            throw CredentialPackError.listing(reason: error)
        }
    }

    public func save(storageManager: StorageManagerInterface) throws {
        let bytes = try self.toBytes()
        do {
            try storageManager.add(key: self.storageKey(), value: bytes)
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
    public func remove(storageManager: StorageManagerInterface) throws {
        let vdcCollection = VdcCollection(engine: storageManager)
        self.credentials.forEach { credential in
            do {
                try vdcCollection.delete(id: credential)
            } catch {
                print("failed to remove Credential '\(credential)' from the VdcCollection")
            }
        }

        do {
            try storageManager.remove(key: self.storageKey())
        } catch {
            throw CredentialPackError.removing(reason: error)
        }
    }

    private func storageKey() -> String {
        "\(Self.storagePrefix)\(self.id)"
    }
}

enum CredentialPackError: Error {
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

public enum CredentialStatusList {
    /// Valid credential
    case valid
    /// Credential revoked
    case revoked
    /// Credential suspended
    case suspended
    /// No connection
    case unknown
    /// Invalid credential
    case invalid
    /// Credential doesn't have status list
    case undefined
}
